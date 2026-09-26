package com.example.splatter.processor.sfm

import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Two-view geometry: robust (RANSAC) essential-matrix estimation from pixel
 * correspondences, followed by decomposition into the relative rotation and
 * translation using the cheirality (positive-depth) test.
 *
 * Pure Kotlin — no Android dependencies.
 */
object TwoViewGeometry {

    /** Result of the robust relative-pose estimation. */
    class Result(
        /** 3x3 row-major essential matrix (normalized camera coordinates). */
        val E: FloatArray,
        /** 16-float column-major camera-to-world pose of camera B, with camera A at identity. */
        val poseB: FloatArray,
        /** Number of RANSAC inliers for the winning model. */
        val inlierCount: Int,
        /** Per-match inlier flags for the winning model. */
        val inlierMask: BooleanArray
    )

    /** Deterministic xorshift RNG so repeated runs give identical results. */
    private class Rng(seed: Long) {
        private var s = seed or 1L
        fun nextInt(bound: Int): Int {
            s = s xor (s shl 13); s = s xor (s ushr 17); s = s xor (s shl 5)
            return ((s ushr 1) % bound).toInt()
        }
    }

    /**
     * Estimate the pose of camera B relative to camera A (A at identity).
     *
     * @param matches putative matches (indices into the feature lists)
     * @param pointsA flat xy pixel coordinates in image A
     * @param pointsB flat xy pixel coordinates in image B
     */
    fun relativePose(
        matches: List<FeatureMatcher.Match>,
        pointsA: FloatArray,
        pointsB: FloatArray,
        fx: Float, fy: Float, cx: Float, cy: Float,
        iterations: Int = 256,
        thresholdPx: Float = 1.5f,
        seed: Long = 0x5F3759DFL
    ): Result? {
        val n = matches.size
        if (n < 8) return null
        require(pointsA.size >= n * 2 && pointsB.size >= n * 2) {
            "point arrays must be per-match: n*2 floats"
        }

        // Normalized image coordinates. pointsA/pointsB hold ONE (x, y) pair
        // per match in match order — not the full feature lists. (Indexing
        // them by matches[k].i reads a wrong-but-in-bounds pixel when the
        // feature lists are bigger than the match count, and crashes when
        // they are smaller.)
        val na = FloatArray(n * 2)
        val nb = FloatArray(n * 2)
        for (k in 0 until n) {
            na[2 * k] = (pointsA[2 * k] - cx) / fx
            na[2 * k + 1] = (pointsA[2 * k + 1] - cy) / fy
            nb[2 * k] = (pointsB[2 * k] - cx) / fx
            nb[2 * k + 1] = (pointsB[2 * k + 1] - cy) / fy
        }
        val thresholdNorm = thresholdPx / fx

        val rng = Rng(seed)
        var bestE: FloatArray? = null
        var bestMask: BooleanArray? = null
        var bestCount = 0
    
        repeat(iterations) {
            // Minimal sample of 8 unique correspondences
            val sample = IntArray(8)
            var got = 0
            while (got < 8) {
                val k = rng.nextInt(n)
                var dup = false
                for (g in 0 until got) if (sample[g] == k) { dup = true; break }
                if (!dup) { sample[got++] = k }
            }
            val E = eightPoint(na, nb, sample) ?: return@repeat
            val count = countInliers(E, na, nb, thresholdNorm, null)
            if (count > bestCount) {
                bestCount = count
                bestE = E
            }
        }
        if (bestCount < 8 || bestE == null) return null

        // Re-estimate on the inlier set of the winning model
        val mask = BooleanArray(n)
        val inliers = countInliers(bestE!!, na, nb, thresholdNorm, mask)
        val inlierIdx = (0 until n).filter { mask[it] }
        if (inliers >= 8 && inlierIdx.size >= 8) {
            val refined = eightPoint(na, nb, inlierIdx.toIntArray())
            if (refined != null) {
                val refinedCount = countInliers(refined, na, nb, thresholdNorm, mask)
                if (refinedCount >= inliers) {
                    bestE = refined
                    bestCount = refinedCount
                } else {
                    // keep original model, restore its mask
                    java.util.Arrays.fill(mask, false)
                    countInliers(bestE!!, na, nb, thresholdNorm, mask)
                }
            }
        }

        // Note: no explicit rank-2 projection is applied here. The 8-point
        // estimate is already essentially rank 2 for near-epipolar data, and
        // svd3() handles any residual rank deficiency by rebuilding the third
        // U column as u0 x u1 — a hand-rolled projection with an imperfect
        // SVD underneath corrupted E more than it fixed it.
        val pose = recoverPose(bestE!!, na, nb, mask) ?: return null
        return Result(bestE!!, pose, bestCount, mask)
    }

