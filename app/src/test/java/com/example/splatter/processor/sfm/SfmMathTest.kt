package com.example.splatter.processor.sfm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

/**
 * Synthetic-scene tests for the pure-Kotlin SfM core: rotations, DLT
 * triangulation, two-view geometry, and end-to-end reconstruction on a
 * rendered virtual scene.
 */
class SfmMathTest {

    @Test
    fun `rotation angle-axis round trip`() {
        val w = floatArrayOf(0.1f, -0.2f, 0.35f)
        val R = SfmMath.rotationFromAngleAxis(w)
        val w2 = SfmMath.rotationToAngleAxis(R)
        val R2 = SfmMath.rotationFromAngleAxis(w2)
        for (i in 0 until 9) assertEquals(R[i], R2[i], 1e-4f)
        // Orthonormality
        assertEquals(1f, SfmMath.det3(R), 1e-5f)
    }

    @Test
    fun `rotate3 and rotate3T are inverse`() {
        val R = SfmMath.rotationFromAngleAxis(floatArrayOf(0.3f, 0.4f, -0.2f))
        val v = floatArrayOf(1f, -2f, 0.5f)
        val vr = SfmMath.rotate3T(R, SfmMath.rotate3(R, v))
        for (i in 0 until 3) assertEquals(v[i], vr[i], 1e-4f)
    }

    @Test
    fun `jacobi eigen finds known eigenvalues`() {
        // Diagonal(3, 1, 2) — eigenvalues 3, 2, 1 in descending order
        val a = doubleArrayOf(
            3.0, 0.0, 0.0,
            0.0, 1.0, 0.0,
            0.0, 0.0, 2.0
        )
        val (values, vectors) = SfmMath.jacobiEigen(a, 3)
        assertEquals(3.0, values[0], 1e-9)
        assertEquals(2.0, values[1], 1e-9)
        assertEquals(1.0, values[2], 1e-9)
        // Eigenvector for eigenvalue 3 is (1, 0, 0)
        assertEquals(1.0, vectors[0], 1e-9)
        assertEquals(0.0, vectors[1], 1e-9)
    }

    @Test
    fun `projection matrix round trips a world point`() {
        // Column-major cam-to-world: translation in the last column
        val pose = floatArrayOf(
            1f, 0f, 0f, 0f,
            0f, 1f, 0f, 0f,
            0f, 0f, 1f, 0f,
            1f, 2f, 3f, 1f
        )
        val fx = 500f; val fy = 500f; val cx = 320f; val cy = 240f
        val P = SfmMath.projectionMatrix(pose, fx, fy, cx, cy)
        // World point at the camera position + (0, 0, 2) forward
        val X = floatArrayOf(1f, 2f, 5f, 1f)
        val uv = SfmMath.project(P, X)
        assertNotNull(uv)
        // Camera coords: (0, 0, 2) -> u = 320, v = 240
        assertEquals(320f, uv!![0], 1e-3f)
        assertEquals(240f, uv[1], 1e-3f)
    }

    @Test
    fun `triangulation recovers a synthetic 3d point`() {
        val rng = Random(42)
        val X = floatArrayOf(0.2f, -0.1f, 2.5f)

        fun cameraPose(tx: Float, ty: Float, tz: Float, yaw: Float): FloatArray {
            val c = cos(yaw); val s = sin(yaw)
            // camera-to-world, COLUMN-major: R = yaw about Y, t last column
            return floatArrayOf(
                c, 0f, -s, 0f,
                0f, 1f, 0f, 0f,
                s, 0f, c, 0f,
                tx, ty, tz, 1f
            )
        }

        val fx = 400f; val fy = 400f; val cx = 320f; val cy = 240f
        val cams = listOf(
            cameraPose(0f, 0f, 0f, 0f),
            cameraPose(0.4f, 0f, 0f, 0.15f),
            cameraPose(-0.3f, 0.1f, 0.2f, -0.1f)
        )
        val mats = cams.map { SfmMath.projectionMatrix(it, fx, fy, cx, cy) }
        val us = FloatArray(3); val vs = FloatArray(3)
        for (k in 0 until 3) {
            val uv = SfmMath.project(mats[k], floatArrayOf(X[0], X[1], X[2], 1f))!!
            us[k] = uv[0] + (rng.nextFloat() - 0.5f) * 0.2f
            vs[k] = uv[1] + (rng.nextFloat() - 0.5f) * 0.2f
        }
        val out = SfmMath.triangulate(mats, us, vs)
        assertNotNull(out)
        for (i in 0 until 3) assertEquals(X[i], out!![i], 2e-2f)
    }

    @Test
    fun `triangulate rejects degenerate systems`() {
        val p = floatArrayOf(
            1f, 0f, 0f, 0f,
            0f, 1f, 0f, 0f,
            0f, 0f, 1f, 0f
        )
        // Same pixel in both views = parallel rays = no intersection
        val out = SfmMath.triangulate(listOf(p, p), floatArrayOf(100f, 100f), floatArrayOf(100f, 100f))
        assertNull(out)
    }
}

class TwoViewGeometryTest {

