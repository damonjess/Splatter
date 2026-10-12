package com.example.splatter.processor.publish

import com.example.splatter.processor.panorama.PanoramaMetadata
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream

/**
 * The publish path can fail in ways that look like nothing: a wrong header, a
 * reference pointing at the wrong URL, a photo created without a location. None
 * of those are visible without a real OAuth client and a real upload, which is
 * exactly why the wire format is pinned here instead — every request is asserted
 * field by field, and the four-step flow is driven through a fake transport that
 * answers with Google's documented response shapes.
 */
class StreetViewPublishTest {

    private val token = "ya29.test-token"
    private val uploadUrl = "https://streetviewpublish.googleapis.com/v1/upload/AbC123"
    private val sessionUrl = "https://streetviewpublish.googleapis.com/upload/v1/session/XyZ789"

    /** Records what was sent and replies with scripted responses. */
    private class FakeTransport(
        private val responses: MutableList<StreetViewPublish.HttpResponse> = mutableListOf()
    ) : StreetViewPublish.HttpTransport {
        val sent = ArrayList<StreetViewPublish.HttpRequest>()
        var networkFailure: String? = null

        override suspend fun execute(request: StreetViewPublish.HttpRequest): StreetViewPublish.HttpResponse {
            sent.add(request)
            networkFailure?.let { throw java.io.IOException(it) }
            return responses.removeFirst()
        }
    }

    private fun response(status: Int, body: String, headers: Map<String, String> = emptyMap()) =
        StreetViewPublish.HttpResponse(status = status, headers = headers, body = body)

    /** A 4096 × 2048 JPEG carrying the GPano tags and a GPS position. */
    private fun publishableJpeg(): ByteArray = panoramaJpeg(4096, 2048)

    // ---- The request shapes -------------------------------------------------

    @Test
    fun startUploadIsAnEmptyPostWithABearerToken() {
        val request = StreetViewPublish.startUploadRequest(token)
        assertEquals("POST", request.method)
        assertEquals(
            "https://streetviewpublish.googleapis.com/v1/photo:startUpload",
            request.url
        )
        assertEquals("Bearer $token", request.headers["Authorization"])
        assertNull("the start request must have no body", request.body)
    }

    @Test
    fun theResumableStartCarriesTheDocumentedHeadersAndTheSize() {
        val request = StreetViewPublish.resumableStartRequest(token, uploadUrl, 354_656)
        assertEquals("POST", request.method)
        assertEquals("it must post to the URL startUpload returned", uploadUrl, request.url)
        assertEquals("resumable", request.headers["X-Goog-Upload-Protocol"])
        assertEquals("start", request.headers["X-Goog-Upload-Command"])
        assertEquals("354656", request.headers["X-Goog-Upload-Header-Content-Length"])
        assertEquals("image/jpeg", request.headers["X-Goog-Upload-Header-Content-Type"])
    }

    @Test
    fun theBytesAreSentToTheSessionUrlWithUploadFinalize() {
        val jpeg = byteArrayOf(1, 2, 3, 4)
        val request = StreetViewPublish.uploadBytesRequest(token, sessionUrl, jpeg)
        assertEquals(sessionUrl, request.url)
        assertEquals("upload, finalize", request.headers["X-Goog-Upload-Command"])
        assertEquals("0", request.headers["X-Goog-Upload-Offset"])
        assertEquals("4", request.headers["Content-Length"])
        assertTrue("the raw JPEG must be the body", request.body!!.contentEquals(jpeg))
    }

    @Test
    fun theSessionUrlIsReadFromTheResponseHeaderNotTheBody() {
        val upperCased = response(
            200,
            body = "",
            headers = mapOf("X-Goog-Upload-URL" to sessionUrl, "Content-Length" to "0")
        )
        assertEquals(sessionUrl, StreetViewPublish.sessionUrl(upperCased))

        // Header casing is not guaranteed, so lookups must be case-insensitive.
        val lowerCased = response(200, "", mapOf("x-goog-upload-url" to sessionUrl))
        assertEquals(sessionUrl, StreetViewPublish.sessionUrl(lowerCased))
    }