    /**
     * Normalized 8-point algorithm over the correspondences listed in
     * [sample] (indices into the flat normalized coordinate arrays).
     */
    private fun eightPoint(na: FloatArray, nb: FloatArray, sample: IntArray): FloatArray? {
        // Hartley normalization: centroid at origin, mean distance sqrt(2)
        var cax = 0f; var cay = 0f; var cbx = 0f; var cby = 0f
        for (k in sample) {
            cax += na[2 * k]; cay += na[2 * k + 1]
            cbx += nb[2 * k]; cby += nb[2 * k + 1]
        }
        cax /= sample.size; cay /= sample.size
        cbx /= sample.size; cby /= sample.size

        var dax = 0f; var day = 0f; var dbx = 0f; var dby = 0f
        for (k in sample) {
            dax += dist2(na[2 * k], na[2 * k + 1], cax, cay)
            day += dist2(nb[2 * k], nb[2 * k + 1], cbx, cby)
        }
        val sA = sqrt2OverMean(dax / sample.size, day / sample.size)
        val sB = sqrt2OverMean(dbx / sample.size, dby / sample.size)
        if (sA <= 0f || sB <= 0f || !sA.isFinite() || !sB.isFinite()) return null

        fun tx(x: Float, y: Float, cx: Float, cy: Float, s: Float): Pair<Float, Float> =
            ((x - cx) * s) to ((y - cy) * s)

        // A f = 0 with f = (f1..f9): rows [u2*u1, u2*v1, u2, v2*u1, v2*v1, v2, u1, v1, 1]
        val m = sample.size
        val ata = DoubleArray(81)
        for (k in sample) {
            val (u1, v1) = tx(na[2 * k], na[2 * k + 1], cax, cay, sA)
            val (u2, v2) = tx(nb[2 * k], nb[2 * k + 1], cbx, cby, sB)
            val row = floatArrayOf(u2 * u1, u2 * v1, u2, v2 * u1, v2 * v1, v2, u1, v1, 1f)
            for (i in 0 until 9) {
                for (j in 0 until 9) {
                    ata[i * 9 + j] += (row[i] * row[j]).toDouble()
                }
            }
        }

        val (values, vectors) = SfmMath.jacobiEigen(ata, 9)
        // Smallest eigenvalue's eigenvector (descending order => index 8)
        if (values[8] > 1e-6 * abs(values[0]) + 1e-12) return null
        val fNorm = FloatArray(9) { i -> vectors[8 * 9 + i].toFloat() }
        if (!fNorm.all { it.isFinite() }) return null

        // Un-normalize. fNorm is the essential matrix for the TRANSFORMED
        // points x' = T x, so the original essential matrix is E = T2^T fNorm T1:
        // x2^T E x1 = (T2^-1 x2')^T E (T1^-1 x1') = x2'^T (T2^-T E T1^-1) x1'
        val E = FloatArray(9)
        val t2t = floatArrayOf(
            sB, 0f, 0f,
            0f, sB, 0f,
            -sB * cbx, -sB * cby, 1f
        )
        val t1 = floatArrayOf(
            sA, 0f, -sA * cax,
            0f, sA, -sA * cay,
            0f, 0f, 1f
        )
        val tmp = SfmMath.multiply3(t2t, fNorm)
        for (r in 0 until 3) {
            for (c in 0 until 3) {
                E[r * 3 + c] = tmp[r * 3] * t1[c] + tmp[r * 3 + 1] * t1[3 + c] + tmp[r * 3 + 2] * t1[6 + c]
            }
        }
        val scale = sqrt(norm2(E))
        if (scale < 1e-12f) return null
        for (i in 0 until 9) E[i] /= scale
        return E
    }

