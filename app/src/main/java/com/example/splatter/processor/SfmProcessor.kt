package com.example.splatter.processor

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import com.example.splatter.model.ScanMode
import com.example.splatter.processor.mesh.DepthFilters
import com.example.splatter.processor.mesh.DepthFrame
import com.example.splatter.processor.mesh.DepthMeshFuser
import com.example.splatter.processor.mesh.MeshColorBaker
import com.example.splatter.processor.mesh.PhotoView
import com.example.splatter.processor.mesh.TriangleMesh
import com.example.splatter.processor.sfm.ImageOps
import com.example.splatter.processor.sfm.PlaneSweepStereo
import com.example.splatter.processor.sfm.SfmFrame
import com.example.splatter.processor.sfm.SfmReconstructor
import com.example.splatter.processor.sfm.SfmResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.roundToInt

/**
 * Photo-only photogrammetry pipeline ("Photo SfM" mode) — the true SfM/MVS
 * counterpart of [PhotogrammetryProcessor], which relies on ARCore depth.
 *
 *  1. Keyframe selection — farthest-point sampling of captured frames by
 *     camera position (AR poses are used only for picking good viewpoints;
 *     geometry comes purely from the photos)
 *  2. Structure-from-Motion — corner detection, matching, track chaining,
 *     two-view seeding, incremental pose estimation, alternating refinement
 *     (see [SfmReconstructor])
 *  3. Dense plane-sweep stereo — census-consistency depth maps per keyframe
 *     from the SfM poses (see [PlaneSweepStereo])
 *  4. Mesh fusion — the pseudo depth maps are fused into a textured triangle
 *     mesh by the same [DepthMeshFuser] the depth-assisted mode uses
 *  5. Photo color baking — [MeshColorBaker] over the captured views
 *
 * Everything geometric lives in the pure-Kotlin `processor.sfm` package and
 * is covered by JVM unit tests on synthetic scenes; this class only does
 * dataset I/O and orchestration.
 */
object SfmProcessor {
    private const val TAG = "SfmProcessor"

    /**
     * Max keyframes used for the SfM stage. Raised from 32: keyframe
     * selection farthest-point-samples across the WHOLE orbit, so with only
     * 32 slots a full 360-degree orbit puts >11 degrees between adjacent
     * chosen keyframes — right where the matcher's success rate falls off a
     * cliff. More slots (paired with the matching-tolerance changes in
     * SfmReconstructor) keep adjacent keyframes closer together.
     */
    private const val MAX_SFM_FRAMES = 48

    /** Max reference frames for dense plane-sweep stereo. */
    private const val MAX_DENSE_FRAMES = 12

    /** Max support (comparison) views per dense sweep. */
    private const val MAX_SUPPORT_VIEWS = 5

    /** Longest side of the images used for feature detection. */
    private const val SFM_IMAGE_MAX_DIM = 640

