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
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

object FrameProcessor {
    private const val TAG = "FrameProcessor"

    // These are now read from ScanMode — kept as fallback defaults only
    private const val DEFAULT_VOXEL_SIZE = 0.008f
    private const val DEFAULT_MIN_DEPTH = 0.25f
    private const val DEFAULT_MAX_DEPTH = 3.5f

    // Downscale RGB bitmaps during processing to reduce memory pressure.
    // A 1920x1080 ARGB_8888 bitmap is ~8 MB; with sampleSize=2 it becomes ~2 MB.
    // Color accuracy impact is negligible for splat generation.
    private const val RGB_SAMPLE_SIZE = 2

    data class ProcessingProgress(
        val currentStep: String,
        val progressPercent: Int,
        val pointCount: Int
    )

    suspend fun processDataset(
        datasetDir: File,
        scanMode: ScanMode = ScanMode.OBJECT,
        onProgress: (ProcessingProgress) -> Unit
    ): MutableList<SplatPoint> = withContext(Dispatchers.IO) {
        onProgress(ProcessingProgress("Scanning captured dataset...", 5, 0))

        if (!datasetDir.exists() || !datasetDir.isDirectory) {
            Log.e(TAG, "Dataset directory does not exist: ${datasetDir.absolutePath}")
            return@withContext mutableListOf()
        }

        // Find all pose files
        val poseFiles = datasetDir.listFiles { _, name -> name.startsWith("pose_") && name.endsWith(".txt") }
            ?.sortedBy { file ->
                file.name.removePrefix("pose_").removeSuffix(".txt").toLongOrNull() ?: 0L
            } ?: emptyList()

        if (poseFiles.isEmpty()) {
            Log.w(TAG, "No pose files found in dataset directory.")
            return@withContext mutableListOf()
        }

        val totalFrames = poseFiles.size
        val maxPoints = scanMode.maxPointLimit
        Log.i(TAG, "Processing $totalFrames recorded AR frames (maxPoints=$maxPoints)...")

        // Voxel Grid spatial hash map to merge overlapping 3D points
        val voxelGrid = HashMap<Long, SplatPoint>()

        for ((index, poseFile) in poseFiles.withIndex()) {
            val timestampStr = poseFile.name.removePrefix("pose_").removeSuffix(".txt")
            val timestamp = timestampStr.toLongOrNull() ?: continue

            val depthFile = File(datasetDir, "depth_$timestamp.raw")
            val rgbFile = File(datasetDir, "rgb_$timestamp.jpg")
            val intrinsicsFile = File(datasetDir, "intrinsics_$timestamp.txt")

            if (!rgbFile.exists()) continue

            // 1. Read Pose Matrix (16 floats)
            val poseMatrix = parsePoseMatrix(poseFile) ?: continue

            // 2. Read Intrinsics (fx, fy, cx, cy, width, height)
            val intrinsics = parseIntrinsics(intrinsicsFile)

            // 3. Load RGB Bitmap (downscaled to reduce memory pressure)
            val (bitmap, origImgDims) = try {
                loadSampledBitmap(rgbFile, RGB_SAMPLE_SIZE) ?: continue
            } catch (e: OutOfMemoryError) {
                Log.e(TAG, "OOM loading bitmap for frame ${index + 1}, skipping", e)
                System.gc()
                continue
            }
            val origImgWidth = origImgDims.first
            val origImgHeight = origImgDims.second

            val fx = intrinsics?.getOrNull(0) ?: (origImgWidth * 0.8f)
            val fy = intrinsics?.getOrNull(1) ?: (origImgHeight * 0.8f)
            val cx = intrinsics?.getOrNull(2) ?: (origImgWidth * 0.5f)
            val cy = intrinsics?.getOrNull(3) ?: (origImgHeight * 0.5f)

            // Step size — use 1 for full density (every depth pixel contributes)
            val step = 1

            try {
                if (depthFile.exists()) {
                    // Read exact depth dimensions saved at capture time (depthdims_*.txt)
                    // Falls back to pixel-count heuristic only if the file is missing
                    val depthDimsFile = File(datasetDir, "depthdims_$timestamp.txt")
                    var depthWidth = 0
                    var depthHeight = 0
                    if (depthDimsFile.exists()) {
                        try {
                            val dimsParts = depthDimsFile.readText().trim().split(",")
                            if (dimsParts.size >= 2) {
                                depthWidth = dimsParts[0].trim().toIntOrNull() ?: 0
                                depthHeight = dimsParts[1].trim().toIntOrNull() ?: 0
                            }
                        } catch (_: Exception) { }
                    }
                    // Depth-based 3D unprojection (processFrameWithDepth has its own fallback if depth dims are 0)
                    processFrameWithDepth(
                        depthFile = depthFile,
                        bitmap = bitmap,
                        origImgWidth = origImgWidth,
                        origImgHeight = origImgHeight,
                        poseMatrix = poseMatrix,
                        fx = fx,
                        fy = fy,
                        cx = cx,
                        cy = cy,
                        step = step,
                        voxelGrid = voxelGrid,
                        depthWidth = depthWidth,
                        depthHeight = depthHeight,
                        scanMode = scanMode,
                        maxPoints = maxPoints
                    )
                } else {
                    // Fallback for frames without raw depth (e.g. estimated plane unprojection)
                    processFrameWithoutDepth(
                        bitmap = bitmap,
                        origImgWidth = origImgWidth,
                        origImgHeight = origImgHeight,
                        poseMatrix = poseMatrix,
                        fx = fx,
                        fy = fy,
                        cx = cx,
                        cy = cy,
                        step = step * 2,
                        voxelGrid = voxelGrid,
                        scanMode = scanMode,
                        maxPoints = maxPoints
                    )
                }
            } catch (e: OutOfMemoryError) {
                Log.e(TAG, "OOM processing frame ${index + 1}/${totalFrames}, continuing with ${voxelGrid.size} points so far", e)
                System.gc()
            } catch (e: Exception) {
                Log.e(TAG, "Error processing frame ${index + 1}/${totalFrames}: ${e.message}", e)
            } finally {
                bitmap.recycle()
            }

            // Early exit if we've hit the point cap — no need to process remaining frames
            if (voxelGrid.size >= maxPoints) {
                Log.i(TAG, "Point cap ($maxPoints) reached after frame ${index + 1}/$totalFrames, skipping remaining frames")
            }

            val percent = 10 + ((index + 1).toFloat() / totalFrames * 70).roundToInt()
            onProgress(
                ProcessingProgress(
                    currentStep = "Unprojecting RGB-D frame ${index + 1}/$totalFrames...",
                    progressPercent = percent,
                    pointCount = voxelGrid.size
                )
            )
        }

        onProgress(ProcessingProgress("Applying 3D Gaussian spatial smoothing...", 85, voxelGrid.size))

        val points = ArrayList(voxelGrid.values)
        Log.i(TAG, "Finished processing. Total Gaussians generated: ${points.size}")

        onProgress(ProcessingProgress("Finalizing 3D Splat representation...", 95, points.size))
        points
    }

