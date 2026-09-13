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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Photogrammetry-style "Photo Mesh" pipeline (Polycam-like):
 *
 *  1. Keyframe selection — evenly samples the captured RGB-D frames
 *  2. Depth fusion — unproject + triangulate depth grids into a single
 *     stitched triangle mesh (see [DepthMeshFuser])
 *  3. Photo color baking — projects every vertex into the captured photos
 *     and blends colors, weighted by viewing angle and distance, with
 *     depth-based occlusion rejection (see [MeshColorBaker])
 *  4. Export — textured PLY (via MeshIo, called by MainActivity) and OBJ
 */
object PhotogrammetryProcessor {
    private const val TAG = "PhotoMeshProcessor"

    /** Max frames used to build the geometry. */
    private const val MAX_GEOMETRY_FRAMES = 72
    /** Raw-depth confidence below this (0–255) is masked out before fusion. */
    private const val CONFIDENCE_THRESHOLD = 75

    /** Max views used for color baking (can differ from geometry frames). */
    private const val MAX_COLOR_VIEWS = 60

    /** Downscale factor for photos during color baking (memory + speed). */
    private const val RGB_SAMPLE_SIZE = 2

    private data class FrameInfo(
        val timestamp: Long,
        val pose: FloatArray,
        val fx: Float,
        val fy: Float,
        val cx: Float,
        val cy: Float,
        val imgWidth: Int,
        val imgHeight: Int
    )

