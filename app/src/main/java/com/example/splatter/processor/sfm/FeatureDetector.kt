package com.example.splatter.processor.sfm

/**
 * Corner features detected in a single (downscaled) image.
 *
 * [positions] is a flat xy array: (x0, y0, x1, y1, ...). Each feature keeps
 * a 9-bit census signature and a small grayscale patch for NCC scoring, so
 * the matcher can run without touching the image again.
 */
class FeatureList(
    val positions: FloatArray,
    val census: IntArray,
    val patches: FloatArray,
    val patchSize: Int,
    val imageWidth: Int,
    val imageHeight: Int
) {
    val count: Int get() = census.size

    fun x(i: Int): Float = positions[2 * i]
    fun y(i: Int): Float = positions[2 * i + 1]
}

/**
 * Corner detection for SfM matching — Shi-Tomasi ("good features to track")
 * minimum-eigenvalue scoring on the structure tensor, plus non-maximum
 * suppression and a uniform occupancy grid so features spread over the whole
 * image instead of clustering on one textured area.
 *
 * Pure Kotlin — no Android dependencies.
 */
object FeatureDetector {

    /**
     * Detect up to [maxFeatures] corners in [gray] ([width] x [height]).
     *
     * @param qualityThreshold corners must score at least this fraction of
     *        the strongest corner's score (0..1)
     * @param gridCells occupancy grid per axis; at most one feature per cell
     * @param patchSize NCC patch half-extent stored per feature
     */
    fun detect(
        gray: FloatArray,
        width: Int,
        height: Int,
        maxFeatures: Int = 512,
        qualityThreshold: Float = 0.08f,
        gridCells: Int = 8,
        patchSize: Int = 3
    ): FeatureList {
        require(width >= 8 && height >= 8) { "image too small for corner detection" }
        require(patchSize >= 2)

        // Structure tensor per pixel from central-difference gradients
        val n = width * height
        val score = FloatArray(n)
        var maxScore = 0f

        for (v in 3 until height - 3) {
            for (u in 3 until width - 3) {
                val i = v * width + u
                val ix = (gray[i + 1] - gray[i - 1]) * 0.5f
                val iy = (gray[i + width] - gray[i - width]) * 0.5f

                // Sum over a 5x5 window (box filter is fine for corner ranking)
                var sxx = 0f; var syy = 0f; var sxy = 0f
                for (dv in -2..2) {
                    for (du in -2..2) {
                        val j = i + dv * width + du
                        val gx = gray[j + 1] - gray[j - 1]
                        val gy = gray[j + width] - gray[j - width]
                        sxx += gx * gx
                        syy += gy * gy
                        sxy += gx * gy
                    }
                }

                // Shi-Tomasi score: smaller eigenvalue of the 2x2 tensor
                val tr = sxx + syy
                val det = sxx * syy - sxy * sxy
                val disc = kotlin.math.sqrt((tr * tr * 0.25f - det).coerceAtLeast(0f))
                val lambdaMin = tr * 0.5f - disc
                score[i] = lambdaMin
                if (lambdaMin > maxScore) maxScore = lambdaMin
            }
        }

        val cut = maxScore * qualityThreshold

        // Non-maximum suppression in a 3x3 neighbourhood (skipping the border
        // margin so every kept feature has full census + patch support)
        val margin = 2 + patchSize
        val isMax = BooleanArray(n)
        val candidates = ArrayList<Int>()
        for (v in margin until height - margin) {
            for (u in margin until width - margin) {
                val i = v * width + u
                val s = score[i]
                if (s <= cut || s <= 0f) continue
                if (s >= score[i - 1] && s >= score[i + 1] &&
                    s >= score[i - width] && s >= score[i + width] &&
                    s >= score[i - width - 1] && s >= score[i - width + 1] &&
                    s >= score[i + width - 1] && s >= score[i + width + 1]
                ) {
                    isMax[i] = true
                    candidates.add(i)
                }
            }
        }

        // Uniform grid: strongest feature per cell first pass
        val cellW = width.toFloat() / gridCells
        val cellH = height.toFloat() / gridCells
        data class Candidate(val index: Int, val u: Int, val v: Int, val score: Float)
        val sorted = candidates.map { Candidate(it, it % width, it / width, score[it]) }.sortedByDescending { it.score }

        val chosen = ArrayList<Candidate>()
        val cellUsed = BooleanArray(gridCells * gridCells)
        for (c in sorted) {
            if (chosen.size >= maxFeatures) break
            val cell = (c.v / cellH).toInt().coerceIn(0, gridCells - 1) * gridCells +
                (c.u / cellW).toInt().coerceIn(0, gridCells - 1)
            if (cellUsed[cell]) continue
            cellUsed[cell] = true
            chosen.add(c)
        }
        // Second pass: fill remaining slots (some cells may still be empty)
        if (chosen.size < maxFeatures) {
            val used = HashSet<Int>(chosen.map { it.index })
            for (c in sorted) {
                if (chosen.size >= maxFeatures) break
                if (c.index in used) continue
                used.add(c.index)
                chosen.add(c)
            }
        }

        // Precompute census signatures + NCC patches for the whole image once
        val census = ImageOps.census3x3(gray, width, height)
        val positions = FloatArray(chosen.size * 2)
        val censusOut = IntArray(chosen.size)
        val patchLen = (2 * patchSize + 1) * (2 * patchSize + 1)
        val patches = FloatArray(chosen.size * patchLen)

        for ((out, c) in chosen.withIndex()) {
            positions[out * 2] = c.u.toFloat()
            positions[out * 2 + 1] = c.v.toFloat()
            censusOut[out] = census[c.index]
            var p = out * patchLen
            for (dv in -patchSize..patchSize) {
                for (du in -patchSize..patchSize) {
                    patches[p++] = gray[c.index + dv * width + du]
                }
            }
        }

        return FeatureList(positions, censusOut, patches, patchSize, width, height)
    }
}
