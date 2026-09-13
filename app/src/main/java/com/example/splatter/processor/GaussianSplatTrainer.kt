package com.example.splatter.processor

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import com.example.splatter.model.ScanMode
import com.example.splatter.model.SplatPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * On-device Gaussian splat refinement trainer.
 *
 * After the initial unprojection pass (FrameProcessor), this trainer iteratively
 * refines splat parameters using multi-view observations:
 *
 * 1. Multi-view color fusion — each splat's RGB is refined by projecting it into
 *    multiple camera views and gradient-descending toward the observed pixel color.
 * 2. Depth-gated visibility — before comparing a splat to an RGB pixel, the
 *    projected depth is checked against the saved raw depth at that pixel.
 *    Only splats whose depth matches the observed depth (within a tolerance)
 *    are trained, preventing occluded/background points from learning wrong colors.
 * 3. Opacity confidence — splats with high cross-view color variance have their
 *    opacity reduced (they are likely edge or noise points).
 * 4. Scale smoothing — splat scales are nudged toward a distance-aware target
 *    for more uniform appearance.
 * 5. Pruning — splats with very low opacity after training are removed.
 *
 * This is not full differentiable 3DGS training (which requires CUDA-style
 * differentiable rasterization), but a practical on-device refinement pass
 * that measurably improves color accuracy and reduces noise.
 */
object GaussianSplatTrainer {
    private const val TAG = "GaussianSplatTrainer"

    data class TrainingConfig(
        val iterations: Int = 10,
        val colorLearningRate: Float = 0.12f,
        val opacityLearningRate: Float = 0.06f,
        val scaleLearningRate: Float = 0.015f,
        val depthToleranceMeters: Float = 0.08f,
        val renderDownscale: Int = 4,
        val framesPerIteration: Int = 6,
        val pruneOpacityThreshold: Float = 0.05f,
        val pruneInterval: Int = 5,
        val maxPoints: Int = 3_000_000
    )

    data class TrainingProgress(
        val iteration: Int,
        val totalIterations: Int,
        val loss: Float,
        val pointCount: Int,
        val currentStep: String,
        val progressPercent: Int
    )

    /** A single camera viewpoint loaded from saved frame data. */
    private data class CameraView(
        val timestamp: Long,
        val poseMatrix: FloatArray,
        val invPose: FloatArray,
        val fx: Float,
        val fy: Float,
        val cx: Float,
        val cy: Float,
        val rgbFile: File,
        val depthFile: File?,
        val depthWidth: Int,
        val depthHeight: Int
    )