    suspend fun process(
        datasetDir: File,
        scanMode: ScanMode,
        onProgress: (FrameProcessor.ProcessingProgress) -> Unit
    ): TriangleMesh? = withContext(Dispatchers.IO) {
        onProgress(FrameProcessor.ProcessingProgress("Scanning captured frames...", 4, 0))

        if (!datasetDir.exists() || !datasetDir.isDirectory) {
            Log.e(TAG, "Dataset directory does not exist: ${datasetDir.absolutePath}")
            return@withContext null
        }

        // ---- 0. Prefer real depth when the capture has it ----------------
        // A device that recorded raw depth gives us metric, per-pixel geometry,
        // which beats anything plane-sweep stereo can infer from census costs.
        // Fusing it (through the depth pipeline, which also bakes the photos)
        // is what turns this mode from scattered triangle shards into a solid
        // model. The photo-only route below still runs when depth is missing
        // or unusable — the shiny / thin / dark subjects this mode exists for.
        if (DepthAvailability.hasUsableDepth(datasetDir)) {
            Log.i(TAG, "Usable raw depth found — fusing depth + photos instead of plane-sweep stereo")
            onProgress(
                FrameProcessor.ProcessingProgress(
                    "Raw depth found — fusing depth and photos...",
                    6,
                    0
                )
            )
            return@withContext PhotogrammetryProcessor.process(datasetDir, scanMode, onProgress)
        }

        // ---- 1. Enumerate frames (same dataset layout as Photo Mesh) ----
        val frameFiles = enumerateFrames(datasetDir)
        if (frameFiles.size < 6) {
            Log.w(TAG, "Only ${frameFiles.size} usable frames — too few for photo SfM")
            return@withContext null
        }
        Log.i(TAG, "Found ${frameFiles.size} frames for photo SfM")

        // ---- 2. Keyframe selection: farthest-point sampling on AR positions ----
        val keyframeIndices = selectKeyframes(frameFiles, MAX_SFM_FRAMES)
        onProgress(FrameProcessor.ProcessingProgress("Loading ${keyframeIndices.size} keyframes...", 6, 0))

        // ---- 3. Load and preprocess keyframes ----
        data class LoadedFrame(
            val sfm: SfmFrame,
            val pose: FloatArray,
            val fx: Float, val fy: Float, val cx: Float, val cy: Float
        )

        val loaded = ArrayList<LoadedFrame>(keyframeIndices.size)
        for ((n, idx) in keyframeIndices.withIndex()) {
            val ff = frameFiles[idx]
            val bmpData = try {
                loadDownscaledPixels(File(datasetDir, "rgb_${ff.timestamp}.jpg"), SFM_IMAGE_MAX_DIM)
            } catch (e: OutOfMemoryError) {
                Log.e(TAG, "OOM loading frame ${ff.timestamp}", e)
                System.gc()
                null
            } ?: continue

            val scale = bmpData.width.toFloat() / ff.imgWidth
            val sfmFrame = SfmFrame(
                index = loaded.size,
                pixels = bmpData.pixels,
                width = bmpData.width,
                height = bmpData.height,
                fx = (ff.fx * scale).coerceAtLeast(1f),
                fy = (ff.fy * scale).coerceAtLeast(1f),
                cx = ff.cx * scale,
                cy = ff.cy * scale,
                arPose = ff.pose
            )
            loaded.add(LoadedFrame(sfmFrame, ff.pose, ff.fx, ff.fy, ff.cx, ff.cy))

            onProgress(
                FrameProcessor.ProcessingProgress(
                    "Loading keyframes ${loaded.size}/${keyframeIndices.size}...",
                    6 + (n + 1) * 8 / keyframeIndices.size,
                    0
                )
            )
        }
        if (loaded.size < 6) {
            Log.w(TAG, "Only ${loaded.size} keyframes loaded — too few for photo SfM")
            return@withContext null
        }
        val sfmFrames = loaded.map { it.sfm }

        // ---- 4. Structure-from-Motion ----
        val reconstructor = SfmReconstructor(
            maxFeatures = 1000,
            matchRadius = 180f,
            ransacIterations = 300,
            maxReprojErrorPx = 2.5f,
            onProgress = { step, pct ->
                onProgress(
                    FrameProcessor.ProcessingProgress(
                        "Photo SfM: $step",
                        14 + pct * 36 / 100,
                        0
                    )
                )
            }
        )
        val sfm = reconstructor.reconstruct(sfmFrames)
            ?: runRelaxedSfm(sfmFrames, onProgress)

        val solved = resolvePoses(sfmFrames, sfm)
        val poses = solved.poses
        val registered = solved.registered
        Log.i(TAG, "Pose source: ${solved.source} — ${registered.size}/${sfmFrames.size} frames")
        onProgress(
            FrameProcessor.ProcessingProgress(
                "Solving camera motion — ${solved.source}, ${registered.size} views",
                50,
                0,
                note = "Pose source: ${solved.source} (${registered.size}/${sfmFrames.size} registered)"
            )
        )
        if (registered.size < 2) {
            Log.w(TAG, "No usable camera poses for the dense stage")
            return@withContext null
        }

        // ---- 5. Dense plane-sweep stereo ----
        val sweep = PlaneSweepStereo(
            gridWidth = 128,
            gridHeight = 96,
            minDepthM = scanMode.minDepthMeters,
            maxDepthM = scanMode.maxDepthMeters,
            depthSteps = 64,
            maxSupportViews = MAX_SUPPORT_VIEWS,
            maxCost = 0.52f
        )

        // Reference frames: spread over the registered set
        val denseRefs: List<Int> = registered.let { all ->
            if (all.size <= MAX_DENSE_FRAMES) all else evenlySampleIndices(all, MAX_DENSE_FRAMES)
        }

        val fuser = DepthMeshFuser(
            voxelSize = scanMode.voxelSizeMeters,
            maxTriangles = scanMode.maxPointLimit,
            // Plane-sweep depth maps are quantised to ~0.12 m steps (64 steps
            // over 0.5–8 m). The default 0.06 tolerance rejects neighbouring
            // cells that differ by a single depth step, shredding the mesh
            // into fragments. 0.15 keeps genuine depth jumps out while
            // accepting the quantisation noise.
            depthToleranceFactor = 0.15f,
            depthToleranceMin = 0.05f,
            // Photo-only meshes are sparse: a 128x96 sweep over a dozen views
            // yields isolated triangles wherever the cost curve happened to dip.
            // The depth pipeline's default of 15 triangles is far too small to
            // tell a real patch from a stray shard, so require a real cluster.
            minComponentTriangles = 40
        )

        // Camera positions for nearest-support lookup
        val camPos = Array(sfmFrames.size) { i ->
            val p = poses[i]
            if (p != null) floatArrayOf(p[12], p[13], p[14]) else null
        }

        var sweepValid = 0
        for ((n, refIdx) in denseRefs.withIndex()) {
            if (fuser.isFull) break
            val refPose = poses[refIdx] ?: continue
            val ref = sfmFrames[refIdx]

            // Supports: nearest registered cameras (excluding the reference)
            val supports = registered.asSequence()
                .filter { it != refIdx }
                .sortedBy { i ->
                    val c = camPos[i] ?: return@sortedBy Float.MAX_VALUE
                    val dx = c[0] - refPose[12]; val dy = c[1] - refPose[13]; val dz = c[2] - refPose[14]
                    dx * dx + dy * dy + dz * dz
                }
                .take(MAX_SUPPORT_VIEWS)
                .map { i ->
                    PlaneSweepStereo.SweepFrame(
                        pixels = sfmFrames[i].pixels,
                        width = sfmFrames[i].width,
                        height = sfmFrames[i].height,
                        pose = poses[i]!!,
                        fx = sfmFrames[i].fx,
                        fy = sfmFrames[i].fy,
                        cx = sfmFrames[i].cx,
                        cy = sfmFrames[i].cy
                    )
                }
                .toList()
            if (supports.size < 2) continue

            val pseudo = sweep.sweep(
                PlaneSweepStereo.SweepFrame(
                    pixels = ref.pixels,
                    width = ref.width,
                    height = ref.height,
                    pose = refPose,
                    fx = ref.fx,
                    fy = ref.fy,
                    cx = ref.cx,
                    cy = ref.cy
                ),
                supports
            )
            if (pseudo.validFraction < 0.02f) {
                Log.i(TAG, "Sweep for frame $refIdx produced almost no valid depth — skipping")
                continue
            }
            sweepValid++

            // Median-filter, then fuse with the adaptive stride used by the
            // depth-assisted Photo Mesh pipeline
            val filtered = DepthFilters.medianFilter3x3(pseudo.depthMm, pseudo.width, pseudo.height)
            val medianM = DepthFilters.medianDepthMm(filtered) / 1000f
            val stride = if (medianM > 0f) {
                DepthFilters.suggestStride(medianM, pseudo.fx, scanMode.voxelSizeMeters * 1.5f)
            } else 1

            fuser.fuseFrame(
                frame = DepthFrame(
                    depthMm = filtered,
                    depthWidth = pseudo.width,
                    depthHeight = pseudo.height,
                    pose = pseudo.pose,
                    fx = pseudo.fx,
                    fy = pseudo.fy,
                    cx = pseudo.cx,
                    cy = pseudo.cy
                ),
                minDepthM = scanMode.minDepthMeters,
                maxDepthM = scanMode.maxDepthMeters,
                stride = stride
            )

            onProgress(
                FrameProcessor.ProcessingProgress(
                    "Sweeping depth ${n + 1}/${denseRefs.size}...",
                    50 + (n + 1) * 36 / denseRefs.size,
                    fuser.vertexCount
                )
            )
        }
        if (sweepValid == 0) {
            Log.w(TAG, "All dense sweeps failed — SfM poses likely inconsistent")
            return@withContext null
        }

        val mesh = fuser.buildMesh()
        if (mesh.triangleCount == 0) {
            Log.w(TAG, "Fused mesh came out empty")
            return@withContext null
        }

        onProgress(FrameProcessor.ProcessingProgress("Smoothing mesh topology...", 88, mesh.vertexCount))
        mesh.laplacianSmooth(iterations = 3, alpha = 0.4f)

        onProgress(FrameProcessor.ProcessingProgress("Computing vertex normals...", 90, mesh.vertexCount))
        mesh.computeNormals()

        // ---- 6. Bake photo colors from the SfM-posed views ----
        val baker = MeshColorBaker(mesh)
        val colorViews = registered.toIntArray()
        for ((n, i) in colorViews.withIndex()) {
            val f = sfmFrames[i]
            baker.bakeView(
                PhotoView(
                    pixels = f.pixels,
                    width = f.width,
                    height = f.height,
                    pose = poses[i]!!,
                    fx = f.fx,
                    fy = f.fy,
                    cx = f.cx,
                    cy = f.cy
                )
            )
            onProgress(
                FrameProcessor.ProcessingProgress(
                    "Baking photo colors ${n + 1}/${colorViews.size}...",
                    90 + (n + 1) * 9 / colorViews.size,
                    mesh.vertexCount
                )
            )
        }
        baker.applyTo(mesh)

        onProgress(
            FrameProcessor.ProcessingProgress(
                "Photo SfM mesh ready — ${mesh.triangleCount} triangles",
                99,
                mesh.vertexCount,
                note = noteFor(solved, registered.size, sfmFrames.size, mesh.triangleCount)
            )
        )
        mesh
    }

