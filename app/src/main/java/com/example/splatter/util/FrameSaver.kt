package com.example.splatter.util

import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.YuvImage
import android.media.Image
import android.util.Log
import com.google.ar.core.Frame
import com.google.ar.core.exceptions.NotYetAvailableException
import java.io.ByteArrayOutputStream
import java.io.File

object FrameSaver {
    private const val TAG = "FrameSaver"

    fun saveFrameData(frame: Frame, timestamp: Long, storageDir: File): Boolean {
        if (!storageDir.exists()) {
            storageDir.mkdirs()
        }

        // 1. Save Pose Matrix
        val poseMatrix = FloatArray(16)
        frame.camera.pose.toMatrix(poseMatrix, 0)

        val poseFile = File(storageDir, "pose_$timestamp.txt")
        poseFile.writeText(poseMatrix.joinToString(","))

        // 1b. Save Camera Image Intrinsics
        try {
            val intrinsics = frame.camera.imageIntrinsics
            val fl = intrinsics.focalLength
            val pp = intrinsics.principalPoint
            val dims = intrinsics.imageDimensions
            val intrinsicsFile = File(storageDir, "intrinsics_$timestamp.txt")
            intrinsicsFile.writeText("${fl[0]},${fl[1]},${pp[0]},${pp[1]},${dims[0]},${dims[1]}")
        } catch (e: Exception) {
            Log.w(TAG, "Could not acquire camera intrinsics: ${e.message}")
        }

        var rgbImage: Image? = null
        var depthImage: Image? = null
        var confidenceImage: Image? = null

        try {
            // 2. Extract Images
            rgbImage = frame.acquireCameraImage()
            depthImage = try {
                frame.acquireRawDepthImage16Bits()
            } catch (_: NotYetAvailableException) {
                Log.w(TAG, "Raw depth image not yet available for timestamp $timestamp")
                null
            }
            confidenceImage = try {
                frame.acquireRawDepthConfidenceImage()
            } catch (_: NotYetAvailableException) {
                null
            }

            // 3. Save Raw Depth Buffer (de-padded to exactly width*height*2 bytes)
            depthImage?.let { depth ->
                val depthWidth = depth.width
                val depthHeight = depth.height
                val depthBuffer = depth.planes[0].buffer
                val rowStride = depth.planes[0].rowStride
                val pixelStride = depth.planes[0].pixelStride

                val depthBytes = if (pixelStride == 2 && rowStride == depthWidth * 2) {
                    // Tightly packed: copy directly
                    ByteArray(depthBuffer.remaining()).also { depthBuffer.get(it) }
                } else {
                    // Row-stride padding present: strip it row by row
                    val limit = depthBuffer.remaining()
                    val out = ByteArray(depthWidth * depthHeight * 2)
                    for (row in 0 until depthHeight) {
                        val rowStart = row * rowStride
                        for (col in 0 until depthWidth) {
                            val srcIdx = rowStart + col * pixelStride
                            if (srcIdx + 1 >= limit) break
                            depthBuffer.position(srcIdx)
                            val dst = (row * depthWidth + col) * 2
                            out[dst] = depthBuffer.get()
                            out[dst + 1] = depthBuffer.get()
                        }
                    }
                    out
                }

                val depthFile = File(storageDir, "depth_$timestamp.raw")
                depthFile.writeBytes(depthBytes)

                // Persist exact depth dimensions so processing never has to guess
                val dimsFile = File(storageDir, "depthdims_$timestamp.txt")
                dimsFile.writeText("$depthWidth,$depthHeight")
            }

            confidenceImage?.let { conf ->
                val confBuffer = conf.planes[0].buffer
                val confBytes = ByteArray(confBuffer.remaining())
                confBuffer.get(confBytes)
                File(storageDir, "confidence_$timestamp.raw").writeBytes(confBytes)
            }

            // 4. Save RGB Image
            rgbImage?.let { rgb ->
                val jpegBytes = convertYuvToJpeg(rgb)
                val rgbFile = File(storageDir, "rgb_$timestamp.jpg")
                rgbFile.writeBytes(jpegBytes)
            }

            return true
        } catch (e: Exception) {
            Log.e(TAG, "Error saving frame data for timestamp $timestamp", e)
            return false
        } finally {
            // IMPORTANT: Free the buffers so the AR session doesn't freeze
            rgbImage?.close()
            depthImage?.close()
            confidenceImage?.close()
        }
    }

    fun convertYuvToJpeg(image: Image): ByteArray {
        val width = image.width
        val height = image.height

        val yBuffer = image.planes[0].buffer
        val uBuffer = image.planes[1].buffer
        val vBuffer = image.planes[2].buffer

        val ySize = yBuffer.remaining()
        val uSize = uBuffer.remaining()
        val vSize = vBuffer.remaining()

        val nv21 = ByteArray(ySize + uSize + vSize)

        // Quick merge of planes into NV21 layout
        yBuffer.get(nv21, 0, ySize)

        val vPixelStride = image.planes[2].pixelStride
        val uPixelStride = image.planes[1].pixelStride

        if (vPixelStride == 2 && uPixelStride == 2 && vBuffer.remaining() >= uSize) {
            // Interleaved V and U in vBuffer (standard NV21 layout)
            vBuffer.get(nv21, ySize, vSize)
        } else {
            // Interleave fallback
            var position = ySize
            val uBytes = ByteArray(uSize)
            val vBytes = ByteArray(vSize)
            uBuffer.get(uBytes)
            vBuffer.get(vBytes)

            val chromaSize = uSize.coerceAtMost(vSize)
            for (i in 0 until chromaSize) {
                if (position < nv21.size) {
                    nv21[position++] = vBytes[i]
                }
                if (position < nv21.size) {
                    nv21[position++] = uBytes[i]
                }
            }
        }

        val yuvImage = YuvImage(nv21, ImageFormat.NV21, width, height, null)
        val out = ByteArrayOutputStream()
        yuvImage.compressToJpeg(Rect(0, 0, width, height), 90, out)
        return out.toByteArray()
    }
}
