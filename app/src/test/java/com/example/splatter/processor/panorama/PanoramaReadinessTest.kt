package com.example.splatter.processor.panorama

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream

/**
 * Round-trips a panorama through the writer and the reader. If these pass, the
 * bytes on disk really do carry the tags Google asks for — the question the UI
 * answers with "Ready to publish to Google Maps".
 */
class PanoramaReadinessTest {

    /** A minimal JPEG: SOI, a JFIF APP0 to be skipped, SOF0 with the size, EOI. */
    private fun fakeJpeg(width: Int, height: Int): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(0xFF); out.write(0xD8)

        out.write(0xFF); out.write(0xE0)
        val jfif = byteArrayOf(
            'J'.code.toByte(), 'F'.code.toByte(), 'I'.code.toByte(), 'F'.code.toByte(), 0,
            1, 1, 0, 0, 1, 0, 1, 0, 0
        )
        writeSegment(out, jfif)

        out.write(0xFF); out.write(0xC0)
        val sof = ByteArray(6 + 3 * 3)
        sof[0] = 8 // precision
        sof[1] = (height shr 8).toByte(); sof[2] = height.toByte()
        sof[3] = (width shr 8).toByte(); sof[4] = width.toByte()
        sof[5] = 3
        for (i in 0 until 3) {
            sof[6 + i * 3] = (i + 1).toByte()
            sof[7 + i * 3] = 0x11
            sof[8 + i * 3] = 0
        }
        writeSegment(out, sof)

        out.write(0xFF); out.write(0xD9)
        return out.toByteArray()
    }

    private fun writeSegment(out: ByteArrayOutputStream, payload: ByteArray) {
        val length = payload.size + 2
        out.write((length shr 8) and 0xFF)
        out.write(length and 0xFF)
        out.write(payload)
    }

    private fun panorama(
        width: Int = 4096,
        height: Int = 2048,
        heading: Float? = 137.5f,
        latitude: Double? = 51.500729,
        longitude: Double? = -0.124625
    ): ByteArray = PanoramaMetadata.insert(
        jpeg = fakeJpeg(width, height),
        xmp = PanoramaMetadata.xmpPacket(width, height, heading),
        latitude = latitude,
        longitude = longitude
    )

    @Test
    fun aStitchedPanoramaReadsBackAsPublishable() {
        val report = PanoramaReadiness.evaluate(panorama())
        assertNotNull(report)
        report!!
        assertEquals(4096, report.metadata.widthPx)
        assertEquals(2048, report.metadata.heightPx)
        assertEquals(8.4, report.metadata.megapixels, 0.05)
        assertEquals("equirectangular", report.metadata.projectionType)
        assertEquals(4096, report.metadata.fullPanoWidthPx)
        assertEquals(137.5f, report.metadata.poseHeadingDegrees!!, 0.001f)
        assertEquals(51.500729, report.metadata.latitude!!, 1e-5)
        assertEquals(-0.124625, report.metadata.longitude!!, 1e-5)
        assertTrue("expected ready, blocking=${report.blocking}", report.ready)
        assertTrue("no notes expected, warnings=${report.warnings}", report.warnings.isEmpty())
    }

    @Test
    fun aDryRunWithoutGpsIsBlocked() {
        val report = PanoramaReadiness.evaluate(panorama(latitude = null, longitude = null))!!
        assertFalse(report.ready)
        assertTrue(report.blocking.any { it.label == "GPS position" })
    }

    @Test
    fun southernAndWesternPositionsSurviveTheRoundTrip() {
        val report = PanoramaReadiness.evaluate(
            panorama(latitude = -33.8688, longitude = -151.2093)
        )!!
        assertEquals(-33.8688, report.metadata.latitude!!, 1e-5)
        assertEquals(-151.2093, report.metadata.longitude!!, 1e-5)
        assertTrue(report.ready)
    }

    @Test
    fun aMissingCompassReadingIsReported() {
        val report = PanoramaReadiness.evaluate(panorama(heading = null))!!
        assertNull(report.metadata.poseHeadingDegrees)
        assertFalse(report.ready)
        assertTrue(report.blocking.any { it.label == "Compass heading" })
    }

    @Test
    fun tooFewPixelsIsBlocked() {
        // 2:1 but only 2.5 MP — the shape is right, the resolution is not.
        val report = PanoramaReadiness.evaluate(panorama(width = 2240, height = 1120))!!
        assertFalse(report.ready)
        assertTrue(report.blocking.any { it.label == "7.5 MP minimum" })
    }

    @Test
    fun aNonEquirectangularImageIsBlocked() {
        val report = PanoramaReadiness.evaluate(panorama(width = 4096, height = 3072))!!
        assertFalse(report.ready)
        assertTrue(report.blocking.any { it.label == "2:1 equirectangular" })
    }

    @Test
    fun aJpegWithoutGpanoTagsIsBlocked() {
        val bare = PanoramaMetadata.insert(
            jpeg = fakeJpeg(4096, 2048),
            xmp = null,
            latitude = 1.0,
            longitude = 2.0
        )
        val report = PanoramaReadiness.evaluate(bare)!!
        assertFalse(report.ready)
        assertTrue(report.blocking.any { it.label == "GPano projection tags" })
    }

    @Test
    fun aNonJpegIsRejectedRatherThanGraded() {
        assertNull(PanoramaReadiness.evaluate("not an image".toByteArray()))
        assertNull(PanoramaReadiness.evaluate(byteArrayOf(0xFF.toByte(), 0xD8.toByte())))
    }

    @Test
    fun theSummaryNamesWhatBlockedIt() {
        val report = PanoramaReadiness.evaluate(panorama(heading = null, latitude = null, longitude = null))!!
        assertTrue(report.summary.startsWith("Not publishable:"))
        assertTrue(report.summary.contains("GPS position"))
        assertTrue(report.summary.contains("Compass heading"))
    }
}
