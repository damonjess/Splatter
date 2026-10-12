package com.example.splatter.processor.panorama

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Stitches photos taken from one spot into an equirectangular 360° panorama.
 *
 * Unlike a feature-based stitcher, this one *knows* where every photo was
 * taken from: ARCore supplies a camera rotation per frame, so each photo can be
 * projected onto a sphere directly. That removes the two things that make
 * phone panorama stitching fail — matching features across textureless or
 * moving scenes, and estimating rotations from those matches. Here the only
 * input is geometry.
 *
 * For every output pixel the equirectangular grid gives a world direction; that
 * direction is mapped back into each source frame; the frame's pixel is sampled
 * bilinearly and blended with a weight that falls off towards the frame's edge
 * and away from its optical axis, so seams feather instead of showing edges.
 *
 * Pure Kotlin (no Android APIs) so it is covered by JVM unit tests; the caller
 * supplies ARGB pixel arrays in `Bitmap.getPixels` layout.
 */
class PanoramaStitcher(
    private val outputWidth: Int = 4096,
    private val outputHeight: Int = 2048,
    /** Output pixels are sampled from frames whose ray lands this far inside the image. */
    private val edgeMarginPx: Float = 4f,
    /** Rows accumulated at a time; bounds peak memory regardless of output size. */
    private val bandRows: Int = 256
) {

    private companion object {
        /** Neutral fill for the (rare) pixels no photo covered. */
        const val HOLE_COLOR = 0xFF101014.toInt()
    }

    /** One source photo: pixels plus the camera pose that produced it. */
    class Frame(
        val pixels: IntArray,
        val width: Int,
        val height: Int,
        /** Column-major camera-to-world rotation in pipeline space (+Z forward, y down). */
        val pose: FloatArray,
        val fx: Float,
        val fy: Float,
        val cx: Float,
        val cy: Float
    )

    class Result(
        val pixels: IntArray,
        val width: Int,
        val height: Int,
        /** Share of output pixels that received at least one valid sample (0..1). */
        val coverage: Float,
        /** Frames that contributed at least one pixel. */
        val usedFrames: Int
    )

    /**
     * Stitches [frames] into an equirectangular image in the coordinate frame of
     * the FIRST frame (its optical axis points at the panorama's centre).
     */
    fun stitch(frames: List<Frame>, onProgress: ((Int) -> Unit)? = null): Result {
        require(frames.isNotEmpty()) { "no frames to stitch" }

        // A 4096x2048 panorama is 8.4 M pixels. Accumulating three floats plus a
        // weight for all of them at once is ~170 MB, which a phone will not give
        // us; accumulating one horizontal band at a time keeps the working set at
        // a few megabytes for exactly the same number of samples.
        val out = IntArray(outputWidth * outputHeight)
        val weightSum = FloatArray(outputWidth * outputHeight)
        val used = BooleanArray(frames.size)

        var rowStart = 0
        val totalBands = (outputHeight + bandRows - 1) / bandRows
        var completedBands = 0
        while (rowStart < outputHeight) {
            val rowEnd = (rowStart + bandRows).coerceAtMost(outputHeight)
            val bandSize = (rowEnd - rowStart) * outputWidth
            val accR = FloatArray(bandSize)
            val accG = FloatArray(bandSize)
            val accB = FloatArray(bandSize)
            val bandWeight = FloatArray(bandSize)

            for ((i, frame) in frames.withIndex()) {
                if (accumulate(frame, rowStart, rowEnd, accR, accG, accB, bandWeight)) used[i] = true
            }

            for (y in rowStart until rowEnd) {
                for (x in 0 until outputWidth) {
                    val b = (y - rowStart) * outputWidth + x
                    val o = y * outputWidth + x
                    val w = bandWeight[b]
                    weightSum[o] = w
                    out[o] = if (w > 1e-6f) {
                        val inv = 1f / w
                        (0xFF shl 24) or
                            ((accR[b] * inv * 255f).toInt().coerceIn(0, 255) shl 16) or
                            ((accG[b] * inv * 255f).toInt().coerceIn(0, 255) shl 8) or
                            (accB[b] * inv * 255f).toInt().coerceIn(0, 255)
                    } else {
                        HOLE_COLOR
                    }
                }
            }
            rowStart = rowEnd
            completedBands++
            onProgress?.invoke((completedBands * 50 / totalBands).coerceIn(1, 50))
        }

        val covered = weightSum.count { it > 1e-6f }
        fillHoles(out, weightSum, outputWidth, outputHeight)
        return Result(out, outputWidth, outputHeight, covered.toFloat() / out.size, used.count { it })
    }

    private fun accumulate(
        frame: Frame,
        rowStart: Int,
        rowEnd: Int,
        accR: FloatArray,
        accG: FloatArray,
        accB: FloatArray,
        weightSum: FloatArray
    ): Boolean {
        val m = frame.pose
        val w = frame.width
        val h = frame.height
        val fovHalfTan = 0.5f * w / frame.fx

        var touched = false
        // Equirectangular grid -> world directions, expressed in the camera frame
        // of the first photo (the panorama's reference frame).
        for (y in rowStart until rowEnd) {
            val lat = Math.PI / 2.0 - (y + 0.5) / outputHeight * Math.PI   // +pi/2 up
            val cosLat = cos(lat)
            val sinLat = sin(lat)
            for (x in 0 until outputWidth) {
                val lon = (x + 0.5) / outputWidth * 2.0 * Math.PI - Math.PI
                val dirX = (cosLat * sin(lon)).toFloat()
                val dirY = (-sinLat).toFloat()          // y down in our camera convention
                val dirZ = (cosLat * cos(lon)).toFloat()

                // world = R * dir ; then world -> this camera = R^T * world
                val cX = m[0] * dirX + m[1] * dirY + m[2] * dirZ
                val cY = m[4] * dirX + m[5] * dirY + m[6] * dirZ
                val cZ = m[8] * dirX + m[9] * dirY + m[10] * dirZ
                if (cZ <= 1e-3f) continue

                val u = frame.fx * cX / cZ + frame.cx
                val v = frame.fy * cY / cZ + frame.cy
                if (u < edgeMarginPx || u > w - 1 - edgeMarginPx) continue
                if (v < edgeMarginPx || v > h - 1 - edgeMarginPx) continue

                // Weight: feather towards the frame edge, and taper for rays far
                // off the optical axis (they are stretched and noisier).
                val ux = (u / w - 0.5f) * 2f
                val vy = (v / h - 0.5f) * 2f
                val radius = sqrt(ux * ux + vy * vy)
                val feather = (1f - radius).coerceIn(0.05f, 1f)
                val offAxis = 1f / (1f + 2f * abs(cX / cZ) / fovHalfTan + 2f * abs(cY / cZ) / fovHalfTan)
                val weight = feather * feather * offAxis

                val color = sampleBilinear(frame.pixels, w, h, u, v)
                val idx = (y - rowStart) * outputWidth + x
                val r = ((color shr 16) and 0xFF) / 255f
                val g = ((color shr 8) and 0xFF) / 255f
                val b = (color and 0xFF) / 255f
                accR[idx] += r * weight
                accG[idx] += g * weight
                accB[idx] += b * weight
                weightSum[idx] += weight
                touched = true
            }
        }
        return touched
    }

    private fun sampleBilinear(pixels: IntArray, w: Int, h: Int, u: Float, v: Float): Int {
        val x0 = u.toInt()
        val y0 = v.toInt()
        val x1 = (x0 + 1).coerceAtMost(w - 1)
        val y1 = (y0 + 1).coerceAtMost(h - 1)
        val fx = u - x0
        val fy = v - y0

        val c00 = pixels[y0 * w + x0]
        val c10 = pixels[y0 * w + x1]
        val c01 = pixels[y1 * w + x0]
        val c11 = pixels[y1 * w + x1]

        val r = bilinear(c00, c10, c01, c11, fx, fy, 16)
        val g = bilinear(c00, c10, c01, c11, fx, fy, 8)
        val b = bilinear(c00, c10, c01, c11, fx, fy, 0)
        return (0xFF shl 24) or (r shl 16) or (g shl 8) or b
    }

    /** Bilinear interpolation of one 8-bit channel (`shift` = 16 red, 8 green, 0 blue). */
    private fun bilinear(
        c00: Int, c10: Int, c01: Int, c11: Int,
        fx: Float, fy: Float, shift: Int
    ): Int {
        fun ch(c: Int) = ((c shr shift) and 0xFF).toFloat()
        val top = ch(c00) + (ch(c10) - ch(c00)) * fx
        val bottom = ch(c01) + (ch(c11) - ch(c01)) * fx
        return (top + (bottom - top) * fy).toInt().coerceIn(0, 255)
    }

    /**
     * Fills pixels no frame saw by repeatedly averaging covered neighbours, so a
     * panorama with a small empty patch (usually straight up or straight down)
     * has no black holes — Google Maps rejects panoramas with obvious gaps.
     */
    private fun fillHoles(
        out: IntArray,
        weightSum: FloatArray,
        width: Int,
        height: Int
    ) {
        var holes = pixelsHoles(weightSum)
        if (holes == 0) return
        val covered = BooleanArray(out.size) { weightSum[it] > 1e-6f }
        var iterations = 0
        while (holes > 0 && iterations < 64) {
            iterations++
            val nextCovered = covered.copyOf()
            var filledThisPass = 0
            for (y in 0 until height) {
                val rowUp = if (y > 0) y - 1 else height - 1
                val rowDown = if (y < height - 1) y + 1 else 0
                for (x in 0 until width) {
                    val i = y * width + x
                    if (covered[i]) continue
                    val left = y * width + (if (x > 0) x - 1 else width - 1)
                    val right = y * width + (if (x < width - 1) x + 1 else 0)
                    val up = rowUp * width + x
                    val down = rowDown * width + x
                    var r = 0; var g = 0; var b = 0; var n = 0
                    for (j in intArrayOf(left, right, up, down)) {
                        if (covered[j]) {
                            val c = out[j]
                            r += (c shr 16) and 0xFF
                            g += (c shr 8) and 0xFF
                            b += c and 0xFF
                            n++
                        }
                    }
                    if (n > 0) {
                        out[i] = (0xFF shl 24) or
                            (((r / n) and 0xFF) shl 16) or
                            (((g / n) and 0xFF) shl 8) or
                            ((b / n) and 0xFF)
                        nextCovered[i] = true
                        filledThisPass++
                    }
                }
            }
            if (filledThisPass == 0) break
            System.arraycopy(nextCovered, 0, covered, 0, covered.size)
            holes -= filledThisPass
        }
    }

    private fun pixelsHoles(weightSum: FloatArray): Int {
        var holes = 0
        for (value in weightSum) if (value <= 1e-6f) holes++
        return holes
    }

    /**
     * Coverage of the sphere by a set of frames, as a share of the equirect grid.
     * Used by the capture UI to show how much of the 360° is still missing.
     */
    fun coverage(frames: List<Frame>): Float {
        val seen = BooleanArray(outputWidth * outputHeight)
        for (frame in frames) {
            val m = frame.pose
            val w = frame.width
            val h = frame.height
            for (y in 0 until outputHeight) {
                val lat = Math.PI / 2.0 - (y + 0.5) / outputHeight * Math.PI
                val cosLat = cos(lat)
                val sinLat = sin(lat)
                for (x in 0 until outputWidth) {
                    val i = y * outputWidth + x
                    if (seen[i]) continue
                    val lon = (x + 0.5) / outputWidth * 2.0 * Math.PI - Math.PI
                    val dirX = (cosLat * sin(lon)).toFloat()
                    val dirY = (-sinLat).toFloat()
                    val dirZ = (cosLat * cos(lon)).toFloat()
                    val cX = m[0] * dirX + m[1] * dirY + m[2] * dirZ
                    val cY = m[4] * dirX + m[5] * dirY + m[6] * dirZ
                    val cZ = m[8] * dirX + m[9] * dirY + m[10] * dirZ
                    if (cZ <= 1e-3f) continue
                    val u = frame.fx * cX / cZ + frame.cx
                    val v = frame.fy * cY / cZ + frame.cy
                    if (u < 0f || u > w - 1f || v < 0f || v > h - 1f) continue
                    seen[i] = true
                }
            }
        }
        return seen.count { it }.toFloat() / seen.size
    }
}
