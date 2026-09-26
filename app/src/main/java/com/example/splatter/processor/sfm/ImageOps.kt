package com.example.splatter.processor.sfm

/**
 * Pure-Kotlin image operations for the SfM pipeline.
 *
 * All functions operate on ARGB IntArrays (Bitmap.getPixels layout) and
 * grayscale FloatArrays, so they are unit-testable on the JVM with no
 * Android dependencies.
 */
object ImageOps {

    /** ITU-R BT.601 luma, 0..255. */
    fun toGrayscale(pixels: IntArray): FloatArray {
        val gray = FloatArray(pixels.size)
        for (i in pixels.indices) {
            val c = pixels[i]
            val r = (c ushr 16) and 0xFF
            val g = (c ushr 8) and 0xFF
            val b = c and 0xFF
            gray[i] = 0.299f * r + 0.587f * g + 0.114f * b
        }
        return gray
    }

    /**
     * 3x3 census transform: each pixel becomes a 9-bit signature of
     * "neighbour darker than centre" comparisons. Pixels too close to the
     * border get all-zero signatures (never match).
     */
    fun census3x3(gray: FloatArray, width: Int, height: Int): IntArray {
        val out = IntArray(gray.size)
        for (v in 1 until height - 1) {
            for (u in 1 until width - 1) {
                val i = v * width + u
                val center = gray[i]
                var sig = 0
                var bit = 0
                for (dv in -1..1) {
                    for (du in -1..1) {
                        if (dv == 0 && du == 0) continue
                        if (gray[i + dv * width + du] < center) sig = sig or (1 shl bit)
                        bit++
                    }
                }
                out[i] = sig
            }
        }
        return out
    }

    /** 3x3 box blur — cheap smoothing used before matching. */
    fun boxBlur3x3(src: FloatArray, width: Int, height: Int): FloatArray {
        val out = FloatArray(src.size)
        for (v in 0 until height) {
            for (u in 0 until width) {
                var sum = 0f
                var count = 0
                for (dv in -1..1) {
                    val vv = v + dv
                    if (vv < 0 || vv >= height) continue
                    for (du in -1..1) {
                        val uu = u + du
                        if (uu < 0 || uu >= width) continue
                        sum += src[vv * width + uu]
                        count++
                    }
                }
                out[v * width + u] = sum / count
            }
        }
        return out
    }

    /**
     * Downsample an ARGB image by an integer factor using box averaging.
     * Output is (width / factor) x (height / factor).
     */
    fun downsample(pixels: IntArray, width: Int, height: Int, factor: Int): Triple<IntArray, Int, Int> {
        require(factor >= 1)
        if (factor == 1) return Triple(pixels.copyOf(), width, height)
        val outW = width / factor
        val outH = height / factor
        val out = IntArray(outW * outH)
        for (v in 0 until outH) {
            for (u in 0 until outW) {
                var r = 0; var g = 0; var b = 0; var count = 0
                val v0 = v * factor
                val u0 = u * factor
                for (dv in 0 until factor) {
                    val vv = v0 + dv
                    if (vv >= height) continue
                    for (du in 0 until factor) {
                        val uu = u0 + du
                        if (uu >= width) continue
                        val c = pixels[vv * width + uu]
                        r += (c ushr 16) and 0xFF
                        g += (c ushr 8) and 0xFF
                        b += c and 0xFF
                        count++
                    }
                }
                if (count == 0) count = 1
                out[v * outW + u] = (0xFF shl 24) or ((r / count) shl 16) or ((g / count) shl 8) or (b / count)
            }
        }
        return Triple(out, outW, outH)
    }

    /** Mean and variance of a grayscale image. */
    fun meanAndVariance(gray: FloatArray): Pair<Float, Float> {
        if (gray.isEmpty()) return 0f to 0f
        var mean = 0f
        for (v in gray) mean += v
        mean /= gray.size
        var acc = 0f
        for (v in gray) {
            val d = v - mean
            acc += d * d
        }
        return mean to acc / gray.size
    }

    /** Normalized cross-correlation of two equal-length patches (0..1, higher is better). */
    fun ncc(a: FloatArray, b: FloatArray): Float {
        if (a.size != b.size || a.isEmpty()) return 0f
        var ma = 0f; var mb = 0f
        for (i in a.indices) { ma += a[i]; mb += b[i] }
        ma /= a.size; mb /= b.size
        var num = 0f; var da = 0f; var db = 0f
        for (i in a.indices) {
            val x = a[i] - ma
            val y = b[i] - mb
            num += x * y
            da += x * x
            db += y * y
        }
        val denom = kotlin.math.sqrt(da * db)
        return if (denom > 1e-6f) (num / denom).coerceIn(0f, 1f) else 0f
    }
}
