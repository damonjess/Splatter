package com.example.splatter.processor.publish

import com.example.splatter.processor.panorama.PanoramaReadiness
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Publishes a stitched 360° photo to Google Street View (which is what puts it on
 * Google Maps), using the Street View Publish API.
 *
 * The whole protocol lives here as **pure Kotlin**: it builds the requests, drives
 * the four-step upload and reads the responses, but every byte leaves through
 * [HttpTransport]. That split is deliberate — the wire format is the part that
 * can be wrong in ways nobody notices until a publish silently fails, so it is
 * unit tested against Google's documented request and response shapes, while the
 * Android-side transport and OAuth stay a thin, untestable adapter.
 *
 * The documented sequence (all requests carry `Authorization: Bearer <token>`):
 *
 *  1. `POST /v1/photo:startUpload` — empty body, returns an `uploadUrl`.
 *  2. `POST <uploadUrl>` with `X-Goog-Upload-Command: start` — the **response
 *     header** `X-Goog-Upload-URL` is the resumable session URL.
 *  3. `POST <session URL>` with `X-Goog-Upload-Command: upload, finalize` and the
 *     raw JPEG as the body.
 *  4. `POST /v1/photo` with `uploadReference.uploadUrl` — and that must be the URL
 *     from step 1, *not* the session URL from step 2, which is an easy way to get
 *     a successful upload that never becomes a photo.
 *
 * Two more details that bite:
 *  - `photo.create` ignores `pose.heading`, so the compass heading has to be in
 *    the image's own XMP — which is exactly what `PanoramaMetadata` writes.
 *  - A latitude/longitude pair must be present in the request pose or the EXIF,
 *    or the photo is rejected. The app refuses to start the upload without one,
 *    rather than uploading 8 MB and then failing.
 */
object StreetViewPublish {

    const val API_BASE = "https://streetviewpublish.googleapis.com/v1"

    /** The one scope this feature needs. */
    const val SCOPE = "https://www.googleapis.com/auth/streetviewpublish"

    /** Documented ceiling per photo (100 MP resolution is the other limit).
     *  Shared with [PanoramaReadiness] so the app and the API cannot disagree. */
    const val MAX_BYTES = PanoramaReadiness.MAX_BYTES

    /** Documented default quota, surfaced so a 429 can be explained. */
    const val DAILY_QUOTA = 2_000

    private const val SESSION_URL_HEADER = "x-goog-upload-url"

    // ---- Wire types ---------------------------------------------------------

    data class HttpRequest(
        val method: String,
        val url: String,
        val headers: Map<String, String>,
        val body: ByteArray? = null
    )

    data class HttpResponse(
        val status: Int,
        val headers: Map<String, String>,
        val body: String
    ) {
        /** Header lookup is case-insensitive, as HTTP requires. */
        fun header(name: String): String? =
            headers.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value
    }

    interface HttpTransport {
        suspend fun execute(request: HttpRequest): HttpResponse
    }

    /** What a successful publish gives back. */
    data class Published(
        val photoId: String?,
        val shareLink: String?,
        val mapsPublishStatus: String?
    )

    /**
     * Every way this can end, each with the sentence the user should read. The
     * distinctions matter: "you are not signed in" and "your OAuth client does
     * not match this build's signature" are both 4xx responses with a Google
     * message that means nothing to somebody standing in a field.
     */
    sealed interface Outcome {
        data class Success(val published: Published) : Outcome

        /** Not enough of the sphere, too few pixels, no GPano tags... */
        data class NotPublishable(val reason: String) : Outcome

        /** No position on the photo, which the API requires. */
        data object NoLocation : Outcome

        data class NotAuthorized(val detail: String) : Outcome

        /** 403 — usually the OAuth client's package/SHA-1 not matching this build. */
        data class Forbidden(val detail: String) : Outcome

        data class Rejected(val detail: String) : Outcome

        data class QuotaExceeded(val detail: String) : Outcome

        data class Transport(val detail: String) : Outcome
    }

    // ---- Pre-flight ---------------------------------------------------------

    /**
     * Checks the file against the requirements before spending a bearer token and
     * a 350 KB upload on a photo that will be rejected. Uses the same grading the
     * viewer shows, so the app and the API agree on what "publishable" means.
     */
    fun preflight(jpeg: ByteArray): Outcome? {
        val metadata = PanoramaReadiness.readMetadata(jpeg)
            ?: return Outcome.NotPublishable("the file is not a readable JPEG")

        // Location is checked first on purpose. It is also one of the graded
        // requirements, but the generic "gps position" label does not say what
        // to do about it, whereas this outcome tells the user the capture needs
        // location permission — and the API's own failure for it arrives only
        // after the whole file has been uploaded.
        if (metadata.latitude == null || metadata.longitude == null) return Outcome.NoLocation

        val blocking = PanoramaReadiness.evaluate(metadata).blocking.map { it.label }
        if (blocking.isNotEmpty()) {
            return Outcome.NotPublishable(blocking.joinToString(", ").lowercase())
        }
        return null
    }

