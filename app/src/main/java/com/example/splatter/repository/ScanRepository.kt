package com.example.splatter.repository

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.util.Log
import com.example.splatter.model.ScanMode
import com.example.splatter.model.ScanSession
import com.example.splatter.processor.panorama.PanoramaProcessor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class ScanRepository(private val context: Context) {

    private val baseDir: File
        get() = context.getExternalFilesDir(null) ?: context.filesDir

    suspend fun getScanSessions(): List<ScanSession> = withContext(Dispatchers.IO) {
        val sessions = mutableListOf<ScanSession>()
        val parentDir = File(baseDir, "scans")
        if (!parentDir.exists()) return@withContext emptyList()

        val scanDirs = parentDir.listFiles { file -> file.isDirectory } ?: emptyArray()

        for (dir in scanDirs) {
            val timestamp = dir.name.removePrefix("scan_").toLongOrNull() ?: dir.lastModified()
            val dateFormat = SimpleDateFormat("MMM dd, yyyy HH:mm", Locale.getDefault())
            val formattedDate = dateFormat.format(Date(timestamp))

            val plyFile = File(dir, "model.ply")
            val splatFile = File(dir, "model.splat")
            val meshFile = File(dir, "model_mesh.ply")

            val poseCount = dir.listFiles { _, name -> name.startsWith("pose_") }?.size ?: 0

            var pointCount = 0
            if (meshFile.exists()) {
                // Photo Mesh scan — read the vertex count straight from the header
                // (bytes, never a Reader: the rest of the file is binary)
                pointCount = try {
                    val header = ByteArray(minOf(meshFile.length(), 2048L).toInt())
                    java.io.FileInputStream(meshFile).use { it.read(header) }
                    val text = String(header, Charsets.US_ASCII)
                    Regex("element vertex (\\d+)").find(text)?.groupValues?.get(1)?.toIntOrNull() ?: 0
                } catch (_: Exception) {
                    0
                }
            } else if (plyFile.exists()) {
                // Estimate point count from PLY size or header
                val sizeBytes = plyFile.length()
                pointCount = ((sizeBytes - 400).coerceAtLeast(0) / 56).toInt()
            }

            val panoramaFile = File(dir, PanoramaProcessor.PANORAMA_FILENAME)
            val thumbFile = if (panoramaFile.exists()) ensurePanoramaThumbnail(dir, panoramaFile) else ensureThumbnail(dir)
            val titleFile = File(dir, "title.txt")
            val title = if (titleFile.exists()) titleFile.readText().trim() else "Scan $formattedDate"

            val modeFile = File(dir, "mode.txt")
            val scanMode = if (modeFile.exists()) ScanMode.fromId(modeFile.readText().trim()) else ScanMode.OBJECT

            sessions.add(
                ScanSession(
                    id = dir.name,
                    title = title,
                    timestamp = timestamp,
                    frameCount = poseCount,
                    pointCount = pointCount,
                    datasetDirPath = dir.absolutePath,
                    scanMode = scanMode,
                    plyFilePath = if (plyFile.exists()) plyFile.absolutePath else null,
                    splatFilePath = if (splatFile.exists()) splatFile.absolutePath else null,
                    meshFilePath = if (meshFile.exists()) meshFile.absolutePath else null,
                    thumbnailPath = thumbFile?.absolutePath,
                    isFavorite = File(dir, FAVORITE_MARKER).exists(),
                    processingNote = File(dir, NOTE_FILE).takeIf { it.exists() }
                        ?.readText()?.trim()?.takeIf { it.isNotEmpty() },
                    panoramaFilePath = panoramaFile.takeIf { it.exists() }?.absolutePath
                )
            )
        }

        sessions.sortedByDescending { it.timestamp }
    }

    /**
     * A panorama is 2:1, so the usual portrait-crop thumbnail would show a
     * sliver of it. This one keeps the middle third of the image instead.
     */
    private fun ensurePanoramaThumbnail(datasetDir: File, panoramaFile: File): File? {
        val thumbFile = File(datasetDir, "thumbnail.jpg")
        if (thumbFile.exists() && thumbFile.length() > 0) return thumbFile

        var bitmap: Bitmap? = null
        var cropped: Bitmap? = null
        var scaled: Bitmap? = null
        return try {
            // Sample down first: the panorama is 8 MP and this is only a card thumbnail.
            bitmap = BitmapFactory.decodeFile(
                panoramaFile.absolutePath,
                BitmapFactory.Options().apply { inSampleSize = 4 }
            ) ?: return ensureThumbnail(datasetDir)

            val cropWidth = (bitmap.height * 1.4f).toInt().coerceAtMost(bitmap.width)
            val left = ((bitmap.width - cropWidth) / 2).coerceAtLeast(0)
            cropped = Bitmap.createBitmap(bitmap, left, 0, cropWidth, bitmap.height)

            val targetHeight = 480
            val targetWidth = (targetHeight.toFloat() * cropped.width / cropped.height).toInt()
            scaled = Bitmap.createScaledBitmap(cropped, targetWidth, targetHeight, true)
            FileOutputStream(thumbFile).use { out -> scaled.compress(Bitmap.CompressFormat.JPEG, 85, out) }
            thumbFile
        } catch (e: Exception) {
            Log.w(TAG, "Failed to build panorama thumbnail: ${e.message}")
            ensureThumbnail(datasetDir)
        } finally {
            scaled?.recycle()
            if (cropped !== scaled) cropped?.recycle()
            bitmap?.recycle()
        }
    }

    private fun ensureThumbnail(datasetDir: File): File? {
        val thumbFile = File(datasetDir, "thumbnail.jpg")
        if (thumbFile.exists() && thumbFile.length() > 0) return thumbFile

        val firstRgbFile = datasetDir.listFiles { _, name -> name.startsWith("rgb_") && name.endsWith(".jpg") }
            ?.minByOrNull { it.name.removePrefix("rgb_").removeSuffix(".jpg").toLongOrNull() ?: Long.MAX_VALUE }
            ?: return null

        return try {
            val bitmap = BitmapFactory.decodeFile(firstRgbFile.absolutePath) ?: return null
            val orientedBmp = if (bitmap.width > bitmap.height) {
                val matrix = Matrix().apply { postRotate(90f) }
                val rotated = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
                bitmap.recycle()
                rotated
            } else {
                bitmap
            }

            val targetWidth = 320
            val targetHeight = (targetWidth.toFloat() * orientedBmp.height / orientedBmp.width).toInt()
            val scaled = Bitmap.createScaledBitmap(orientedBmp, targetWidth, targetHeight, true)
            if (scaled != orientedBmp) orientedBmp.recycle()

            FileOutputStream(thumbFile).use { out -> scaled.compress(Bitmap.CompressFormat.JPEG, 85, out) }
            scaled.recycle()
            thumbFile
        } catch (e: Exception) {
            Log.w(TAG, "Failed to create thumbnail: ${e.message}")
            null
        }
    }

    suspend fun createNewScanSession(scanMode: ScanMode = ScanMode.OBJECT): ScanSession = withContext(Dispatchers.IO) {
        val timestamp = System.currentTimeMillis()
        val parentDir = File(baseDir, "scans")
        val scanDir = File(parentDir, "scan_$timestamp").apply { mkdirs() }

        val dateFormat = SimpleDateFormat("MMM dd, yyyy HH:mm", Locale.getDefault())
        val formattedDate = dateFormat.format(Date(timestamp))

        val titleFile = File(scanDir, "title.txt")
        val title = if (titleFile.exists()) titleFile.readText().trim() else "Scan $formattedDate"

        File(scanDir, "mode.txt").writeText(scanMode.id)

        ScanSession(
            id = scanDir.name,
            title = title,
            timestamp = timestamp,
            frameCount = 0,
            pointCount = 0,
            datasetDirPath = scanDir.absolutePath,
            scanMode = scanMode
        )
    }

    suspend fun renameScanSession(session: ScanSession, newTitle: String) = withContext(Dispatchers.IO) {
        session.title = newTitle
        File(session.datasetDirPath, "title.txt").writeText(newTitle)
    }

    /** Records how a scan was reconstructed, so the UI can explain it later. */
    suspend fun setProcessingNote(session: ScanSession, note: String) = withContext(Dispatchers.IO) {
        try {
            File(session.datasetDirPath, NOTE_FILE).writeText(note)
            session.processingNote = note
        } catch (e: Exception) {
            Log.w(TAG, "Could not store processing note: ${e.message}")
        }
    }

    /** Persist a scan's favourite status as a marker file inside its directory. */
    suspend fun setFavorite(session: ScanSession, favorite: Boolean): Boolean = withContext(Dispatchers.IO) {
        val marker = File(session.datasetDirPath, FAVORITE_MARKER)
        val ok = try {
            if (favorite) {
                marker.parentFile?.mkdirs()
                marker.writeText("1")
            } else if (marker.exists()) {
                marker.delete()
            }
            true
        } catch (e: Exception) {
            Log.e(TAG, "Error updating favourite: ${e.message}")
            false
        }
        if (ok) session.isFavorite = favorite
        ok
    }

    suspend fun deleteScanSession(session: ScanSession): Boolean = withContext(Dispatchers.IO) {
        try {
            val dir = File(session.datasetDirPath)
            if (dir.exists()) {
                dir.deleteRecursively()
            } else true
        } catch (e: Exception) {
            Log.e(TAG, "Error deleting scan session: ${e.message}", e)
            false
        }
    }

    companion object {
        private const val TAG = "ScanRepository"
        const val FAVORITE_MARKER = "favorite.marker"
        const val NOTE_FILE = "note.txt"
    }
}
