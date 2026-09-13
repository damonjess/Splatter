package com.example.splatter.repository

import android.content.Context
import android.util.Log
import com.example.splatter.model.ScanMode
import com.example.splatter.model.ScanSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
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

            val poseCount = dir.listFiles { _, name -> name.startsWith("pose_") }?.size ?: 0

            var pointCount = 0
            if (plyFile.exists()) {
                // Estimate point count from PLY size or header
                val sizeBytes = plyFile.length()
                pointCount = ((sizeBytes - 400).coerceAtLeast(0) / 56).toInt()
            }

            val thumbFile = File(dir, "thumbnail.jpg")
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
                    thumbnailPath = if (thumbFile.exists()) thumbFile.absolutePath else null
                )
            )
        }

        sessions.sortedByDescending { it.timestamp }
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
    }
}
