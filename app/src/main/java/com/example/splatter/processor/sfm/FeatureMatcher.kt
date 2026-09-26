package com.example.splatter.processor.sfm

/**
 * Feature matching between two frames: spatial-radius + census-signature
 * shortlisting with a mutual-best (reverse-match) check, then NCC patch
 * correlation for the final decision. Pure Kotlin.
 */
object FeatureMatcher {

    /** One accepted 2-view match. */
    data class Match(val i: Int, val j: Int, val score: Float)

    /**
     * Match [a] against [b].
     *
     * @param radius maximum pixel distance between matched corners
     * @param censusMaxBits census signatures may differ in at most this many bits
     * @param minNcc minimum patch NCC to accept the match
     */
    fun match(
        a: FeatureList,
        b: FeatureList,
        radius: Float = 64f,
        censusMaxBits: Int = 2,
        minNcc: Float = 0.8f
    ): List<Match> {
        if (a.count == 0 || b.count == 0) return emptyList()
        if (a.patchSize != b.patchSize) return emptyList()
        val radius2 = radius * radius
        val patchLen = (2 * a.patchSize + 1) * (2 * a.patchSize + 1)

        // Hamming-distance lookup for all 512 possible signature pairs
        val hamming = IntArray(512 * 512)
        for (s1 in 0 until 512) {
            for (s2 in 0 until 512) {
                hamming[s1 * 512 + s2] = (s1 xor s2).countOneBits()
            }
        }

        class Best(var j: Int = -1, var ncc: Float = minNcc, var dist2: Float = Float.MAX_VALUE)

        val fromPatch = FloatArray(patchLen)
        val toPatch = FloatArray(patchLen)

        fun findBest(from: FeatureList, fi: Int, to: FeatureList): Best {
            val best = Best()
            val fx = from.x(fi); val fy = from.y(fi)
            val fc = from.census[fi]
            from.patches.copyInto(fromPatch, 0, fi * patchLen, (fi + 1) * patchLen)
            for (j in 0 until to.count) {
                val dx = to.x(j) - fx
                val dy = to.y(j) - fy
                val d2 = dx * dx + dy * dy
                if (d2 > radius2) continue
                if (hamming[fc * 512 + to.census[j]] > censusMaxBits) continue
                to.patches.copyInto(toPatch, 0, j * patchLen, (j + 1) * patchLen)
                val score = ImageOps.ncc(fromPatch, toPatch)
                if (score > best.ncc || (score == best.ncc && d2 < best.dist2)) {
                    best.j = j
                    best.ncc = score
                    best.dist2 = d2
                }
            }
            return best
        }

        // Forward pass: best match in b for every feature in a,
        // then the reverse pass; a match is kept only when both agree.
        val forward = ArrayList<Best>(a.count)
        for (i in 0 until a.count) forward.add(findBest(a, i, b))
        val reverse = arrayOfNulls<Best>(b.count)
        for (j in 0 until b.count) reverse[j] = findBest(b, j, a)

        val out = ArrayList<Match>()
        for (i in 0 until a.count) {
            val f = forward[i]
            if (f.j < 0) continue
            val r = reverse[f.j] ?: continue
            if (r.j == i) out.add(Match(i, f.j, f.ncc))
        }
        return out
    }
}
