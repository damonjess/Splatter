package com.example.splatter.processor.panorama

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs

/**
 * Writes the metadata a 360° photo needs to be accepted as a Photo Sphere by
 * Google Maps / Street View, straight into the JPEG byte stream.
 *
 * Two things are required on top of a plain 2:1 JPEG:
 *
 *  - **XMP GPano tags** describing the projection (`equirectangular`), the
 *    cropped and full panorama pixel sizes, and the compass heading. Google's
 *    metadata reference says all of these are needed for a panorama to be
 *    recognised, and that `PoseHeadingDegrees` is required for display on Maps
 *    so the image is oriented to the right direction.
 *  - **EXIF GPS tags** (`GPSLatitudeRef`, `GPSLatitude`, `GPSLongitudeRef`,
 *    `GPSLongitude`) so the photo lands at the right place on the map.
 *
 * Written by hand rather than pulling in a metadata library: it is a couple of
 * fixed segments inserted right after SOI, and keeping it pure Kotlin means the
 * bytes can be asserted on by JVM tests.
 */
object PanoramaMetadata {

    /** GPano namespace, exactly as Google's documentation specifies. */
    const val GPANO_NAMESPACE = "http://ns.google.com/photos/1.0/panorama/"

    private const val XMP_NAMESPACE = "http://ns.adobe.com/xap/1.0/\u0000"
    private val EXIF_NAMESPACE = byteArrayOf(
        'E'.code.toByte(), 'x'.code.toByte(), 'i'.code.toByte(), 'f'.code.toByte(), 0, 0
    )

    /**
     * The XMP packet for an uncropped equirectangular panorama.
     *
     * @param headingDegrees clockwise compass heading of the panorama centre, or
     *        null when no compass reading was available (Maps then cannot orient
     *        it, so the tags are only valid for a horizontal placement).
     */
    fun xmpPacket(
        widthPx: Int,
        heightPx: Int,
        headingDegrees: Float? = null
    ): String = buildString {
        append("<?xpacket begin=\"\uFEFF\" id=\"W5M0MpCehiHzreSzNTczkc9d\"?>\n")
        append("<x:xmpmeta xmlns:x=\"adobe:ns:meta/\">\n")
        append(" <rdf:RDF xmlns:rdf=\"http://www.w3.org/1999/02/22-rdf-syntax-ns#\">\n")
        append("  <rdf:Description rdf:about=\"\" xmlns:GPano=\"")
        append(GPANO_NAMESPACE)
        append("\"\n")
        append("    GPano:ProjectionType=\"equirectangular\"\n")
        append("    GPano:UsePanoramaViewer=\"True\"\n")
        append("    GPano:CroppedAreaImageWidthPixels=\"").append(widthPx).append("\"\n")
        append("    GPano:CroppedAreaImageHeightPixels=\"").append(heightPx).append("\"\n")
        append("    GPano:FullPanoWidthPixels=\"").append(widthPx).append("\"\n")
        append("    GPano:FullPanoHeightPixels=\"").append(heightPx).append("\"\n")
        append("    GPano:CroppedAreaLeftPixels=\"0\"\n")
        append("    GPano:CroppedAreaTopPixels=\"0\"\n")
        if (headingDegrees != null) {
            val normalised = ((headingDegrees % 360f) + 360f) % 360f
            append("    GPano:PoseHeadingDegrees=\"")
            append(String.format(java.util.Locale.US, "%.4f", normalised))
            append("\"\n")
        }
        append("  />\n")
        append(" </rdf:RDF>\n")
        append("</x:xmpmeta>\n")
        append("<?xpacket end=\"w\"?>")
    }

    /**
     * Inserts the XMP segment and (optionally) an EXIF GPS segment into a JPEG,
     * immediately after SOI so readers see the panorama tags before any image
     * data. Returns the input unchanged when it is not a JPEG.
     */
    fun insert(jpeg: ByteArray, xmp: String?, latitude: Double?, longitude: Double?): ByteArray {
        if (jpeg.size < 2 || jpeg[0] != 0xFF.toByte() || jpeg[1] != 0xD8.toByte()) return jpeg

        val segments = ByteArrayOutputStream()
        if (xmp != null) {
            val packet = xmp.toByteArray(Charsets.UTF_8)
            val payload = XMP_NAMESPACE.toByteArray(Charsets.UTF_8) + packet
            writeApp1(segments, payload)
        }
        if (latitude != null && longitude != null) {
            writeApp1(
                segments,
                EXIF_NAMESPACE + exifGpsTiff(
                    latitude, longitude,
                    latitudeRef(latitude), longitudeRef(longitude)
                )
            )
        }
        if (segments.size() == 0) return jpeg

        val out = ByteArray(2 + segments.size() + jpeg.size - 2)
        out[0] = 0xFF.toByte()
        out[1] = 0xD8.toByte()
        System.arraycopy(segments.toByteArray(), 0, out, 2, segments.size())
        System.arraycopy(jpeg, 2, out, 2 + segments.size(), jpeg.size - 2)
        return out
    }

