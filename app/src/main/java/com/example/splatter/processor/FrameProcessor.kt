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

object FrameProcessor {
    private const val TAG = "FrameProcessor"

    // ARCore raw depth confidence threshold (0–255)
    private const val MIN_CONFIDENCE = 128

    private class VoxelAccumulator {
        var sumX = 0.0
        var sumY = 0.0
        var sumZ = 0.0
        var sumR = 0.0
        var sumG = 0.0
        var sumB = 0.0
        var count = 0
    }

    data class ProcessingProgress(
        val currentStep: String,
        val progressPercent: Int,
        val pointCount: Int
    )

    suspend fun processDataset(
        datasetDir: File,
        scanMode: ScanMode = ScanMode.OBJECT,
        onProgress: (ProcessingProgress) -> Unit
    ): List<SplatPoint> = withContext(Dispatchers.IO) {
        onProgress(ProcessingProgress("Scanning captured dataset...", 5, 0))

        if (!datasetDir.exists() || !datasetDir.isDirectory) {
            Log.e(TAG, "Dataset directory does not exist: ${datasetDir.absolutePath}")
            return@withContext emptyList()
        }

        // Check if mode was persisted in session directory
        val modeFile = File(datasetDir, "mode.txt")
        val activeMode = if (modeFile.exists()) {
            ScanMode.fromId(modeFile.readText().trim())
        } else {
            scanMode
        }

        Log.i(TAG, "Processing dataset with mode: ${activeMode.displayName} (Depth: ${activeMode.minDepthMeters}-${activeMode.maxDepthMeters}m, Voxel: ${activeMode.voxelSizeMeters * 1000}mm)")

        // Find all pose files
        val poseFiles = datasetDir.listFiles { _, name -> name.startsWith("pose_") && name.endsWith(".txt") }
            ?.sortedBy { file ->
                file.name.removePrefix("pose_").removeSuffix(".txt").toLongOrNull() ?: 0L
            } ?: emptyList()

        if (poseFiles.isEmpty()) {
            Log.w(TAG, "No pose files found in dataset directory.")
            return@withContext emptyList()
        }

        val totalFrames = poseFiles.size
        Log.i(TAG, "Processing $totalFrames recorded AR frames for ${activeMode.displayName} mode...")

        // Voxel Grid spatial hash map with accumulators for blending repeat hits
        val voxelGrid = HashMap<Long, VoxelAccumulator>()

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

            // 3. Load RGB Bitmap
            val bitmap = BitmapFactory.decodeFile(rgbFile.absolutePath) ?: continue
            val imgWidth = bitmap.width
            val imgHeight = bitmap.height

            val fx = intrinsics?.getOrNull(0) ?: (imgWidth * 0.8f)
            val fy = intrinsics?.getOrNull(1) ?: (imgHeight * 0.8f)
            val cx = intrinsics?.getOrNull(2) ?: (imgWidth * 0.5f)
            val cy = intrinsics?.getOrNull(3) ?: (imgHeight * 0.5f)

            // Step size for pixel sampling based on mode resolution requirements
            val step = if (activeMode == ScanMode.OBJECT) 2 else 3

            if (depthFile.exists()) {
                // Depth-based 3D unprojection
                processFrameWithDepth(
                    depthFile = depthFile,
                    bitmap = bitmap,
                    poseMatrix = poseMatrix,
                    fx = fx,
                    fy = fy,
                    cx = cx,
                    cy = cy,
                    step = step,
                    scanMode = activeMode,
                    voxelGrid = voxelGrid
                )
            } else {
                // Fallback for frames without raw depth
                processFrameWithoutDepth(
                    bitmap = bitmap,
                    poseMatrix = poseMatrix,
                    fx = fx,
                    fy = fy,
                    cx = cx,
                    cy = cy,
                    step = step * 2,
                    scanMode = activeMode,
                    voxelGrid = voxelGrid
                )
            }

            bitmap.recycle()

            val percent = 10 + ((index + 1).toFloat() / totalFrames * 70).roundToInt()
            onProgress(
                ProcessingProgress(
                    currentStep = "Unprojecting frame ${index + 1}/$totalFrames (${activeMode.displayName} mode)...",
                    progressPercent = percent,
                    pointCount = voxelGrid.size
                )
            )
        }

