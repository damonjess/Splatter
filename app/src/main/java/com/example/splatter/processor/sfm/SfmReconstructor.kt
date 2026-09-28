package com.example.splatter.processor.sfm

import com.example.splatter.processor.mesh.TriangleMesh
import java.util.Random
import kotlin.math.abs
import kotlin.math.sqrt

/** One processed keyframe ready for SfM. */
class SfmFrame(
    val index: Int,
    val pixels: IntArray,
    val width: Int,
    val height: Int,
    val fx: Float,
    val fy: Float,
    val cx: Float,
    val cy: Float,
    /** ARCore camera-to-world pose, used only as a sanity reference — the SfM result is metric-scaled. */
    val arPose: FloatArray? = null
)

/** Result of the photo-only reconstruction. */
class SfmResult(
    val poses: List<FloatArray?>,
    val pointsXyz: List<FloatArray>,
    @Suppress("unused") val pointsRgb: List<IntArray>,
    @Suppress("unused") val tracks: List<Track>,
    val mesh: TriangleMesh
)

/** A 3D point tracked across multiple frames with its pixel observations. */
class Track(
    /** (frameIndex, x, y, featureIndex) observations, in ascending frame order. */
    val observations: MutableList<Obs> = mutableListOf()
) {
    class Obs(val frame: Int, val x: Float, val y: Float, val feature: Int = -1)

    @Suppress("unused")
    val observationCount: Int get() = observations.size
}

/**
 * Incremental photo-only Structure-from-Motion over a set of keyframes.
 *
 * Pipeline per run:
 *  1. Detect corners in every keyframe (already downscaled by the caller)
 *  2. Match consecutive frames and chain a feature track map
 *  3. Seed scale + first pose with the strongest two-view geometry
 *  4. Estimate each further pose from 2D-3D correspondences of already
 *     triangulated tracks (RANSAC over pose hypotheses)
 *  5. Triangulate new tracks, prune those with high reprojection error
 *  6. Alternate: re-refine poses from points, then re-triangulate points
 *
 * Pure Kotlin — no Android dependencies — so the whole stage is covered by
 * JVM unit tests on synthetic scenes.
 */