    /**
     * Train (refine) the given splat points on-device using multi-view observations.
     *
     * @param initialPoints Mutable list of splat points from the unprojection pass.
     *        This list is modified in-place during training.
     * @param datasetDir Directory containing saved frame files (rgb_*.jpg, depth_*.raw,
     *        pose_*.txt, intrinsics_*.txt, depthdims_*.txt).
     * @param scanMode The scan mode (affects point cap and depth range).
     * @param config Training configuration.
     * @param onProgress Callback for progress updates.
     * @return The refined list of splat points (same list, modified in-place).
     */
    suspend fun trainOnDevice(
        initialPoints: MutableList<SplatPoint>,
        datasetDir: File,
        scanMode: ScanMode,
        config: TrainingConfig = TrainingConfig(),
        onProgress: (TrainingProgress) -> Unit
    ): List<SplatPoint> = withContext(Dispatchers.Default) {

        // Load camera views from saved frame files
        val views = loadCameraViews(datasetDir)
        if (views.isEmpty()) {
            Log.w(TAG, "No camera views found for training — skipping training")
            return@withContext initialPoints.toList()
        }

        val pointCap = minOf(config.maxPoints, scanMode.maxPointLimit)
        if (initialPoints.size > pointCap) {
            Log.i(TAG, "Capping training set from ${initialPoints.size} to $pointCap points")
            // Keep the first pointCap points (they are in voxel-grid order)
            while (initialPoints.size > pointCap) {
                initialPoints.removeAt(initialPoints.size - 1)
            }
        }

        Log.i(TAG, "Training: ${views.size} views, ${initialPoints.size} points, ${config.iterations} iterations")

        val points = initialPoints
        var currentLoss = 1.0f

        for (iteration in 1..config.iterations) {
            val iterStart = System.currentTimeMillis()

            onProgress(TrainingProgress(
                iteration = iteration,
                totalIterations = config.iterations,
                loss = currentLoss,
                pointCount = points.size,
                currentStep = "On-device training — iteration $iteration/${config.iterations}",
                progressPercent = ((iteration.toFloat() / config.iterations) * 100).toInt()
            ))

            // Select a different subset of frames each iteration (round-robin with offset)
            val frameIndices = selectFrameIndices(views.size, config.framesPerIteration, iteration)

            var totalError = 0.0f
            var totalSamples = 0

            for (frameIdx in frameIndices) {
                val view = views[frameIdx]

                // Load downscaled RGB bitmap
                val bitmap = loadDownscaledBitmap(view.rgbFile, config.renderDownscale) ?: continue
                val scaledW = bitmap.width
                val scaledH = bitmap.height

                // Extract pixel array for fast access
                val pixels = IntArray(scaledW * scaledH)
                bitmap.getPixels(pixels, 0, scaledW, 0, 0, scaledW, scaledH)
                bitmap.recycle()

                // Scale intrinsics for the downscaled image
                val scale = 1f / config.renderDownscale
                val sFx = view.fx * scale
                val sFy = view.fy * scale
                val sCx = view.cx * scale
                val sCy = view.cy * scale

                // Load depth data if available (for depth-gated visibility)
                val depthData = loadDepthData(view)

                // Color variance accumulators per splat (for opacity confidence)
                // We track sum and sum-of-squares of observed colors across views
                // But for memory efficiency, we process per-frame and update incrementally

                // Training pass over all points for this frame
                for (i in points.indices) {
                    val p = points[i]

                    // Transform world point to camera space using inverse pose
                    val camX = view.invPose[0] * p.x + view.invPose[4] * p.y + view.invPose[8] * p.z + view.invPose[12]
                    val camY = view.invPose[1] * p.x + view.invPose[5] * p.y + view.invPose[9] * p.z + view.invPose[13]
                    val camZ = view.invPose[2] * p.x + view.invPose[6] * p.y + view.invPose[10] * p.z + view.invPose[14]

                    // Reject points behind the camera
                    if (camZ <= 0.05f) continue

                    // Project to image pixel (downscaled coordinates)
                    val u = (sFx * camX / camZ + sCx).roundToInt()
                    val v = (sFy * camY / camZ + sCy).roundToInt()

                    // Reject points outside the image
                    if (u < 0 || u >= scaledW || v < 0 || v >= scaledH) continue

                    // Depth-gated visibility: check if this splat's depth matches the
                    // observed depth at the projected pixel. This prevents occluded
                    // or background points from learning wrong colors.
                    // Map from downscaled RGB coords to depth image coords proportionally
                    // (depth image may have different dimensions, e.g. 160x120 vs 1920x1080)
                    if (depthData != null) {
                        val (depthU, depthV) = mapRgbToDepthPixel(
                            rgbU = u, rgbV = v,
                            rgbWidth = scaledW, rgbHeight = scaledH,
                            depthWidth = view.depthWidth, depthHeight = view.depthHeight
                        )
                        // Depth tolerance scales with distance (farther points have more depth noise)
                        val dynamicTolerance = maxOf(config.depthToleranceMeters, camZ * 0.05f)
                        if (!depthMatches(
                                splatDepthM = camZ,
                                depthData = depthData,
                                depthU = depthU,
                                depthV = depthV,
                                depthWidth = view.depthWidth,
                                toleranceMeters = dynamicTolerance
                            )
                        ) {
                            continue // Depth mismatch — this splat is occluded at this view
                        }
                    }

                    // Get observed pixel color
                    val pixelColor = pixels[v * scaledW + u]
                    val obsR = ((pixelColor shr 16) and 0xFF) / 255.0f
                    val obsG = ((pixelColor shr 8) and 0xFF) / 255.0f
                    val obsB = (pixelColor and 0xFF) / 255.0f

                    // Compute color error (splat color minus observed color)
                    val errR = p.r - obsR
                    val errG = p.g - obsG
                    val errB = p.b - obsB

                    val pixelError = sqrt(errR * errR + errG * errG + errB * errB)
                    totalError += pixelError
                    totalSamples++

                    // Gradient descent: nudge splat color toward observed color
                    p.r = (p.r - config.colorLearningRate * errR).coerceIn(0f, 1f)
                    p.g = (p.g - config.colorLearningRate * errG).coerceIn(0f, 1f)
                    p.b = (p.b - config.colorLearningRate * errB).coerceIn(0f, 1f)

                    // Opacity confidence: high error reduces opacity (inconsistent point),
                    // low error increases opacity (consistent, well-observed point)
                    val opacityDelta = config.opacityLearningRate * (0.1f - pixelError)
                    p.alpha = (p.alpha + opacityDelta).coerceIn(0f, 1f)

                    // Scale smoothing: nudge toward a distance-aware target scale
                    val distanceFactor = (camZ / 1.5f).coerceIn(0.5f, 3.0f)
                    val targetScale = (if (scanMode == ScanMode.OBJECT) 0.005f else 0.012f) * distanceFactor
                    val newScale = (p.scaleX + config.scaleLearningRate * (targetScale - p.scaleX))
                        .coerceIn(0.003f, 0.05f)
                    p.scaleX = newScale
                    p.scaleY = newScale
                    p.scaleZ = newScale
                }

                // Yield periodically to keep the UI thread responsive
                Thread.yield()
            }

            currentLoss = if (totalSamples > 0) totalError / totalSamples else currentLoss
            val iterMs = System.currentTimeMillis() - iterStart
            Log.i(TAG, "Iteration $iteration: loss=${"%.4f".format(currentLoss)}, samples=$totalSamples, ${iterMs}ms")

            // Pruning: remove low-opacity splats periodically
            if (iteration % config.pruneInterval == 0) {
                onProgress(TrainingProgress(
                    iteration = iteration,
                    totalIterations = config.iterations,
                    loss = currentLoss,
                    pointCount = points.size,
                    currentStep = "Pruning low-confidence Gaussians...",
                    progressPercent = ((iteration.toFloat() / config.iterations) * 100).toInt()
                ))

                val beforeCount = points.size
                val pruned = points.filter { it.alpha >= config.pruneOpacityThreshold }
                points.clear()
                points.addAll(pruned)
                Log.i(TAG, "Pruned ${beforeCount - points.size} low-opacity splats (before=$beforeCount, after=${points.size})")
            }
        }

        onProgress(TrainingProgress(
            iteration = config.iterations,
            totalIterations = config.iterations,
            loss = currentLoss,
            pointCount = points.size,
            currentStep = "Training complete — optimized ${points.size} Gaussians (loss=${"%.4f".format(currentLoss)})",
            progressPercent = 100
        ))

        Log.i(TAG, "Training complete: ${points.size} points, final loss=${"%.4f".format(currentLoss)}")
        points.toList()
    }

