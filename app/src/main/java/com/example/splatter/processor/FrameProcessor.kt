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
import kotlin.math.sqrt

object FrameProcessor {
    private const val TAG = "FrameProcessor"

    // These are now read from ScanMode — kept as fallback defaults only
    private const val DEFAULT_VOXEL_SIZE = 0.008f
    private const val DEFAULT_MIN_DEPTH = 0.25f
    private const val DEFAULT_MAX_DEPTH = 3.5f

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
        Log.i(TAG, "Processing $totalFrames recorded AR frames...")

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

            // 3. Load RGB Bitmap
            val bitmap = BitmapFactory.decodeFile(rgbFile.absolutePath) ?: continue
            val imgWidth = bitmap.width
            val imgHeight = bitmap.height

            val fx = intrinsics?.getOrNull(0) ?: (imgWidth * 0.8f)
            val fy = intrinsics?.getOrNull(1) ?: (imgHeight * 0.8f)
            val cx = intrinsics?.getOrNull(2) ?: (imgWidth * 0.5f)
            val cy = intrinsics?.getOrNull(3) ?: (imgHeight * 0.5f)

            // Step size — use 1 for full density (every depth pixel contributes)
            val step = 1

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
                    poseMatrix = poseMatrix,
                    fx = fx,
                    fy = fy,
                    cx = cx,
                    cy = cy,
                    step = step,
                    voxelGrid = voxelGrid,
                    depthWidth = depthWidth,
                    depthHeight = depthHeight,
                    scanMode = scanMode
                )
            } else {
                // Fallback for frames without raw depth (e.g. estimated plane unprojection)
                processFrameWithoutDepth(
                    bitmap = bitmap,
                    poseMatrix = poseMatrix,
                    fx = fx,
                    fy = fy,
                    cx = cx,
                    cy = cy,
                    step = step * 2,
                    voxelGrid = voxelGrid,
                    scanMode = scanMode
                )
            }

            bitmap.recycle()

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

        val points = voxelGrid.values.toList()
        Log.i(TAG, "Finished processing. Total Gaussians generated: ${points.size}")

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
        voxelGrid: HashMap<Long, SplatPoint>,
        depthWidth: Int,
        depthHeight: Int,
        scanMode: ScanMode
    ) {
        val depthBytes = depthFile.readBytes()
        if (depthBytes.isEmpty()) return

        val depthBuffer = ByteBuffer.wrap(depthBytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
        val depthPixelCount = depthBuffer.remaining()

        val imgWidth = bitmap.width
        val imgHeight = bitmap.height

        // Use the exact depth dimensions passed in (from depthdims_*.txt or fallback)
        val actualDepthWidth = if (depthWidth > 0) depthWidth else if (depthPixelCount == 160 * 120) 160 else if (depthPixelCount == 640 * 480) 640 else 160
        val actualDepthHeight = if (depthHeight > 0) depthHeight else (depthPixelCount / actualDepthWidth)

        // CRITICAL FIX: Scale camera intrinsics to depth image resolution.
        // The camera intrinsics (fx, fy, cx, cy) are in camera-image pixels (e.g. 1920x1080),
        // but depth pixels are in a much smaller image (e.g. 160x120). Using the camera
        // intrinsics directly for depth pixels produces completely wrong 3D coordinates.
        val depthScaleX = actualDepthWidth.toFloat() / imgWidth.toFloat()
        val depthScaleY = actualDepthHeight.toFloat() / imgHeight.toFloat()
        val depthFx = (fx * depthScaleX).coerceAtLeast(1f)
        val depthFy = (fy * depthScaleY).coerceAtLeast(1f)
        val depthCx = cx * depthScaleX
        val depthCy = cy * depthScaleY

        // Use actual camera intrinsics for RGB mapping (not the old 0.8f estimate)
        val rgbFx = fx.coerceAtLeast(1f)
        val rgbFy = fy.coerceAtLeast(1f)
        val rgbCx = cx
        val rgbCy = cy

        // Use scan mode's depth range and voxel size
        val minDepth = scanMode.minDepthMeters
        val maxDepth = scanMode.maxDepthMeters
        val voxelSize = scanMode.voxelSizeMeters

        for (v in 0 until actualDepthHeight step step) {
            for (u in 0 until actualDepthWidth step step) {
                val index = v * actualDepthWidth + u
                if (index >= depthPixelCount) continue

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
                    rgbWidth = imgWidth,
                    rgbHeight = imgHeight,
                    rgbFx = rgbFx,
                    rgbFy = rgbFy,
                    rgbCx = rgbCx,
                    rgbCy = rgbCy,
                    rotationDegrees = 0,
                    cropX = 0f,
                    cropY = 0f,
                    distortionK1 = 0.0f
                )

                val rgbU = rgbCoords.first.coerceIn(0, imgWidth - 1)
                val rgbV = rgbCoords.second.coerceIn(0, imgHeight - 1)
                val pixelColor = bitmap.getPixel(rgbU, rgbV)

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
        poseMatrix: FloatArray,
        fx: Float,
        fy: Float,
        cx: Float,
        cy: Float,
        step: Int,
        voxelGrid: HashMap<Long, SplatPoint>,
        scanMode: ScanMode
    ) {
        val imgWidth = bitmap.width
        val imgHeight = bitmap.height
        val defaultDepth = 1.0f // 1 meter default plane distance
        val voxelSize = scanMode.voxelSizeMeters

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
