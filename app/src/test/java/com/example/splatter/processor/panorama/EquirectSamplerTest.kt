package com.example.splatter.processor.panorama

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI

/**
 * The viewer's maths is the one part of the 360 feature that can be checked
 * exactly, so these tests pin down which direction each output pixel looks at
 * and which source pixel it therefore gets.
 *
 * Odd destination sizes are used throughout so that there is a pixel whose
 * centre sits exactly on the optical axis — with an even size every pixel is
 * half a pixel (a few degrees) off, which is real behaviour rather than a bug.
 */
class EquirectSamplerTest {

    private val fov90 = (PI / 2).toFloat()

    /** 360 × 180 equirect, one distinct grey level per 30° longitude band. */
    private fun bandedSource(): EquirectSampler {
        val w = 360
        val h = 180
        val pixels = IntArray(w * h)
        for (y in 0 until h) {
            for (x in 0 until w) {
                val band = x / 30 // 12 bands of 30° each
                pixels[y * w + x] = 0xFF000000.toInt() or (band * 20 shl 16)
            }
        }
        return EquirectSampler(pixels, w, h)
    }

    /** Grey level of one destination pixel's high byte (the band id). */
    private fun bandOf(pixel: Int): Int = (pixel shr 16) and 0xFF

    private fun look(
        sampler: EquirectSampler,
        yaw: Float = 0f,
        pitch: Float = 0f
    ): Pair<Float, Float> = sampler.directionOf(
        x = 8, y = 4, destWidth = 17, destHeight = 9,
        yawRadians = yaw, pitchRadians = pitch, horizontalFovRadians = fov90
    )

    @Test
    fun theOpticalAxisLooksStraightAhead() {
        val (longitude, latitude) = look(bandedSource())
        assertEquals(0.0, longitude.toDouble(), 1e-4)
        assertEquals(0.0, latitude.toDouble(), 1e-4)
    }

    @Test
    fun yawTurnsTheViewToTheRight() {
        val (longitude, _) = look(bandedSource(), yaw = (PI / 2).toFloat())
        // A positive yaw is a rightward look, onto the +X side of the sphere.
        assertEquals(PI / 2, longitude.toDouble(), 1e-3)
    }

    @Test
    fun pitchLooksUpAndDown() {
        val sampler = bandedSource()
        assertEquals(PI / 2, look(sampler, pitch = (PI / 2).toFloat()).second.toDouble(), 1e-3)
        assertEquals(-PI / 2, look(sampler, pitch = (-PI / 2).toFloat()).second.toDouble(), 1e-3)
    }

    @Test
    fun aFullTurnIsTheSameView() {
        val sampler = bandedSource()
        val (longitude, _) = look(sampler, yaw = (2 * PI).toFloat())
        assertEquals(0.0, longitude.toDouble(), 1e-3)
    }

    @Test
    fun centreOfAnUnrotatedViewSamplesTheCentreOfTheImage() {
        val sampler = bandedSource()
        val dest = IntArray(17 * 9)
        sampler.render(dest, 17, 9, 0f, 0f, fov90)
        // Centre pixel looks at longitude 0 → u = 0.5 → column 180 → band 6.
        assertEquals(6 * 20, bandOf(dest[4 * 17 + 8]))
    }

    @Test
    fun lookingRightReachesTheLastBand() {
        val sampler = bandedSource()
        val dest = IntArray(17 * 9)
        // 180° right is the seam: u wraps back to 0 → band 0.
        sampler.render(dest, 17, 9, (PI).toFloat(), 0f, fov90)
        assertEquals(0, bandOf(dest[4 * 17 + 8]))
    }

    /** 360 × 180 equirect where the grey level encodes the latitude band. */
    private fun rowBandedSource(): EquirectSampler {
        val w = 360
        val h = 180
        val pixels = IntArray(w * h)
        for (y in 0 until h) {
            for (x in 0 until w) {
                pixels[y * w + x] = 0xFF000000.toInt() or ((y / 6) shl 16)
            }
        }
        return EquirectSampler(pixels, w, h)
    }

    @Test
    fun pitchChoosesWhichLatitudeRowIsSampled() {
        val sampler = rowBandedSource()
        val dest = IntArray(17 * 9)

        // 80° down: v = 0.5 + 80/180 of the way down the image.
        sampler.render(dest, 17, 9, 0f, -(80f * PI.toFloat() / 180f), fov90)
        assertEquals(
            ((0.5 + 80.0 / 180.0) * 180).toInt() / 6,
            bandOf(dest[4 * 17 + 8])
        )

        // 80° up: the mirror image, near the top of the image.
        sampler.render(dest, 17, 9, 0f, 80f * PI.toFloat() / 180f, fov90)
        assertEquals(
            ((0.5 - 80.0 / 180.0) * 180).toInt() / 6,
            bandOf(dest[4 * 17 + 8])
        )
    }

    @Test
    fun wideFieldOfViewSpansTheRequestedAngle() {
        val sampler = bandedSource()
        val wide = 120f * PI.toFloat() / 180f
        val left = sampler.directionOf(
            x = 0, y = 4, destWidth = 64, destHeight = 9,
            yawRadians = 0f, pitchRadians = 0f, horizontalFovRadians = wide
        ).first
        val right = sampler.directionOf(
            x = 63, y = 4, destWidth = 64, destHeight = 9,
            yawRadians = 0f, pitchRadians = 0f, horizontalFovRadians = wide
        ).first
        assertEquals((-wide / 2).toDouble(), left.toDouble(), 0.02)
        assertEquals((wide / 2).toDouble(), right.toDouble(), 0.02)
        assertTrue("edges must straddle the forward direction", left < 0f && right > 0f)
    }

    @Test
    fun polePixelsClampToTheImageEdges() {
        val sampler = bandedSource()
        val dest = IntArray(8 * 4)
        // Repeated full turns of pitch cannot sample outside the source, and
        // must not throw or leave holes.
        sampler.render(dest, 8, 4, 0f, (PI * 4).toFloat(), fov90)
        assertTrue(dest.all { it != 0 })
    }

    @Test
    fun destinationMustBeLargeEnough() {
        val sampler = bandedSource()
        var threw = false
        try {
            sampler.render(IntArray(4), 8, 8, 0f, 0f, fov90)
        } catch (_: IllegalArgumentException) {
            threw = true
        }
        assertTrue("a short destination buffer must be rejected", threw)
    }
}