    // ---- pose solving helpers ----

    /**
     * Poses for the dense stage, plus where they came from.
     *
     * Photo SfM is the higher-quality option but it can legitimately fail on
     * real captures (motion blur, glossy/shiny or texture-less surfaces,
     * repeating patterns, too little overlap). When that happens the ARCore
     * tracking poses saved alongside every photo are used instead: they are
     * metric, drift-free over the length of a scan, and always
     * self-consistent, so the same dense stereo + fusion + colour-baking
     * stages still turn the photos into a real textured model. A scan that
     * would have dead-ended with "Photo SfM failed" now completes.
     */
    private class SolvedPoses(
        val poses: List<FloatArray?>,
        val registered: List<Int>,
        val source: String
    )

    private fun resolvePoses(frames: List<SfmFrame>, sfm: SfmResult?): SolvedPoses {
        if (sfm != null) {
            val registered = frames.indices.filter { sfm.poses.getOrNull(it) != null }
            if (registered.size >= minRegisteredFrames(frames.size)) {
                return SolvedPoses(sfm.poses, registered, "photo SfM")
            }
            Log.i(TAG, "SfM only registered ${registered.size} frames — falling back to AR poses")
        } else {
            Log.w(TAG, "SfM failed — using ARCore tracking poses instead")
        }

        val poses = List<FloatArray?>(frames.size) { i -> frames[i].arPose }
        val registered = frames.indices.filter { poses[it] != null }
        return SolvedPoses(poses, registered, "AR tracking")
    }

