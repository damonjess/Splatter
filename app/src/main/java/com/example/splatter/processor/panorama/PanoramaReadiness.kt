package com.example.splatter.processor.panorama

/**
 * Reads a written 360° JPEG back off disk and grades it against Google's Photo
 * Sphere requirements.
 *
 * The point of reading the file rather than trusting the writer is that a
 * panorama is only accepted if the metadata actually made it into the bytes.
 * This parses the same APP1 segments [PanoramaMetadata] writes — the XMP GPano
 * packet and the EXIF GPS block — so the answer to "can I upload this?" comes
 * from the artifact, not from the code that produced it.
 *
 * The thresholds are the ones Google documents for Photo Spheres:
 *  - 2:1 equirectangular, full 360° horizontally
 *  - at least 7.5 MP (Google's minimum example is 3840 × 1920)
 *  - at most 100 MP and 75 MB per photo (Street View Publish API limits)
 *  - a latitude/longitude pair in the photo or its EXIF header, without which
 *    the upload fails
 *  - `GPano:PoseHeadingDegrees`, required for the panorama to display on Maps
 */
object PanoramaReadiness {

    /** Google's stated minimum: 7.5 MP, given in their docs as 3840 × 1920. */
    const val MIN_PIXELS = 7_500_000L

    /** Street View Publish API hard limit per photo. */
    const val MAX_BYTES = 75L * 1024L * 1024L

    enum class Severity { REQUIRED, ADVISORY }

    data class Check(
        val label: String,
        val detail: String,
        val ok: Boolean,
        val severity: Severity
    )

    data class Metadata(
        val widthPx: Int,
        val heightPx: Int,
        val byteCount: Int,
        val projectionType: String?,
        val fullPanoWidthPx: Int?,
        val fullPanoHeightPx: Int?,
        val poseHeadingDegrees: Float?,
        val latitude: Double?,
        val longitude: Double?
    ) {
        val megapixels: Double get() = widthPx.toLong() * heightPx / 1_000_000.0
    }

    data class Report(val metadata: Metadata, val checks: List<Check>) {
        /** Every requirement Google states, satisfied. */
        val ready: Boolean get() = checks.none { !it.ok && it.severity == Severity.REQUIRED }

        /** Checks Google lists as required that this file does not satisfy. */
        val blocking: List<Check> get() = checks.filter { !it.ok && it.severity == Severity.REQUIRED }

        val warnings: List<Check> get() = checks.filter { !it.ok && it.severity == Severity.ADVISORY }

        /** One-line verdict for the UI. */
        val summary: String
            get() = when {
                ready && warnings.isEmpty() -> "Ready to publish to Google Maps"
                ready -> "${warnings.size} note(s) — still publishable"
                else -> "Not publishable: " + blocking.joinToString("; ") { it.label }
            }
    }

    /** Grades an already-written JPEG. Returns null when [jpeg] is not a JPEG. */
    fun evaluate(jpeg: ByteArray): Report? = readMetadata(jpeg)?.let { evaluate(it) }

    fun evaluate(metadata: Metadata): Report {
        val checks = ArrayList<Check>(7)

        val ratio = if (metadata.heightPx > 0) {
            metadata.widthPx.toDouble() / metadata.heightPx.toDouble()
        } else 0.0
        checks.add(
            Check(
                label = "2:1 equirectangular",
                detail = "%.3f : 1".format(ratio),
                ok = kotlin.math.abs(ratio - 2.0) < 0.02,
                severity = Severity.REQUIRED
            )
        )

        checks.add(
            Check(
                label = "7.5 MP minimum",
                detail = "%d × %d = %.1f MP".format(
                    metadata.widthPx, metadata.heightPx, metadata.megapixels
                ),
                ok = metadata.widthPx.toLong() * metadata.heightPx >= MIN_PIXELS,
                severity = Severity.REQUIRED
            )
        )

        checks.add(
            Check(
                label = "Under 75 MB",
                detail = "%.1f MB".format(metadata.byteCount / (1024.0 * 1024.0)),
                ok = metadata.byteCount in 1..MAX_BYTES.toInt(),
                severity = Severity.REQUIRED
            )
        )

        val projected = metadata.projectionType.equals("equirectangular", ignoreCase = true) &&
            metadata.fullPanoWidthPx != null && metadata.fullPanoHeightPx != null
        checks.add(
            Check(
                label = "GPano projection tags",
                detail = if (metadata.projectionType == null) {
                    "no XMP GPano packet"
                } else {
                    "${metadata.projectionType}, full ${metadata.fullPanoWidthPx ?: 0}×${metadata.fullPanoHeightPx ?: 0}"
                },
                ok = projected,
                severity = Severity.REQUIRED
            )
        )

        val gps = metadata.latitude != null && metadata.longitude != null &&
            metadata.latitude in -90.0..90.0 && metadata.longitude in -180.0..180.0
        checks.add(
            Check(
                label = "GPS position",
                detail = if (gps) {
                    "%.6f, %.6f".format(metadata.latitude, metadata.longitude)
                } else {
                    "no EXIF GPS — the upload is rejected without a position"
                },
                ok = gps,
                severity = Severity.REQUIRED
            )
        )

        val heading = metadata.poseHeadingDegrees
        checks.add(
            Check(
                label = "Compass heading",
                detail = if (heading == null) {
                    "no compass reading — Maps cannot orient it"
                } else {
                    "%.1f°".format(heading)
                },
                ok = heading != null && heading >= 0f && heading < 360f,
                severity = Severity.REQUIRED
            )
        )

        return Report(metadata, checks)
    }

