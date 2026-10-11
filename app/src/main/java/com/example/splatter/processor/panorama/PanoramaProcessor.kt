package com.example.splatter.processor.panorama

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import com.example.splatter.processor.CameraPose
import com.example.splatter.processor.FrameProcessor
import com.example.splatter.util.CaptureContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.math.sqrt

/**
 * Turns a 360° capture into a single equirectangular JPEG ready for Google Maps.
 *
 * The capture side needs nothing new: standing in one spot and sweeping the
 * phone around while recording already produces frames with ARCore poses and
 * intrinsics, which is exactly what [PanoramaStitcher] needs. This class does
 * the dataset I/O, frame selection and JPEG export around it.
 *
 * Output defaults to 4096 × 2048 — Google's Photo Sphere requirements ask for a
 * 2:1 image and at least 3840 × 1920 — with the GPano XMP tags and (when the
 * scan recorded a location) EXIF GPS tags written in by [PanoramaMetadata].
 */
object PanoramaProcessor {

    private const val TAG = "PanoramaProcessor"

    const val PANORAMA_FILENAME = "panorama.jpg"
    const val LOCATION_FILENAME = "location.txt"

    /** 2:1 and comfortably above Google's 3840 × 1920 minimum. */
    const val OUTPUT_WIDTH = 4096
    const val OUTPUT_HEIGHT = 2048

    /** Frames fed to the stitcher. More adds little and costs time quadratically-ish. */
    private const val MAX_FRAMES = 40

    /** Minimum angular change (degrees) between selected frames, so a still phone
     *  doesn't contribute forty frames of the same direction. */
    private const val MIN_ANGLE_STEP_DEG = 12f

    /** Longest side of each decoded source photo. */
    private const val FRAME_MAX_DIM = 1600

    /** Below this coverage the capture missed too much of the sphere to publish. */
    const val MIN_COVERAGE = 0.35f

    suspend fun process(
        datasetDir: File,
        headingDegrees: Float? = null,
        onProgress: (FrameProcessor.ProcessingProgress) -> Unit
    ): File? = withContext(Dispatchers.IO) {
        onProgress(FrameProcessor.ProcessingProgress("Scanning panorama frames...", 4, 0))

        val frames = selectFrames(datasetDir, onProgress) ?: return@withContext null
        onProgress(
            FrameProcessor.ProcessingProgress(
                "Stitching ${frames.size} frames into a 360° panorama...",
                25,
                0
            )
        )

        val stitcher = PanoramaStitcher(OUTPUT_WIDTH, OUTPUT_HEIGHT)
        val coverage = stitcher.coverage(frames)
        if (coverage < MIN_COVERAGE) {
            Log.w(TAG, "Panorama coverage too low: ${(coverage * 100).toInt()}%")
            return@withContext null
        }

        val result = stitcher.stitch(frames)
        onProgress(
            FrameProcessor.ProcessingProgress(
                "Writing 360° image (%.0f%% of the sphere covered)...".format(result.coverage * 100f),
                75,
                0
            )
        )

        val bitmap = Bitmap.createBitmap(result.pixels, result.width, result.height, Bitmap.Config.ARGB_8888)
        val jpegBytes = ByteArrayOutputStream().use { out ->
            bitmap.compress(Bitmap.CompressFormat.JPEG, 92, out)
            bitmap.recycle()
            out.toByteArray()
        }

        val (latitude, longitude) = readLocation(datasetDir)
        val withMetadata = PanoramaMetadata.insert(
            jpeg = jpegBytes,
            xmp = PanoramaMetadata.xmpPacket(result.width, result.height, headingDegrees),
            latitude = latitude,
            longitude = longitude
        )

        val outFile = File(datasetDir, PANORAMA_FILENAME)
        outFile.writeBytes(withMetadata)
        Log.i(
            TAG,
            "Wrote ${outFile.name}: ${result.width}x${result.height}, ${withMetadata.size / 1024} KB, " +
                "coverage ${(result.coverage * 100).toInt()}%, gps=${latitude != null}"
        )
        outFile
    }