    /**
     * Human-readable explanation of how the geometry was obtained, stored with
     * the scan. When SfM failed this tells the user their scan is still real
     * geometry from AR tracking rather than leaving them guessing.
     */
    private fun noteFor(
        solved: SolvedPoses,
        registeredCount: Int,
        totalFrames: Int,
        triangleCount: Int
    ): String = if (solved.source == "photo SfM") {
        "Photo SfM reconstructed %d of %d keyframes into %,d triangles".format(
            registeredCount, totalFrames, triangleCount
        )
    } else {
        "Photo SfM couldn't solve the camera path — rebuilt from AR tracking over %d frames (%,d triangles)".format(
            registeredCount, triangleCount
        )
    }

    /** A partial SfM solution is only preferred when it covers most keyframes. */
    private fun minRegisteredFrames(total: Int): Int = maxOf(2, minOf(6, total / 2))

    /**
     * Second SfM attempt with looser feature/matching parameters, run only if
     * the default attempt failed. Doubling the retry cost (SfM runs on at most
     * [MAX_SFM_FRAMES] keyframes) is cheap insurance against a dead-end scan.
     */
    private fun runRelaxedSfm(
        frames: List<SfmFrame>,
        onProgress: (FrameProcessor.ProcessingProgress) -> Unit
    ): SfmResult? {
        onProgress(FrameProcessor.ProcessingProgress("Photo SfM: retrying with looser matching...", 48, 0))
        return SfmReconstructor(
            maxFeatures = 1600,
            matchRadius = 240f,
            ransacIterations = 400,
            maxReprojErrorPx = 3.5f,
            onProgress = { step, pct ->
                onProgress(
                    FrameProcessor.ProcessingProgress(
                        "Photo SfM (retry): $step",
                        14 + pct * 36 / 100,
                        0
                    )
                )
            }
        ).reconstruct(frames)
    }