    private fun dist2(x: Float, y: Float, cx: Float, cy: Float): Float {
        val dx = x - cx; val dy = y - cy
        return dx * dx + dy * dy
    }

    /** Scale so mean squared distance becomes 2 (i.e. mean distance sqrt(2)). */
    private fun sqrt2OverMean(d2x: Float, d2y: Float): Float {
        val mean = (d2x + d2y) * 0.5f
        if (mean < 1e-12f) return 1f
        return (sqrt(2f) / sqrt(mean))
    }

    private fun norm2(m: FloatArray): Float {
        var acc = 0.0
        for (v in m) acc += (v * v).toDouble()
        return sqrt(acc).toFloat()
    }

    /** Project a 3x3 (row-major) onto the closest rank-2 essential matrix. */
    private fun nearestEssential(E: FloatArray): FloatArray {
        // E^T E eigendecomposition gives V and squared singular values
        val ete = FloatArray(9)
        for (r in 0 until 3) {
            for (c in 0 until 3) {
                var acc = 0f
                for (k in 0 until 3) acc += E[k * 3 + r] * E[k * 3 + c]
                ete[r * 3 + c] = acc
            }
        }
        val (values, vectors) = SfmMath.jacobiEigen(ete, 3)
        val s1 = sqrt(values[0].coerceAtLeast(0f))
        val s2 = sqrt(values[1].coerceAtLeast(0f))
        val m = (s1 + s2) * 0.5f

        // V columns = eigenvectors; U columns = E v_i / s_i
        val u = FloatArray(9)
        for (i in 0 until 2) {
            val v = floatArrayOf(vectors[i * 3], vectors[i * 3 + 1], vectors[i * 3 + 2])
            val ev = SfmMath.rotate3(E, v)
            val s = sqrt(values[i].coerceAtLeast(0f))
            if (s > 1e-9f) {
                u[i * 3] = ev[0] / s; u[i * 3 + 1] = ev[1] / s; u[i * 3 + 2] = ev[2] / s
            } else {
                u[i * 3] = if (i == 0) 1f else 0f; u[i * 3 + 1] = 0f; u[i * 3 + 2] = 0f
            }
        }
        // Third U column to keep U right-handed: u2 x u0
        val u0 = floatArrayOf(u[0], u[1], u[2])
        val u1 = floatArrayOf(u[3], u[4], u[5])
        val u2v = SfmMath.cross3(u0, u1)
        u[6] = u2v[0]; u[7] = u2v[1]; u[8] = u2v[2]

        // E' = U diag(m, m, 0) V^T. u[i*3 + r] holds component r of column
        // u_i (column-wise storage), so u_k[r] = u[k*3 + r] — NOT u[r*3+k].
        val out = FloatArray(9)
        for (r in 0 until 3) {
            for (c in 0 until 3) {
                var acc = 0f
                // sum over the two nonzero singular values
                for (k in 0 until 2) {
                    acc += u[k * 3 + r] * m * vectors[k * 3 + c]
                }
                out[r * 3 + c] = acc
            }
        }
        return out
    }

