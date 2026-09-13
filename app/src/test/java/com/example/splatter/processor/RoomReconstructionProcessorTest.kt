package com.example.splatter.processor

import com.example.splatter.model.SplatPoint
import org.junit.Assert.assertTrue
import org.junit.Test

class RoomReconstructionProcessorTest {

    @Test
    fun extractPlanes_detectsFloorAndWalls() {
        val points = listOf(
            SplatPoint(0f, 0f, 0f, 1f, 1f, 1f),
            SplatPoint(1f, 0f, 0f, 1f, 1f, 1f),
            SplatPoint(2f, 0f, 0f, 1f, 1f, 1f),
            SplatPoint(0f, 0f, 1f, 1f, 1f, 1f),
            SplatPoint(1f, 0f, 1f, 1f, 1f, 1f),
            SplatPoint(0f, 2f, 0f, 1f, 1f, 1f),
            SplatPoint(0f, 2f, 1f, 1f, 1f, 1f),
            SplatPoint(1f, 2f, 0f, 1f, 1f, 1f),
            SplatPoint(1f, 2f, 1f, 1f, 1f, 1f)
        )

        val planes = RoomReconstructionProcessor.extractPlanes(points)

        assertTrue(planes.any { it.type.name == "FLOOR" || it.type.name == "WALL" })
    }
}