    @Test
    fun thePhotoBodyUsesTheStartUploadReferenceNotTheSessionUrl() {
        val body = StreetViewPublish.photoBody(
            uploadUrl = uploadUrl,
            latitude = 51.500729,
            longitude = -0.124625,
            headingDegrees = 137.5f,
            capturedAtMillis = 1_791_676_056_000L
        )
        assertTrue("the reference must be the start-upload URL", body.contains(uploadUrl))
        assertFalse("the session URL is not a valid reference", body.contains(sessionUrl))
        assertTrue(body.contains("\"latitude\":51.5007290"))
        assertTrue(body.contains("\"longitude\":-0.1246250"))
        assertTrue(body.contains("\"heading\":137.5000000"))
        assertTrue(body.contains("\"captureTime\":\""))
        // An 8-byte token file timestamp: 2026-10-11 in UTC.
        assertTrue("captureTime must be trimmed to seconds, UTC", body.endsWith("Z\"}"))
    }

    @Test
    fun thePhotoBodyIsWellFormedJsonWithOneOfEachKey() {
        val body = StreetViewPublish.photoBody(uploadUrl, 1.0, 2.0, null, 0L)
        var depth = 0
        var inString = false
        var escaped = false
        for (c in body) {
            when {
                escaped -> escaped = false
                c == '\\' && inString -> escaped = true
                c == '"' -> inString = !inString
                inString -> Unit
                c == '{' -> depth++
                c == '}' -> depth--
                c == '[' || c == ']' -> throw AssertionError("unexpected array in $body")
            }
        }
        assertEquals("braces must balance", 0, depth)
        assertFalse("no unterminated string", inString)
        // A duplicated "pose" key is how this file once produced unparseable
        // JSON — an earlier version emitted it twice for the heading branch.
        assertEquals("exactly one pose object", 1, Regex("\"pose\"").findAll(body).count())
        assertEquals(1, Regex("\"uploadReference\"").findAll(body).count())
        assertEquals(1, Regex("\"latLngPair\"").findAll(body).count())
        assertEquals(1, Regex("\"captureTime\"").findAll(body).count())
    }

    @Test
    fun theTrailingZerosAreLocaleIndependent() {
        // A comma decimal separator would make the request invalid JSON, and it
        // is the sort of thing only a German phone would ever reveal.
        val body = StreetViewPublish.photoBody(uploadUrl, 51.5007294, -0.1246, 0f, 0L)
        assertFalse(body.contains("51,5007294"))
        assertTrue(body.contains("51.5007294"))
    }

    @Test
    fun timestampsAreUtcToTheSecond() {
        // Compared against java.time rather than a hardcoded string, which makes
        // this a real check of UTC handling: this JVM runs in a non-UTC zone, so
        // any use of the default zone (a classic bug) fails here.
        for (millis in listOf(0L, 1_000_000_000_000L, 1_791_676_200_000L, 1_791_676_199_999L)) {
            assertEquals(
                "millis=$millis",
                java.time.Instant.ofEpochMilli(millis)
                    .atOffset(java.time.ZoneOffset.UTC)
                    .format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'")),
                StreetViewPublish.iso8601(millis)
            )
        }
    }

    // ---- Parsing ------------------------------------------------------------

    @Test
    fun theUploadReferenceIsReadFromTheStartUploadResponse() {
        val body = """{"uploadUrl":"$uploadUrl"}"""
        assertEquals(uploadUrl, StreetViewPublish.parseUploadRef(body))
    }

    @Test
    fun aSuccessfulCreateIsReadBackWithItsStatusAndLink() {
        val body = """
            {"photoId":{"id":"CAoSF0NhbGdUb1N0cmVldFZpZXc"},
             "shareLink":"https://maps.google.com/maps/contrib/123/photos/CAoSF0",
             "captureTime":"2026-10-11T01:10:00Z",
             "mapsPublishStatus":"PUBLISHED",
             "transferStatus":"TRANSFERRED"}
        """.trimIndent()
        val published = StreetViewPublish.parsePhoto(body)
        assertEquals("CAoSF0NhbGdUb1N0cmVldFZpZXc", published.photoId)
        assertEquals("https://maps.google.com/maps/contrib/123/photos/CAoSF0", published.shareLink)
        assertEquals("PUBLISHED", published.mapsPublishStatus)
    }

    @Test
    fun aRejectedPhotoStillParsesItsStatus() {
        val published = StreetViewPublish.parsePhoto("""{"photoId":{"id":"X"},"mapsPublishStatus":"REJECTED_UNKNOWN"}""")
        assertEquals("REJECTED_UNKNOWN", published.mapsPublishStatus)
        assertNull(published.shareLink)
    }

    @Test
    fun googleErrorMessagesAreExtractedIncludingEscapes() {
        val body = """{"error":{"code":403,"message":"Request had insufficient \"scope\" (streetviewpublish)","status":"PERMISSION_DENIED"}}"""
        assertEquals(
            "Request had insufficient \"scope\" (streetviewpublish)",
            StreetViewPublish.errorMessage(body)
        )
    }

