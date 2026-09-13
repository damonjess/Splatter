package com.example.splatter.processor

import com.example.splatter.model.SplatPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class GaussianSplatTrainerTest {

    // ---------------------------------------------------------------------------
    // Inverse rigid transform tests
    // ---------------------------------------------------------------------------

    @Test
    fun invertRigidTransform_identityReturnsIdentity() {
        val identity = floatArrayOf(
            1f, 0f, 0f, 0f,
            0f, 1f, 0f, 0f,
            0f, 0f, 1f, 0f,
            0f, 0f, 0f, 1f
        )

        val inv = GaussianSplatTrainer.invertRigidTransform(identity)

        for (i in 0 until 16) {
            assertEquals("Element $i should match identity", identity[i], inv[i], 0.0001f)
        }
    }

    @Test
    fun invertRigidTransform_composeWithInverseGivesIdentity() {
        // A rigid transform: rotation 90° around Y + translation
        val pose = floatArrayOf(
            // Column-major: R = [cos, 0, sin; 0, 1, 0; -sin, 0, cos] for 90° around Y
            // cos(90) = 0, sin(90) = 1
            // Column 0: [0, 0, -1]
            0f, 0f, -1f, 0f,
            // Column 1: [0, 1, 0]
            0f, 1f, 0f, 0f,
            // Column 2: [1, 0, 0]
            1f, 0f, 0f, 0f,
            // Translation: [1, 2, 3]
            1f, 2f, 3f, 1f
        )

        val inv = GaussianSplatTrainer.invertRigidTransform(pose)

        // Compose pose * invPose = identity
        // Matrix multiply (column-major): result = pose * inv
        val result = FloatArray(16)
        for (col in 0 until 4) {
            for (row in 0 until 4) {
                var sum = 0f
                for (k in 0 until 4) {
                    sum += pose[k * 4 + row] * inv[col * 4 + k]
                }
                result[col * 4 + row] = sum
            }
        }

        for (i in 0 until 16) {
            val expected = if (i % 5 == 0) 1f else 0f // Identity diagonal
            assertEquals("pose * invPose element $i should be identity", expected, result[i], 0.001f)
        }
    }

    @Test
    fun invertRigidTransform_pointRoundTrip() {
        // Transform a point to world, then back to camera using inverse
        val pose = floatArrayOf(
            // 90° rotation around Z: R = [cos, -sin, 0; sin, cos, 0; 0, 0, 1]
            // cos(90)=0, sin(90)=1 → column-major: [0,1,0; -1,0,0; 0,0,1]
            0f, 1f, 0f, 0f,
            -1f, 0f, 0f, 0f,
            0f, 0f, 1f, 0f,
            5f, 3f, 0f, 1f
        )

        val inv = GaussianSplatTrainer.invertRigidTransform(pose)

        // Camera point (1, 0, 0) → world
        val camX = 1f; val camY = 0f; val camZ = 0f
        val worldX = pose[0] * camX + pose[4] * camY + pose[8] * camZ + pose[12]
        val worldY = pose[1] * camX + pose[5] * camY + pose[9] * camZ + pose[13]
        val worldZ = pose[2] * camX + pose[6] * camY + pose[10] * camZ + pose[14]

        // World point back to camera using inverse
        val backCamX = inv[0] * worldX + inv[4] * worldY + inv[8] * worldZ + inv[12]
        val backCamY = inv[1] * worldX + inv[5] * worldY + inv[9] * worldZ + inv[13]
        val backCamZ = inv[2] * worldX + inv[6] * worldY + inv[10] * worldZ + inv[14]

        assertEquals(camX, backCamX, 0.001f)
        assertEquals(camY, backCamY, 0.001f)
        assertEquals(camZ, backCamZ, 0.001f)
    }

    // ---------------------------------------------------------------------------
    // Projection tests
    // ---------------------------------------------------------------------------

    @Test
    fun projectWorldToImage_basicProjection() {
        // Camera at origin looking along +Z, no rotation
        val invPose = floatArrayOf(
            1f, 0f, 0f, 0f,
            0f, 1f, 0f, 0f,
            0f, 0f, 1f, 0f,
            0f, 0f, 0f, 1f
        )

        // Point at (0, 0, 2) in world = (0, 0, 2) in camera
        // With fx=100, fy=100, cx=50, cy=50: u = 100*0/2 + 50 = 50, v = 100*0/2 + 50 = 50
        val result = GaussianSplatTrainer.projectWorldToImage(
            worldX = 0f, worldY = 0f, worldZ = 2f,
            invPose = invPose,
            fx = 100f, fy = 100f, cx = 50f, cy = 50f
        )

        assertNotNull(result)
        assertEquals(50, result!!.first)
        assertEquals(50, result.second)
    }

    @Test
    fun projectWorldToImage_offsetPoint() {
        val invPose = floatArrayOf(
            1f, 0f, 0f, 0f,
            0f, 1f, 0f, 0f,
            0f, 0f, 1f, 0f,
            0f, 0f, 0f, 1f
        )

        // Point at (1, 1, 2) with fx=fy=100, cx=cy=50
        // u = 100*1/2 + 50 = 100, v = 100*1/2 + 50 = 100
        val result = GaussianSplatTrainer.projectWorldToImage(
            worldX = 1f, worldY = 1f, worldZ = 2f,
            invPose = invPose,
            fx = 100f, fy = 100f, cx = 50f, cy = 50f
        )

        assertNotNull(result)
        assertEquals(100, result!!.first)
        assertEquals(100, result.second)
    }

    @Test
    fun projectWorldToImage_behindCameraReturnsNull() {
        val invPose = floatArrayOf(
            1f, 0f, 0f, 0f,
            0f, 1f, 0f, 0f,
            0f, 0f, 1f, 0f,
            0f, 0f, 0f, 1f
        )

        // Point at z = -1 (behind camera)
        val result = GaussianSplatTrainer.projectWorldToImage(
            worldX = 0f, worldY = 0f, worldZ = -1f,
            invPose = invPose,
            fx = 100f, fy = 100f, cx = 50f, cy = 50f
        )

        assertNull(result)
    }

    @Test
    fun worldToCamera_transformsPointCorrectly() {
        // Camera at (5, 0, 0) looking along -X (rotated 90° around Y)
        // Inverse pose: world-to-camera
        val invPose = floatArrayOf(
            // R^T of 90° Y rotation = [0, 0, -1; 0, 1, 0; 1, 0, 0]
            // But invPose = [R^T | -R^T * t] where t = (5, 0, 0)
            // -R^T * (5,0,0) = -(0*5, 0*5, 1*5) = (0, 0, -5)
            // Column-major:
            // Col 0: [0, 0, 1]
            0f, 0f, 1f, 0f,
            // Col 1: [0, 1, 0]
            0f, 1f, 0f, 0f,
            // Col 2: [-1, 0, 0]
            -1f, 0f, 0f, 0f,
            // Translation: [0, 0, -5]
            0f, 0f, -5f, 1f
        )

        // World point at (5, 0, 0) should map to camera (0, 0, 0)... wait that's at the camera.
        // Let's use world point (10, 0, 0) → should be 5 units in front of camera → camera (0, 0, 5)? 
        // Actually with this setup, camera Z is -X in world, so (10,0,0) → camZ = 10-5 = 5
        val result = GaussianSplatTrainer.worldToCamera(10f, 0f, 0f, invPose)

        assertNotNull(result)
        // camX = 0*10 + 0*0 + (-1)*0 + 0 = 0
        // camY = 0*10 + 1*0 + 0*0 + 0 = 0
        // camZ = 1*10 + 0*0 + 0*0 + (-5) = 5
        assertEquals(0f, result!!.first, 0.001f)
        assertEquals(0f, result.second, 0.001f)
        assertEquals(5f, result.third, 0.001f)
    }

    @Test
    fun worldToCamera_behindCameraReturnsNull() {
        val invPose = floatArrayOf(
            1f, 0f, 0f, 0f,
            0f, 1f, 0f, 0f,
            0f, 0f, 1f, 0f,
            0f, 0f, 0f, 1f
        )

        val result = GaussianSplatTrainer.worldToCamera(0f, 0f, 0.01f, invPose)
        // z=0.01 is exactly at the threshold (0.05), should return null
        assertNull(result)
    }

    // ---------------------------------------------------------------------------
    // Depth-gate tests
    // ---------------------------------------------------------------------------

    @Test
    fun depthMatches_matchingDepthReturnsTrue() {
        val depthData = shortArrayOf(1000) // 1000mm = 1.0m
        val result = GaussianSplatTrainer.depthMatches(
            splatDepthM = 1.0f,
            depthData = depthData,
            depthU = 0,
            depthV = 0,
            depthWidth = 1,
            toleranceMeters = 0.1f
        )
        assertTrue(result)
    }

    @Test
    fun depthMatches_withinToleranceReturnsTrue() {
        val depthData = shortArrayOf(1050) // 1.05m
        val result = GaussianSplatTrainer.depthMatches(
            splatDepthM = 1.0f,
            depthData = depthData,
            depthU = 0,
            depthV = 0,
            depthWidth = 1,
            toleranceMeters = 0.1f
        )
        assertTrue(result)
    }

    @Test
    fun depthMatches_outsideToleranceReturnsFalse() {
        val depthData = shortArrayOf(1500) // 1.5m
        val result = GaussianSplatTrainer.depthMatches(
            splatDepthM = 1.0f,
            depthData = depthData,
            depthU = 0,
            depthV = 0,
            depthWidth = 1,
            toleranceMeters = 0.1f
        )
        assertFalse(result)
    }

    @Test
    fun depthMatches_zeroDepthReturnsFalse() {
        val depthData = shortArrayOf(0) // No depth data
        val result = GaussianSplatTrainer.depthMatches(
            splatDepthM = 1.0f,
            depthData = depthData,
            depthU = 0,
            depthV = 0,
            depthWidth = 1,
            toleranceMeters = 0.1f
        )
        assertFalse(result)
    }

    @Test
    fun depthMatches_outOfBoundsReturnsFalse() {
        val depthData = shortArrayOf(1000)
        val result = GaussianSplatTrainer.depthMatches(
            splatDepthM = 1.0f,
            depthData = depthData,
            depthU = 5,
            depthV = 5,
            depthWidth = 1,
            toleranceMeters = 0.1f
        )
        assertFalse(result)
    }

    // ---------------------------------------------------------------------------
    // Pruning behavior tests
    // ---------------------------------------------------------------------------

    @Test
    fun pruning_doesNotIncreasePointCount() {
        val points = mutableListOf(
            SplatPoint(0f, 0f, 0f, 1f, 0f, 0f, alpha = 0.9f),
            SplatPoint(1f, 0f, 0f, 0f, 1f, 0f, alpha = 0.01f), // Below threshold
            SplatPoint(0f, 1f, 0f, 0f, 0f, 1f, alpha = 0.5f),
            SplatPoint(0f, 0f, 1f, 1f, 1f, 0f, alpha = 0.03f)  // Below threshold
        )

        val threshold = 0.05f
        val pruned = points.filter { it.alpha >= threshold }

        assertTrue("Pruning must not increase point count", pruned.size <= points.size)
        assertEquals(2, pruned.size)
    }

    @Test
    fun pruning_deterministicBehavior() {
        val points1 = mutableListOf(
            SplatPoint(0f, 0f, 0f, 1f, 0f, 0f, alpha = 0.9f),
            SplatPoint(1f, 0f, 0f, 0f, 1f, 0f, alpha = 0.01f),
            SplatPoint(0f, 1f, 0f, 0f, 0f, 1f, alpha = 0.5f)
        )

        val points2 = mutableListOf(
            SplatPoint(0f, 0f, 0f, 1f, 0f, 0f, alpha = 0.9f),
            SplatPoint(1f, 0f, 0f, 0f, 1f, 0f, alpha = 0.01f),
            SplatPoint(0f, 1f, 0f, 0f, 0f, 1f, alpha = 0.5f)
        )

        val threshold = 0.05f
        val pruned1 = points1.filter { it.alpha >= threshold }
        val pruned2 = points2.filter { it.alpha >= threshold }

        assertEquals(pruned1.size, pruned2.size)
        for (i in pruned1.indices) {
            assertEquals(pruned1[i].alpha, pruned2[i].alpha, 0.0001f)
        }
    }

    @Test
    fun pruning_keepsHighOpacityPoints() {
        val points = mutableListOf(
            SplatPoint(0f, 0f, 0f, 1f, 0f, 0f, alpha = 0.9f),
            SplatPoint(1f, 0f, 0f, 0f, 1f, 0f, alpha = 0.5f),
            SplatPoint(0f, 1f, 0f, 0f, 0f, 1f, alpha = 0.99f)
        )

        val threshold = 0.05f
        val pruned = points.filter { it.alpha >= threshold }

        assertEquals(3, pruned.size) // All above threshold
    }

    @Test
    fun pruning_removesAllBelowThreshold() {
        val points = mutableListOf(
            SplatPoint(0f, 0f, 0f, 1f, 0f, 0f, alpha = 0.01f),
            SplatPoint(1f, 0f, 0f, 0f, 1f, 0f, alpha = 0.02f),
            SplatPoint(0f, 1f, 0f, 0f, 0f, 1f, alpha = 0.04f)
        )

        val threshold = 0.05f
        val pruned = points.filter { it.alpha >= threshold }

        assertEquals(0, pruned.size) // All below threshold
    }

    // ---------------------------------------------------------------------------
    // RGB-to-depth coordinate mapping tests
    // ---------------------------------------------------------------------------

    @Test
    fun mapRgbToDepthPixel_centerMapsToCenter() {
        val result = GaussianSplatTrainer.mapRgbToDepthPixel(
            rgbU = 240, rgbV = 135,
            rgbWidth = 480, rgbHeight = 270,
            depthWidth = 160, depthHeight = 90
        )
        // Center of 480x270 maps to center of 160x90
        assertEquals(80, result.first)
        assertEquals(45, result.second)
    }

    @Test
    fun mapRgbToDepthPixel_cornerMapsToCorner() {
        val result = GaussianSplatTrainer.mapRgbToDepthPixel(
            rgbU = 0, rgbV = 0,
            rgbWidth = 480, rgbHeight = 270,
            depthWidth = 160, depthHeight = 90
        )
        assertEquals(0, result.first)
        assertEquals(0, result.second)
    }

    @Test
    fun mapRgbToDepthPixel_differentAspectRatiosClampsCorrectly() {
        // RGB 480x270, depth 160x120 (different aspect)
        val result = GaussianSplatTrainer.mapRgbToDepthPixel(
            rgbU = 479, rgbV = 269,
            rgbWidth = 480, rgbHeight = 270,
            depthWidth = 160, depthHeight = 120
        )
        // Should map to the far corner, clamped within bounds
        assertTrue(result.first >= 0 && result.first < 160)
        assertTrue(result.second >= 0 && result.second < 120)
    }

    @Test
    fun mapRgbToDepthPixel_doesNotExceedDepthBounds() {
        for (u in 0..479 step 48) {
            for (v in 0..269 step 27) {
                val result = GaussianSplatTrainer.mapRgbToDepthPixel(
                    rgbU = u, rgbV = v,
                    rgbWidth = 480, rgbHeight = 270,
                    depthWidth = 160, depthHeight = 90
                )
                assertTrue("depthU ($u,$v) -> ${result.first} should be < 160", result.first < 160)
                assertTrue("depthV ($u,$v) -> ${result.second} should be < 90", result.second < 90)
                assertTrue("depthU should be >= 0", result.first >= 0)
                assertTrue("depthV should be >= 0", result.second >= 0)
            }
        }
    }

    // ---------------------------------------------------------------------------
    // Color gradient descent test (simulated training step)
    // ---------------------------------------------------------------------------

    @Test
    fun colorUpdate_nudgesTowardObservedColor() {
        val learningRate = 0.1f
        val splatR = 0.8f
        val splatG = 0.2f
        val splatB = 0.5f
        val obsR = 0.4f
        val obsG = 0.6f
        val obsB = 0.3f

        // Error = splat - observed
        val errR = splatR - obsR  // 0.4
        val errG = splatG - obsG  // -0.4
        val errB = splatB - obsB  // 0.2

        // Update: splat -= lr * error
        val newR = (splatR - learningRate * errR).coerceIn(0f, 1f)
        val newG = (splatG - learningRate * errG).coerceIn(0f, 1f)
        val newB = (splatB - learningRate * errB).coerceIn(0f, 1f)

        // After update, splat should be closer to observed
        val oldDist = abs(splatR - obsR) + abs(splatG - obsG) + abs(splatB - obsB)
        val newDist = abs(newR - obsR) + abs(newG - obsG) + abs(newB - obsB)

        assertTrue("Updated color should be closer to observed", newDist < oldDist)
    }
}
