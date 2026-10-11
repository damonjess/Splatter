package com.example.splatter.processor

import com.example.splatter.model.SplatPoint
import kotlin.math.roundToInt

/**
 * Post-processing that turns a raw reconstruction into something that looks
 * like a demoable model instead of a cloud with confetti around it.
 *
 * Reconstruction always leaves a few stray points behind: depth speckle,
 * specular highlights, and mis-matched pixels all unproject to isolated
 * floaters far from any real surface. Statistical outlier removal counts the
 * neighbours around each point and drops the ones with almost none — the same
 * first step Polycam-style apps apply before exporting.
 *
 * Pure Kotlin (no Android APIs) so it is covered by JVM unit tests.
 */
object ModelCleanup {

    /** Above this many points the O(27n) neighbour scan is skipped to keep
     *  processing snappy on low-end devices. */
    const val MAX_POINTS = 600_000

    /**
     * Drops points that have fewer than [minNeighbours] other points within a
     * radius of [neighbourRadiusMeters].
     *
     * @param points the reconstruction to clean (not modified).
     * @param neighbourRadiusMeters search radius; should be a couple of times
     *        the reconstruction's voxel size so surface points always see
     *        their immediate neighbours.
     * @param minNeighbours minimum neighbours (excluding the point itself) a
     *        point needs to survive. 3 keeps surfaces and edges, kills floaters.
     * @return a new list with outliers removed, or the input list unchanged if
     *         it is too large or too small to bother.
     */
    fun removeOutliers(
        points: List<SplatPoint>,
        neighbourRadiusMeters: Float,
        minNeighbours: Int = 3
    ): List<SplatPoint> {
        if (points.size < 24 || points.size > MAX_POINTS || neighbourRadiusMeters <= 0f) return points

        // Voxel hash of the reconstruction. Cell size = search radius, so a
        // point's neighbours can only live in its own cell or the 26 around it.
        val cell = neighbourRadiusMeters
        val counts = HashMap<Long, Int>(points.size * 2)
        for (p in points) {
            val key = cellKey(p.x, p.y, p.z, cell)
            counts[key] = (counts[key] ?: 0) + 1
        }

        val radiusSq = neighbourRadiusMeters * neighbourRadiusMeters
        val kept = ArrayList<SplatPoint>(points.size)
        for (p in points) {
            val cx = (p.x / cell).roundToInt()
            val cy = (p.y / cell).roundToInt()
            val cz = (p.z / cell).roundToInt()

            // Cheap voxel-neighbourhood count: the point itself is in its own
            // cell, so it contributes one to the total.
            var neighbours = -1
            for (dx in -1..1) {
                for (dy in -1..1) {
                    for (dz in -1..1) {
                        neighbours += counts[packKey(cx + dx, cy + dy, cz + dz)] ?: 0
                    }
                }
            }

            // A sparse voxel neighbourhood is decisive; anything that is close
            // to the threshold gets the exact radius test instead.
            if (neighbours <= 0) continue
            if (neighbours >= minNeighbours) {
                kept.add(p)
                continue
            }
            if (countWithinRadius(points, p, radiusSq) >= minNeighbours) kept.add(p)
        }
        return kept
    }

    private fun countWithinRadius(
        points: List<SplatPoint>,
        origin: SplatPoint,
        radiusSq: Float
    ): Int {
        var n = 0
        for (q in points) {
            if (q === origin) continue
            val dx = q.x - origin.x
            val dy = q.y - origin.y
            val dz = q.z - origin.z
            if (dx * dx + dy * dy + dz * dz <= radiusSq) {
                n++
                if (n > 8) return n
            }
        }
        return n
    }

    /**
     * Removes duplicate points that landed in the same voxel (they render as
     * over-bright clumps because alpha stacks).
     */
    fun dedupeByVoxel(points: List<SplatPoint>, voxelSizeMeters: Float): List<SplatPoint> {
        if (voxelSizeMeters <= 0f) return points
        val seen = HashSet<Long>(points.size * 2)
        val kept = ArrayList<SplatPoint>(points.size)
        for (p in points) {
            if (seen.add(cellKey(p.x, p.y, p.z, voxelSizeMeters))) kept.add(p)
        }
        return kept
    }

    // Cells are centred on multiples of `size` (round, not floor): dividing by
    // a cell size in float leaves values like 0.07f/0.01f == 6.9999995, and
    // flooring that would merge two neighbouring voxels.
    private fun cellKey(x: Float, y: Float, z: Float, size: Float): Long =
        packKey(
            (x / size).roundToInt(),
            (y / size).roundToInt(),
            (z / size).roundToInt()
        )

    private fun packKey(cx: Int, cy: Int, cz: Int): Long =
        (cx.toLong() and 0x1FFFFF) or
            ((cy.toLong() and 0x1FFFFF) shl 21) or
            ((cz.toLong() and 0x1FFFFF) shl 42)
}
