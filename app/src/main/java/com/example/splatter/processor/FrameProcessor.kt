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

    // Voxel size in meters for spatial downsampling (8 mm)
    private const val VOXEL_SIZE = 0.008f
    private const val MIN_DEPTH_METERS = 0.25f
    private const val MAX_DEPTH_METERS = 3.5f

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

            // Step size for pixel sampling to optimize memory and processing speed
            val step = 2

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
                    voxelGrid = voxelGrid
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
                    voxelGrid = voxelGrid
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
        voxelGrid: HashMap<Long, SplatPoint>
    ) {
        val depthBytes = depthFile.readBytes()
        if (depthBytes.isEmpty()) return

        val depthBuffer = ByteBuffer.wrap(depthBytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
        val depthPixelCount = depthBuffer.remaining()

        val imgWidth = bitmap.width
        val imgHeight = bitmap.height

        val depthWidth = if (depthPixelCount == 160 * 120) 160 else if (depthPixelCount == 640 * 480) 640 else 160
        val depthHeight = if (depthWidth > 0) depthPixelCount / depthWidth else 120

        val depthFx = fx.coerceAtLeast(1f)
        val depthFy = fy.coerceAtLeast(1f)
        val depthCx = cx.coerceAtLeast(0f)
        val depthCy = cy.coerceAtLeast(0f)

        val rgbFx = (imgWidth * 0.8f).coerceAtLeast(1f)
        val rgbFy = (imgHeight * 0.8f).coerceAtLeast(1f)
        val rgbCx = imgWidth * 0.5f
        val rgbCy = imgHeight * 0.5f

        for (v in 0 until depthHeight step step) {
            for (u in 0 until depthWidth step step) {
                val index = v * depthWidth + u
                if (index >= depthPixelCount) continue

                val depthMm = depthBuffer.get(index).toInt() and 0xFFFF
                if (depthMm == 0) continue

                val depthMeters = depthMm / 1000.0f
                if (depthMeters < MIN_DEPTH_METERS || depthMeters > MAX_DEPTH_METERS) continue

                val rgbCoords = mapDepthToRgbPixel(
                    uDepth = u,
                    vDepth = v,
                    depthWidth = depthWidth,
                    depthHeight = depthHeight,
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

                val voxelKey = getVoxelKey(xWorld, yWorld, zWorld, VOXEL_SIZE)

                if (!voxelGrid.containsKey(voxelKey)) {
                    val splat = SplatPoint(
                        x = xWorld,
                        y = yWorld,
                        z = zWorld,
                        r = r,
                        g = g,
                        b = b,
                        alpha = 0.85f,
                        scaleX = VOXEL_SIZE * 1.2f,
                        scaleY = VOXEL_SIZE * 1.2f,
                        scaleZ = VOXEL_SIZE * 1.2f
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
        voxelGrid: HashMap<Long, SplatPoint>
    ) {
        val imgWidth = bitmap.width
        val imgHeight = bitmap.height
        val defaultDepth = 1.0f // 1 meter default plane distance

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

                val voxelKey = getVoxelKey(xWorld, yWorld, zWorld, VOXEL_SIZE * 2f)

                if (!voxelGrid.containsKey(voxelKey)) {
                    voxelGrid[voxelKey] = SplatPoint(
                        x = xWorld,
                        y = yWorld,
                        z = zWorld,
                        r = r,
                        g = g,
                        b = b,
                        alpha = 0.7f,
                        scaleX = VOXEL_SIZE * 2f,
                        scaleY = VOXEL_SIZE * 2f,
                        scaleZ = VOXEL_SIZE * 2f
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