    private fun processFrameWithDepth(
        depthFile: File,
        bitmap: Bitmap,
        origImgWidth: Int,
        origImgHeight: Int,
        poseMatrix: FloatArray,
        fx: Float,
        fy: Float,
        cx: Float,
        cy: Float,
        step: Int,
        voxelGrid: HashMap<Long, SplatPoint>,
        depthWidth: Int,
        depthHeight: Int,
        scanMode: ScanMode,
        maxPoints: Int
    ) {
        val depthBytes = depthFile.readBytes()
        if (depthBytes.isEmpty()) return

        val depthBuffer = ByteBuffer.wrap(depthBytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
        val depthPixelCount = depthBuffer.remaining()

        val bitmapWidth = bitmap.width
        val bitmapHeight = bitmap.height

        // Use the exact depth dimensions passed in (from depthdims_*.txt or fallback)
        val actualDepthWidth = if (depthWidth > 0) depthWidth else if (depthPixelCount == 160 * 120) 160 else if (depthPixelCount == 640 * 480) 640 else 160
        val actualDepthHeight = if (depthHeight > 0) depthHeight else (depthPixelCount / actualDepthWidth)

        // CRITICAL FIX: Scale camera intrinsics to depth image resolution.
        // The camera intrinsics (fx, fy, cx, cy) are in camera-image pixels (e.g. 1920x1080),
        // but depth pixels are in a much smaller image (e.g. 160x120). Using the camera
        // intrinsics directly for depth pixels produces completely wrong 3D coordinates.
        // Use original image dimensions (not downscaled bitmap) for depth scaling.
        val depthScaleX = actualDepthWidth.toFloat() / origImgWidth.toFloat()
        val depthScaleY = actualDepthHeight.toFloat() / origImgHeight.toFloat()
        val depthFx = (fx * depthScaleX).coerceAtLeast(1f)
        val depthFy = (fy * depthScaleY).coerceAtLeast(1f)
        val depthCx = cx * depthScaleX
        val depthCy = cy * depthScaleY

        // Scale RGB intrinsics to the downscaled bitmap's coordinate space.
        // The bitmap may be smaller than the original camera image (due to RGB_SAMPLE_SIZE),
        // so intrinsics must be scaled to match the bitmap dimensions for correct pixel lookup.
        val bitmapScaleX = bitmapWidth.toFloat() / origImgWidth.toFloat()
        val bitmapScaleY = bitmapHeight.toFloat() / origImgHeight.toFloat()
        val rgbFx = (fx * bitmapScaleX).coerceAtLeast(1f)
        val rgbFy = (fy * bitmapScaleY).coerceAtLeast(1f)
        val rgbCx = cx * bitmapScaleX
        val rgbCy = cy * bitmapScaleY

        // Use scan mode's depth range and voxel size
        val minDepth = scanMode.minDepthMeters
        val maxDepth = scanMode.maxDepthMeters
        val voxelSize = scanMode.voxelSizeMeters

        // Batch-extract all bitmap pixels into an IntArray for fast access.
        // This replaces thousands of JNI-heavy bitmap.getPixel() calls per frame
        // with a single bulk transfer followed by cheap array indexing.
        val pixels = IntArray(bitmapWidth * bitmapHeight)
        bitmap.getPixels(pixels, 0, bitmapWidth, 0, 0, bitmapWidth, bitmapHeight)

        for (v in 0 until actualDepthHeight step step) {
            for (u in 0 until actualDepthWidth step step) {
                val index = v * actualDepthWidth + u
                if (index >= depthPixelCount) continue

                // Stop adding points once we hit the cap — prevents OOM from unbounded grid growth
                if (voxelGrid.size >= maxPoints) return

                val depthMm = depthBuffer.get(index).toInt() and 0xFFFF
                if (depthMm == 0) continue

                val depthMeters = depthMm / 1000.0f
                if (depthMeters < minDepth || depthMeters > maxDepth) continue

                val rgbCoords = mapDepthToRgbPixel(
                    uDepth = u,
                    vDepth = v,
                    depthWidth = actualDepthWidth,
                    depthHeight = actualDepthHeight,
                    depthFx = depthFx,
                    depthFy = depthFy,
                    depthCx = depthCx,
                    depthCy = depthCy,
                    rgbWidth = bitmapWidth,
                    rgbHeight = bitmapHeight,
                    rgbFx = rgbFx,
                    rgbFy = rgbFy,
                    rgbCx = rgbCx,
                    rgbCy = rgbCy,
                    rotationDegrees = 0,
                    cropX = 0f,
                    cropY = 0f,
                    distortionK1 = 0.0f
                )

                val rgbU = rgbCoords.first.coerceIn(0, bitmapWidth - 1)
                val rgbV = rgbCoords.second.coerceIn(0, bitmapHeight - 1)
                val pixelColor = pixels[rgbV * bitmapWidth + rgbU]

                val xCam = (u - depthCx) * depthMeters / depthFx
                val yCam = (v - depthCy) * depthMeters / depthFy
                val zCam = depthMeters

                val xWorld = poseMatrix[0] * xCam + poseMatrix[4] * yCam + poseMatrix[8] * zCam + poseMatrix[12]
                val yWorld = poseMatrix[1] * xCam + poseMatrix[5] * yCam + poseMatrix[9] * zCam + poseMatrix[13]
                val zWorld = poseMatrix[2] * xCam + poseMatrix[6] * yCam + poseMatrix[10] * zCam + poseMatrix[14]

                val r = ((pixelColor shr 16) and 0xFF) / 255.0f
                val g = ((pixelColor shr 8) and 0xFF) / 255.0f
                val b = (pixelColor and 0xFF) / 255.0f

                val voxelKey = getVoxelKey(xWorld, yWorld, zWorld, voxelSize)

                if (!voxelGrid.containsKey(voxelKey)) {
                    val splat = SplatPoint(
                        x = xWorld,
                        y = yWorld,
                        z = zWorld,
                        r = r,
                        g = g,
                        b = b,
                        alpha = 0.85f,
                        scaleX = voxelSize * 1.5f,
                        scaleY = voxelSize * 1.5f,
                        scaleZ = voxelSize * 1.5f
                    )
                    voxelGrid[voxelKey] = splat
                }
            }
        }
    }

    private fun processFrameWithoutDepth(
        bitmap: Bitmap,
        origImgWidth: Int,
        origImgHeight: Int,
        poseMatrix: FloatArray,
        fx: Float,
        fy: Float,
        cx: Float,
        cy: Float,
        step: Int,
        voxelGrid: HashMap<Long, SplatPoint>,
        scanMode: ScanMode,
        maxPoints: Int
    ) {
        val bitmapWidth = bitmap.width
        val bitmapHeight = bitmap.height
        val defaultDepth = 1.0f // 1 meter default plane distance
        val voxelSize = scanMode.voxelSizeMeters

        // Scale intrinsics to bitmap coordinate space
        val bitmapScaleX = bitmapWidth.toFloat() / origImgWidth.toFloat()
        val bitmapScaleY = bitmapHeight.toFloat() / origImgHeight.toFloat()
        val scaledFx = (fx * bitmapScaleX).coerceAtLeast(1f)
        val scaledFy = (fy * bitmapScaleY).coerceAtLeast(1f)
        val scaledCx = cx * bitmapScaleX
        val scaledCy = cy * bitmapScaleY

        // Batch-extract all pixels for fast access
        val pixels = IntArray(bitmapWidth * bitmapHeight)
        bitmap.getPixels(pixels, 0, bitmapWidth, 0, 0, bitmapWidth, bitmapHeight)

        for (v in 0 until bitmapHeight step (step * 3)) {
            for (u in 0 until bitmapWidth step (step * 3)) {
                if (voxelGrid.size >= maxPoints) return

                val xCam = (u - scaledCx) * defaultDepth / scaledFx
                val yCam = (v - scaledCy) * defaultDepth / scaledFy
                val zCam = defaultDepth

                val xWorld = poseMatrix[0] * xCam + poseMatrix[4] * yCam + poseMatrix[8] * zCam + poseMatrix[12]
                val yWorld = poseMatrix[1] * xCam + poseMatrix[5] * yCam + poseMatrix[9] * zCam + poseMatrix[13]
                val zWorld = poseMatrix[2] * xCam + poseMatrix[6] * yCam + poseMatrix[10] * zCam + poseMatrix[14]

                val pixelColor = pixels[v * bitmapWidth + u]
                val r = ((pixelColor shr 16) and 0xFF) / 255.0f
                val g = ((pixelColor shr 8) and 0xFF) / 255.0f
                val b = (pixelColor and 0xFF) / 255.0f

                val voxelKey = getVoxelKey(xWorld, yWorld, zWorld, voxelSize * 2f)

                if (!voxelGrid.containsKey(voxelKey)) {
                    voxelGrid[voxelKey] = SplatPoint(
                        x = xWorld,
                        y = yWorld,
                        z = zWorld,
                        r = r,
                        g = g,
                        b = b,
                        alpha = 0.7f,
                        scaleX = voxelSize * 2f,
                        scaleY = voxelSize * 2f,
                        scaleZ = voxelSize * 2f
                    )
                }
            }
        }
    }

    /**
     * Load a downscaled bitmap and return it along with the original image dimensions.
     * The original dimensions are needed to correctly scale camera intrinsics.
     */
    private fun loadSampledBitmap(file: File, sampleSize: Int): Pair<Bitmap, Pair<Int, Int>>? {
        return try {
            // First, decode bounds only to get original dimensions
            val boundsOpts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.absolutePath, boundsOpts)
            val origWidth = boundsOpts.outWidth
            val origHeight = boundsOpts.outHeight

            if (origWidth <= 0 || origHeight <= 0) return null

            // Then, decode the actual bitmap at reduced resolution
            val opts = BitmapFactory.Options().apply {
                inSampleSize = sampleSize
                inPreferredConfig = Bitmap.Config.RGB_565
            }
            val bitmap = BitmapFactory.decodeFile(file.absolutePath, opts) ?: return null
            Pair(bitmap, Pair(origWidth, origHeight))
        } catch (e: Exception) {
            Log.w(TAG, "Failed to load bitmap: ${file.name} — ${e.message}")
            null
        }
    }

