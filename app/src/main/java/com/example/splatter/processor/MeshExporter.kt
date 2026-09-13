package com.example.splatter.processor

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import android.widget.Toast
import androidx.core.content.FileProvider
import com.example.splatter.processor.mesh.MeshIo
import com.example.splatter.processor.mesh.TriangleMesh
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream

/**
 * Android-facing helpers for Photo Mesh files: export (PLY + OBJ), load,
 * share, and save-to-Downloads.
 */
object MeshExporter {
    private const val TAG = "MeshExporter"

    const val MESH_PLY_FILENAME = "model_mesh.ply"
    const val MESH_OBJ_FILENAME = "model_mesh.obj"

    suspend fun exportMeshFiles(mesh: TriangleMesh, datasetDir: File): File? =
        withContext(Dispatchers.IO) {
            try {
                val plyFile = File(datasetDir, MESH_PLY_FILENAME)
                FileOutputStream(plyFile).use { MeshIo.writePly(mesh, it) }

                val objFile = File(datasetDir, MESH_OBJ_FILENAME)
                FileOutputStream(objFile).use { MeshIo.writeObj(mesh, it) }

                Log.i(TAG, "Exported photo mesh: ${mesh.vertexCount} verts / ${mesh.triangleCount} tris")
                plyFile
            } catch (e: Exception) {
                Log.e(TAG, "Failed exporting mesh files: ${e.message}", e)
                null
            }
        }

    suspend fun loadMeshPly(plyFile: File): TriangleMesh? = withContext(Dispatchers.IO) {
        if (!plyFile.exists()) return@withContext null
        try {
            FileInputStream(plyFile).use { MeshIo.readPly(it) }
        } catch (e: Exception) {
            Log.e(TAG, "Failed reading mesh PLY: ${e.message}", e)
            null
        }
    }

    fun exportFileToDownloads(context: Context, file: File, scanTitle: String): Boolean {
        if (!file.exists()) return false
        return try {
            val sanitizedName = scanTitle.replace(Regex("[^a-zA-Z0-9._-]"), "_")
            val fileName = "${sanitizedName}_${file.name}"
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val contentValues = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                    put(MediaStore.MediaColumns.MIME_TYPE, "application/octet-stream")
                    put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/Splatter3D")
                }
                val resolver = context.contentResolver
                val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, contentValues)
                    ?: return false
                resolver.openOutputStream(uri)?.use { out ->
                    file.inputStream().use { input -> input.copyTo(out) }
                }
                true
            } else {
                val downloadsDir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "Splatter3D").apply { mkdirs() }
                val destFile = File(downloadsDir, fileName)
                file.copyTo(destFile, overwrite = true)
                true
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed exporting mesh to Downloads: ${e.message}", e)
            false
        }
    }

    fun shareFile(context: Context, file: File, scanTitle: String) {
        if (!file.exists()) {
            Toast.makeText(context, "Mesh file not found", Toast.LENGTH_SHORT).show()
            return
        }
        try {
            val uri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                file
            )
            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                type = "application/octet-stream"
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_SUBJECT, "$scanTitle (${file.extension.uppercase()} 3D Mesh)")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(Intent.createChooser(shareIntent, "Export ${file.extension.uppercase()} mesh"))
        } catch (e: Exception) {
            Log.e(TAG, "Failed to share mesh file: ${e.message}", e)
        }
    }
}