    /**
     * Pulls the dimensions out of the JPEG's start-of-frame marker and the
     * GPano/GPS values out of the APP1 segments.
     *
     * Deliberately tolerant: a phone JPEG with extra segments, a colour profile
     * or an APP0 JFIF header still parses, and anything unreadable simply comes
     * back as null instead of failing the whole grading.
     */
    fun readMetadata(jpeg: ByteArray): Metadata? {
        if (jpeg.size < 4 || jpeg[0] != 0xFF.toByte() || jpeg[1] != 0xD8.toByte()) return null

        var width = 0
        var height = 0
        var projectionType: String? = null
        var fullWidth: Int? = null
        var fullHeight: Int? = null
        var heading: Float? = null
        var latitude: Double? = null
        var longitude: Double? = null

        var offset = 2
        while (offset + 4 <= jpeg.size) {
            if (jpeg[offset] != 0xFF.toByte()) {
                offset++ // resynchronise on padding
                continue
            }
            val marker = jpeg[offset + 1].toInt() and 0xFF
            if (marker == 0xD8 || marker == 0x01 || (marker in 0xD0..0xD7)) {
                offset += 2
                continue
            }
            if (marker == 0xDA || marker == 0xD9) break // start of scan / end of image
            if (offset + 4 > jpeg.size) break

            val length = ((jpeg[offset + 2].toInt() and 0xFF) shl 8) or
                (jpeg[offset + 3].toInt() and 0xFF)
            val payloadStart = offset + 4
            val payloadEnd = offset + 2 + length
            if (length < 2 || payloadEnd > jpeg.size) break

            // SOF0..SOF15 hold the frame dimensions (C4 = Huffman table,
            // C8 = JPEG extension, CC = arithmetic coding table, none are SOF).
            if (marker in 0xC0..0xCF && marker != 0xC4 && marker != 0xC8 && marker != 0xCC) {
                if (payloadStart + 5 <= payloadEnd) {
                    height = ((jpeg[payloadStart + 1].toInt() and 0xFF) shl 8) or
                        (jpeg[payloadStart + 2].toInt() and 0xFF)
                    width = ((jpeg[payloadStart + 3].toInt() and 0xFF) shl 8) or
                        (jpeg[payloadStart + 4].toInt() and 0xFF)
                }
            } else if (marker == 0xE1) {
                val payload = String(jpeg, payloadStart, payloadEnd - payloadStart, Charsets.ISO_8859_1)
                if (payload.startsWith("http://ns.adobe.com/xap/1.0/")) {
                    projectionType = tag(payload, "ProjectionType") ?: projectionType
                    fullWidth = tag(payload, "FullPanoWidthPixels")?.toIntOrNull() ?: fullWidth
                    fullHeight = tag(payload, "FullPanoHeightPixels")?.toIntOrNull() ?: fullHeight
                    heading = tag(payload, "PoseHeadingDegrees")?.toFloatOrNull() ?: heading
                } else if (payload.startsWith("Exif")) {
                    val tiff = jpeg.copyOfRange(payloadStart + 6, payloadEnd)
                    val position = readGps(tiff)
                    latitude = position?.first ?: latitude
                    longitude = position?.second ?: longitude
                }
            }
            offset = payloadEnd
        }

        if (width <= 0 || height <= 0) return null
        return Metadata(
            widthPx = width,
            heightPx = height,
            byteCount = jpeg.size,
            projectionType = projectionType,
            fullPanoWidthPx = fullWidth,
            fullPanoHeightPx = fullHeight,
            poseHeadingDegrees = heading,
            latitude = latitude,
            longitude = longitude
        )
    }

