package com.example.splatter.processor

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import androidx.core.content.FileProvider
import com.example.splatter.model.SplatPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

object PlyExporter {
    private const val TAG = "PlyExporter"

    suspend fun exportToPly(points: List<SplatPoint>, outputFile: File): Boolean = withContext(Dispatchers.IO) {
        try {
            if (outputFile.exists()) outputFile.delete()
            outputFile.parentFile?.mkdirs()

            val header = StringBuilder().apply {
                append("ply\n")
                append("format binary_little_endian 1.0\n")
                append("element vertex ${points.size}\n")
                append("property float x\n")
                append("property float y\n")
                append("property float z\n")
                append("property float nx\n")
                append("property float ny\n")
                append("property float nz\n")
                append("property uchar red\n")
                append("property uchar green\n")
                append("property uchar blue\n")
                append("property uchar alpha\n")
                append("property float scale_0\n")
                append("property float scale_1\n")
                append("property float scale_2\n")
                append("property float rot_0\n")
                append("property float rot_1\n")
                append("property float rot_2\n")
                append("property float rot_3\n")
                append("end_header\n")
            }.toString()

            BufferedOutputStream(FileOutputStream(outputFile)).use { out ->
                out.write(header.toByteArray(Charsets.US_ASCII))

                val bufferSize = 56
                val byteBuffer = ByteBuffer.allocate(bufferSize).order(ByteOrder.LITTLE_ENDIAN)

                for (p in points) {
                    byteBuffer.clear()
                    // Position (3 floats = 12 bytes)
                    byteBuffer.putFloat(p.x)
                    byteBuffer.putFloat(p.y)
                    byteBuffer.putFloat(p.z)

                    // Normals (3 floats = 12 bytes)
                    byteBuffer.putFloat(0.0f)
                    byteBuffer.putFloat(0.0f)
                    byteBuffer.putFloat(0.0f)

                    // RGBA (4 uchars = 4 bytes)
                    byteBuffer.put((p.r.coerceIn(0f, 1f) * 255).toInt().toByte())
                    byteBuffer.put((p.g.coerceIn(0f, 1f) * 255).toInt().toByte())
                    byteBuffer.put((p.b.coerceIn(0f, 1f) * 255).toInt().toByte())
                    byteBuffer.put((p.alpha.coerceIn(0f, 1f) * 255).toInt().toByte())

                    // Scale (3 floats = 12 bytes)
                    byteBuffer.putFloat(p.scaleX)
                    byteBuffer.putFloat(p.scaleY)
                    byteBuffer.putFloat(p.scaleZ)

                    // Rotation (4 floats = 16 bytes)
                    byteBuffer.putFloat(p.rotW)
                    byteBuffer.putFloat(p.rotX)
                    byteBuffer.putFloat(p.rotY)
                    byteBuffer.putFloat(p.rotZ)

                    out.write(byteBuffer.array())
                }
                out.flush()
            }
            Log.i(TAG, "Successfully exported ${points.size} splat points to PLY: ${outputFile.absolutePath}")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed exporting PLY file: ${e.message}", e)
            false
        }
    }

    suspend fun exportToSplat(points: List<SplatPoint>, outputFile: File): Boolean = withContext(Dispatchers.IO) {
        try {
            if (outputFile.exists()) outputFile.delete()
            outputFile.parentFile?.mkdirs()

            BufferedOutputStream(FileOutputStream(outputFile)).use { out ->
                val bufferSize = 32
                val byteBuffer = ByteBuffer.allocate(bufferSize).order(ByteOrder.LITTLE_ENDIAN)

                for (p in points) {
                    byteBuffer.clear()
                    // Position (3 floats)
                    byteBuffer.putFloat(p.x)
                    byteBuffer.putFloat(p.y)
                    byteBuffer.putFloat(p.z)

                    // Scale (3 floats)
                    byteBuffer.putFloat(p.scaleX)
                    byteBuffer.putFloat(p.scaleY)
                    byteBuffer.putFloat(p.scaleZ)

                    // RGBA (4 uchars)
                    byteBuffer.put((p.r.coerceIn(0f, 1f) * 255).toInt().toByte())
                    byteBuffer.put((p.g.coerceIn(0f, 1f) * 255).toInt().toByte())
                    byteBuffer.put((p.b.coerceIn(0f, 1f) * 255).toInt().toByte())
                    byteBuffer.put((p.alpha.coerceIn(0f, 1f) * 255).toInt().toByte())

                    // Rotation quaternion (4 uchars)
                    byteBuffer.put((p.rotW.coerceIn(-1f, 1f) * 127 + 128).toInt().toByte())
                    byteBuffer.put((p.rotX.coerceIn(-1f, 1f) * 127 + 128).toInt().toByte())
                    byteBuffer.put((p.rotY.coerceIn(-1f, 1f) * 127 + 128).toInt().toByte())
                    byteBuffer.put((p.rotZ.coerceIn(-1f, 1f) * 127 + 128).toInt().toByte())

                    out.write(byteBuffer.array())
                }
                out.flush()
            }
            Log.i(TAG, "Successfully exported SPLAT file: ${outputFile.absolutePath}")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed exporting SPLAT file: ${e.message}", e)
            false
        }
    }