    // ---- Failure classification --------------------------------------------

    @Test
    fun failuresBecomeSentencesWorthReading() {
        val unauthorized = StreetViewPublish.classifyFailure(
            response(401, """{"error":{"code":401,"status":"UNAUTHENTICATED","message":"Request is missing required authentication credential."}}""")
        )
        assertTrue(unauthorized is StreetViewPublish.Outcome.NotAuthorized)

        val forbidden = StreetViewPublish.classifyFailure(
            response(403, """{"error":{"code":403,"status":"PERMISSION_DENIED","message":"Permission denied."}}""")
        )
        assertTrue(forbidden is StreetViewPublish.Outcome.Forbidden)
        assertTrue(
            "a 403 must mention the signature mismatch, the usual cause",
            (forbidden as StreetViewPublish.Outcome.Forbidden).detail.contains("SHA-1")
        )

        val invalid = StreetViewPublish.classifyFailure(
            response(400, """{"error":{"code":400,"status":"INVALID_ARGUMENT","message":"Photo must contain GPS data."}}""")
        )
        assertTrue(invalid is StreetViewPublish.Outcome.Rejected)
        assertEquals("Photo must contain GPS data.", (invalid as StreetViewPublish.Outcome.Rejected).detail)

        val quota = StreetViewPublish.classifyFailure(
            response(429, """{"error":{"code":429,"status":"RESOURCE_EXHAUSTED","message":"Quota exceeded."}}""")
        )
        assertTrue(quota is StreetViewPublish.Outcome.QuotaExceeded)

        val serverError = StreetViewPublish.classifyFailure(response(503, "upstream unavailable"))
        assertTrue(serverError is StreetViewPublish.Outcome.Transport)
    }

    // ---- Pre-flight ---------------------------------------------------------

    @Test
    fun aPhotoWithoutGpsIsRefusedBeforeAnythingIsUploaded() {
        // The API accepts the upload and then fails the photo for a missing
        // position, so the app must catch this from the file instead.
        val outcome = StreetViewPublish.preflight(publishableJpegNoGps())
        assertTrue("expected NoLocation, got $outcome", outcome is StreetViewPublish.Outcome.NoLocation)
    }

    @Test
    fun aPhotoThatFailsGooglesRequirementsIsRefusedWithTheReasons() {
        // Right shape, far too few pixels: the API would reject it after the
        // upload, so the app must not spend the quota on it.
        val small = panoramaJpeg(1024, 512)
        val outcome = StreetViewPublish.preflight(small)
        assertTrue(outcome is StreetViewPublish.Outcome.NotPublishable)
        assertTrue(
            (outcome as StreetViewPublish.Outcome.NotPublishable).reason.contains("7.5 mp")
        )
    }

    @Test
    fun aPublishablePhotoPassesPreflight() {
        assertNull(StreetViewPublish.preflight(publishableJpeg()))
    }

    // ---- The whole flow -----------------------------------------------------

    @Test
    fun theFourStepsRunInOrderAndEndWithTheShareLink() = runBlocking {
        val jpeg = publishableJpeg()
        val transport = FakeTransport(
            mutableListOf(
                response(200, """{"uploadUrl":"$uploadUrl"}"""),
                response(200, "", mapOf("x-goog-upload-url" to sessionUrl)),
                response(200, """{"state":"FINALIZED"}"""),
                response(
                    200,
                    """{"photoId":{"id":"PHOTO1"},"shareLink":"https://maps.google.com/maps/contrib/1/photos/PHOTO1","mapsPublishStatus":"PUBLISHED"}"""
                )
            )
        )
        val progress = mutableListOf<String>()

        val outcome = StreetViewPublish.publish(
            transport = transport,
            token = token,
            jpeg = jpeg,
            latitude = 51.500729,
            longitude = -0.124625,
            headingDegrees = 137.5f,
            capturedAtMillis = 1_791_676_200_000L,
            onProgress = { progress.add(it) }
        )

        assertTrue("expected success, got $outcome", outcome is StreetViewPublish.Outcome.Success)
        val published = (outcome as StreetViewPublish.Outcome.Success).published
        assertEquals("PHOTO1", published.photoId)
        assertEquals("PUBLISHED", published.mapsPublishStatus)
        assertTrue(published.shareLink!!.startsWith("https://maps.google.com/maps/contrib/"))

        assertEquals("four requests, in order", 4, transport.sent.size)
        assertTrue(transport.sent[0].url.endsWith("/photo:startUpload"))
        assertEquals(uploadUrl, transport.sent[1].url)
        assertEquals(sessionUrl, transport.sent[2].url)
        assertTrue(transport.sent[3].url.endsWith("/v1/photo"))
        assertTrue("the bytes really are the body", transport.sent[2].body!!.contentEquals(jpeg))
        assertEquals("every request is authorised", token, transport.sent[3].headers["Authorization"]!!.removePrefix("Bearer "))
        assertTrue("progress should be reported", progress.size >= 4)
    }

