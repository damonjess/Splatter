package com.example.splatter.processor.panorama

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The stitcher is verified against a synthetic 360° scene: a colour field that
 * is a pure function of direction. Photos of that scene are rendered from known
 * camera rotations (the same way ARCore would hand them over), stitched, and
 * the resulting panorama is compared with the scene it was built from.
 *
 * Because both the render and the stitch are functions of the same direction
 * vector, any error in the projection maths, the blending, or the equirect
 * mapping shows up as a mismatched colour — not as a fuzzy image nobody can
 * assert on.
 */
class PanoramaStitcherTest {

    private val photoW = 240
    private val photoH = 160
    private val fx = 200f
    private val fy = 200f
    private val cx = (photoW - 1) / 2f
    private val cy = (photoH - 1) / 2f

    /** Ground truth scene: hue by azimuth, brightness by elevation. */
    private fun sceneColor(dirX: Float, dirY: Float, dirZ: Float): Int {
        val az = atan2(dirX.toDouble(), dirZ.toDouble())          // -pi..pi
        val el = atan2(-dirY.toDouble(), sqrt((dirX * dirX + dirZ * dirZ).toDouble()))
        val r = ((sin(az) + 1.0) * 127.0).toInt().coerceIn(0, 255)
        val g = ((cos(az * 2) + 1.0) * 127.0).toInt().coerceIn(0, 255)
        val b = ((el / (Math.PI / 2) + 1.0) * 127.0).toInt().coerceIn(0, 255)
        return (0xFF shl 24) or (r shl 16) or (g shl 8) or b
    }

    /** Renders one photo of the scene from the given camera rotation. */
    private fun renderPhoto(pose: FloatArray): PanoramaStitcher.Frame {
        val pixels = IntArray(photoW * photoH)
        for (v in 0 until photoH) {
            for (u in 0 until photoW) {
                val cX = (u - cx) / fx
                val cY = (v - cy) / fy
                val cZ = 1f
                // camera -> world (column-major rotation)
                val wX = pose[0] * cX + pose[4] * cY + pose[8] * cZ
                val wY = pose[1] * cX + pose[5] * cY + pose[9] * cZ
                val wZ = pose[2] * cX + pose[6] * cY + pose[10] * cZ
                pixels[v * photoW + u] = sceneColor(wX, wY, wZ)
            }
        }
        return PanoramaStitcher.Frame(pixels, photoW, photoH, pose, fx, fy, cx, cy)
    }

    /** Column-major camera-to-world rotation from yaw (Y) and pitch (X). */
    private fun pose(yawDeg: Float, pitchDeg: Float): FloatArray {
        val y = Math.toRadians(yawDeg.toDouble())
        val p = Math.toRadians(pitchDeg.toDouble())
        val cy = cos(y); val sy = sin(y)
        val cp = cos(p); val sp = sin(p)
        // R = Ry * Rx
        return floatArrayOf(
            cy.toFloat(), (sy * sp).toFloat(), (-sy * cp).toFloat(), 0f,
            0f, cp.toFloat(), sp.toFloat(), 0f,
            sy.toFloat(), (-cy * sp).toFloat(), (cy * cp).toFloat(), 0f,
            0f, 0f, 0f, 1f
        )
    }

    /** Photos around a full circle plus two pitch rings, like a real capture. */
    private fun ringPhotos(): List<PanoramaStitcher.Frame> {
        val frames = ArrayList<PanoramaStitcher.Frame>()
        for (pitch in listOf(0f, -35f, 35f, -65f, 65f)) {
            for (yaw in 0 until 360 step 30) {
                frames.add(renderPhoto(pose(yaw.toFloat(), pitch)))
            }
        }
        return frames
    }

    @Test
    fun stitchedPanoramaMatchesTheSceneItWasBuiltFrom() {
        val stitcher = PanoramaStitcher(outputWidth = 720, outputHeight = 360)
        val result = stitcher.stitch(ringPhotos())

        assertEquals(720, result.width)
        assertEquals(360, result.height)
        assertTrue("every frame should contribute, used=${result.usedFrames}", result.usedFrames > 50)

        // Compare a grid of output pixels with the scene as seen from the
        // panorama's reference frame (the first photo's pose).
        val reference = pose(0f, 0f)
        var worstError = 0.0
        var meanError = 0.0
        var samples = 0
        for (y in 20 until 360 - 20 step 10) {
            for (x in 0 until 720 step 10) {
                val lat = Math.PI / 2.0 - (y + 0.5) / 360 * Math.PI
                val lon = (x + 0.5) / 720 * 2.0 * Math.PI - Math.PI
                val dX = cos(lat) * sin(lon)
                val dY = -sin(lat)
                val dZ = cos(lat) * cos(lon)
                // Reference frame direction -> world
                val wX = reference[0] * dX + reference[4] * dY + reference[8] * dZ
                val wY = reference[1] * dX + reference[5] * dY + reference[9] * dZ
                val wZ = reference[2] * dX + reference[6] * dY + reference[10] * dZ
                val expected = sceneColor(wX.toFloat(), wY.toFloat(), wZ.toFloat())

                val got = result.pixels[y * 720 + x]
                val err = (0..2).maxOf { shift ->
                    abs(((got shr (shift * 8)) and 0xFF) - ((expected shr (shift * 8)) and 0xFF))
                }.toDouble()
                worstError = maxOf(worstError, err)
                meanError += err
                samples++
            }
        }
        meanError /= samples
        assertTrue("mean channel error $meanError should be small", meanError < 6.0)
        assertTrue("worst channel error $worstError is too large", worstError < 40.0)
    }

    @Test
    fun reportsHighCoverageForARingCapture() {
        val stitcher = PanoramaStitcher(outputWidth = 360, outputHeight = 180)
        val frames = ringPhotos()
        val coverage = stitcher.coverage(frames)
        assertTrue("coverage=$coverage", coverage > 0.9f)
    }

    @Test
    fun leavesNoBlackHolesEvenWhenTheTopAndBottomAreMissing() {
        // Only a horizontal ring: the zenith and nadir have no data at all, so
        // the hole filler has to invent them — Google Maps rejects panoramas
        // with visible gaps.
        val stitcher = PanoramaStitcher(outputWidth = 360, outputHeight = 180)
        val frames = (0 until 360 step 30).map { renderPhoto(pose(it.toFloat(), 0f)) }
        val result = stitcher.stitch(frames)

        val black = result.pixels.count { (it and 0xFFFFFF) == 0 }
        assertEquals("no pitch-black pixels should survive", 0, black)
    }

    @Test
    fun aSingleFrameStillProducesAResult() {
        val stitcher = PanoramaStitcher(outputWidth = 180, outputHeight = 90)
        val result = stitcher.stitch(listOf(renderPhoto(pose(0f, 0f))))
        assertTrue(result.coverage > 0.0f)
        assertTrue(result.coverage < 0.5f)
    }
}