        onProgress(ProcessingProgress("Applying 3D Gaussian spatial smoothing...", 85, voxelGrid.size))

        val voxelSize = activeMode.voxelSizeMeters
        val generatedPoints = voxelGrid.values.map { acc ->
            val finalX = (acc.sumX / acc.count).toFloat()
            var finalY = (acc.sumY / acc.count).toFloat()
            val finalZ = (acc.sumZ / acc.count).toFloat()

            // For Room mode floor/wall detection refinement: align planar features
            if (activeMode.enablePlaneDetection) {
                // Floor plane planar alignment (e.g., snap subtle height noise near main surfaces)
                val roundedY = (finalY / (voxelSize * 0.5f)).roundToInt() * (voxelSize * 0.5f)
                if (abs(finalY - roundedY) < voxelSize * 0.2f) {
                    finalY = roundedY
                }
            }

            SplatPoint(
                x = finalX,
                y = finalY,
                z = finalZ,
                r = (acc.sumR / acc.count).toFloat(),
                g = (acc.sumG / acc.count).toFloat(),
                b = (acc.sumB / acc.count).toFloat(),
                alpha = 0.85f,
                scaleX = voxelSize * 1.2f,
                scaleY = voxelSize * 1.2f,
                scaleZ = voxelSize * 1.2f
            )
        }

        val points = if (generatedPoints.size > activeMode.maxPointLimit) {
            Log.i(TAG, "Limiting points from ${generatedPoints.size} to max point limit ${activeMode.maxPointLimit} for ${activeMode.displayName} mode")
            generatedPoints.take(activeMode.maxPointLimit)
        } else {
            generatedPoints
        }

        Log.i(TAG, "Finished processing ${activeMode.displayName} mode dataset. Total Gaussians generated: ${points.size}")