    /** Sampson-distance inlier counting; fills [mask] when provided. */
    private fun countInliers(
        E: FloatArray,
        na: FloatArray,
        nb: FloatArray,
        threshold: Float,
        mask: BooleanArray?
    ): Int {
        var count = 0
        val n = na.size / 2
        for (k in 0 until n) {
            val x1 = floatArrayOf(na[2 * k], na[2 * k + 1], 1f)
            val x2 = floatArrayOf(nb[2 * k], nb[2 * k + 1], 1f)
            val e1 = SfmMath.rotate3(E, x1)          // E x1
            val e2t = SfmMath.rotate3T(E, x2)        // E^T x2
            val num = SfmMath.dot3(x2, e1)
            val denom = e1[0] * e1[0] + e1[1] * e1[1] + e2t[0] * e2t[0] + e2t[1] * e2t[1]
            val d = if (denom > 1e-12f) num * num / denom else Float.MAX_VALUE
            val inlier = d < threshold * threshold
            if (inlier) count++
            if (mask != null) mask[k] = inlier
        }
        return count
    }

    /**
     * Choose among the four (R, t) hypotheses with the cheirality test:
     * triangulate inliers with P1 = [I|0] and P2 = [R|t]; keep the hypothesis
     * where most points land in front of both cameras.
     */
    private fun recoverPose(E: FloatArray, na: FloatArray, nb: FloatArray, mask: BooleanArray): FloatArray? {
        // SVD of E via E^T E and E E^T eigendecompositions
        val (u, sv, vt) = svd3(E)
        val W = floatArrayOf(0f, -1f, 0f, 1f, 0f, 0f, 0f, 0f, 1f)
        val Wt = floatArrayOf(0f, 1f, 0f, -1f, 0f, 0f, 0f, 0f, 1f)

        val uw = SfmMath.multiply3(u, W)
        val uwt = SfmMath.multiply3(u, Wt)
        var r1 = SfmMath.multiply3(uw, vt)
        var r2 = SfmMath.multiply3(uwt, vt)
        // Handedness normalization (OpenCV-style): if a candidate rotation
        // came out as a reflection (det < 0 — V's column sort can flip
        // handedness), NEGATE the whole matrix. Negation keeps the epipolar
        // relation intact because E is defined only up to sign: if
        // E ∝ [t]× R then also E ∝ [t]× (−R). Cheirality then reliably
        // picks the true motion instead of an impostor.
        if (SfmMath.det3(r1) < 0f) {
            for (i in 0 until 9) r1[i] = -r1[i]
        }
        if (SfmMath.det3(r2) < 0f) {
            for (i in 0 until 9) r2[i] = -r2[i]
        }
        // t = third column of U*W. NOTE: W and W^T share their third column
        // (0,0,1), so both rotations pair with the SAME t direction — the
        // four hypotheses are (R1, ±t) and (R2, ±t). Forgetting the −t half
        // makes valid scenes fail cheirality outright.
        val t1 = floatArrayOf(uw[2], uw[5], uw[8])
        val t1neg = floatArrayOf(-t1[0], -t1[1], -t1[2])

        val p1 = floatArrayOf(
            1f, 0f, 0f, 0f,
            0f, 1f, 0f, 0f,
            0f, 0f, 1f, 0f
        )

        var bestPose: FloatArray? = null
        var bestFront = -1
        for (R in arrayOf(r1, r2)) {
            for (t in arrayOf(t1, t1neg)) {
                val p2 = floatArrayOf(
                    R[0], R[1], R[2], t[0],
                    R[3], R[4], R[5], t[1],
                    R[6], R[7], R[8], t[2]
                )
                var front = 0
                var nulls = 0
                var z1neg = 0; var z2neg = 0
                for (k in 0 until na.size / 2) {
                    if (!mask[k]) continue
                    val X = SfmMath.triangulate(
                        listOf(p1, p2),
                        floatArrayOf(na[2 * k], nb[2 * k]),
                        floatArrayOf(na[2 * k + 1], nb[2 * k + 1])
                    )
                    if (X == null) { nulls++; continue }
                    // Depth in camera 2: z of R X + t
                    val xc = SfmMath.rotate3(R, floatArrayOf(X[0], X[1], X[2]))
                    val z1 = X[2]
                    val z2 = xc[2] + t[2]
                    if (z1 <= 0f) z1neg++
                    if (z2 <= 0f) z2neg++
                    if (z1 > 0f && z2 > 0f) front++
                }
                println("DBG hyp: front=$front nulls=$nulls z1neg=$z1neg z2neg=$z2neg")
                if (front > bestFront) {
                    bestFront = front
                    bestPose = composePose(R, t)
                }
            }
        }

        if (bestFront < 8) return null
        return bestPose
    }