    private fun getVoxelKey(x: Float, y: Float, z: Float, voxelSize: Float): Long {
        val vx = (x / voxelSize).roundToInt().toLong()
        val vy = (y / voxelSize).roundToInt().toLong()
        val vz = (z / voxelSize).roundToInt().toLong()

        // 64-bit spatial hash key mapping 3D voxel coordinates
        return (vx and 0x1FFFFFL) or ((vy and 0x1FFFFFL) shl 21) or ((vz and 0x1FFFFFL) shl 42)
    }

    fun mapDepthToRgbPixel(
        uDepth: Int,
        vDepth: Int,
        depthWidth: Int,
        depthHeight: Int,
        depthFx: Float,
        depthFy: Float,
        depthCx: Float,
        depthCy: Float,
        rgbWidth: Int,
        rgbHeight: Int,
        rgbFx: Float,
        rgbFy: Float,
        rgbCx: Float,
        rgbCy: Float,
        rotationDegrees: Int,
        cropX: Float,
        cropY: Float,
        distortionK1: Float
    ): Pair<Int, Int> {
        val normalizedX = (uDepth - depthCx) / depthFx
        val normalizedY = (vDepth - depthCy) / depthFy

        val r2 = normalizedX * normalizedX + normalizedY * normalizedY
        val radial = 1.0f + distortionK1 * r2
        val undistortedX = normalizedX * radial
        val undistortedY = normalizedY * radial

        val angle = Math.toRadians(rotationDegrees.toDouble())
        val cos = cos(angle).toFloat()
        val sin = sin(angle).toFloat()

        val rotatedX = undistortedX * cos - undistortedY * sin
        val rotatedY = undistortedX * sin + undistortedY * cos

        val rgbX = rgbCx + rotatedX * rgbFx + cropX
        val rgbY = rgbCy + rotatedY * rgbFy + cropY

        val x = rgbX.roundToInt().coerceIn(0, rgbWidth - 1)
        val y = rgbY.roundToInt().coerceIn(0, rgbHeight - 1)
        return x to y
    }

    private fun parsePoseMatrix(poseFile: File): FloatArray? {
        return try {
            val text = poseFile.readText().trim()
            val parts = text.split(",")
            if (parts.size == 16) {
                FloatArray(16) { i -> parts[i].trim().toFloat() }
            } else null
        } catch (e: Exception) {
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
        } catch (e: Exception) {
            null
        }
    }
}
