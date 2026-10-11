package com.example.splatter.processor.panorama

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Walks a capture all the way to the artifact that gets handed to Google Maps:
 * render frames of a known scene from known camera rotations, stitch them, JPEG
 * encode them, write the GPano + GPS metadata, then grade the resulting bytes
 * with the same code the app shows the user.
 *
 * This is the test that answers "is the default configuration publishable?" —
 * it uses the production output size, so if the stitcher's defaults ever drop
 * below Google's 7.5 MP minimum or stop being 2:1, it fails here rather than on
 * a phone after a walk in the rain.
 *
 * It is the slowest test in the suite (a full-resolution stitch) and is kept
 * separate from the fast stitcher tests for that reason.
 */
class PanoramaPublishPipelineTest {

    private val photoW = 240
    private val photoH = 160
    private val fx = 200f
    private val fy = 200f
    private val cx = (photoW - 1) / 2f
    private val cy = (photoH - 1) / 2f

    /** Same ground-truth scene as the stitcher tests: colour is a function of direction. */
    private fun sceneColor(dirX: Float, dirY: Float, dirZ: Float): Int {
        val az = atan2(dirX.toDouble(), dirZ.toDouble())
        val el = atan2(-dirY.toDouble(), sqrt((dirX * dirX + dirZ * dirZ).toDouble()))
        val r = ((sin(az) + 1.0) * 127.0).toInt().coerceIn(0, 255)
        val g = ((cos(az * 2) + 1.0) * 127.0).toInt().coerceIn(0, 255)
        val b = ((el / (PI / 2) + 1.0) * 127.0).toInt().coerceIn(0, 255)
        return (0xFF shl 24) or (r shl 16) or (g shl 8) or b
    }

    private fun pose(yawDeg: Float, pitchDeg: Float): FloatArray {
        val y = Math.toRadians(yawDeg.toDouble())
        val p = Math.toRadians(pitchDeg.toDouble())
        val cy = cos(y); val sy = sin(y)
        val cp = cos(p); val sp = sin(p)
        return floatArrayOf(
            cy.toFloat(), (sy * sp).toFloat(), (-sy * cp).toFloat(), 0f,
            0f, cp.toFloat(), sp.toFloat(), 0f,
            sy.toFloat(), (-cy * sp).toFloat(), (cy * cp).toFloat(), 0f,
            0f, 0f, 0f, 1f
        )
    }

    private fun renderPhoto(rotation: FloatArray): PanoramaStitcher.Frame {
        val pixels = IntArray(photoW * photoH)
        for (v in 0 until photoH) {
            for (u in 0 until photoW) {
                val cX = (u - cx) / fx
                val cY = (v - cy) / fy
                val wX = rotation[0] * cX + rotation[4] * cY + rotation[8]
                val wY = rotation[1] * cX + rotation[5] * cY + rotation[9]
                val wZ = rotation[2] * cX + rotation[6] * cY + rotation[10]
                pixels[v * photoW + u] = sceneColor(wX, wY, wZ)
            }
        }
        return PanoramaStitcher.Frame(pixels, photoW, photoH, rotation, fx, fy, cx, cy)
    }

    /** A sweep of the sphere: three pitch rings of 30°-spaced yaws. */
    private fun sweep(): List<PanoramaStitcher.Frame> {
        val frames = ArrayList<PanoramaStitcher.Frame>()
        for (pitch in listOf(0f, -40f, 40f, -70f, 70f)) {
            for (yaw in 0 until 360 step 45) {
                frames.add(renderPhoto(pose(yaw.toFloat(), pitch)))
            }
        }
        return frames
    }

    private fun encodeJpeg(argb: IntArray, width: Int, height: Int): ByteArray {
        val image = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
        image.setRGB(0, 0, width, height, argb, 0, width)
        return ByteArrayOutputStream().use { out ->
            ImageIO.write(image, "jpg", out)
            out.toByteArray()
        }
    }

    @Test
    fun theConfiguredOutputClearsGooglesMinimum() {
        val pixels = PanoramaProcessor.OUTPUT_WIDTH.toLong() * PanoramaProcessor.OUTPUT_HEIGHT
        assertTrue(
            "the app stitches at $pixels px, below Google's ${PanoramaReadiness.MIN_PIXELS} px",
            pixels >= PanoramaReadiness.MIN_PIXELS
        )
        assertEquals(
            "a panorama must be 2:1",
            2.0,
            PanoramaProcessor.OUTPUT_WIDTH.toDouble() / PanoramaProcessor.OUTPUT_HEIGHT.toDouble(),
            1e-9
        )
    }

    @Test
    fun aStitchedCaptureBecomesAPublishableFile() {
        val stitcher = PanoramaStitcher(
            PanoramaProcessor.OUTPUT_WIDTH,
            PanoramaProcessor.OUTPUT_HEIGHT
        )
        val frames = sweep()
        assertTrue("the sweep should be mostly covered", stitcher.coverage(frames) > 0.5f)

        val result = stitcher.stitch(frames)
        assertEquals(PanoramaProcessor.OUTPUT_WIDTH, result.width)
        assertEquals(PanoramaProcessor.OUTPUT_HEIGHT, result.height)

        val jpeg = encodeJpeg(result.pixels, result.width, result.height)
        val deliverable = PanoramaMetadata.insert(
            jpeg = jpeg,
            xmp = PanoramaMetadata.xmpPacket(result.width, result.height, headingDegrees = 137.5f),
            latitude = 51.500729,
            longitude = -0.124625
        )

        val report = PanoramaReadiness.evaluate(deliverable)
        assertTrue("the stitched file should be gradeable", report != null)
        report!!
        assertTrue(
            "the shipped pipeline produced a file Google would reject: " +
                "${report.summary} (${report.blocking.joinToString { it.label }})",
            report.ready
        )
        assertEquals(137.5f, report.metadata.poseHeadingDegrees!!, 1e-3f)
        assertEquals(51.500729, report.metadata.latitude!!, 1e-5)
        assertEquals(2.0, report.metadata.widthPx.toDouble() / report.metadata.heightPx, 1e-9)
    }
}