    // ---- dataset loading helpers ----

    private class FrameFile(
        val timestamp: Long,
        val pose: FloatArray,
        val fx: Float, val fy: Float, val cx: Float, val cy: Float,
        val imgWidth: Int, val imgHeight: Int
    )

    private fun enumerateFrames(datasetDir: File): List<FrameFile> {
        val poseFiles = datasetDir
            .listFiles { _, name -> name.startsWith("pose_") && name.endsWith(".txt") }
            ?.sortedBy { it.name.removePrefix("pose_").removeSuffix(".txt").toLongOrNull() ?: 0L }
            ?: emptyList()

        val out = ArrayList<FrameFile>(poseFiles.size)
        for (poseFile in poseFiles) {
            val ts = poseFile.name.removePrefix("pose_").removeSuffix(".txt").toLongOrNull() ?: continue
            if (!File(datasetDir, "rgb_$ts.jpg").exists()) continue

            val pose = parsePoseMatrix(poseFile) ?: continue
            val intrinsics = parseIntrinsics(File(datasetDir, "intrinsics_$ts.txt"))

            val imgW: Int
            val imgH: Int
            if (intrinsics != null && intrinsics.size >= 6 && intrinsics[4] > 0) {
                imgW = intrinsics[4].toInt(); imgH = intrinsics[5].toInt()
            } else {
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(File(datasetDir, "rgb_$ts.jpg").absolutePath, bounds)
                imgW = bounds.outWidth; imgH = bounds.outHeight
            }
            if (imgW <= 0 || imgH <= 0) continue

            out.add(
                FrameFile(
                    timestamp = ts,
                    pose = pose,
                    fx = intrinsics?.getOrNull(0) ?: (imgW * 0.8f),
                    fy = intrinsics?.getOrNull(1) ?: (imgH * 0.8f),
                    cx = intrinsics?.getOrNull(2) ?: (imgW * 0.5f),
                    cy = intrinsics?.getOrNull(3) ?: (imgH * 0.5f),
                    imgWidth = imgW,
                    imgHeight = imgH
                )
            )
        }
        return out
    }