    /** APP1 marker + big-endian length (length includes the two length bytes). */
    private fun writeApp1(out: ByteArrayOutputStream, payload: ByteArray) {
        val length = payload.size + 2
        out.write(0xFF)
        out.write(0xE1)
        out.write((length shr 8) and 0xFF)
        out.write(length and 0xFF)
        out.write(payload)
    }

    /**
     * Minimal little-endian EXIF block with a GPS IFD holding the four tags
     * Google's example lists, plus altitude when we know it.
     */
    private fun exifGpsTiff(
        latitude: Double,
        longitude: Double,
        latRef: Char,
        lonRef: Char
    ): ByteArray {
        val tiff = ByteArray(TIFF_SIZE)
        val buf = ByteBuffer.wrap(tiff).order(ByteOrder.LITTLE_ENDIAN)

        // TIFF header: "II", 42, offset of IFD0
        buf.put('I'.code.toByte())
        buf.put('I'.code.toByte())
        buf.putShort(42)
        buf.putInt(IFD0_OFFSET)

        // IFD0: a single entry pointing at the GPS IFD
        buf.position(IFD0_OFFSET)
        buf.putShort(1)
        putEntry(buf, tag = 0x8825, type = TYPE_LONG, count = 1, valueOffset = GPS_IFD_OFFSET.toLong())
        buf.putInt(0) // no IFD1

        // GPS IFD
        buf.position(GPS_IFD_OFFSET)
        buf.putShort(5)
        putEntry(buf, 0x0001, TYPE_ASCII, 2, inline = latRef.code.toLong() or (0L shl 8))
        putEntry(buf, 0x0002, TYPE_RATIONAL, 3, valueOffset = LAT_DATA_OFFSET.toLong())
        putEntry(buf, 0x0003, TYPE_ASCII, 2, inline = lonRef.code.toLong() or (0L shl 8))
        putEntry(buf, 0x0004, TYPE_RATIONAL, 3, valueOffset = LON_DATA_OFFSET.toLong())
        putEntry(buf, 0x0006, TYPE_RATIONAL, 1, valueOffset = ALT_DATA_OFFSET.toLong())
        buf.putInt(0)

        writeDms(buf, LAT_DATA_OFFSET, abs(latitude))
        writeDms(buf, LON_DATA_OFFSET, abs(longitude))
        // Altitude is unknown here; a zero rational is valid and ignored.
        buf.position(ALT_DATA_OFFSET)
        buf.putInt(0)
        buf.putInt(1)

        return tiff
    }

    private fun putEntry(
        buf: ByteBuffer,
        tag: Int,
        type: Int,
        count: Int,
        valueOffset: Long = 0,
        inline: Long = 0L
    ) {
        buf.putShort(tag.toShort())
        buf.putShort(type.toShort())
        buf.putInt(count)
        val value = if (valueOffset != 0L) valueOffset else inline
        buf.putInt(value.toInt())
    }

    /** Writes degrees/minutes/seconds as three RATIONALs, the EXIF GPS layout. */
    private fun writeDms(buf: ByteBuffer, offset: Int, degrees: Double) {
        val d = kotlin.math.floor(degrees).toInt()
        val minutesFull = (degrees - d) * 60.0
        val m = kotlin.math.floor(minutesFull).toInt()
        val seconds = (minutesFull - m) * 60.0
        buf.position(offset)
        for (part in intArrayOf(d, m)) {
            buf.putInt(part)
            buf.putInt(1)
        }
        // Seconds as a rational with 4 decimal places so the position is exact
        // to well under a metre.
        buf.putInt(kotlin.math.round(seconds * 10000.0).toInt())
        buf.putInt(10000)
    }

    /** Latitude sign for the GPS reference tag, exposed for callers/tests. */
    fun latitudeRef(latitude: Double): Char = if (latitude < 0) 'S' else 'N'

    fun longitudeRef(longitude: Double): Char = if (longitude < 0) 'W' else 'E'

    private const val TYPE_ASCII = 2
    private const val TYPE_LONG = 4
    private const val TYPE_RATIONAL = 5

    private const val IFD0_OFFSET = 8
    private const val GPS_IFD_OFFSET = IFD0_OFFSET + 2 + 12 + 4          // 26
    private const val LAT_DATA_OFFSET = GPS_IFD_OFFSET + 2 + 5 * 12 + 4 // 92
    private const val LON_DATA_OFFSET = LAT_DATA_OFFSET + 24
    private const val ALT_DATA_OFFSET = LON_DATA_OFFSET + 24
    private const val TIFF_SIZE = ALT_DATA_OFFSET + 8
}
