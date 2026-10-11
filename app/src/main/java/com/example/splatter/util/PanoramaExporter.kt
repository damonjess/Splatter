package com.example.splatter.util

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import androidx.core.content.FileProvider
import java.io.File

/**
 * Gets a finished 360° panorama where Google Maps can pick it up.
 *
 * There is no documented Android intent that hands an image straight to a Maps
 * upload screen — Google's own guidance is that Photo Spheres go up through
 * Maps itself ("Contribute → Add photo") or the Street View Publish API. The
 * Maps contribution screen can only see photos in the device's media library, so
 * the file being written into `Pictures/` is what actually makes the upload
 * possible; sharing is offered as well because Google Photos' "Add to Maps"
 * flow works from an incoming share.
 */
object PanoramaExporter {

    private const val TAG = "PanoramaExporter"
    private const val ALBUM = "Splatter360"

    /**
     * Copies the panorama into the shared Pictures collection and returns its
     * content URI. The URI is what makes it visible to Google Photos and to the
     * Maps photo picker.
     */
    fun saveToPictures(context: Context, panoramaFile: File, title: String): Uri? {
        if (!panoramaFile.exists()) return null
        val fileName = "${sanitise(title)}_360.jpg"
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val resolver = context.contentResolver
                val values = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                    put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
                    put(
                        MediaStore.MediaColumns.RELATIVE_PATH,
                        Environment.DIRECTORY_PICTURES + "/" + ALBUM
                    )
                    put(MediaStore.MediaColumns.IS_PENDING, 1)
                }
                val uri = resolver.insert(
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values
                ) ?: return null
                resolver.openOutputStream(uri)?.use { out ->
                    panoramaFile.inputStream().use { input -> input.copyTo(out) }
                }
                values.clear()
                values.put(MediaStore.MediaColumns.IS_PENDING, 0)
                resolver.update(uri, values, null, null)
                uri
            } else {
                val pictures = Environment.getExternalStoragePublicDirectory(
                    Environment.DIRECTORY_PICTURES
                )
                val album = File(pictures, ALBUM).apply { mkdirs() }
                val dest = File(album, fileName)
                panoramaFile.copyTo(dest, overwrite = true)
                MediaScannerConnection.scanFile(
                    context, arrayOf(dest.absolutePath), arrayOf("image/jpeg"), null
                )
                Uri.fromFile(dest)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Could not export panorama: ${e.message}", e)
            null
        }
    }

    /** Shares the JPEG itself, so Google Photos can offer "Add to Maps". */
    fun share(context: Context, panoramaFile: File, title: String) {
        if (!panoramaFile.exists()) return
        try {
            val uri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                panoramaFile
            )
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "image/jpeg"
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_SUBJECT, "$title (360° photo)")
                putExtra(Intent.EXTRA_TEXT, "Equirectangular 360° photo, ready for Google Maps.")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(
                Intent.createChooser(intent, "Share 360° photo")
            )
        } catch (e: Exception) {
            Log.e(TAG, "Could not share panorama: ${e.message}", e)
        }
    }

    /**
     * Opens Maps centred on the capture position using the documented Maps URL
     * scheme, so the contributor can start "Contribute → Add photo" right where
     * the panorama was taken.
     */
    fun openInMaps(context: Context, latitude: Double, longitude: Double): Boolean {
        val url = "https://www.google.com/maps/@?api=1&map_action=map" +
            "&center=$latitude,$longitude&zoom=20"
        return try {
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
            true
        } catch (e: Exception) {
            Log.w(TAG, "No app can open Maps: ${e.message}")
            false
        }
    }

    private fun sanitise(title: String): String =
        title.replace(Regex("[^a-zA-Z0-9._-]"), "_").trim('_').ifBlank { "panorama" }
}