    suspend fun loadPlyFile(plyFile: File): List<SplatPoint> = withContext(Dispatchers.IO) {
        if (!plyFile.exists()) return@withContext emptyList()
        val points = mutableListOf<SplatPoint>()

        try {
            BufferedInputStream(FileInputStream(plyFile)).use { input ->
                val headerLines = mutableListOf<String>()
                var currentLine = StringBuilder()

                // Read header line by line
                while (true) {
                    val b = input.read()
                    if (b == -1) break
                    val char = b.toChar()
                    if (char == '\n') {
                        val line = currentLine.toString().trim()
                        headerLines.add(line)
                        currentLine = StringBuilder()
                        if (line == "end_header") break
                    } else {
                        currentLine.append(char)
                    }
                }

                var elementCount = 0
                for (line in headerLines) {
                    if (line.startsWith("element vertex")) {
                        elementCount = line.substringAfter("element vertex").trim().toIntOrNull() ?: 0
                    }
                }

                if (elementCount == 0) return@withContext emptyList()

                val bufferSize = 56
                val byteBuffer = ByteBuffer.allocate(bufferSize).order(ByteOrder.LITTLE_ENDIAN)
                val rawBytes = ByteArray(bufferSize)

                for (i in 0 until elementCount) {
                    val bytesRead = input.read(rawBytes)
                    if (bytesRead < bufferSize) break

                    byteBuffer.clear()
                    byteBuffer.put(rawBytes)
                    byteBuffer.position(0)

                    val x = byteBuffer.float
                    val y = byteBuffer.float
                    val z = byteBuffer.float

                    // Skip normals (12 bytes)
                    byteBuffer.float
                    byteBuffer.float
                    byteBuffer.float

                    val r = (byteBuffer.get().toInt() and 0xFF) / 255.0f
                    val g = (byteBuffer.get().toInt() and 0xFF) / 255.0f
                    val b = (byteBuffer.get().toInt() and 0xFF) / 255.0f
                    val alpha = (byteBuffer.get().toInt() and 0xFF) / 255.0f

                    val scaleX = byteBuffer.float
                    val scaleY = byteBuffer.float
                    val scaleZ = byteBuffer.float

                    val rotW = byteBuffer.float
                    val rotX = byteBuffer.float
                    val rotY = byteBuffer.float
                    val rotZ = byteBuffer.float

                    points.add(
                        SplatPoint(
                            x = x, y = y, z = z,
                            r = r, g = g, b = b, alpha = alpha,
                            scaleX = scaleX, scaleY = scaleY, scaleZ = scaleZ,
                            rotW = rotW, rotX = rotX, rotY = rotY, rotZ = rotZ
                        )
                    )
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error reading PLY file: ${e.message}", e)
        }

        points
    }

    fun exportPlyToDownloads(context: Context, plyFile: File, scanTitle: String): Boolean {
        if (!plyFile.exists()) return false
        return try {
            val sanitizedName = scanTitle.replace(Regex("[^a-zA-Z0-9._-]"), "_")
            val fileName = "${sanitizedName}_model.ply"
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val contentValues = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                    put(MediaStore.MediaColumns.MIME_TYPE, "application/x-ply")
                    put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/Splatter3D")
                }
                val resolver = context.contentResolver
                val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, contentValues)
                    ?: return false
                resolver.openOutputStream(uri)?.use { out ->
                    plyFile.inputStream().use { input -> input.copyTo(out) }
                }
                true
            } else {
                val downloadsDir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "Splatter3D").apply { mkdirs() }
                val destFile = File(downloadsDir, fileName)
                plyFile.copyTo(destFile, overwrite = true)
                true
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed exporting PLY to Downloads: ${e.message}", e)
            false
        }
    }

    fun sharePlyFile(context: Context, plyFile: File, scanTitle: String) {
        if (!plyFile.exists()) return
        try {
            val uri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                plyFile
            )
            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                type = "application/x-ply"
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_SUBJECT, "$scanTitle (3D PLY Model)")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(Intent.createChooser(shareIntent, "Export PLY for MeshLab"))
        } catch (e: Exception) {
            Log.e(TAG, "Failed to share PLY file: ${e.message}", e)
        }
    }
}