    /** [t]× as a 3x3 row-major matrix times [R]. */
    private fun crossTimes(R: FloatArray, t: FloatArray): FloatArray {
        val skew = floatArrayOf(
            0f, -t[2], t[1],
            t[2], 0f, -t[0],
            -t[1], t[0], 0f
        )
        return SfmMath.multiply3(skew, R)
    }    /**
     * Full 3x3 SVD via one-sided Jacobi (Hestenes) orthogonalization of the
     * columns of M — returns (U, singular values descending, V^T).
     *
     * One-sided Jacobi is used instead of an eigendecomposition of M^T M
     * because an essential matrix has two nearly EQUAL singular values;
     * the M^T M route then yields U/V pairs that are individually
     * orthonormal but mutually inconsistent (the in-subspace basis rotation
     * doesn't cancel exactly), producing impostor (R, t) hypotheses that
     * pass neither the E = [t]x R check nor true cheirality reliably.
     */
    internal fun svd3(M: FloatArray): Triple<FloatArray, FloatArray, FloatArray> {
        // One-sided Jacobi: orthogonalize the COLUMNS of A = M with right
        // rotations J, accumulating V = J1 J2 ...  so that A' = M J is
        // column-orthogonal and M = A' V^T. Column norms of A' are the
        // singular values; U = A' diag(1/sv).
        // (Column storage: col[i] = (a[i], a[3+i], a[6+i]); v[i*3+r] = comp r of v-column i.)
        val a = M.copyOf()
        val v = SfmMath.identity3()

        repeat(60) {
            var converged = true
            for (p in 0 until 2) {
                for (q in p + 1 until 3) {
                    val dp = a[p] * a[p] + a[3 + p] * a[3 + p] + a[6 + p] * a[6 + p]
                    val dq = a[q] * a[q] + a[3 + q] * a[3 + q] + a[6 + q] * a[6 + q]
                    val dpq = a[p] * a[q] + a[3 + p] * a[3 + q] + a[6 + p] * a[6 + q]
                    if (kotlin.math.abs(dpq) <= 1e-15f * sqrt(dp * dq).coerceAtLeast(1e-30f)) continue
                    converged = false
                    val tau = (dq - dp) / (2f * dpq)
                    val t = (if (tau >= 0f) 1f else -1f) / (kotlin.math.abs(tau) + sqrt(1f + tau * tau))
                    val c = 1f / sqrt(1f + t * t)
                    val s = t * c
                    // rotate columns p, q of A
                    for (r in 0 until 3) {
                        val arp = a[r * 3 + p]
                        val arq = a[r * 3 + q]
                        a[r * 3 + p] = c * arp - s * arq
                        a[r * 3 + q] = s * arp + c * arq
                        // same rotation on the columns of V
                        val vrp = v[p * 3 + r]
                        val vrq = v[q * 3 + r]
                        v[p * 3 + r] = c * vrp - s * vrq
                        v[q * 3 + r] = s * vrp + c * vrq
                    }
                }
            }
            if (converged) return@repeat
        }

        // Singular values = column norms of A; sort descending
        data class Col(val sv: Float, val idx: Int)
        val cols = (0 until 3).map { i ->
            Col(sqrt(a[i] * a[i] + a[3 + i] * a[3 + i] + a[6 + i] * a[6 + i]), i)
        }.sortedByDescending { it.sv }
        val sv = FloatArray(3) { cols[it].sv }

        // U row-major: u[r*3 + i] = component r of u_i = column i of A / sv_i
        val u = FloatArray(9)
        for (i in 0 until 3) {
            val src = cols[i].idx
            if (sv[i] > 1e-8f * (sv[0].coerceAtLeast(1e-30f))) {
                u[0 * 3 + i] = a[src] / sv[i]
                u[1 * 3 + i] = a[3 + src] / sv[i]
                u[2 * 3 + i] = a[6 + src] / sv[i]
            }
            // else: left zero — rebuilt below as u0 x u1 (rank-2 essential)
        }
        // The third singular direction of a near-rank-2 matrix is pure noise:
        // its singular value (e.g. 3.6e-6) is far above any sane zero
        // threshold, yet the direction it produces is meaningless and NOT
        // orthogonal to u0/u1. An essential matrix is theoretically rank 2,
        // so ALWAYS rebuild the third column as u0 x u1 (Gram-Schmidt first
        // for numerical safety). Reconstruction is unaffected: sv[2] ~ 0.
        run {
            // U is ROW-MAJOR: column i of U is (u[i], u[3+i], u[6+i]) —
            // NOT the contiguous (u[3i], u[3i+1], u[3i+2]), which is row i.
            val u0 = SfmMath.normalize3(floatArrayOf(u[0], u[3], u[6]))
            val u1r = floatArrayOf(u[1], u[4], u[7])
            val d = SfmMath.dot3(u0, u1r)
            val u1 = SfmMath.normalize3(floatArrayOf(u1r[0] - d * u0[0], u1r[1] - d * u0[1], u1r[2] - d * u0[2]))
            val u2c = SfmMath.cross3(u0, u1)
            // Write COLUMN 2: u[2], u[5], u[8] (row-major). Writing u[6..8]
            // would silently overwrite ROW 2 instead — the bug that made
            // U's third column (and therefore t) garbage.
            u[2] = u2c[0]; u[5] = u2c[1]; u[8] = u2c[2]
        }

        // V^T row-major, sorted: (V^T)[i][k] = V[k][i], and V[k][c] = v[c*3 + k]
        // (verified empirically: E * V = A' with this indexing). Sorting pairs
        // V's column idx(r) with U's column r, so the row index carries idx:
        //   vt[r*3 + c] = V[c][idx(r)] = v[idx(r)*3 + c]
        val vt = FloatArray(9)
        for (r in 0 until 3) {
            for (c in 0 until 3) {
                vt[r * 3 + c] = v[cols[r].idx * 3 + c]
            }
        }
        return Triple(u, sv, vt)
    }

    /** Compose the 16-float column-major camera-to-world pose from R (world-from-cam rotation) and t (camera center in world). */
    private fun composePose(R: FloatArray, t: FloatArray): FloatArray {
        // The decomposed R maps cam1 -> cam2 coords with t as cam2 origin in cam1.
        // Camera B camera-to-world: R^T rotation, position -R^T t.
        val rt = FloatArray(9)
        for (r in 0 until 3) {
            for (c in 0 until 3) {
                rt[r * 3 + c] = R[c * 3 + r]
            }
        }
        val pos = floatArrayOf(
            -(rt[0] * t[0] + rt[1] * t[1] + rt[2] * t[2]),
            -(rt[3] * t[0] + rt[4] * t[1] + rt[5] * t[2]),
            -(rt[6] * t[0] + rt[7] * t[1] + rt[8] * t[2])
        )
        return floatArrayOf(
            rt[0], rt[3], rt[6], 0f,
            rt[1], rt[4], rt[7], 0f,
            rt[2], rt[5], rt[8], 0f,
            pos[0], pos[1], pos[2], 1f
        )
    }
}