class SfmReconstructor(
    private val maxFeatures: Int = 1000,
    private val matchRadius: Float = 180f,
    private val ransacIterations: Int = 320,
    private val maxReprojErrorPx: Float = 2.5f,
    private val minTrackViews: Int = 2,
    private val onProgress: ((String, Int) -> Unit)? = null
) {

    /**
     * Set right before `reconstruct()` returns null, naming which stage and
     * with what numbers — so a failure in the field can be diagnosed from a
     * single logcat line instead of guessing between "not enough texture",
     * "not enough matches", "no viable seed pair", "seed had no parallax",
     * and "registration stalled partway through".
     */
    var lastFailureReason: String? = null
        private set

    /**
     * Run SfM over the keyframes. Returns null when the sequence cannot be
     * reconstructed (too few features, degenerate motion, ...).
     */
    fun reconstruct(frames: List<SfmFrame>): SfmResult? {
        lastFailureReason = null
        if (frames.size < 2) {
            lastFailureReason = "only ${frames.size} frame(s) passed in"
            return null
        }
        onProgress?.invoke("Detecting features...", 5)

        // ---- 1. Feature detection ----
        val features = ArrayList<FeatureList>(frames.size)
        val lowTextureFrames = ArrayList<Int>()
        for (f in frames) {
            val gray = ImageOps.boxBlur3x3(ImageOps.toGrayscale(f.pixels), f.width, f.height)
            val feat = FeatureDetector.detect(
                gray, f.width, f.height,
                maxFeatures = maxFeatures,
                qualityThreshold = 0.06f,
                gridCells = 10,
                // A real "orbit and snap photos" capture has far more
                // rotation between shots than the few-degree inter-frame
                // motion this pipeline was originally tuned against — the
                // default 3px patch (7x7) barely covers a corner's own
                // texture and NCC on it collapses once the corner rotates
                // or foreshortens even slightly. A 6px patch (13x13) gives
                // the correlation enough surrounding structure to survive
                // real wide-baseline viewpoint change.
                patchSize = 6
            )
            features.add(feat)
            if (feat.count < 24) {
                // A single blurry/dark/off-target frame (e.g. the phone
                // swung past the subject for an instant, or briefly caught
                // a blank wall) used to abort the ENTIRE reconstruction here
                // — throwing away every other good keyframe over one bad
                // one. FeatureMatcher already handles low/zero-feature
                // lists gracefully (returns no matches, doesn't crash), so
                // a sparse frame simply fails to link with its neighbours
                // and never gets a pose — exactly like an incremental
                // registration failure — instead of nuking the whole run.
                lowTextureFrames.add(f.index)
            }
        }
        val usableFrameCount = frames.size - lowTextureFrames.size
        if (usableFrameCount < 2) {
            lastFailureReason = "only $usableFrameCount/${frames.size} frame(s) had 24+ corner features " +
                "(low-texture frames: $lowTextureFrames) — subject may be too flat/matte/low-texture overall, or too dark/blurry throughout"
            return null
        }

        // ---- 2. Feature matching + track building ----
        // Matches are linked via union-find so wider-baseline pairs can merge
        // tracks that consecutive matching alone would keep separate — this
        // both lengthens tracks (more triangulation views per point) and
        // connects viewpoints that chain drift would otherwise split.
        onProgress?.invoke("Matching features...", 20)
        val keyOf = { frame: Int, idx: Int -> frame.toLong() * 1_000_000L + idx }
        val uf = HashMap<Long, Long>()

        fun find(k: Long): Long {
            var root = k
            while (true) {
                val p = uf[root] ?: break
                root = p
            }
            var cur = k
            while (cur != root) {
                val nxt = uf[cur]!!
                uf[cur] = root
                cur = nxt
            }
            return root
        }

        fun union(a: Long, b: Long) {
            val ra = find(a); val rb = find(b)
            if (ra != rb) uf[ra] = rb
        }

        fun link(a: Int, b: Int, radiusScale: Float, strict: Boolean) {
            // Loosened from (2/3 bits, 0.75/0.70 NCC): those thresholds were
            // tuned against near-zero-baseline synthetic frames and reject
            // almost everything once real photos have more than a few
            // degrees of rotation between them — which is normal for a
            // hand-held orbit capture, not an edge case.
            val ms = FeatureMatcher.match(
                features[a], features[b],
                radius = matchRadius * radiusScale,
                censusMaxBits = if (strict) 3 else 5,
                minNcc = if (strict) 0.62f else 0.55f
            )
            for (m in ms) union(keyOf(a, m.i), keyOf(b, m.j))
        }

        for (f in 0 until frames.size - 1) link(f, f + 1, 1f, strict = false)
        // Wider baselines merge chain-split tracks...
        val maxGap = minOf(4, frames.size - 1)
        for (gap in 2..maxGap) {
            for (f in 0..frames.size - 1 - gap) link(f, f + gap, 1.25f * gap, strict = true)
        }

        val trackObservations = HashMap<Long, MutableList<Track.Obs>>()
        for (f in frames.indices) {
            for (i in 0 until features[f].count) {
                val root = find(keyOf(f, i))
                trackObservations.getOrPut(root) { mutableListOf() }
                    .add(Track.Obs(f, features[f].x(i), features[f].y(i), i))
            }
        }

        val tracks = trackObservations.entries
            .filter { it.value.size >= minTrackViews }
            .map { (_, obsList) ->
                val byFrame = LinkedHashMap<Int, Track.Obs>()
                for (o in obsList.sortedBy { it.frame }) {
                    if (!byFrame.containsKey(o.frame)) byFrame[o.frame] = o
                }
                Track(byFrame.values.toMutableList())
            }
            .toMutableList()
        if (tracks.size < 25) {
            val counts = features.map { it.count }
            lastFailureReason = "only ${tracks.size} feature tracks survived linking across ${frames.size} frames (need 25+; per-frame feature counts ranged ${counts.min()}-${counts.max()}, avg ${counts.average().toInt()}) — consecutive keyframes may be too far apart for the matcher, or the subject may lack repeatable texture"
            return null
        }

        // Track index lookup by (frame, featureIndex) for pose registration
        val trackIndexByKey = HashMap<Long, Int>(tracks.size * 2)
        for ((ti, t) in tracks.withIndex()) {
            for (o in t.observations) {
                if (o.feature >= 0) trackIndexByKey[keyOf(o.frame, o.feature)] = ti
            }
        }

        // ---- 3. Two-view seed ----
        // A candidate pair is only worth what it can TRIANGULATE: near-
        // identical frames win on essential-matrix inliers yet carry no
        // parallax, so inlier count must never decide which pair seeds the
        // reconstruction. Every RANSAC survivor gets triangulated and the
        // pair with the most usable points wins (see attemptSeed below).
        onProgress?.invoke("Estimating initial geometry...", 35)
        val fx0 = frames[0].fx; val fy0 = frames[0].fy
        val cx0 = frames[0].cx; val cy0 = frames[0].cy

        data class TrackPoint(val track: Int, var xyz: FloatArray, var error: Float) {
            override fun equals(other: Any?): Boolean {
                if (this === other) return true
                if (other !is TrackPoint) return false
                if (track != other.track) return false
                if (!xyz.contentEquals(other.xyz)) return false
                return error == other.error
            }

            override fun hashCode(): Int {
                var result = track
                result = 31 * result + xyz.contentHashCode()
                result = 31 * result + error.hashCode()
                return result
            }
        }
        val poses = arrayOfNulls<FloatArray>(frames.size)
        val points = ArrayList<TrackPoint>()

        /**
         * Triangulate a track from the observations whose frames already have
         * a pose.
         *
         * When [stats] is supplied, failure reasons are counted for seed
         * diagnostics: slot 0 = degenerate triangulation, slot 1 = rejected
         * for < ~1.8 deg ray parallax.
         */
        fun triangulateTrack(trackIndex: Int, stats: IntArray? = null): TrackPoint? {
            fun fail(slot: Int): TrackPoint? {
                stats?.let { it[slot]++ }
                return null
            }

            val track = tracks[trackIndex]
            val obs = track.observations
            // Use only observations whose frame has a pose yet — tracks that
            // ALSO span not-yet-registered frames must still triangulate from
            // the posed subset, otherwise seed points never observe the
            // pending frames and incremental registration deadlocks.
            val mats = ArrayList<FloatArray>(obs.size)
            val us = ArrayList<Float>(obs.size)
            val vs = ArrayList<Float>(obs.size)
            val posedFrames = ArrayList<Int>()
            for (o in obs) {
                val pose = poses[o.frame] ?: continue
                mats.add(SfmMath.projectionMatrix(pose, frames[o.frame].fx, frames[o.frame].fy, frames[o.frame].cx, frames[o.frame].cy))
                us.add(o.x)
                vs.add(o.y)
                posedFrames.add(o.frame)
            }
            if (mats.size < 2) return fail(0)
            val xyz = SfmMath.triangulate(mats, us.toFloatArray(), vs.toFloatArray()) ?: return fail(0)

            var hasParallax = false
            for (i in posedFrames.indices) {
                val pi = poses[posedFrames[i]]!!
                val dx1 = xyz[0] - pi[12]; val dy1 = xyz[1] - pi[13]; val dz1 = xyz[2] - pi[14]
                val len1 = sqrt(dx1 * dx1 + dy1 * dy1 + dz1 * dz1)
                if (len1 < 1e-6f) continue
                val vx1 = dx1 / len1; val vy1 = dy1 / len1; val vz1 = dz1 / len1

                for (j in i + 1 until posedFrames.size) {
                    val pj = poses[posedFrames[j]]!!
                    val dx2 = xyz[0] - pj[12]; val dy2 = xyz[1] - pj[13]; val dz2 = xyz[2] - pj[14]
                    val len2 = sqrt(dx2 * dx2 + dy2 * dy2 + dz2 * dz2)
                    if (len2 < 1e-6f) continue
                    val vx2 = dx2 / len2; val vy2 = dy2 / len2; val vz2 = dz2 / len2

                    val dot = vx1 * vx2 + vy1 * vy2 + vz1 * vz2
                    if (dot < 0.9995f) {
                        hasParallax = true
                        break
                    }
                }
                if (hasParallax) break
            }
            if (!hasParallax) return fail(1)
            // Mean reprojection error over the posed observations used
            var err = 0f
            var n = 0
            for (k in mats.indices) {
                val p = SfmMath.project(mats[k], floatArrayOf(xyz[0], xyz[1], xyz[2], 1f)) ?: return fail(0)
                err += sqrt((p[0] - us[k]) * (p[0] - us[k]) + (p[1] - vs[k]) * (p[1] - vs[k]))
                n++
            }
            if (n == 0) return fail(0)
            return TrackPoint(trackIndex, xyz, err / n)
        }

        /** One essential-matrix candidate pair for the two-view seed. */
        data class SeedCandidate(val a: Int, val b: Int, val res: TwoViewGeometry.Result)

        /** What one seed attempt got, and why the pair's other tracks were rejected. */
        data class SeedAttempt(
            val cand: SeedCandidate,
            val gateScale: Float,
            val pointCount: Int,
            /** Tracks with observations in BOTH seed frames. */
            val seedTracks: Int,
            /** Seed tracks whose least-squares triangulation was degenerate. */
            val degenerate: Int,
            /** Seed tracks rejected for < ~1.8 deg ray parallax. */
            val parallaxFail: Int
        ) {
            val gatePx: Float = maxReprojErrorPx * gateScale
            /** Seed tracks that triangulated fine but exceeded the reprojection gate. */
            val errorFail: Int get() = seedTracks - degenerate - parallaxFail - pointCount
        }

        /**
         * Install this candidate's two poses, triangulate every track spanning
         * both frames, and count what survives the [gateScale]x reprojection
         * gate. Fills [poses]/[points] as a side effect — the winning attempt
         * is re-run once afterwards so those hold the chosen seed.
         */
        fun attemptSeed(cand: SeedCandidate, gateScale: Float): SeedAttempt {
            poses.fill(null)
            points.clear()
            poses[cand.a] = SfmMath.identityPose()
            poses[cand.b] = cand.res.poseB
            var seedTracks = 0
            val stats = IntArray(2)
            for ((ti, track) in tracks.withIndex()) {
                val inSeed = track.observations.any { it.frame == cand.a } &&
                    track.observations.any { it.frame == cand.b }
                if (!inSeed) continue
                seedTracks++
                val tp = triangulateTrack(ti, stats) ?: continue
                if (tp.error <= maxReprojErrorPx * gateScale) points.add(tp)
            }
            return SeedAttempt(cand, gateScale, points.size, seedTracks, stats[0], stats[1])
        }

        var pairsTried = 0
        var pairsWithEnoughMatches = 0
        var bestAttempt: SeedAttempt? = null
        var bestAttemptPoints = 0

        // Interleaved frame-major order over gaps 1..seedMaxGap. The old
        // gap-major order spent the entire pair budget on gap-1 pairs first,
        // and ranking candidates by raw inlier count then tried only the top
        // 8 — always narrow-baseline pairs with the LEAST parallax, which is
        // exactly how the seed died with "best was frames 17,18". (An earlier
        // flat take(24) cap had the same shape of bug for larger captures.)
        // The budget covers every pair within seedMaxGap for up to ~53 frames
        // (the pipeline caps keyframes at 48).
        val seedMaxGap = minOf(6, frames.size - 1)
        val seedPairBudget = (frames.size * seedMaxGap).coerceIn(48, 320)
        val tryPairs = sequence {
            for (f in 0 until frames.size - 1) {
                for (gap in 1..seedMaxGap) {
                    if (f + gap < frames.size) yield(f to f + gap)
                }
            }
        }.take(seedPairBudget)
        for ((a, b) in tryPairs) {
            pairsTried++
            val matches = FeatureMatcher.match(
                features[a], features[b],
                radius = matchRadius * (b - a),
                censusMaxBits = 5,
                minNcc = 0.55f
            )
            if (matches.size < 18) continue
            pairsWithEnoughMatches++
            val pa = FloatArray(matches.size * 2)
            val pb = FloatArray(matches.size * 2)
            for ((k, m) in matches.withIndex()) {
                pa[2 * k] = features[a].x(m.i); pa[2 * k + 1] = features[a].y(m.i)
                pb[2 * k] = features[b].x(m.j); pb[2 * k + 1] = features[b].y(m.j)
            }
            val res = TwoViewGeometry.relativePose(
                matches, pa, pb, fx0, fy0, cx0, cy0,
                iterations = ransacIterations
            ) ?: continue
            // 8-9 RANSAC inliers is a coin flip; a seed needs a real model.
            if (res.inlierCount < 10) continue
            val attempt = attemptSeed(SeedCandidate(a, b, res), 2f)
            if (bestAttempt == null || attempt.pointCount > bestAttemptPoints) {
                bestAttempt = attempt
                bestAttemptPoints = attempt.pointCount
            }
            // Confident seed — points ARE the parallax evidence, so there is
            // no reason to keep matching pairs once plenty have survived.
            if (bestAttemptPoints >= 50) break
        }

        val firstAttempt = bestAttempt
        if (firstAttempt == null) {
            lastFailureReason = "no viable 2-view seed among $pairsTried pairs tried across ${frames.size} frames " +
                "($pairsWithEnoughMatches had 18+ matches, but none passed essential-matrix RANSAC with enough inliers)"
            return null
        }

        // A pair can clear RANSAC yet still land just under the 20-point bar
        // once the parallax + reprojection gates run. Give the best pair one
        // more chance under the same 3x gate the rest of the pipeline uses.
        var winner = firstAttempt
        if (winner.pointCount < 20) {
            val relaxed = attemptSeed(firstAttempt.cand, 3f)
            if (relaxed.pointCount > winner.pointCount) winner = relaxed
        }
        if (winner.pointCount < 20) {
            val c = winner.cand
            lastFailureReason = "tried $pairsTried candidate seed pairs across ${frames.size} frames " +
                "($pairsWithEnoughMatches had 18+ matches); best was frames ${c.a},${c.b} with " +
                "${winner.seedTracks} tracks spanning the pair but only ${winner.pointCount} points surviving the " +
                "${winner.gatePx}px gate (need 20+): ${winner.parallaxFail} failed the 1.8 deg parallax check, " +
                "${winner.degenerate} triangulated degenerately, ${winner.errorFail} exceeded the error gate — " +
                "likely too little camera movement between any pair of views tried"
            return null
        }

        // Re-run the winner so poses[]/points[] hold the chosen seed.
        val seedA = winner.cand.a
        attemptSeed(winner.cand, winner.gateScale)

        // ---- 6. Incremental pose estimation for remaining frames ----
        val order = buildList {
            for (f in frames.indices) if (poses[f] == null) add(f)
        }.sortedBy { abs(it - seedA) }

        fun registeredFrames(): List<Int> = frames.indices.filter { poses[it] != null }

        /**
         * Register frame [f] against the already-posed frame [ref] via
         * 2D-2D essential matching, then recover the arbitrary relative
         * scale by 1D search over the known triangulated points.
         *
         * This replaces 2D-3D DLT PnP here: chained tracks carry enough
         * outliers that a 10-point RANSAC sample is almost never all-inlier,
         * and smaller samples make the DLT null space ill-defined. The
         * essential path reuses the strong, well-tested two-view RANSAC.
         */
        fun registerFrameByEssential(f: Int, ref: Int): FloatArray? {
            val matches = FeatureMatcher.match(
                features[ref], features[f],
                radius = matchRadius, censusMaxBits = 5, minNcc = 0.55f
            )
            if (matches.size < 18) return null
            val pa = FloatArray(matches.size * 2)
            val pb = FloatArray(matches.size * 2)
            for ((k, m) in matches.withIndex()) {
                pa[2 * k] = features[ref].x(m.i); pa[2 * k + 1] = features[ref].y(m.i)
                pb[2 * k] = features[f].x(m.j); pb[2 * k + 1] = features[f].y(m.j)
            }
            val fr = frames[ref]
            val rel = TwoViewGeometry.relativePose(
                matches, pa, pb, fr.fx, fr.fy, fr.cx, fr.cy,
                iterations = ransacIterations
            )
            rel ?: return null
            if (rel.inlierCount < 8) return null

            // Correlations between matches and known 3D points via the track
            // map — RESTRICTED to the essential-matrix inliers. The full match
            // set carries ~30% outliers which bury the true scale signal.
            val pointByTrack = HashMap<Int, TrackPoint>(points.size)
            points.forEach { pointByTrack[it.track] = it }
            data class C(val xf: Float, val yf: Float, val X: FloatArray) {
                override fun equals(other: Any?): Boolean {
                    if (this === other) return true
                    if (other !is C) return false
                    if (xf != other.xf || yf != other.yf) return false
                    return X.contentEquals(other.X)
                }

                override fun hashCode(): Int {
                    var result = xf.hashCode()
                    result = 31 * result + yf.hashCode()
                    result = 31 * result + X.contentHashCode()
                    return result
                }
            }
            val corr = ArrayList<C>(matches.size)
            for ((k, m) in matches.withIndex()) {
                if (!rel.inlierMask[k]) continue
                val tid = trackIndexByKey[keyOf(ref, m.i)] ?: continue
                val tp = pointByTrack[tid] ?: continue
                corr.add(C(pb[2 * k], pb[2 * k + 1], tp.xyz))
            }
            if (corr.size < 5) return null

            // COMPOSE with the reference pose: rel.poseB is cam_f-to-world in
            // the gauge where cam_ref sits at IDENTITY, but the global gauge
            // has cam_ref at poses[ref]. Skipping this composition makes
            // every scale candidate wrong (observed: 0-2 inliers of 25-50).
            // For cam-to-world matrices (R1, C1) and (R2, C2):
            //   R = R1 R2,  C = R1 C2 + C1
            val refPose = poses[ref]!!
            val relPose = rel.poseB
            val r1 = floatArrayOf(refPose[0], refPose[4], refPose[8], refPose[1], refPose[5], refPose[9], refPose[2], refPose[6], refPose[10])
            val r2 = floatArrayOf(relPose[0], relPose[4], relPose[8], relPose[1], relPose[5], relPose[9], relPose[2], relPose[6], relPose[10])
            val rComp = SfmMath.multiply3(r1, r2)
            val c2 = floatArrayOf(relPose[12], relPose[13], relPose[14])
            val cComp = floatArrayOf(
                r1[0] * c2[0] + r1[1] * c2[1] + r1[2] * c2[2] + refPose[12],
                r1[3] * c2[0] + r1[4] * c2[1] + r1[5] * c2[2] + refPose[13],
                r1[6] * c2[0] + r1[7] * c2[1] + r1[8] * c2[2] + refPose[14]
            )
            // Base composed pose (scale 1); the scale search only adjusts C
            val basePose = FloatArray(16)
            for (r in 0 until 3) for (cc in 0 until 3) basePose[cc * 4 + r] = rComp[r * 3 + cc]
            basePose[15] = 1f

            // 1D scale search over the COMPOSED translation, scored by
            // INLIER COUNT with a generous 12 px gate (the 3D points carry
            // real triangulation noise; a tight gate never sees the true
            // scale win). Ties broken by lower mean error.
            val frame = frames[f]
            var bestS = 1f
            var bestInl = -1
            var bestMean = Float.MAX_VALUE
            for (step in 0 until 40) {
                val s = 0.2f + 5.8f * step / 39f
                val candidate = basePose.copyOf()
                candidate[12] = refPose[12] + s * (cComp[0] - refPose[12])
                candidate[13] = refPose[13] + s * (cComp[1] - refPose[13])
                candidate[14] = refPose[14] + s * (cComp[2] - refPose[14])
                var inl = 0
                var sum = 0f
                for (c in corr) {
                    val p = SfmMath.projectWithPose(
                        candidate, frame.fx, frame.fy, frame.cx, frame.cy,
                        c.X[0], c.X[1], c.X[2]
                    ) ?: continue
                    val dx = p[0] - c.xf
                    val dy = p[1] - c.yf
                    val e2 = dx * dx + dy * dy
                    if (e2 <= 144f) {
                        inl++
                        sum += sqrt(e2)
                    }
                }
                val mean = if (inl > 0) sum / inl else Float.MAX_VALUE
                if (inl > bestInl || (inl == bestInl && mean < bestMean)) {
                    bestInl = inl
                    bestMean = mean
                    bestS = s
                }
            }
            if (bestInl < 5 || (corr.size >= 8 && bestInl < corr.size / 4)) return null

            val scaled = basePose.copyOf()
            scaled[12] = refPose[12] + bestS * (cComp[0] - refPose[12])
            scaled[13] = refPose[13] + bestS * (cComp[1] - refPose[13])
            scaled[14] = refPose[14] + bestS * (cComp[2] - refPose[14])

            // Refine with DLT PnP over the correspondences this pose already
            // explains — removes residual rotation error and locks the scale.
            val inlierXs = ArrayList<FloatArray>()
            val inlierU = ArrayList<Float>()
            val inlierV = ArrayList<Float>()
            for (c in corr) {
                val p = SfmMath.projectWithPose(
                    scaled, frame.fx, frame.fy, frame.cx, frame.cy,
                    c.X[0], c.X[1], c.X[2]
                ) ?: continue
                val dx = p[0] - c.xf
                val dy = p[1] - c.yf
                if (dx * dx + dy * dy <= 144f) { // 12 px
                    inlierXs.add(c.X)
                    inlierU.add(c.xf)
                    inlierV.add(c.yf)
                }
            }
            if (inlierXs.size < 5) return scaled
            return solvePoseDlt(inlierU, inlierV, inlierXs, frame.fx, frame.fy, frame.cx, frame.cy) ?: scaled
        }

        for (f in order) {
            val pose = registeredFrames()
                .sortedBy { abs(it - f) }
                .take(5)
                .firstNotNullOfOrNull { ref -> registerFrameByEssential(f, ref) }
            if (pose != null) {
                poses[f] = pose
                // Triangulate any tracks newly covered by this frame
                val usedTracks = HashSet<Int>(points.size).also { s -> points.forEach { s.add(it.track) } }
                var added = 0
                for ((ti, track) in tracks.withIndex()) {
                    if (track.observations.none { it.frame == f }) continue
                    if (ti in usedTracks) continue
                    val tp = triangulateTrack(ti) ?: continue
                    if (tp.error <= maxReprojErrorPx * 3f) {
                        points.add(tp)
                        usedTracks.add(ti)
                        added++
                    }
                }
                onProgress?.invoke("Registered frame $f (+$added points)", 35 + (60 * order.indexOf(f) / order.size))
            }
        }

        val registeredCount = poses.count { it != null }
        if (registeredCount < 2 || points.size < 12) {
            lastFailureReason = "after incremental registration only $registeredCount/${frames.size} frames posed and ${points.size} points triangulated (need 2+ frames, 12+ points) — most frames likely failed to match against an already-posed neighbour"
            return null
        }

        // ---- 7. Alternating refinement ----
        onProgress?.invoke("Refining reconstruction...", 88)
        val registered = frames.indices.filter { poses[it] != null }
        for (round in 0 until 2) {
            // (a) Re-refine each registered pose from its 2D-3D correspondences
            for (f in registered) {
                if (f == seedA) continue // keep the gauge fixed
                data class C(val x: Float, val y: Float, val X: FloatArray) {
                    override fun equals(other: Any?): Boolean {
                        if (this === other) return true
                        if (other !is C) return false
                        if (x != other.x || y != other.y) return false
                        return X.contentEquals(other.X)
                    }

                    override fun hashCode(): Int {
                        var result = x.hashCode()
                        result = 31 * result + y.hashCode()
                        result = 31 * result + X.contentHashCode()
                        return result
                    }
                }
                val corr = ArrayList<C>()
                for (tp in points) {
                    for (o in tracks[tp.track].observations) {
                        if (o.frame == f) { corr.add(C(o.x, o.y, tp.xyz)); break }
                    }
                }
                if (corr.size >= 8) {
                    estimatePoseRansac(
                        corr.map { it.x }, corr.map { it.y }, corr.map { it.X },
                        frames[f].fx, frames[f].fy, frames[f].cx, frames[f].cy
                    )?.let { poses[f] = it }
                }
            }
            // (b) Re-triangulate every point from the improved poses.
            // The gate must reflect REAL track noise: census/patch matching on
            // a quantized grid carries ~2 px of jitter, so a strict 1.5 px
            // gate prunes virtually everything and the whole reconstruction
            // dies here. 3x the nominal gate keeps genuinely wrong points
            // (usually >> 6 px) out while retaining the noisy-but-correct.
            val kept = ArrayList<TrackPoint>(points.size)
            for (tp in points) {
                val t = triangulateTrack(tp.track) ?: continue
                if (t.error <= maxReprojErrorPx * 3f) kept.add(t)
            }
            points.clear()
            points.addAll(kept)
            if (points.size < 20) {
                lastFailureReason = "after refinement round ${round + 1} only ${points.size} points survived " +
                    "the ${maxReprojErrorPx * 3f}px reprojection gate (need 20) — poses and points are inconsistent, " +
                    "likely from a weak seed or bad registrations"
                return null
            }
        }

        // ---- 7b. Salvage pass: triangulate every remaining track now that
        // all poses are final. Tracks that skipped the incremental phase
        // (never in the seed, or rejected by a then-tighter gate) are the
        // bulk of the map at this point and cost nothing to recover.
        run {
            val usedTracks = HashSet<Int>(points.size).also { s -> points.forEach { s.add(it.track) } }
            for (ti in tracks.indices) {
                if (ti in usedTracks) continue
                val tp = triangulateTrack(ti) ?: continue
                if (tp.error <= maxReprojErrorPx * 3f) {
                    points.add(tp)
                }
            }
        }

        // ---- 8. Colors + output ----
        onProgress?.invoke("Colorizing points...", 94)
        val pointXyz = ArrayList<FloatArray>(points.size)
        val pointRgb = ArrayList<IntArray>(points.size)
        for (tp in points) {
            pointXyz.add(tp.xyz)
            pointRgb.add(sampleColor(frames, tracks[tp.track]))
        }

        // ---- 8b. Metric scale recovery ----
        // SfM poses are in arbitrary scale (the gauge is set by seedA at
        // identity). Recover the METRIC scale by comparing SfM camera
        // baselines to ARCore camera baselines — the AR poses are metric and
        // always available (SfmProcessor loads them from pose_*.txt).
        //
        // The previous code normalised by the median nearest-NEIGHBOUR
        // distance among the sparse points, which measures point DENSITY,
        // not scene extent. With many SfM points (typical real scans) this
        // blew the scene up to tens of metres, so the plane-sweep depth
        // search range (0.5–8 m) never covered the actual depths and the
        // fused mesh came out as a handful of fragments.
        val metricScale = computeMetricScale(frames, poses)
        if (metricScale > 0f) {
            for (p in pointXyz) {
                p[0] *= metricScale; p[1] *= metricScale; p[2] *= metricScale
            }
            for (i in poses.indices) {
                val pose = poses[i] ?: continue
                pose[12] *= metricScale; pose[13] *= metricScale; pose[14] *= metricScale
            }
        } else {
            // Fallback: normalise by bounding-box diagonal to ~3 m so depths
            // land inside the plane-sweep search range.
            val extent = estimateSceneExtent(pointXyz)
            if (extent > 0f) {
                val s = 3f / extent
                for (p in pointXyz) {
                    p[0] *= s; p[1] *= s; p[2] *= s
                }
                for (i in poses.indices) {
                    val pose = poses[i] ?: continue
                    pose[12] *= s; pose[13] *= s; pose[14] *= s
                }
            }
        }

        val mesh = TriangleMesh(
            FloatArray(pointXyz.size * 3),
            FloatArray(pointXyz.size * 3),
            IntArray(0)
        )
        for ((i, p) in pointXyz.withIndex()) {
            mesh.positions[3 * i] = p[0]
            mesh.positions[3 * i + 1] = p[1]
            mesh.positions[3 * i + 2] = p[2]
            val c = pointRgb[i]
            mesh.colors[3 * i] = ((c[0] shr 16) and 0xFF) / 255f
            mesh.colors[3 * i + 1] = ((c[0] shr 8) and 0xFF) / 255f
            mesh.colors[3 * i + 2] = (c[0] and 0xFF) / 255f
        }
        mesh.computeNormals()

        onProgress?.invoke("Photo SfM done — ${points.size} points, $registeredCount frames", 98)
        return SfmResult(poses.toList(), pointXyz, pointRgb, tracks, mesh)
    }

    /**
     * Estimate a camera pose from 2D-3D correspondences by RANSAC over
     * 3-point samples (solve for pose from 3 points via two-indices trial —
     * a lightweight alternative to full P3P: sample 3 points, compute pose
     * from the strongest inlier consensus using the DLT-based linear method
     * over 6+ points, keep the best consensus).
     */
    internal fun estimatePoseRansac(
        xs: List<Float>, ys: List<Float>,
        Xs: List<FloatArray>,
        fx: Float, fy: Float, cx: Float, cy: Float
    ): FloatArray? {
        val n = xs.size
        if (n < 8) return null
        // Sample size must never exceed n, or the uniqueness loop below
        // spins forever (8-9 correspondence calls are legal here).
        val sampleSize = minOf(10, n)
        val rng = Random(12345L)

        var bestPose: FloatArray? = null
        var bestInliers = 0
        var bestMask = BooleanArray(n)
        var solved = 0
        var attempts = 0

        repeat(ransacIterations.coerceAtMost(160)) {
            // Sample of 10 correspondences. A 6-point "minimal" sample makes
            // the 12-unknown normal-equation system square: with any noise it
            // is full rank, there is no true null vector, and the smallest
            // eigenvector is pure noise — the returned pose fits nothing
            // (bestInliers stagnated at 6–7 of 100+ with 6-point samples).
            // 10 points give 20 equations for rank ≤ 11, so the smallest
            // eigenvector is a proper least-squares solution.
            val idx = mutableSetOf<Int>()
            while (idx.size < sampleSize) idx.add(rng.nextInt(n))
            attempts++
            val pose = solvePoseDlt(
                idx.map { xs[it] }, idx.map { ys[it] }, idx.map { Xs[it] }, fx, fy, cx, cy
            ) ?: return@repeat
            solved++
            var inliers = 0
            val mask = BooleanArray(n)
            // Inlier gate adapts to the 3D-point quality: triangulated points
            // carry pixel-level noise amplified by the ray geometry, so a
            // fixed tight threshold rejects even the TRUE pose (observed:
            // 0 inliers of 105 with a 2px gate on clean synthetic data).
            // The gate scales with the median error of the candidate pose and
            // is clamped to [maxReprojErrorPx, 12px].
            val valid = BooleanArray(n)
            val errs = FloatArray(n)
            var have = 0
            for (k in 0 until n) {
                val p = SfmMath.projectWithPose(pose, fx, fy, cx, cy, Xs[k][0], Xs[k][1], Xs[k][2])
                if (p == null) continue
                val dx = p[0] - xs[k]
                val dy = p[1] - ys[k]
                errs[k] = sqrt(dx * dx + dy * dy)
                valid[k] = true
                have++
            }
            var gatePx = maxReprojErrorPx
            if (have > 0) {
                val sorted = errs.filterIndexed { i, _ -> valid[i] }.sorted()
                if (sorted.isNotEmpty()) {
                    val median = sorted[sorted.size / 2]
                    gatePx = (median * 3f).coerceIn(maxReprojErrorPx, 12f)
                }
            }
            val gate2 = gatePx * gatePx
            for (k in 0 until n) {
                if (valid[k] && errs[k] * errs[k] <= gate2) {
                    inliers++
                    mask[k] = true
                }
            }
            if (inliers > bestInliers) {
                bestInliers = inliers
                bestPose = pose
                bestMask = mask
            }
        }
        bestPose ?: return null
        if (bestInliers < 8) return null

        // Final linear solve over all inliers
        val inlierIdx = (0 until n).filter { bestMask[it] }
        return solvePoseDlt(
            inlierIdx.map { xs[it] }, inlierIdx.map { ys[it] }, inlierIdx.map { Xs[it] },
            fx, fy, cx, cy
        ) ?: bestPose
    }

    /**
     * Linear (DLT) pose solve: for each correspondence
     *   u = (P X)_0 / (P X)_2, v = (P X)_1 / (P X)_2
     * with P = K [R|t]. Using world->camera rows Ri = R^T rows and
     * tc = -R^T t, each point gives 2 linear equations in the 12 unknowns
     * of [R^T | tc]. We solve the homogeneous system (as [R^T|tc] up to
     * scale) via the 12x12 normal equations, then orthonormalize R^T and
     * fix the scale from the depth signs.
     */
    internal fun solvePoseDlt(
        xs: List<Float>, ys: List<Float>, Xs: List<FloatArray>,
        fx: Float, fy: Float, cx: Float, cy: Float
    ): FloatArray? {
        val n = xs.size
        if (n < 6) return null

        // Build 2n x 12 system: for point (u, v, X):
        // row1: [X^T, 0, -u*X^T] with K absorbed: u' = (u - cx)/fx, v' likewise
        val ata = DoubleArray(144)
        for (k in 0 until n) {
            val u = (xs[k] - cx) / fx
            val v = (ys[k] - cy) / fy
            val X = Xs[k]
            val row1 = doubleArrayOf(
                X[0].toDouble(), X[1].toDouble(), X[2].toDouble(), 1.0,
                0.0, 0.0, 0.0, 0.0,
                (-u * X[0]).toDouble(), (-u * X[1]).toDouble(), (-u * X[2]).toDouble(), (-u).toDouble()
            )
            val row2 = doubleArrayOf(
                0.0, 0.0, 0.0, 0.0,
                X[0].toDouble(), X[1].toDouble(), X[2].toDouble(), 1.0,
                (-v * X[0]).toDouble(), (-v * X[1]).toDouble(), (-v * X[2]).toDouble(), (-v).toDouble()
            )
            for (row in arrayOf(row1, row2)) {
                for (i in 0 until 12) {
                    for (j in 0 until 12) {
                        ata[i * 12 + j] += row[i] * row[j]
                    }
                }
            }
        }

        val (values, vectors) = SfmMath.jacobiEigen(ata, 12)
        if (values[11] > 1e-6 * abs(values[0]) + 1e-14) return null
        val m = DoubleArray(12)
        for (i in 0 until 12) m[i] = vectors[11 * 12 + i]
        if (!m.all { it.isFinite() }) return null

        // m = rows of the world->camera matrix [R_wc | tc], stacked:
        // r1 = m[0..3], r2 = m[4..7], r3 = m[8..11]. The rotation block is
        // the first 3 columns of each row (skipping each row's 4th element).
        val rt = FloatArray(9)
        val rowStarts = intArrayOf(0, 4, 8)
        for (r in 0 until 3) {
            for (c in 0 until 3) {
                rt[r * 3 + c] = m[rowStarts[r] + c].toFloat()
            }
        }
        val tc = floatArrayOf(m[3].toFloat(), m[7].toFloat(), m[11].toFloat())

        // The null-space vector has arbitrary sign; a negative-sign solution
        // makes R_wc a reflection. Flip the whole solution (rotation AND
        // translation) so the rotation block is a proper rotation.
        if (SfmMath.det3(rt) < 0f) {
            for (i in 0 until 9) rt[i] = -rt[i]
            for (i in 0 until 3) tc[i] = -tc[i]
        }

        // Orthonormalize R^T = U S V^T via eigendecomposition of (R^T)^T (R^T)
        val rtrt = FloatArray(9)
        for (r in 0 until 3) {
            for (c in 0 until 3) {
                var acc = 0f
                for (k in 0 until 3) acc += rt[k * 3 + r] * rt[k * 3 + c]
                rtrt[r * 3 + c] = acc
            }
        }
        val (sv, vec) = SfmMath.jacobiEigen(rtrt, 3)
        if (abs(sv[0]) < 1e-9f) return null
        val s0 = sqrt(sv[0].coerceAtLeast(0f))
        val s1 = sqrt(sv[1].coerceAtLeast(0f))
        val s2 = sqrt(sv[2].coerceAtLeast(0f))
        if ((s0 + s1 + s2) / 3f < 1e-9f) return null

        // Closest orthogonal matrix via the polar decomposition:
        // R^T_polar = R^T (R^T R^T^T)^{-1/2}, with (R^T R^T^T)^{-1/2} = Σ (1/s_i) v_i v_i^T
        val invSqrt = FloatArray(9)
        for (r in 0 until 3) {
            for (c in 0 until 3) {
                var acc = 0f
                for (i in 0 until 3) {
                    val s = when (i) {
                        0 -> s0
                        1 -> s1
                        else -> s2
                    }
                    if (s > 1e-9f) acc += (vec[i * 3 + r] * vec[i * 3 + c]) / s
                }
                invSqrt[r * 3 + c] = acc
            }
        }
        val rtPolar = SfmMath.multiply3(rt, invSqrt)
        if (SfmMath.det3(rtPolar) < 0f) return null // should not happen after the sign fix

        // The homogeneous solution has arbitrary scale: rt = k * R_wc with
        // k = mean singular value (since R_wc is orthonormal, all three
        // singular values equal k). Undo that scale so the translation is in
        // the same units as the triangulated points — otherwise the camera
        // lands at a wrong distance and every reprojection check fails.
        val k = (s0 + s1 + s2) / 3f
        if (k <= 1e-9f) return null
        tc[0] /= k; tc[1] /= k; tc[2] /= k

        // World->camera rotation R_wc = rtPolar (rows). Camera-to-world R_cw = rtPolar^T.
        // Build the pose: columns of the pose rotation block = camera axes in world = rows of R_wc? No:
        // X_cam = R_wc (X_w - C). Pose (cam->world): X_w = R_cw X_cam + C with R_cw = R_wc^T.
        val rcw = FloatArray(9)
        for (r in 0 until 3) for (c in 0 until 3) rcw[r * 3 + c] = rtPolar[c * 3 + r]

        // Camera center: tc = -R_wc C  =>  C = -R_wc^T tc
        val center = floatArrayOf(
            -(rtPolar[0] * tc[0] + rtPolar[3] * tc[1] + rtPolar[6] * tc[2]),
            -(rtPolar[1] * tc[0] + rtPolar[4] * tc[1] + rtPolar[7] * tc[2]),
            -(rtPolar[2] * tc[0] + rtPolar[5] * tc[1] + rtPolar[8] * tc[2])
        )

        val pose = FloatArray(16)
        // Column-major: pose[col*4 + row]
        for (r in 0 until 3) {
            for (c in 0 until 3) {
                pose[c * 4 + r] = rcw[r * 3 + c]
            }
        }
        pose[12] = center[0]; pose[13] = center[1]; pose[14] = center[2]; pose[15] = 1f

        // Orientation check: at least half the points must be in front
        var front = 0
        for (X in Xs) {
            val p = SfmMath.projectWithPose(pose, fx, fy, cx, cy, X[0], X[1], X[2])
            if (p != null) front++
        }
        if (front * 2 < Xs.size) {
            // Wrong direction: reflect the camera (flip rotation and center)
            return null
        }
        return pose
    }

    /**
     * Recover the metric scale by comparing SfM camera baselines to ARCore
     * camera baselines. Both describe the same camera trajectory; the ratio
     * of AR baseline to SfM baseline is the scale factor that brings the SfM
     * reconstruction into metric units. Returns the median ratio, or 0 when
     * too few AR poses are available.
     */
    private fun computeMetricScale(frames: List<SfmFrame>, poses: Array<FloatArray?>): Float {
        val ratios = ArrayList<Float>()
        for (i in frames.indices) {
            val pi = poses[i] ?: continue
            val arI = frames[i].arPose ?: continue
            for (j in i + 1 until frames.size) {
                val pj = poses[j] ?: continue
                val arJ = frames[j].arPose ?: continue
                val sdx = pi[12] - pj[12]; val sdy = pi[13] - pj[13]; val sdz = pi[14] - pj[14]
                val sfmBaseline = sqrt(sdx * sdx + sdy * sdy + sdz * sdz)
                val adx = arI[12] - arJ[12]; val ady = arI[13] - arJ[13]; val adz = arI[14] - arJ[14]
                val arBaseline = sqrt(adx * adx + ady * ady + adz * adz)
                if (sfmBaseline > 1e-6f && arBaseline > 1e-6f) {
                    ratios.add(arBaseline / sfmBaseline)
                }
            }
        }
        if (ratios.size < 3) return 0f
        ratios.sort()
        return ratios[ratios.size / 2]
    }

    /** Bounding-box diagonal of the point cloud — a robust scene-extent gauge. */
    private fun estimateSceneExtent(points: List<FloatArray>): Float {
        if (points.size < 8) return 0f
        var minX = Float.MAX_VALUE; var minY = Float.MAX_VALUE; var minZ = Float.MAX_VALUE
        var maxX = -Float.MAX_VALUE; var maxY = -Float.MAX_VALUE; var maxZ = -Float.MAX_VALUE
        for (p in points) {
            if (p[0] < minX) minX = p[0]
            if (p[1] < minY) minY = p[1]
            if (p[2] < minZ) minZ = p[2]
            if (p[0] > maxX) maxX = p[0]
            if (p[1] > maxY) maxY = p[1]
            if (p[2] > maxZ) maxZ = p[2]
        }
        val dx = maxX - minX; val dy = maxY - minY; val dz = maxZ - minZ
        return sqrt(dx * dx + dy * dy + dz * dz)
    }

    /** Sample an RGB color for a track from its observations. */
    private fun sampleColor(frames: List<SfmFrame>, track: Track): IntArray {
        var r = 0; var g = 0; var b = 0; var count = 0
        for (o in track.observations) {
            val f = frames[o.frame]
            val u = o.x.toInt().coerceIn(0, f.width - 1)
            val v = o.y.toInt().coerceIn(0, f.height - 1)
            val c = f.pixels[v * f.width + u]
            r += (c ushr 16) and 0xFF
            g += (c ushr 8) and 0xFF
            b += c and 0xFF
            count++
            if (count >= 5) break
        }
        if (count == 0) return intArrayOf(128, 128, 128)
        return intArrayOf(r / count, g / count, b / count)
    }
}