    /**
     * Picks frames spread around the sphere: walks the capture in order and keeps
     * a frame when the camera direction has moved at least [MIN_ANGLE_STEP_DEG]
     * from the last kept one, then thins the result down to [MAX_FRAMES].
     */
    private fun selectFrames(
        datasetDir: File,
        onProgress: (FrameProcessor.ProcessingProgress) -> Unit
    ): List<PanoramaStitcher.Frame>? {
        val poseFiles = datasetDir
            .listFiles { _, name -> name.startsWith("pose_") && name.endsWith(".txt") }
            ?.sortedBy { it.name.removePrefix("pose_").removeSuffix(".txt").toLongOrNull() ?: 0L }
            ?: return null

        val candidates = ArrayList<Pair<Long, FloatArray>>()
        var lastDir: FloatArray? = null
        for (poseFile in poseFiles) {
            val ts = poseFile.name.removePrefix("pose_").removeSuffix(".txt").toLongOrNull() ?: continue
            if (!File(datasetDir, "rgb_$ts.jpg").exists()) continue
            val pose = try {
                CameraPose.parsePoseFile(poseFile.readText())
            } catch (_: Exception) {
                null
            } ?: continue

            val dir = floatArrayOf(pose[8], pose[9], pose[10]) // camera forward (pipeline space)
            val previous = lastDir
            if (previous == null || angleBetween(previous, dir) >= MIN_ANGLE_STEP_DEG) {
                candidates.add(ts to pose)
                lastDir = dir
            }
        }
        if (candidates.size < 3) {
            Log.w(TAG, "Only ${candidates.size} distinct view directions — not a 360° sweep")
            return null
        }

        val step = (candidates.size / MAX_FRAMES).coerceAtLeast(1)
        val chosen = candidates.filterIndexed { index, _ -> index % step == 0 }.take(MAX_FRAMES)

        val frames = ArrayList<PanoramaStitcher.Frame>(chosen.size)
        chosen.forEachIndexed { index, entry ->
            val (ts, pose) = entry
            val decoded = loadFrame(File(datasetDir, "rgb_$ts.jpg")) ?: return@forEachIndexed
            val intrinsics = readIntrinsics(File(datasetDir, "intrinsics_$ts.txt"))
            val (fx, fy, cx, cy) = intrinsics
            val scaleX = decoded.width.toFloat() / intrinsics.imageWidth
            val scaleY = decoded.height.toFloat() / intrinsics.imageHeight
            frames.add(
                PanoramaStitcher.Frame(
                    pixels = decoded.pixels,
                    width = decoded.width,
                    height = decoded.height,
                    pose = pose,
                    fx = (fx * scaleX).coerceAtLeast(1f),
                    fy = (fy * scaleY).coerceAtLeast(1f),
                    cx = cx * scaleX,
                    cy = cy * scaleY
                )
            )
            onProgress(
                FrameProcessor.ProcessingProgress(
                    "Loading panorama photo ${index + 1}/${chosen.size}...",
                    6 + (index + 1) * 16 / chosen.size,
                    0
                )
            )
        }
        return frames.takeIf { it.size >= 3 }
    }

    private fun angleBetween(a: FloatArray, b: FloatArray): Float {
        val dot = (a[0] * b[0] + a[1] * b[1] + a[2] * b[2]).coerceIn(-1f, 1f)
        return Math.toDegrees(kotlin.math.acos(dot.toDouble())).toFloat()
    }

    private class DecodedFrame(val pixels: IntArray, val width: Int, val height: Int)

    private class Intrinsics(
        val fx: Float,
        val fy: Float,
        val cx: Float,
        val cy: Float,
        val imageWidth: Float,
        val imageHeight: Float
    ) {
        operator fun component1() = fx
        operator fun component2() = fy
        operator fun component3() = cx
        operator fun component4() = cy
    }

    private fun readIntrinsics(file: File): Intrinsics {
        val parts = if (file.exists()) {
            file.readText().trim().split(",").mapNotNull { it.trim().toFloatOrNull() }
        } else {
            emptyList()
        }
        val w = parts.getOrNull(4) ?: 1920f
        val h = parts.getOrNull(5) ?: 1080f
        return Intrinsics(
            fx = parts.getOrNull(0) ?: (w * 0.8f),
            fy = parts.getOrNull(1) ?: (h * 0.8f),
            cx = parts.getOrNull(2) ?: (w * 0.5f),
            cy = parts.getOrNull(3) ?: (h * 0.5f),
            imageWidth = w,
            imageHeight = h
        )
    }

    private fun loadFrame(file: File): DecodedFrame? {
        if (!file.exists()) return null
        return try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.absolutePath, bounds)
            if (bounds.outWidth <= 0) return null

            var sample = 1
            while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= FRAME_MAX_DIM) sample *= 2
            val bitmap = BitmapFactory.decodeFile(
                file.absolutePath,
                BitmapFactory.Options().apply {
                    inSampleSize = sample
                    inPreferredConfig = Bitmap.Config.ARGB_8888
                }
            ) ?: return null

            val pixels = IntArray(bitmap.width * bitmap.height)
            bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
            val frame = DecodedFrame(pixels, bitmap.width, bitmap.height)
            bitmap.recycle()
            frame
        } catch (e: OutOfMemoryError) {
            System.gc()
            null
        } catch (e: Exception) {
            Log.w(TAG, "Could not load ${file.name}: ${e.message}")
            null
        }
    }

    /**
     * Reads `location.txt`, written at capture time when available. The parsing
     * lives with the writer in [CaptureContext] so the two halves cannot drift.
     */
    private fun readLocation(datasetDir: File): Pair<Double?, Double?> =
        CaptureContext.readLocation(datasetDir) ?: (null to null)

    /**
     * Camera path extent of the capture: how far the phone travelled while the
     * sweep was recorded. Reported with the finished panorama, because a
     * "panorama" that was actually a walk is where smearing comes from.
     */
    fun captureFootprint(datasetDir: File): Float {
        val poses: List<FloatArray> = datasetDir
            .listFiles { _, name -> name.startsWith("pose_") && name.endsWith(".txt") }
            ?.mapNotNull { file ->
                try {
                    CameraPose.parsePoseFile(file.readText())?.let { CameraPose.cameraCenter(it) }
                } catch (_: Exception) {
                    null
                }
            } ?: return 0f
        if (poses.size < 2) return 0f
        val min = floatArrayOf(poses.minOf { it[0] }, poses.minOf { it[1] }, poses.minOf { it[2] })
        val max = floatArrayOf(poses.maxOf { it[0] }, poses.maxOf { it[1] }, poses.maxOf { it[2] })
        val dx = max[0] - min[0]
        val dy = max[1] - min[1]
        val dz = max[2] - min[2]
        return sqrt(dx * dx + dy * dy + dz * dz)
    }
}