    // ---------------------------------------------------------------------------
    // Camera view loading
    // ---------------------------------------------------------------------------

    private fun loadCameraViews(datasetDir: File): List<CameraView> {
        val views = mutableListOf<CameraView>()

        val poseFiles = datasetDir.listFiles { _, name -> name.startsWith("pose_") && name.endsWith(".txt") }
            ?.sortedBy { it.name.removePrefix("pose_").removeSuffix(".txt").toLongOrNull() ?: 0L }
            ?: emptyList()

        for (poseFile in poseFiles) {
            val timestamp = poseFile.name.removePrefix("pose_").removeSuffix(".txt").toLongOrNull() ?: continue

            val rgbFile = File(datasetDir, "rgb_$timestamp.jpg")
            if (!rgbFile.exists()) continue

            val depthFile = File(datasetDir, "depth_$timestamp.raw")
            val depthDimsFile = File(datasetDir, "depthdims_$timestamp.txt")

            // Parse pose matrix (16 floats, comma-separated)
            val poseMatrix = parsePoseMatrix(poseFile) ?: continue

            // Parse intrinsics (fx, fy, cx, cy, width, height)
            val intrinsicsFile = File(datasetDir, "intrinsics_$timestamp.txt")
            val intrinsics = parseIntrinsics(intrinsicsFile)

            // Parse depth dimensions if available
            var depthWidth = 0
            var depthHeight = 0
            if (depthDimsFile.exists()) {
                try {
                    val parts = depthDimsFile.readText().trim().split(",")
                    if (parts.size >= 2) {
                        depthWidth = parts[0].trim().toIntOrNull() ?: 0
                        depthHeight = parts[1].trim().toIntOrNull() ?: 0
                    }
                } catch (_: Exception) { }
            }

            // Get image dimensions from the RGB file (decode bounds only)
            val imgDims = getImageDimensions(rgbFile)
            val imgWidth = imgDims.first
            val imgHeight = imgDims.second

            // Use intrinsics if available, otherwise estimate from image dimensions
            val fx = intrinsics?.getOrNull(0) ?: (imgWidth * 0.8f)
            val fy = intrinsics?.getOrNull(1) ?: (imgHeight * 0.8f)
            val cx = intrinsics?.getOrNull(2) ?: (imgWidth * 0.5f)
            val cy = intrinsics?.getOrNull(3) ?: (imgHeight * 0.5f)

            // Compute inverse pose (world-to-camera transform)
            val invPose = invertRigidTransform(poseMatrix)

            views.add(CameraView(
                timestamp = timestamp,
                poseMatrix = poseMatrix,
                invPose = invPose,
                fx = fx,
                fy = fy,
                cx = cx,
                cy = cy,
                rgbFile = rgbFile,
                depthFile = if (depthFile.exists()) depthFile else null,
                depthWidth = depthWidth,
                depthHeight = depthHeight
            ))
        }

        Log.i(TAG, "Loaded ${views.size} camera views from ${datasetDir.name}")
        return views
    }

