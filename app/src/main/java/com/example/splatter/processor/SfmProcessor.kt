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

    /** Max keyframes used for the SfM stage. */
    private const val MAX_SFM_FRAMES = 32

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
        if (sfm == null) {
            Log.w(TAG, "SfM failed — not enough trackable features or degenerate motion")
            return@withContext null
        }
        val poses = sfm.poses
        val registered = sfmFrames.indices.filter { poses[it] != null }
        Log.i(TAG, "SfM registered ${registered.size}/${sfmFrames.size} frames, ${sfm.pointsXyz.size} points")

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
            depthToleranceMin = 0.05f
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
                mesh.vertexCount
            )
        )
        mesh
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

    private fun parsePoseMatrix(poseFile: File): FloatArray? {
        return try {
            val parts = poseFile.readText().trim().split(",")
            if (parts.size == 16) FloatArray(16) { i -> parts[i].trim().toFloat() } else null
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
