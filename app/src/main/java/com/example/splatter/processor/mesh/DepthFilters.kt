package com.example.splatter.processor.mesh

import kotlin.math.roundToInt

/**
 * Depth-map pre-processing for the Photo Mesh pipeline. Pure Kotlin — no
 * Android dependencies, covered by unit tests.
 */
object DepthFilters {

    /**
     * 3x3 median filter over a 16-bit millimetre depth map (0 = invalid).
     * Removes single-pixel depth speckle that ARCore raw depth produces,
     * which would otherwise become floating mesh fragments and break the
     * depth-continuity check during triangulation.
     */
    fun medianFilter3x3(depth: ShortArray, width: Int, height: Int): ShortArray {
        if (width < 3 || height < 3) return depth.copyOf()
        val out = ShortArray(depth.size)
        val window = IntArray(9)

        for (v in 0 until height) {
            for (u in 0 until width) {
                var n = 0
                for (dv in -1..1) {
                    val vv = v + dv
                    if (vv < 0 || vv >= height) continue
                    val rowBase = vv * width
                    for (du in -1..1) {
                        val uu = u + du
                        if (uu < 0 || uu >= width) continue
                        val d = depth[rowBase + uu].toInt() and 0xFFFF
                        if (d > 0) window[n++] = d
                    }
                }
                out[v * width + u] = when (n) {
                    0 -> 0
                    else -> {
                        java.util.Arrays.sort(window, 0, n)
                        window[n / 2].toShort()
                    }
                }
            }
        }
        return out
    }

    /**
     * Chooses a sampling stride for triangulating a depth map so that one
     * quad edge spans roughly [targetMeters] of world space (default 1.5x the
     * fusion voxel). If the stride is too small relative to the voxel size,
     * neighbouring quad corners collapse into the same voxel and triangles
     * are rejected as degenerate — which punches holes in the mesh.
     *
     * @param medianDepthM typical valid depth of the frame, in metres
     * @param fx horizontal focal length in depth-image pixels
     * @param targetMeters desired world-space quad edge length
     */
    fun suggestStride(medianDepthM: Float, fx: Float, targetMeters: Float): Int {
        if (fx <= 0f || medianDepthM <= 0f) return 1
        val spacingM = medianDepthM / fx
        // ceil, never round: undershooting leaves adjacent quad corners
        // inside the same voxel, which turns triangles into degenerates
        val stride = kotlin.math.ceil(targetMeters / spacingM).toInt()
        return stride.coerceIn(1, 8)
    }

    /** Median of the valid depth values (mm); 0 if none are valid. */
    fun medianDepthMm(depth: ShortArray, sampleStep: Int = 7): Int {
        val values = mutableListOf<Int>()
        var i = 0
        while (i < depth.size) {
            val d = depth[i].toInt() and 0xFFFF
            if (d > 0) values.add(d)
            i += sampleStep
        }
        if (values.isEmpty()) return 0
        values.sort()
        return values[values.size / 2]
    }
}