    @Test
    fun aFailedUploadNeverCreatesAPhoto() = runBlocking {
        val transport = FakeTransport(
            mutableListOf(
                response(200, """{"uploadUrl":"$uploadUrl"}"""),
                response(200, "", mapOf("x-goog-upload-url" to sessionUrl)),
                response(500, """{"error":{"code":500,"status":"INTERNAL","message":"upload blew up"}}""")
            )
        )
        val outcome = StreetViewPublish.publish(
            transport, token, publishableJpeg(), 51.5, -0.12, 137.5f, 0L
        )
        assertTrue(outcome is StreetViewPublish.Outcome.Transport)
        assertEquals("photo.create must not run after a failed upload", 3, transport.sent.size)
    }

    @Test
    fun aMissingSessionUrlStopsBeforeSendingBytes() = runBlocking {
        val transport = FakeTransport(
            mutableListOf(
                response(200, """{"uploadUrl":"$uploadUrl"}"""),
                response(200, "", emptyMap()) // no X-Goog-Upload-URL header
            )
        )
        val outcome = StreetViewPublish.publish(
            transport, token, publishableJpeg(), 51.5, -0.12, 137.5f, 0L
        )
        assertTrue("expected a rejection, got $outcome", outcome is StreetViewPublish.Outcome.Rejected)
        assertEquals(2, transport.sent.size)
    }

    @Test
    fun aNetworkErrorIsReportedRatherThanCrashing() = runBlocking {
        val jpeg = publishableJpeg()
        val transport = FakeTransport(mutableListOf()).apply { networkFailure = "connection reset" }
        val outcome = StreetViewPublish.publish(transport, token, jpeg, 51.5, -0.12, 137.5f, 0L)
        assertTrue(outcome is StreetViewPublish.Outcome.Transport)
        assertTrue((outcome as StreetViewPublish.Outcome.Transport).detail.contains("connection reset"))
    }

    @Test
    fun nothingIsSentForAPhotoWithNoLocation() = runBlocking {
        val transport = FakeTransport(mutableListOf())
        // The caller also passes the pose it read off the file; a file without
        // GPS must still stop the flow before a byte goes anywhere.
        val outcome = StreetViewPublish.publish(
            transport, token, publishableJpegNoGps(), 51.5, -0.12, 137.5f, 0L
        )
        assertTrue(outcome is StreetViewPublish.Outcome.NoLocation)
        assertEquals("no request may be sent", 0, transport.sent.size)
    }

    // ---- Fixtures -----------------------------------------------------------

    /** A minimal JPEG: SOI, a SOF0 carrying the dimensions, EOI. */
    private fun bareJpeg(width: Int, height: Int): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(0xFF); out.write(0xD8)
        out.write(0xFF); out.write(0xC0)
        val sof = ByteArray(6 + 3 * 3)
        sof[0] = 8
        sof[1] = (height shr 8).toByte(); sof[2] = height.toByte()
        sof[3] = (width shr 8).toByte(); sof[4] = width.toByte()
        sof[5] = 3
        val length = sof.size + 2
        out.write((length shr 8) and 0xFF); out.write(length and 0xFF)
        out.write(sof)
        out.write(0xFF); out.write(0xD9)
        return out.toByteArray()
    }

    /** A stitched panorama as the app writes it: XMP GPano tags plus EXIF GPS. */
    private fun panoramaJpeg(width: Int, height: Int): ByteArray = PanoramaMetadata.insert(
        jpeg = bareJpeg(width, height),
        xmp = PanoramaMetadata.xmpPacket(width, height, 137.5f),
        latitude = 51.500729,
        longitude = -0.124625
    )

    /**
     * 4096 × 2048 with the GPano tags but deliberately no GPS. Built from a bare
     * JPEG so no earlier GPS segment can survive into it.
     */
    private fun publishableJpegNoGps(): ByteArray = PanoramaMetadata.insert(
        jpeg = bareJpeg(4096, 2048),
        xmp = PanoramaMetadata.xmpPacket(4096, 2048, 137.5f),
        latitude = null,
        longitude = null
    )
}
