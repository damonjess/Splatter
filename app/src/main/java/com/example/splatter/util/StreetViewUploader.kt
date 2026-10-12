package com.example.splatter.util

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import com.example.splatter.processor.panorama.PanoramaReadiness
import com.example.splatter.processor.publish.StreetViewPublish
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.AuthorizationResult
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.Scope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * Everything Google-facing about publishing that needs Android: the OAuth
 * authorization request, the HTTP transport, and the one number the Cloud setup
 * needs from this build — its signing certificate's SHA-1.
 *
 * The protocol itself lives in [StreetViewPublish] as pure Kotlin and is unit
 * tested there; this file is the thin, deliberately dumb adapter, because it is
 * the part that cannot be tested without a real OAuth client and a real network.
 *
 * Authorization uses `AuthorizationClient` rather than the deprecated
 * `GoogleSignIn` APIs: the latter is for authentication (who you are) and is
 * being retired, whereas this asks for *authorization* to a single scope
 * (`streetviewpublish`) with the account already on the device.
 */
object StreetViewUploader {

    private const val TAG = "StreetViewUploader"

    const val SCOPE = StreetViewPublish.SCOPE

    /** The scope request; the user sees Google's consent screen for this. */
    fun authorizationRequest(): AuthorizationRequest = AuthorizationRequest.builder()
        .setRequestedScopes(listOf(Scope(SCOPE)))
        .build()

    fun authorizationClient(context: Context) = Identity.getAuthorizationClient(context)

    /**
     * Reads the result of the consent screen the app had to launch. Returns null
     * when the user declined or the intent carried nothing usable.
     */
    fun authorizationResult(context: Context, data: Intent?): AuthorizationResult? = try {
        @Suppress("DEPRECATION")
        Identity.getAuthorizationClient(context).getAuthorizationResultFromIntent(data)
    } catch (e: Exception) {
        Log.w(TAG, "No authorization result: ${e.message}")
        null
    }

    /**
     * The SHA-1 of the certificate this build was signed with, formatted the way
     * the Cloud console wants it.
     *
     * This is the single most common reason a first publish fails with 403: the
     * Android OAuth client must list the package name *and* this fingerprint, and
     * a debug build and a release build have different ones. Showing it in the
     * app means it can be copied from the device instead of dug out of a
     * keystore with keytool.
     */
    fun signingSha1(context: Context): String? = try {
        val pm = context.packageManager
        val packageName = context.packageName
        val signature = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val info = pm.getPackageInfo(packageName, PackageManager.GET_SIGNING_CERTIFICATES)
            info.signingInfo?.apkContentsSigners?.firstOrNull()
        } else {
            @Suppress("DEPRECATION")
            pm.getPackageInfo(packageName, PackageManager.GET_SIGNATURES).signatures?.firstOrNull()
        }
        signature?.let {
            MessageDigest.getInstance("SHA-1")
                .digest(it.toByteArray())
                .joinToString(":") { byte -> "%02X".format(byte) }
        }
    } catch (e: Exception) {
        Log.w(TAG, "Could not read the signing certificate: ${e.message}")
        null
    }

    /** This app's package name, the other half of the OAuth client match. */
    fun packageName(context: Context): String = context.packageName

    /**
     * Uploads a stitched panorama and publishes it as a Photo Sphere.
     *
     * The file is read once and the position is taken from the image's own EXIF —
     * the same bytes the readiness panel graded — so what gets published is
     * exactly what was checked. Capture time comes from the file's timestamp,
     * because an older scan published today should not claim to be new.
     */
    suspend fun publish(
        token: String,
        panoramaFile: File,
        headingDegrees: Float?,
        onProgress: (String) -> Unit
    ): StreetViewPublish.Outcome {
        val jpeg = withContext(Dispatchers.IO) {
            runCatching { panoramaFile.readBytes() }.getOrNull()
        } ?: return StreetViewPublish.Outcome.Rejected("the 360° image could not be read")

        val metadata = PanoramaReadiness.readMetadata(jpeg)
        val latitude = metadata?.latitude
        val longitude = metadata?.longitude
        if (latitude == null || longitude == null) return StreetViewPublish.Outcome.NoLocation

        return StreetViewPublish.publish(
            transport = HttpUrlTransport,
            token = token,
            jpeg = jpeg,
            latitude = latitude,
            longitude = longitude,
            headingDegrees = headingDegrees,
            capturedAtMillis = panoramaFile.lastModified(),
            onProgress = onProgress
        )
    }

    /**
     * A minimal `HttpURLConnection` transport. Fixed-length streaming is set from
     * the body size so the resumable upload sends an exact `Content-Length`
     * without holding a second copy of an 8 MB JPEG in memory.
     */
    private object HttpUrlTransport : StreetViewPublish.HttpTransport {

        override suspend fun execute(request: StreetViewPublish.HttpRequest): StreetViewPublish.HttpResponse =
            withContext(Dispatchers.IO) {
                val connection = (URL(request.url).openConnection() as HttpURLConnection).apply {
                    requestMethod = request.method
                    connectTimeout = 20_000
                    readTimeout = 120_000
                    instanceFollowRedirects = false
                    useCaches = false
                }
                try {
                    request.headers.forEach { (name, value) ->
                        // Content-Length is set by the platform from the bytes
                        // actually written; setting it by hand can conflict.
                        if (!name.equals("Content-Length", ignoreCase = true)) {
                            connection.setRequestProperty(name, value)
                        }
                    }
                    request.body?.let { body ->
                        connection.setFixedLengthStreamingMode(body.size)
                        connection.doOutput = true
                        connection.outputStream.use { it.write(body) }
                    }

                    val status = connection.responseCode
                    val headers = buildMap {
                        connection.headerFields.forEach { (name, values) ->
                            if (name != null && values != null) put(name.lowercase(), values.joinToString(", "))
                        }
                    }
                    val stream = if (status in 200..299) connection.inputStream else connection.errorStream
                    val body = stream?.use { it.readBytes().toString(Charsets.UTF_8) } ?: ""
                    StreetViewPublish.HttpResponse(status = status, headers = headers, body = body)
                } finally {
                    connection.disconnect()
                }
            }
    }
}