    // ---- Request builders ---------------------------------------------------

    fun startUploadRequest(token: String): HttpRequest = HttpRequest(
        method = "POST",
        url = "$API_BASE/photo:startUpload",
        headers = mapOf(
            "Authorization" to "Bearer $token",
            "Content-Length" to "0"
        )
    )

    /** Step 2: asks for a resumable session; the session URL comes back in a header. */
    fun resumableStartRequest(token: String, uploadUrl: String, byteCount: Long): HttpRequest =
        HttpRequest(
            method = "POST",
            url = uploadUrl,
            headers = mapOf(
                "Authorization" to "Bearer $token",
                "Content-Length" to "0",
                "X-Goog-Upload-Protocol" to "resumable",
                "X-Goog-Upload-Command" to "start",
                "X-Goog-Upload-Header-Content-Length" to byteCount.toString(),
                "X-Goog-Upload-Header-Content-Type" to "image/jpeg"
            )
        )

    /** Step 3: the bytes, in one request. */
    fun uploadBytesRequest(token: String, sessionUrl: String, jpeg: ByteArray): HttpRequest =
        HttpRequest(
            method = "POST",
            url = sessionUrl,
            headers = mapOf(
                "Authorization" to "Bearer $token",
                "Content-Length" to jpeg.size.toString(),
                "X-Goog-Upload-Command" to "upload, finalize",
                "X-Goog-Upload-Offset" to "0"
            ),
            body = jpeg
        )

    /**
     * Step 4: the Photo resource. [uploadUrl] is the start-upload reference, and
     * the capture time is the file's own timestamp — Google shows it on the
     * contribution, so inventing "now" would misdate an older scan.
     */
    fun createPhotoRequest(
        token: String,
        uploadUrl: String,
        latitude: Double,
        longitude: Double,
        headingDegrees: Float?,
        capturedAtMillis: Long
    ): HttpRequest = HttpRequest(
        method = "POST",
        url = "$API_BASE/photo",
        headers = mapOf(
            "Authorization" to "Bearer $token",
            "Content-Type" to "application/json"
        ),
        body = photoBody(uploadUrl, latitude, longitude, headingDegrees, capturedAtMillis)
            .toByteArray(Charsets.UTF_8)
    )

    /** Split out so the JSON itself can be asserted on directly. */
    fun photoBody(
        uploadUrl: String,
        latitude: Double,
        longitude: Double,
        headingDegrees: Float?,
        capturedAtMillis: Long
    ): String = buildString {
        append("{\"uploadReference\":{\"uploadUrl\":\"")
        append(escape(uploadUrl))
        append("\"},\"pose\":{\"latLngPair\":{\"latitude\":")
        append(formatDouble(latitude))
        append(",\"longitude\":")
        append(formatDouble(longitude))
        // photo.create is documented as ignoring pose.heading — the heading that
        // orients a photo sphere is GPano:PoseHeadingDegrees inside the JPEG,
        // which PanoramaMetadata writes. Sent for completeness only; do not
        // assume it is what places the image on the map.
        append("},\"heading\":")
        val heading = headingDegrees?.let { (((it % 360f) + 360f) % 360f).toDouble() }
        append(if (heading != null) formatDouble(heading) else "0.0000000")
        append("},\"captureTime\":\"")
        append(iso8601(capturedAtMillis))
        append("\"}")
    }

    // ---- Response parsing ---------------------------------------------------

    /** `{"uploadUrl": "https://..."}` */
    fun parseUploadRef(body: String): String? = stringField(body, "uploadUrl")

    /** The resumable session URL, which arrives as a response *header*. */
    fun sessionUrl(response: HttpResponse): String? = response.header(SESSION_URL_HEADER)

    /**
     * A successful `photo.create` returns a Photo resource. `mapsPublishStatus`
     * is the one that says whether Maps has taken it (`PUBLISHED`) or rejected
     * it (`REJECTED_UNKNOWN`), and `shareLink` is what the user can open.
     */
    fun parsePhoto(body: String): Published = Published(
        photoId = readPhotoId(body),
        shareLink = stringField(body, "shareLink"),
        mapsPublishStatus = stringField(body, "mapsPublishStatus")
    )

    /** `{"photoId":{"id":"..."}}` */
    private fun readPhotoId(body: String): String? {
        val photoIdIndex = body.indexOf("\"photoId\"")
        if (photoIdIndex < 0) return null
        return stringField(body.substring(photoIdIndex), "id")
    }

    /** Google wraps API errors as `{"error":{"code":401,"status":"UNAUTHENTICATED","message":"..."}}`. */
    fun errorMessage(body: String): String? {
        val errorIndex = body.indexOf("\"error\"")
        val scope = if (errorIndex >= 0) body.substring(errorIndex) else body
        return stringField(scope, "message")
    }