    /**
     * Farthest-point keyframe sampling over AR camera positions (greedy:
     * start at the first frame, repeatedly add the frame farthest from all
     * chosen ones). Falls back to even sampling when poses are unusable.
     */
    private fun selectKeyframes(frames: List<FrameFile>, maxCount: Int): List<Int> {
        val all = frames.indices.toList()
        if (frames.size <= maxCount) return all

        val positions = frames.mapIndexed { i, f ->
            val p = f.pose
            if (p.size == 16) floatArrayOf(p[12], p[13], p[14]) else null
        }
        val havePositions = positions.all { it != null } &&
            positions.map { it!![0] to it[1] }.distinct().size > 2
        if (!havePositions) {
            return evenlySampleIndices(all, maxCount)
        }

        val chosen = mutableListOf(0)
        while (chosen.size < maxCount) {
            var bestIdx = -1
            var bestDist = -1f
            for (i in all) {
                if (i in chosen) continue
                val p = positions[i]!!
                var minD = Float.MAX_VALUE
                for (c in chosen) {
                    val q = positions[c]!!
                    val dx = p[0] - q[0]; val dy = p[1] - q[1]; val dz = p[2] - q[2]
                    val d = dx * dx + dy * dy + dz * dz
                    if (d < minD) minD = d
                }
                if (minD > bestDist) {
                    bestDist = minD
                    bestIdx = i
                }
            }
            if (bestIdx < 0) break
            chosen.add(bestIdx)
        }
        // Preserve trajectory order for track chaining
        return chosen.sorted()
    }

    private fun evenlySampleIndices(items: List<Int>, maxCount: Int): List<Int> {
        if (items.size <= maxCount) return items
        val out = ArrayList<Int>(maxCount)
        for (i in 0 until maxCount) {
            val idx = (i.toDouble() * (items.size - 1) / (maxCount - 1).coerceAtLeast(1))
                .roundToInt()
                .coerceIn(0, items.size - 1)
            out.add(items[idx])
        }
        return out
    }

    private class BitmapData(val pixels: IntArray, val width: Int, val height: Int)

    /** Decode a JPEG and return ARGB pixels downscaled so max side ≈ [maxDim]. */
    private fun loadDownscaledPixels(file: File, maxDim: Int): BitmapData? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxDim) sample *= 2

        val opts = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val bitmap = BitmapFactory.decodeFile(file.absolutePath, opts) ?: return null
        try {
            var pixels = IntArray(bitmap.width * bitmap.height).also {
                bitmap.getPixels(it, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
            }
            var w = bitmap.width
            var h = bitmap.height
            // Second stage: exact downsample to reach the target resolution
            val maxSide = maxOf(w, h)
            if (maxSide > maxDim) {
                val factor = (maxSide + maxDim - 1) / maxDim
                val (p2, w2, h2) = ImageOps.downsample(pixels, w, h, factor)
                pixels = p2; w = w2; h = h2
            }
            return BitmapData(pixels, w, h)
        } finally {
            bitmap.recycle()
        }
    }

    /** Reads a pose file, converting the ARCore pose into pipeline camera space. */
    private fun parsePoseMatrix(poseFile: File): FloatArray? {
        return try {
            CameraPose.parsePoseFile(poseFile.readText())
        } catch (_: Exception) {
            null
        }
    }

    private fun parseIntrinsics(intrinsicsFile: File): FloatArray? {
        if (!intrinsicsFile.exists()) return null
        return try {
            val parts = intrinsicsFile.readText().trim().split(",")
            if (parts.size >= 4) FloatArray(parts.size) { i -> parts[i].trim().toFloat() } else null
        } catch (_: Exception) {
            null
        }
    }
}