    /** Extracts `GPano:<name>="value"` from an XMP packet, namespace-agnostic. */
    private fun tag(xmp: String, name: String): String? {
        val match = Regex("""$name\s*=\s*"([^"]*)"""").find(xmp) ?: return null
        return match.groupValues[1].takeIf { it.isNotEmpty() }
    }

    /**
     * Walks a little-endian TIFF block to the GPS IFD and reads the four tags
     * Google's example lists: the two reference chars and the two rationals.
     */
    private fun readGps(tiff: ByteArray): Pair<Double, Double>? {
        if (tiff.size < 8) return null
        val littleEndian = tiff[0] == 'I'.code.toByte() && tiff[1] == 'I'.code.toByte()
        val u16: (Int) -> Int = { at ->
            if (at + 2 > tiff.size) -1
            else if (littleEndian) (tiff[at].toInt() and 0xFF) or ((tiff[at + 1].toInt() and 0xFF) shl 8)
            else ((tiff[at].toInt() and 0xFF) shl 8) or (tiff[at + 1].toInt() and 0xFF)
        }
        val u32: (Int) -> Long = { at ->
            if (at + 4 > tiff.size) -1L
            else {
                val b = LongArray(4) { i -> (tiff[at + i].toInt() and 0xFF).toLong() }
                if (littleEndian) b[0] or (b[1] shl 8) or (b[2] shl 16) or (b[3] shl 24)
                else (b[0] shl 24) or (b[1] shl 16) or (b[2] shl 8) or b[3]
            }
        }

        val ifd0 = u32(4).toInt()
        if (ifd0 <= 0 || ifd0 >= tiff.size) return null
        val gpsIfd = run {
            val count = u16(ifd0)
            if (count <= 0) return null
            var found = -1
            for (i in 0 until count) {
                val entry = ifd0 + 2 + i * 12
                if (entry + 12 > tiff.size) break
                if (u16(entry) == 0x8825) {
                    found = u32(entry + 8).toInt()
                    break
                }
            }
            found
        }
        if (gpsIfd <= 0 || gpsIfd >= tiff.size) return null

        val gpsCount = u16(gpsIfd)
        if (gpsCount <= 0) return null
        var latRef = 'N'
        var lonRef = 'E'
        var lat: Double? = null
        var lon: Double? = null

        for (i in 0 until gpsCount) {
            val entry = gpsIfd + 2 + i * 12
            if (entry + 12 > tiff.size) break
            val tagId = u16(entry)
            when (tagId) {
                0x0001 -> latRef = (u32(entry + 8) and 0xFF).toInt().toChar()
                0x0003 -> lonRef = (u32(entry + 8) and 0xFF).toInt().toChar()
                0x0002 -> lat = readDms(tiff, u32(entry + 8).toInt(), u16, u32)
                0x0004 -> lon = readDms(tiff, u32(entry + 8).toInt(), u16, u32)
            }
        }

        val latitude = lat?.let { if (latRef == 'S') -it else it }
        val longitude = lon?.let { if (lonRef == 'W') -it else it }
        if (latitude == null || longitude == null) return null
        return latitude to longitude
    }

    /** Three RATIONALs (degrees, minutes, seconds) to decimal degrees. */
    private fun readDms(
        tiff: ByteArray,
        offset: Int,
        u16: (Int) -> Int,
        u32: (Int) -> Long
    ): Double? {
        if (offset <= 0 || offset + 24 > tiff.size) return null
        var degrees = 0.0
        for (i in 0 until 3) {
            val numerator = u32(offset + i * 8).toDouble()
            val denominator = u32(offset + i * 8 + 4).toDouble()
            if (denominator == 0.0) return null
            degrees += when (i) {
                0 -> numerator / denominator
                1 -> numerator / denominator / 60.0
                else -> numerator / denominator / 3600.0
            }
        }
        return degrees
    }
}