    /** Turns a failed response into the sentence the user should read. */
    fun classifyFailure(response: HttpResponse): Outcome {
        val message = errorMessage(response.body) ?: response.body.take(200).ifBlank { "no response body" }
        return when (response.status) {
            401 -> Outcome.NotAuthorized("the sign-in was not accepted — try again")
            403 -> Outcome.Forbidden(
                "Google refused this app ($message). The OAuth client must be an " +
                    "Android client whose package name and SHA-1 match this build."
            )
            429 -> Outcome.QuotaExceeded("daily quota reached (${DAILY_QUOTA} requests) — try tomorrow")
            400, 404 -> Outcome.Rejected(message)
            in 500..599 -> Outcome.Transport("Google returned ${response.status} — try again")
            else -> Outcome.Rejected("HTTP ${response.status}: $message")
        }
    }

    // ---- The flow -----------------------------------------------------------

    /**
     * Runs the four requests in order, stopping at the first failure so a failed
     * upload never reaches `photo.create` (which would create an empty photo).
     */
    suspend fun publish(
        transport: HttpTransport,
        token: String,
        jpeg: ByteArray,
        latitude: Double,
        longitude: Double,
        headingDegrees: Float?,
        capturedAtMillis: Long,
        onProgress: (String) -> Unit = {}
    ): Outcome {
        preflight(jpeg)?.let { return it }

        onProgress("Requesting an upload slot...")
        val start = runCatching { transport.execute(startUploadRequest(token)) }
            .getOrElse { return Outcome.Transport(it.message ?: "network error") }
        if (start.status !in 200..299) return classifyFailure(start)
        val uploadUrl = parseUploadRef(start.body)
            ?: return Outcome.Rejected("no upload reference in the upload response")

        onProgress("Opening a resumable session...")
        val session = runCatching {
            transport.execute(resumableStartRequest(token, uploadUrl, jpeg.size.toLong()))
        }.getOrElse { return Outcome.Transport(it.message ?: "network error") }
        if (session.status !in 200..299) return classifyFailure(session)
        val sessionUrl = sessionUrl(session)
            ?: return Outcome.Rejected("the upload session did not return a session URL")

        onProgress("Uploading ${"%.1f".format(jpeg.size / 1024f / 1024f)} MB...")
        val uploaded = runCatching { transport.execute(uploadBytesRequest(token, sessionUrl, jpeg)) }
            .getOrElse { return Outcome.Transport(it.message ?: "network error") }
        if (uploaded.status !in 200..299) return classifyFailure(uploaded)

        onProgress("Publishing to Google Maps...")
        val created = runCatching {
            transport.execute(
                createPhotoRequest(token, uploadUrl, latitude, longitude, headingDegrees, capturedAtMillis)
            )
        }.getOrElse { return Outcome.Transport(it.message ?: "network error") }
        if (created.status !in 200..299) return classifyFailure(created)

        return Outcome.Success(parsePhoto(created.body))
    }

    // ---- Small helpers -----------------------------------------------------

    /**
     * Deliberately a targeted extractor, not a JSON parser: the four fields the
     * API's responses are read for are shallow and well documented, and the
     * project has no JSON dependency to justify for them. It handles escaped
     * quotes and the `\uXXXX` escapes Google's messages can contain.
     */
    fun stringField(json: String, name: String): String? {
        val key = "\"$name\""
        var from = json.indexOf(key)
        while (from >= 0) {
            var i = from + key.length
            while (i < json.length && json[i].isWhitespace()) i++
            if (i < json.length && json[i] == ':') {
                i++
                while (i < json.length && json[i].isWhitespace()) i++
                if (i < json.length && json[i] == '"') {
                    val value = StringBuilder()
                    i++
                    while (i < json.length) {
                        val c = json[i]
                        when {
                            c == '\\' && i + 1 < json.length -> {
                                when (val escaped = json[i + 1]) {
                                    'n' -> value.append('\n')
                                    't' -> value.append('\t')
                                    'u' -> {
                                        val hex = json.substring(i + 2, (i + 6).coerceAtMost(json.length))
                                        hex.toIntOrNull(16)?.let { value.append(it.toChar()) }
                                        i += 4
                                    }
                                    else -> value.append(escaped)
                                }
                                i += 2
                            }
                            c == '"' -> return value.toString()
                            else -> {
                                value.append(c)
                                i++
                            }
                        }
                    }
                }
            }
            from = json.indexOf(key, from + key.length)
        }
        return null
    }

    private fun escape(value: String): String =
        value.replace("\\", "\\\\").replace("\"", "\\\"")

    /** Locale-independent: a comma decimal separator would break the API. */
    private fun formatDouble(value: Double): String = String.format(Locale.US, "%.7f", value)

    /** `2026-10-11T01:10:00Z`, the format `captureTime` is documented to accept. */
    fun iso8601(millis: Long): String {
        val format = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
        format.timeZone = TimeZone.getTimeZone("UTC")
        return format.format(Date(millis))
    }
}