    private fun makePose(tx: Float, ty: Float, tz: Float, yaw: Float): FloatArray {
        val c = cos(yaw); val s = sin(yaw)
        // COLUMN-major cam-to-world: yaw about Y, translation last column
        return floatArrayOf(
            c, 0f, -s, 0f,
            0f, 1f, 0f, 0f,
            s, 0f, c, 0f,
            tx, ty, tz, 1f
        )
    }

    @Test
    fun `essential matrix and pose recovered from clean correspondences`() {
        val rng = Random(7)
        val fx = 500f; val fy = 500f; val cx = 320f; val cy = 240f

        // Cloud of points in front of camera A (at origin, identity pose)
        val cloud = Array(60) {
            floatArrayOf(
                (rng.nextFloat() - 0.5f) * 2f,
                (rng.nextFloat() - 0.5f) * 2f,
                2f + rng.nextFloat() * 2f
            )
        }
        val poseB = makePose(0.5f, 0.05f, 0.0f, 0.12f)

        val matches = mutableListOf<FeatureMatcher.Match>()
        val pa = FloatArray(cloud.size * 2)
        val pb = FloatArray(cloud.size * 2)
        for (k in cloud.indices) {
            val uvA = SfmMath.projectWithPose(SfmMath.identityPose(), fx, fy, cx, cy, cloud[k][0], cloud[k][1], cloud[k][2])!!
            val uvB = SfmMath.projectWithPose(poseB, fx, fy, cx, cy, cloud[k][0], cloud[k][1], cloud[k][2])!!
            pa[2 * k] = uvA[0]; pa[2 * k + 1] = uvA[1]
            pb[2 * k] = uvB[0]; pb[2 * k + 1] = uvB[1]
            matches.add(FeatureMatcher.Match(k, k, 1f))
        }

        val result = TwoViewGeometry.relativePose(matches, pa, pb, fx, fy, cx, cy, iterations = 200)
        assertNotNull(result)
        result!!

        // Nearly all matches should be inliers
        assertTrue("inliers=${result.inlierCount}/60", result.inlierCount >= 54)

        // Recovered camera position of B must match the true one (0.5, 0.05, 0)
        // up to a global scale — compare directions of the baseline.
        val t = floatArrayOf(result.poseB[12], result.poseB[13], result.poseB[14])
        val tNorm = kotlin.math.sqrt(t[0] * t[0] + t[1] * t[1] + t[2] * t[2])
        val tUnit = floatArrayOf(t[0] / tNorm, t[1] / tNorm, t[2] / tNorm)
        val trueUnit = floatArrayOf(0.5f, 0.05f, 0f).let {
            val n = kotlin.math.sqrt(it[0] * it[0] + it[1] * it[1] + it[2] * it[2])
            floatArrayOf(it[0] / n, it[1] / n, it[2] / n)
        }
        val dot = tUnit[0] * trueUnit[0] + tUnit[1] * trueUnit[1] + tUnit[2] * trueUnit[2]
        assertTrue("baseline direction dot=$dot", dot > 0.999f)
    }

    @Test
    fun `outliers are rejected by ransac`() {
        val rng = Random(11)
        val fx = 500f; val fy = 500f; val cx = 320f; val cy = 240f
        val cloud = Array(80) {
            floatArrayOf(
                (rng.nextFloat() - 0.5f) * 2f,
                (rng.nextFloat() - 0.5f) * 2f,
                2f + rng.nextFloat() * 2f
            )
        }
        val poseB = makePose(0.4f, 0f, 0f, 0.1f)

        val matches = mutableListOf<FeatureMatcher.Match>()
        val pa = FloatArray(cloud.size * 2)
        val pb = FloatArray(cloud.size * 2)
        for (k in cloud.indices) {
            val uvA = SfmMath.projectWithPose(SfmMath.identityPose(), fx, fy, cx, cy, cloud[k][0], cloud[k][1], cloud[k][2])!!
            val uvB = SfmMath.projectWithPose(poseB, fx, fy, cx, cy, cloud[k][0], cloud[k][1], cloud[k][2])!!
            pa[2 * k] = uvA[0]; pa[2 * k + 1] = uvA[1]
            pb[2 * k] = uvB[0]; pb[2 * k + 1] = uvB[1]
            matches.add(FeatureMatcher.Match(k, k, 1f))
        }
        // Corrupt 25% of the matches with random offsets
        for (k in cloud.indices step 4) {
            pb[2 * k] += (rng.nextFloat() - 0.5f) * 120f
            pb[2 * k + 1] += (rng.nextFloat() - 0.5f) * 120f
        }

        val result = TwoViewGeometry.relativePose(matches, pa, pb, fx, fy, cx, cy, iterations = 300)
        assertNotNull(result)
        result!!
        // Inliers should be roughly the clean 75%
        assertTrue("inliers=${result.inlierCount}", result.inlierCount >= 50)
        // Corrupted matches must be flagged as outliers
        var corruptedInliers = 0
        for (k in cloud.indices step 4) {
            if (result.inlierMask[k]) corruptedInliers++
        }
        assertTrue("corrupted kept: $corruptedInliers", corruptedInliers <= 6)
    }

    @Test
    fun `too few matches return null`() {
        val matches = (0 until 5).map { FeatureMatcher.Match(it, it, 1f) }
        val pa = FloatArray(10); val pb = FloatArray(10)
        assertNull(TwoViewGeometry.relativePose(matches, pa, pb, 500f, 500f, 320f, 240f))
    }
}