    suspend fun process(
        datasetDir: File,
        scanMode: ScanMode,
        onProgress: (FrameProcessor.ProcessingProgress) -> Unit
    ): TriangleMesh? = withContext(Dispatchers.IO) {
        onProgress(FrameProcessor.ProcessingProgress("Scanning captured frames...", 5, 0))

        if (!datasetDir.exists() || !datasetDir.isDirectory) {
            Log.e(TAG, "Dataset directory does not exist: ${datasetDir.absolutePath}")
            return@withContext null
        }

        // ---- 1. Enumerate frames with pose + depth + intrinsics ----
        val frames = mutableListOf<FrameInfo>()
        val poseFiles = datasetDir.listFiles { _, name -> name.startsWith("pose_") && name.endsWith(".txt") }
            ?.sortedBy { it.name.removePrefix("pose_").removeSuffix(".txt").toLongOrNull() ?: 0L }
            ?: emptyList()

        for (poseFile in poseFiles) {
            val ts = poseFile.name.removePrefix("pose_").removeSuffix(".txt").toLongOrNull() ?: continue
            if (!File(datasetDir, "depth_$ts.raw").exists()) continue
            if (!File(datasetDir, "rgb_$ts.jpg").exists()) continue

            val pose = parsePoseMatrix(poseFile) ?: continue
            val intrinsics = parseIntrinsics(File(datasetDir, "intrinsics_$ts.txt"))

            // Original camera image size from the intrinsics file, or from the JPEG
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

            frames.add(
                FrameInfo(
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

        if (frames.isEmpty()) {
            Log.w(TAG, "No usable RGB-D frames found in ${datasetDir.absolutePath}")
            return@withContext null
        }
        Log.i(TAG, "Found ${frames.size} usable frames for photo mesh")

        // ---- 2. Depth fusion into a triangle mesh ----
        val geometryFrames = evenlySample(frames, MAX_GEOMETRY_FRAMES)
        val fuser = DepthMeshFuser(
            voxelSize = scanMode.voxelSizeMeters,
            maxTriangles = scanMode.maxPointLimit
        )

        for ((index, frame) in geometryFrames.withIndex()) {
            if (fuser.isFull) {
                Log.i(TAG, "Triangle cap reached after frame ${index + 1}/${geometryFrames.size}")
                break
            }
            val depth = loadDepth(datasetDir, frame.timestamp) ?: continue

            // Mask out low-confidence depth pixels before fusion — they are
            // the classic source of floating fragments in ARCore raw depth
            val confidence = loadConfidence(datasetDir, frame.timestamp, depth.width, depth.height)
            if (confidence != null) {
                for (i in depth.values.indices) {
                    if (confidence[i] < CONFIDENCE_THRESHOLD && depth.values[i].toInt() != 0) {
                        depth.values[i] = 0
                    }
                }
            }

            // Median-filter the raw depth to kill single-pixel speckle that
            // would otherwise become floating mesh fragments
            val filteredDepth = DepthFilters.medianFilter3x3(depth.values, depth.width, depth.height)

            val depthFx = (frame.fx * depth.width / frame.imgWidth).coerceAtLeast(1f)
            val depthFy = (frame.fy * depth.height / frame.imgHeight).coerceAtLeast(1f)
            val depthCx = frame.cx * depth.width / frame.imgWidth
            val depthCy = frame.cy * depth.height / frame.imgHeight

            // Adaptive stride: sample the depth grid so one quad edge spans
            // roughly 1.5 voxels of world space. Stride 1 at close range makes
            // neighbouring corners collapse into one voxel (degenerate
            // triangles -> holes); a fixed coarse stride loses detail.
            val medianDepthM = DepthFilters.medianDepthMm(filteredDepth) / 1000f
            val stride = if (medianDepthM > 0f) {
                DepthFilters.suggestStride(medianDepthM, depthFx, scanMode.voxelSizeMeters * 1.5f)
            } else 1

            fuser.fuseFrame(
                frame = DepthFrame(
                    depthMm = filteredDepth,
                    depthWidth = depth.width,
                    depthHeight = depth.height,
                    pose = frame.pose,
                    fx = depthFx,
                    fy = depthFy,
                    cx = depthCx,
                    cy = depthCy
                ),
                minDepthM = scanMode.minDepthMeters,
                maxDepthM = scanMode.maxDepthMeters,
                stride = stride
            )

            val percent = 10 + ((index + 1).toFloat() / geometryFrames.size * 50).toInt()
            onProgress(
                FrameProcessor.ProcessingProgress(
                    currentStep = "Fusing depth frame ${index + 1}/${geometryFrames.size}...",
                    progressPercent = percent,
                    pointCount = fuser.vertexCount
                )
            )
        }

        val mesh = fuser.buildMesh()
        if (mesh.triangleCount == 0) {
            Log.w(TAG, "Photo mesh came out empty")
            return@withContext null
        }

        onProgress(
            FrameProcessor.ProcessingProgress(
                "Computing vertex normals...",
                62,
                mesh.vertexCount
            )
        )
        mesh.computeNormals()

        // ---- 3. Bake photo colors from the captured views ----
        val colorViews = evenlySample(frames, MAX_COLOR_VIEWS)
        val baker = MeshColorBaker(mesh)
        for ((index, frame) in colorViews.withIndex()) {
            val bitmap = try {
                loadSampledBitmap(File(datasetDir, "rgb_${frame.timestamp}.jpg"), RGB_SAMPLE_SIZE)
            } catch (e: OutOfMemoryError) {
                Log.e(TAG, "OOM loading photo for frame ${frame.timestamp}, skipping view", e)
                System.gc()
                null
            }
            if (bitmap != null) {
                val depth = loadDepth(datasetDir, frame.timestamp)
                baker.bakeView(
                    PhotoView(
                        pixels = bitmap.pixels,
                        width = bitmap.width,
                        height = bitmap.height,
                        pose = frame.pose,
                        fx = (frame.fx * bitmap.width / frame.imgWidth).coerceAtLeast(1f),
                        fy = (frame.fy * bitmap.height / frame.imgHeight).coerceAtLeast(1f),
                        cx = frame.cx * bitmap.width / frame.imgWidth,
                        cy = frame.cy * bitmap.height / frame.imgHeight,
                        depthMm = depth?.values,
                        depthWidth = depth?.width ?: 0,
                        depthHeight = depth?.height ?: 0
                    )
                )
            }
            val percent = 65 + ((index + 1).toFloat() / colorViews.size * 30).toInt()
            onProgress(
                FrameProcessor.ProcessingProgress(
                    currentStep = "Baking photo colors ${index + 1}/${colorViews.size}...",
                    progressPercent = percent,
                    pointCount = mesh.vertexCount
                )
            )
        }

        baker.applyTo(mesh)

        onProgress(
            FrameProcessor.ProcessingProgress(
                "Photo mesh ready — ${mesh.triangleCount} triangles",
                98,
                mesh.vertexCount
            )
        )
        mesh
    }

    private class DepthData(val values: ShortArray, val width: Int, val height: Int)
    private class BitmapData(val pixels: IntArray, val width: Int, val height: Int)

    /** Loads the per-pixel raw-depth confidence map (1 byte/pixel), or null. */
    private fun loadConfidence(datasetDir: File, timestamp: Long, width: Int, height: Int): ByteArray? {
        val file = File(datasetDir, "confidence_$timestamp.raw")
        if (!file.exists()) return null
        return try {
            val bytes = file.readBytes()
            // Only trust it if it matches the depth resolution exactly
            if (bytes.size == width * height) bytes else null
        } catch (_: Exception) {
            null
        }
    }

    private fun loadDepth(datasetDir: File, timestamp: Long): DepthData? {
        return try {
            val depthFile = File(datasetDir, "depth_$timestamp.raw")
            if (!depthFile.exists()) return null
            val bytes = depthFile.readBytes()
            if (bytes.isEmpty()) return null
            val values = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
            val pixelCount = values.remaining()
            if (pixelCount == 0) return null

            // Exact dims saved at capture time; fall back to common sizes
            val dimsFile = File(datasetDir, "depthdims_$timestamp.txt")
            var w = 0
            var h = 0
            if (dimsFile.exists()) {
                val parts = dimsFile.readText().trim().split(",")
                if (parts.size >= 2) {
                    w = parts[0].trim().toIntOrNull() ?: 0
                    h = parts[1].trim().toIntOrNull() ?: 0
                }
            }
            if (w <= 0) {
                w = when (pixelCount) {
                    160 * 120 -> 160
                    240 * 180 -> 240
                    256 * 192 -> 256
                    320 * 240 -> 320
                    640 * 480 -> 640
                    else -> 0
                }
            }
            if (w <= 0) return null
            if (h <= 0) h = pixelCount / w
            if (w * h != pixelCount) return null

            val array = ShortArray(pixelCount)
            values.get(array)
            DepthData(array, w, h)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to load depth for $timestamp: ${e.message}")
            null
        }
    }

    private fun loadSampledBitmap(file: File, sampleSize: Int): BitmapData? {
        val opts = BitmapFactory.Options().apply {
            inSampleSize = sampleSize
            inPreferredConfig = Bitmap.Config.RGB_565
        }
        val bitmap = BitmapFactory.decodeFile(file.absolutePath, opts) ?: return null
        return try {
            val pixels = IntArray(bitmap.width * bitmap.height)
            bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
            BitmapData(pixels, bitmap.width, bitmap.height)
        } finally {
            bitmap.recycle()
        }
    }

    private fun <T> evenlySample(items: List<T>, maxCount: Int): List<T> {
        if (items.size <= maxCount) return items
        val result = mutableListOf<T>()
        for (i in 0 until maxCount) {
            val index = Math.round(i.toDouble() * (items.size - 1).toDouble() / (maxCount - 1).toDouble()).toInt()
            result.add(items[index])
        }
        return result
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
