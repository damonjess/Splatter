package com.example.splatter.processor.panorama

import kotlin.math.atan2
import kotlin.math.asin
import kotlin.math.tan

/**
 * Renders a perspective view out of a stitched equirectangular image.
 *
 * This is the maths behind the in-app 360 viewer, kept free of Android types so
 * it can be unit tested: a straight window crop of an equirectangular image
 * looks wrong the moment you look away from the horizon (the poles stretch
 * horizontally no matter where you look), so each output pixel is instead cast
 * as a ray from the camera and sampled where that ray hits the sphere.
 *
 * Pixel coordinates follow the usual equirectangular convention:
 * `u = 0.5` is the centre of the image (longitude 0). Captured camera frames
 * use the opposite vertical image convention to the viewer, so the source row
 * is vertically flipped when sampled. Yaw 0 looks at the image centre,
 * positive yaw turns right (increasing longitude).
 */
class EquirectSampler(
    private val source: IntArray,
    private val sourceWidth: Int,
    private val sourceHeight: Int
) {

    init {
        require(sourceWidth > 0 && sourceHeight > 0) { "empty equirectangular source" }
        require(source.size >= sourceWidth * sourceHeight) {
            "source has ${source.size} pixels, expected ${sourceWidth * sourceHeight}"
        }
    }

    /**
     * Fills [dest] (row-major, [destWidth] × [destHeight]) with the view of the
     * sphere seen from yaw [yawRadians], pitch [pitchRadians], using
     * [horizontalFovRadians] across the destination width. The vertical field of
     * view follows from the destination aspect ratio, so the image is never
     * stretched.
     */
    fun render(
        dest: IntArray,
        destWidth: Int,
        destHeight: Int,
        yawRadians: Float,
        pitchRadians: Float,
        horizontalFovRadians: Float
    ) {
        require(dest.size >= destWidth * destHeight) {
            "destination has ${dest.size} pixels, expected ${destWidth * destHeight}"
        }
        val halfFov = horizontalFovRadians / 2f
        val tanHalfH = tan(halfFov.toDouble()).toFloat()
        val tanHalfV = tanHalfH * destHeight.toFloat() / destWidth.toFloat()

        val cosPitch = kotlin.math.cos(pitchRadians)
        val sinPitch = kotlin.math.sin(pitchRadians)
        val cosYaw = kotlin.math.cos(yawRadians)
        val sinYaw = kotlin.math.sin(yawRadians)

        var index = 0
        for (y in 0 until destHeight) {
            // +1 at the top row: screen Y grows downwards, latitude grows upwards.
            val cameraY = (1f - 2f * (y + 0.5f) / destHeight) * tanHalfV
            for (x in 0 until destWidth) {
                val cameraX = (2f * (x + 0.5f) / destWidth - 1f) * tanHalfH

                // Ray in camera space, then pitched and yawed into world space.
                // Pitch is a rotation about the camera's X axis taking forward
                // (0,0,1) to (0, sin p, cos p), so a positive pitch looks up; yaw
                // about Y takes forward to (sin y, 0, cos y), so a positive yaw
                // looks to the right — the same direction a rightward drag moves
                // the view.
                val px = cameraX
                val py = cameraY
                val pz = 1f

                val pitchedY = py * cosPitch + pz * sinPitch
                val pitchedZ = -py * sinPitch + pz * cosPitch

                val dx = px * cosYaw + pitchedZ * sinYaw
                val dz = -px * sinYaw + pitchedZ * cosYaw
                val dy = pitchedY

                val latitude = asin(dy.coerceIn(-1f, 1f))
                // Longitude already includes the yaw applied to the ray above.
                val longitude = atan2(dx, dz)

                dest[index++] = sample(longitude, latitude)
            }
        }
    }

    /** Nearest-neighbour lookup of a (longitude, latitude) direction. */
    private fun sample(longitudeRadians: Float, latitudeRadians: Float): Int {
        // The captured equirectangular frames run in the opposite horizontal
        // direction to the viewer's longitude convention. Flip the source
        // column so left and right are not mirrored in the 360 view.
        var u = 0.5f - longitudeRadians / TWO_PI
        u -= kotlin.math.floor(u) // wrap into [0, 1)
        // ARCore camera JPEGs are vertically inverted relative to the
        // viewer's +latitude-up convention. Keep sky at the top and ground at
        // the bottom by flipping the source row here.
        var v = 0.5f + latitudeRadians / PI
        v = v.coerceIn(0f, 1f)

        val sx = (u * sourceWidth).toInt().coerceIn(0, sourceWidth - 1)
        val sy = (v * sourceHeight).toInt().coerceIn(0, sourceHeight - 1)
        return source[sy * sourceWidth + sx]
    }

    /** The view direction in radians the given destination pixel looks at. */
    fun directionOf(
        x: Int,
        y: Int,
        destWidth: Int,
        destHeight: Int,
        yawRadians: Float,
        pitchRadians: Float,
        horizontalFovRadians: Float
    ): Pair<Float, Float> {
        val tanHalfH = tan((horizontalFovRadians / 2f).toDouble()).toFloat()
        val tanHalfV = tanHalfH * destHeight.toFloat() / destWidth.toFloat()
        val cameraX = (2f * (x + 0.5f) / destWidth - 1f) * tanHalfH
        val cameraY = (1f - 2f * (y + 0.5f) / destHeight) * tanHalfV

        val cosPitch = kotlin.math.cos(pitchRadians)
        val sinPitch = kotlin.math.sin(pitchRadians)
        val pitchedY = cameraY * cosPitch + sinPitch
        val pitchedZ = -cameraY * sinPitch + cosPitch
        val cosYaw = kotlin.math.cos(yawRadians)
        val sinYaw = kotlin.math.sin(yawRadians)
        val dx = cameraX * cosYaw + pitchedZ * sinYaw
        val dz = -cameraX * sinYaw + pitchedZ * cosYaw
        val latitude = asin(pitchedY.coerceIn(-1f, 1f))
        val longitude = atan2(dx, dz)
        return longitude to latitude
    }

    private companion object {
        const val PI = kotlin.math.PI.toFloat()
        const val TWO_PI = (2.0 * kotlin.math.PI).toFloat()
    }
}