        onProgress(ProcessingProgress("Finalizing 3D Splat representation...", 95, points.size))
        points
    }

    private fun processFrameWithDepth(
        depthFile: File,
        bitmap: Bitmap,
        poseMatrix: FloatArray,
        fx: Float,
        fy: Float,
        cx: Float,
        cy: Float,
        step: Int,
        scanMode: ScanMode,
        voxelGrid: HashMap<Long, VoxelAccumulator>
    ) {
        val depthBytes = depthFile.readBytes()
        if (depthBytes.isEmpty()) return

        val confidenceFile = File(depthFile.parentFile, "confidence_${depthFile.name.removePrefix("depth_").removeSuffix(".raw")}.raw")
        val confidenceBytes = if (confidenceFile.exists()) confidenceFile.readBytes() else null

        val depthBuffer = ByteBuffer.wrap(depthBytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
        val depthPixelCount = depthBuffer.remaining()

        val imgWidth = bitmap.width
        val imgHeight = bitmap.height

        val depthWidth = if (depthPixelCount == 160 * 120) 160 else if (depthPixelCount == 640 * 480) 640 else 160
        val depthHeight = if (depthWidth > 0) depthPixelCount / depthWidth else 120

        val scaleX = imgWidth.toFloat() / depthWidth
        val scaleY = imgHeight.toFloat() / depthHeight

        val scaleFx = fx * (depthWidth.toFloat() / imgWidth)
        val scaleFy = fy * (depthHeight.toFloat() / imgHeight)
        val scaleCx = cx * (depthWidth.toFloat() / imgWidth)
        val scaleCy = cy * (depthHeight.toFloat() / imgHeight)

        val voxelSize = scanMode.voxelSizeMeters
        val minDepth = scanMode.minDepthMeters
        val maxDepth = scanMode.maxDepthMeters

        for (v in 0 until depthHeight step step) {
            for (u in 0 until depthWidth step step) {
                val index = v * depthWidth + u
                if (index >= depthPixelCount) continue

                // 16-bit depth in millimeters
                val depthMm = depthBuffer.get(index).toInt() and 0xFFFF
                if (depthMm == 0) continue

                if (confidenceBytes != null && index < confidenceBytes.size) {
                    val confidence = confidenceBytes[index].toInt() and 0xFF
                    if (confidence < MIN_CONFIDENCE) continue
                }

                val depthMeters = depthMm / 1000.0f
                if (depthMeters < minDepth || depthMeters > maxDepth) continue

                // Unproject 2D depth pixel to 3D camera coordinates
                val xCam = (u - scaleCx) * depthMeters / scaleFx
                val yCam = (v - scaleCy) * depthMeters / scaleFy
                val zCam = depthMeters

                // Transform by camera pose matrix into world space
                val xWorld = poseMatrix[0] * xCam + poseMatrix[4] * yCam + poseMatrix[8] * zCam + poseMatrix[12]
                val yWorld = poseMatrix[1] * xCam + poseMatrix[5] * yCam + poseMatrix[9] * zCam + poseMatrix[13]
                val zWorld = poseMatrix[2] * xCam + poseMatrix[6] * yCam + poseMatrix[10] * zCam + poseMatrix[14]

                // Sample RGB color from corresponding image location
                val rgbU = (u * scaleX).toInt().coerceIn(0, imgWidth - 1)
                val rgbV = (v * scaleY).toInt().coerceIn(0, imgHeight - 1)
                val pixelColor = bitmap.getPixel(rgbU, rgbV)

                val r = ((pixelColor shr 16) and 0xFF) / 255.0f
                val g = ((pixelColor shr 8) and 0xFF) / 255.0f
                val b = (pixelColor and 0xFF) / 255.0f

                val voxelKey = getVoxelKey(xWorld, yWorld, zWorld, voxelSize)
                val acc = voxelGrid.getOrPut(voxelKey) { VoxelAccumulator() }
                acc.sumX += xWorld
                acc.sumY += yWorld
                acc.sumZ += zWorld
                acc.sumR += r
                acc.sumG += g
                acc.sumB += b
                acc.count++
            }
        }
    }

    private fun processFrameWithoutDepth(
        bitmap: Bitmap,
        poseMatrix: FloatArray,
        fx: Float,
        fy: Float,
        cx: Float,
        cy: Float,
        step: Int,
        scanMode: ScanMode,
        voxelGrid: HashMap<Long, VoxelAccumulator>
    ) {
        val imgWidth = bitmap.width
        val imgHeight = bitmap.height
        val defaultDepth = (scanMode.minDepthMeters + scanMode.maxDepthMeters) / 2f

        for (v in 0 until imgHeight step (step * 3)) {
            for (u in 0 until imgWidth step (step * 3)) {
                val xCam = (u - cx) * defaultDepth / fx
                val yCam = (v - cy) * defaultDepth / fy
                val zCam = defaultDepth

                val xWorld = poseMatrix[0] * xCam + poseMatrix[4] * yCam + poseMatrix[8] * zCam + poseMatrix[12]
                val yWorld = poseMatrix[1] * xCam + poseMatrix[5] * yCam + poseMatrix[9] * zCam + poseMatrix[13]
                val zWorld = poseMatrix[2] * xCam + poseMatrix[6] * yCam + poseMatrix[10] * zCam + poseMatrix[14]

                val pixelColor = bitmap.getPixel(u, v)
                val r = ((pixelColor shr 16) and 0xFF) / 255.0f
                val g = ((pixelColor shr 8) and 0xFF) / 255.0f
                val b = (pixelColor and 0xFF) / 255.0f

                val voxelKey = getVoxelKey(xWorld, yWorld, zWorld, scanMode.voxelSizeMeters * 2f)
                val acc = voxelGrid.getOrPut(voxelKey) { VoxelAccumulator() }
                acc.sumX += xWorld
                acc.sumY += yWorld
                acc.sumZ += zWorld
                acc.sumR += r
                acc.sumG += g
                acc.sumB += b
                acc.count++
            }
        }
    }

    private fun getVoxelKey(x: Float, y: Float, z: Float, voxelSize: Float): Long {
        val vx = (x / voxelSize).roundToInt().toLong()
        val vy = (y / voxelSize).roundToInt().toLong()
        val vz = (z / voxelSize).roundToInt().toLong()

        // 64-bit spatial hash key mapping 3D voxel coordinates
        return (vx and 0x1FFFFFL) or ((vy and 0x1FFFFFL) shl 21) or ((vz and 0x1FFFFFL) shl 42)
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
