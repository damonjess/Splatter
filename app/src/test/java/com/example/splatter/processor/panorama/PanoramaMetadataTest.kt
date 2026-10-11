package com.example.splatter.processor.panorama

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs

/**
 * Parses the JPEG segments this code writes back out of the byte stream, so the
 * assertions are on real EXIF/GPS structures rather than string presence. If
 * Google can't read these bytes, the photo is rejected — so the test reads them
 * the way a reader would.
 */
class PanoramaMetadataTest {

    /** SOI + a JFIF APP0 + a fake scan + EOI: enough structure to insert into. */
    private fun fakeJpeg(): ByteArray = byteArrayOf(
        0xFF.toByte(), 0xD8.toByte(),
        0xFF.toByte(), 0xE0.toByte(), 0x00, 0x10, // APP0, length 16
        'J'.code.toByte(), 'F'.code.toByte(), 'I'.code.toByte(), 'F'.code.toByte(), 0,
        1, 1, 0, 0, 1, 0, 1, 0, 0,
        0xFF.toByte(), 0xDA.toByte(), 0x00, 0x04, 1, 2,       // SOS
        9, 9, 9, 9,                                            // "image data"
        0xFF.toByte(), 0xD9.toByte()                           // EOI
    )

    private class Segment(val marker: Int, val payload: ByteArray)

    private fun segments(jpeg: ByteArray): List<Segment> {
        val out = ArrayList<Segment>()
        var i = 2
        while (i + 3 < jpeg.size) {
            if (jpeg[i] != 0xFF.toByte()) break
            val marker = jpeg[i + 1].toInt() and 0xFF
            if (marker == 0xDA) break // start of scan; no more metadata segments
            val length = ((jpeg[i + 2].toInt() and 0xFF) shl 8) or (jpeg[i + 3].toInt() and 0xFF)
            out.add(Segment(marker, jpeg.copyOfRange(i + 4, i + 2 + length)))
            i += 2 + length
        }
        return out
    }

    @Test
    fun writesLoadableGpanoTagsForAnUncroppedPano() {
        val jpeg = PanoramaMetadata.insert(
            fakeJpeg(),
            xmp = PanoramaMetadata.xmpPacket(4096, 2048, headingDegrees = 137.5f),
            latitude = 51.5007,
            longitude = -0.1246
        )

        val xmpSegment = segments(jpeg).firstOrNull { segment ->
            segment.marker == 0xE1 && String(segment.payload, Charsets.ISO_8859_1)
                .startsWith("http://ns.adobe.com/xap/1.0/\u0000")
        }
        assertNotNull("an XMP APP1 segment should be present", xmpSegment)

        // The APP1 payload is the XMP namespace, a NUL, then the XML packet.
        val raw = String(xmpSegment!!.payload, Charsets.UTF_8)
        val packet = raw.substring(raw.indexOf('\u0000') + 1)
        assertTrue("GPano namespace", packet.contains("xmlns:GPano=\"${PanoramaMetadata.GPANO_NAMESPACE}\""))
        assertTrue(packet.contains("GPano:ProjectionType=\"equirectangular\""))
        assertTrue(packet.contains("GPano:CroppedAreaImageWidthPixels=\"4096\""))
        assertTrue(packet.contains("GPano:CroppedAreaImageHeightPixels=\"2048\""))
        assertTrue(packet.contains("GPano:FullPanoWidthPixels=\"4096\""))
        assertTrue(packet.contains("GPano:FullPanoHeightPixels=\"2048\""))
        assertTrue(packet.contains("GPano:CroppedAreaLeftPixels=\"0\""))
        assertTrue(packet.contains("GPano:CroppedAreaTopPixels=\"0\""))
        assertTrue("required for Maps display", packet.contains("GPano:PoseHeadingDegrees=\"137.5"))
        assertTrue("xpacket wrapper is required", packet.startsWith("<?xpacket begin="))
        assertTrue(packet.trimEnd().endsWith("<?xpacket end=\"w\"?>"))
    }

    @Test
    fun headingIsNormalisedIntoZeroToThreeSixty() {
        assertEquals(
            true,
            PanoramaMetadata.xmpPacket(100, 50, -90f).contains("GPano:PoseHeadingDegrees=\"270")
        )
        assertEquals(
            true,
            PanoramaMetadata.xmpPacket(100, 50, 400f).contains("GPano:PoseHeadingDegrees=\"40")
        )
    }

    @Test
    fun omitsHeadingWhenThereWasNoCompassReading() {
        val packet = PanoramaMetadata.xmpPacket(100, 50, headingDegrees = null)
        assertTrue("no heading tag should be written", !packet.contains("PoseHeadingDegrees"))
    }

