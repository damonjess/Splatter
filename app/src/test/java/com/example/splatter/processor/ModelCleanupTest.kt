package com.example.splatter.processor

import com.example.splatter.model.SplatPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelCleanupTest {

    private fun point(x: Float, y: Float, z: Float) = SplatPoint(
        x = x, y = y, z = z, r = 0.5f, g = 0.5f, b = 0.5f
    )

    /** 20 x 20 grid of points, 1 cm apart, on the z = 0 plane. */
    private fun wall(): List<SplatPoint> {
        val pts = ArrayList<SplatPoint>(400)
        for (i in 0 until 20) {
            for (j in 0 until 20) {
                pts.add(point(i * 0.01f, j * 0.01f, 0f))
            }
        }
        return pts
    }

    @Test
    fun keepsDenseSurfacePointsAndDropsFloaters() {
        val points = wall().toMutableList()
        points.add(point(5f, 5f, 5f))              // lone floater
        points.add(point(-3f, 2f, 4f))             // lone floater
        points.add(point(0.7f, -0.2f, 1.3f))       // lone floater

        val kept = ModelCleanup.removeOutliers(points, neighbourRadiusMeters = 0.021f)

        assertEquals("only the three floaters should go", points.size - 3, kept.size)
        assertTrue(
            "every surviving point is on the wall",
            kept.all { it.z == 0f }
        )
    }

    @Test
    fun keepsASmallTightClusterBecauseItHasNeighbours() {
        // Two points 1 mm apart are each other's neighbour.
        val points = wall().toMutableList()
        points.add(point(9f, 9f, 9f))
        points.add(point(9.001f, 9f, 9f))

        val kept = ModelCleanup.removeOutliers(points, neighbourRadiusMeters = 0.021f)

        assertEquals("the pair is isolated -> both dropped", wall().size, kept.size)
    }

    @Test
    fun leavesTinyOrHugeCloudsAlone() {
        val tiny = listOf(point(0f, 0f, 0f), point(1f, 1f, 1f))
        assertEquals(tiny.size, ModelCleanup.removeOutliers(tiny, 0.01f).size)
    }

    @Test
    fun dedupeCollapsesSameVoxelPoints() {
        val points = wall() + listOf(
            point(0.001f, 0.001f, 0f),  // same 1 cm voxel as (0,0,0) -> collapsed
            point(0.2f, 0.2f, 0f)       // empty voxel -> survives
        )
        val deduped = ModelCleanup.dedupeByVoxel(points, voxelSizeMeters = 0.01f)
        assertEquals(401, deduped.size)
    }
}