    // ---------------------------------------------------------------------------
    // Bitmap loading
    // ---------------------------------------------------------------------------

    private fun loadDownscaledBitmap(file: File, downscale: Int): Bitmap? {
        return try {
            val opts = BitmapFactory.Options().apply {
                inSampleSize = downscale
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }
            BitmapFactory.decodeFile(file.absolutePath, opts)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to load bitmap: ${file.name} — ${e.message}")
            null
        }
    }

    private fun getImageDimensions(file: File): Pair<Int, Int> {
        return try {
            val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.absolutePath, opts)
            Pair(opts.outWidth, opts.outHeight)
        } catch (_: Exception) {
            Pair(1920, 1080)
        }
    }

    // ---------------------------------------------------------------------------
    // Depth data loading
    // ---------------------------------------------------------------------------

    private fun loadDepthData(view: CameraView): ShortArray? {
        val depthFile = view.depthFile ?: return null
        if (view.depthWidth <= 0 || view.depthHeight <= 0) return null

        return try {
            val bytes = depthFile.readBytes()
            val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
            val depthData = ShortArray(buffer.remaining())
            buffer.get(depthData)
            depthData
        } catch (e: Exception) {
            Log.w(TAG, "Failed to load depth data: ${depthFile.name} — ${e.message}")
            null
        }
    }

    // ---------------------------------------------------------------------------
    // Frame selection
    // ---------------------------------------------------------------------------

    private fun selectFrameIndices(totalFrames: Int, count: Int, iteration: Int): List<Int> {
        if (totalFrames <= count) return (0 until totalFrames).toList()

        val indices = mutableListOf<Int>()
        val stride = totalFrames.toFloat() / count
        val offset = (iteration - 1) % count // Rotate the starting point each iteration

        for (i in 0 until count) {
            val idx = ((i * stride + offset) % totalFrames).toInt()
            if (indices.contains(idx)) continue
            indices.add(idx)
        }

        return indices
    }

    // ---------------------------------------------------------------------------
    // Math: inverse rigid transform, pose parsing
    // ---------------------------------------------------------------------------

    /**
     * Compute the inverse of a rigid transform (rotation + translation) stored as
     * a 4x4 column-major matrix (Android/ARCore convention).
     *
     * For a rigid transform M = [R | t; 0 1], the inverse is [R^T | -R^T * t; 0 1].
     */
    fun invertRigidTransform(pose: FloatArray): FloatArray {
        require(pose.size == 16) { "Pose matrix must be 16 elements" }

        val result = FloatArray(16)

        // Extract rotation (column-major: columns are elements 0-2, 4-6, 8-10)
        val r00 = pose[0]; val r10 = pose[1]; val r20 = pose[2]
        val r01 = pose[4]; val r11 = pose[5]; val r21 = pose[6]
        val r02 = pose[8]; val r12 = pose[9]; val r22 = pose[10]

        // Translation
        val tx = pose[12]; val ty = pose[13]; val tz = pose[14]

        // Inverse rotation = transpose of rotation
        val ir00 = r00; val ir01 = r10; val ir02 = r20
        val ir10 = r01; val ir11 = r11; val ir12 = r21
        val ir20 = r02; val ir21 = r12; val ir22 = r22

        // Inverse translation = -R^T * t
        val itx = -(ir00 * tx + ir01 * ty + ir02 * tz)
        val ity = -(ir10 * tx + ir11 * ty + ir12 * tz)
        val itz = -(ir20 * tx + ir21 * ty + ir22 * tz)

        // Build result (column-major)
        result[0] = ir00; result[1] = ir10; result[2] = ir20; result[3] = 0f
        result[4] = ir01; result[5] = ir11; result[6] = ir21; result[7] = 0f
        result[8] = ir02; result[9] = ir12; result[10] = ir22; result[11] = 0f
        result[12] = itx; result[13] = ity; result[14] = itz; result[15] = 1f

        return result
    }

    /**
     * Project a 3D world point to 2D image coordinates using an inverse pose and
     * pinhole camera intrinsics.
     *
     * @param worldX, worldY, worldZ — world-space point coordinates.
     * @param invPose — 4x4 inverse pose matrix (world-to-camera, column-major).
     * @param fx, fy, cx, cy — camera intrinsics.
     * @return Pair(u, v) of pixel coordinates, or null if the point is behind the camera.
     */
    fun projectWorldToImage(
        worldX: Float, worldY: Float, worldZ: Float,
        invPose: FloatArray,
        fx: Float, fy: Float, cx: Float, cy: Float
    ): Pair<Int, Int>? {
        val camX = invPose[0] * worldX + invPose[4] * worldY + invPose[8] * worldZ + invPose[12]
        val camY = invPose[1] * worldX + invPose[5] * worldY + invPose[9] * worldZ + invPose[13]
        val camZ = invPose[2] * worldX + invPose[6] * worldY + invPose[10] * worldZ + invPose[14]

        if (camZ <= 0.05f) return null

        val u = (fx * camX / camZ + cx).roundToInt()
        val v = (fy * camY / camZ + cy).roundToInt()
        return Pair(u, v)
    }

    /**
     * Transform a 3D world point to camera space using an inverse pose.
     * Returns the camera-space coordinates, or null if behind the camera.
     */
    fun worldToCamera(
        worldX: Float, worldY: Float, worldZ: Float,
        invPose: FloatArray
    ): Triple<Float, Float, Float>? {
        val camX = invPose[0] * worldX + invPose[4] * worldY + invPose[8] * worldZ + invPose[12]
        val camY = invPose[1] * worldX + invPose[5] * worldY + invPose[9] * worldZ + invPose[13]
        val camZ = invPose[2] * worldX + invPose[6] * worldY + invPose[10] * worldZ + invPose[14]

        if (camZ <= 0.05f) return null
        return Triple(camX, camY, camZ)
    }

    /**
     * Check if a splat's depth is consistent with the observed depth at a given pixel.
     *
     * @param splatDepthM The splat's camera-space depth (Z) in meters.
     * @param depthData The raw depth buffer (16-bit depth in millimeters).
     * @param depthU, depthV — pixel coordinates in the depth image.
     * @param depthWidth — width of the depth image.
     * @param toleranceMeters — maximum allowed depth difference in meters.
     * @return true if the depth matches (within tolerance), false otherwise.
     */
    fun depthMatches(
        splatDepthM: Float,
        depthData: ShortArray,
        depthU: Int,
        depthV: Int,
        depthWidth: Int,
        toleranceMeters: Float
    ): Boolean {
        val idx = depthV * depthWidth + depthU
        if (idx < 0 || idx >= depthData.size) return false

        val observedDepthMm = depthData[idx].toInt() and 0xFFFF
        if (observedDepthMm == 0) return false

        val observedDepthM = observedDepthMm / 1000.0f
        return abs(splatDepthM - observedDepthM) <= toleranceMeters
    }

    /**
     * Map from one image's pixel coordinates to another image's pixel coordinates
     * proportionally. Used to map from downscaled RGB coordinates to raw depth
     * coordinates (which may have very different dimensions, e.g. 480x270 vs 160x120).
     */
    fun mapRgbToDepthPixel(
        rgbU: Int, rgbV: Int,
        rgbWidth: Int, rgbHeight: Int,
        depthWidth: Int, depthHeight: Int
    ): Pair<Int, Int> {
        val depthU = ((rgbU.toFloat() / rgbWidth.toFloat()) * depthWidth).roundToInt()
            .coerceIn(0, depthWidth - 1)
        val depthV = ((rgbV.toFloat() / rgbHeight.toFloat()) * depthHeight).roundToInt()
            .coerceIn(0, depthHeight - 1)
        return Pair(depthU, depthV)
    }

    private fun parsePoseMatrix(poseFile: File): FloatArray? {
        return try {
            val text = poseFile.readText().trim()
            val parts = text.split(",")
            if (parts.size == 16) {
                FloatArray(16) { i -> parts[i].trim().toFloat() }
            } else null
        } catch (_: Exception) {
            null
        }
    }

    private fun parseIntrinsics(intrinsicsFile: File): FloatArray? {
        if (!intrinsicsFile.exists()) return null
        return try {
            val parts = intrinsicsFile.readText().trim().split(",")
            if (parts.size >= 4) {
                FloatArray(parts.size) { i -> parts[i].trim().toFloat() }
            } else null
        } catch (_: Exception) {
            null
        }
    }
}