    @Test
    fun writesGpsTagsThatDecodeBackToTheInputCoordinates() {
        val lat = 51.500729
        val lon = -0.124625
        val jpeg = PanoramaMetadata.insert(fakeJpeg(), xmp = null, latitude = lat, longitude = lon)

        val exif = segments(jpeg).firstOrNull { segment ->
            segment.marker == 0xE1 &&
                segment.payload.size > 6 &&
                String(segment.payload.copyOfRange(0, 6), Charsets.ISO_8859_1) == "Exif\u0000\u0000"
        }
        assertNotNull("an EXIF APP1 segment should be present", exif)

        val tiff = ByteBuffer.wrap(exif!!.payload, 6, exif.payload.size - 6).order(ByteOrder.LITTLE_ENDIAN)
        val base = 6
        assertEquals("TIFF little-endian marker", 'I'.code, tiff.get(base).toInt())
        assertEquals("TIFF magic 42", 42, tiff.getShort(base + 2).toInt())

        // IFD0 -> GPS IFD
        val ifd0 = base + tiff.getInt(base + 4)
        val entries = tiff.getShort(ifd0).toInt()
        assertEquals(1, entries)
        assertEquals("GPSInfoIFDPointer tag", 0x8825, tiff.getShort(ifd0 + 2).toInt() and 0xFFFF)
        val gpsIfd = base + tiff.getInt(ifd0 + 2 + 8)

        val gpsEntries = tiff.getShort(gpsIfd).toInt() and 0xFFFF
        assertEquals("five GPS tags", 5, gpsEntries)

        fun entry(tag: Int): Triple<Int, Int, Int> { // type, count, value/offset
            for (i in 0 until gpsEntries) {
                val at = gpsIfd + 2 + i * 12
                if ((tiff.getShort(at).toInt() and 0xFFFF) == tag) {
                    return Triple(
                        tiff.getShort(at + 2).toInt() and 0xFFFF,
                        tiff.getInt(at + 4),
                        tiff.getInt(at + 8)
                    )
                }
            }
            throw AssertionError("GPS tag 0x%04X missing".format(tag))
        }

        fun asciiInline(tag: Int): Char = (entry(tag).third and 0xFF).toChar()

        fun dms(tag: Int): Double {
            val offset = base + entry(tag).third
            val deg = tiff.getInt(offset).toDouble() / tiff.getInt(offset + 4)
            val min = tiff.getInt(offset + 8).toDouble() / tiff.getInt(offset + 12)
            val sec = tiff.getInt(offset + 16).toDouble() / tiff.getInt(offset + 20)
            return deg + min / 60.0 + sec / 3600.0
        }

        assertEquals("GPSLatitudeRef", 'N', asciiInline(0x0001))
        assertEquals("GPSLongitudeRef", 'W', asciiInline(0x0003))
        // EXIF stores magnitudes; the sign lives in the reference tag above.
        assertTrue("latitude round-trips", abs(dms(0x0002) - abs(lat)) < 1e-5)
        assertTrue("longitude round-trips", abs(dms(0x0004) - abs(lon)) < 1e-5)
    }

    @Test
    fun southernAndWesternReferencesAreWritten() {
        assertEquals('S', PanoramaMetadata.latitudeRef(-33.86))
        assertEquals('W', PanoramaMetadata.longitudeRef(-151.2))
        assertEquals('N', PanoramaMetadata.latitudeRef(33.86))
        assertEquals('E', PanoramaMetadata.longitudeRef(151.2))
    }

    @Test
    fun keepsTheOriginalImageIntactAfterTheNewSegments() {
        val original = fakeJpeg()
        val withMetadata = PanoramaMetadata.insert(
            original, PanoramaMetadata.xmpPacket(100, 50, 0f), 1.0, 2.0
        )

        assertEquals("still starts with SOI", 0xFF, withMetadata[0].toInt() and 0xFF)
        assertEquals("still ends with EOI", 0xD9, withMetadata[withMetadata.size - 1].toInt() and 0xFF)
        val tail = withMetadata.copyOfRange(withMetadata.size - original.size + 2, withMetadata.size)
        assertTrue(
            "original bytes must survive intact",
            tail.contentEquals(original.copyOfRange(2, original.size))
        )
    }

    @Test
    fun refusesToMangleANonJpeg() {
        val notAJpeg = byteArrayOf(1, 2, 3, 4)
        val result = PanoramaMetadata.insert(notAJpeg, PanoramaMetadata.xmpPacket(10, 5, null), 1.0, 1.0)
        assertTrue(result.contentEquals(notAJpeg))
    }
}
