package com.example.splatter.processor

import com.example.splatter.processor.mesh.DepthFrame
import com.example.splatter.processor.mesh.DepthMeshFuser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Guards the ARCore -> pipeline camera-frame conversion.
 *
 * The expected values here are derived only from the equations Google
 * documents for ARCore (`Camera.getPose()` is an OpenGL camera pose: +X
 * right, +Y up, -Z forward; the CPU image is top-to-bottom row-major, so a
 * point projects as u = cx + fx*X/-Z, v = cy - fy*Y/-Z), never from
 * [CameraPose] itself.
 */
class CameraPoseTest {

    private val fx = 500f
    private val fy = 500f
    private val cx = 319.5f
    private val cy = 239.5f

    /** ARCore-convention camera-to-world matrix with a rotation about world Y. */
    private fun arcorePose(tx: Float, ty: Float, tz: Float, yaw: Float, pitch: Float): FloatArray {
        // camera basis in the ARCore frame: X right, Y up, Z backward
        val cy1 = cos(yaw); val sy = sin(yaw)
        val cp = cos(pitch); val sp = sin(pitch)
        // Start from the yaw-rotated basis, then apply a pitch about the camera X axis.
        val xx = floatArrayOf(cy1, 0f, -sy)
        val yy = floatArrayOf(sy * sp, cp, cy1 * sp)
        val zz = floatArrayOf(sy * cp, -sp, cy1 * cp)
        return floatArrayOf(
            xx[0], xx[1], xx[2], 0f,
            yy[0], yy[1], yy[2], 0f,
            zz[0], zz[1], zz[2], 0f,
            tx, ty, tz, 1f
        )
    }

    /** Ground truth: world point of pixel (u,v) seen at depth d by an ARCore pose. */
    private fun arcoreWorldPoint(u: Int, v: Int, d: Float, pose: FloatArray): FloatArray {
        // camera-space point in ARCore axes
        val x = (u - cx) * d / fx
        val y = -(v - cy) * d / fy
        val z = -d
        return floatArrayOf(
            pose[0] * x + pose[4] * y + pose[8] * z + pose[12],
            pose[1] * x + pose[5] * y + pose[9] * z + pose[13],
            pose[2] * x + pose[6] * y + pose[10] * z + pose[14]
        )
    }

    @Test
    fun conversionKeepsRotationProperAndCameraCenter() {
        val raw = arcorePose(0.4f, 1.2f, -0.7f, 0.6f, 0.35f)
        val converted = CameraPose.toPipelineSpace(raw)

        assertTrue("ARCore pose is a proper rotation", CameraPose.isProperRotation(raw))
        assertTrue(
            "conversion must not mirror the camera frame (det stayed +1)",
            CameraPose.isProperRotation(converted)
        )
        assertEquals(raw[12], converted[12], 0f)
        assertEquals(raw[13], converted[13], 0f)
        assertEquals(raw[14], converted[14], 0f)
    }

    @Test
    fun convertedPoseReproducesArcoreWorldPoint() {
        val poses = listOf(
            arcorePose(0f, 0f, 0f, 0f, 0f),
            arcorePose(0.35f, 0.1f, -0.2f, 0.45f, 0f),
            arcorePose(-0.2f, 0.3f, 0.15f, -0.8f, 0.25f),
            arcorePose(0.1f, -0.4f, 0.6f, 1.2f, -0.3f)
        )
        val samples = listOf(
            Triple(120, 90, 1.4f),
            Triple(319, 239, 2.0f),
            Triple(500, 400, 0.75f),
            Triple(40, 460, 3.2f)
        )

        for ((pi, raw) in poses.withIndex()) {
            val pipelinePose = CameraPose.toPipelineSpace(raw)
            for ((u, v, d) in samples) {
                val expected = arcoreWorldPoint(u, v, d, raw)
                val actual = CameraPose.unprojectToWorld(u, v, d, pipelinePose, fx, fy, cx, cy)
                val err = sqrt(
                    (expected[0] - actual[0]) * (expected[0] - actual[0]) +
                        (expected[1] - actual[1]) * (expected[1] - actual[1]) +
                        (expected[2] - actual[2]) * (expected[2] - actual[2])
                )
                assertEquals("pose $pi pixel ($u,$v) at ${d}m", 0f, err, 1e-3f)
            }
        }
    }

    @Test
    fun rawArcPoseWouldLandBehindTheCamera() {
        // Sanity check that this test is actually guarding the bug it claims to:
        // without the conversion, the identity ARCore pose (looking -Z) puts the
        // reconstructed point behind itself.
        val raw = arcorePose(0f, 0f, 0f, 0f, 0f)
        val wrong = CameraPose.unprojectToWorld(319, 239, 2f, raw, fx, fy, cx, cy)
        assertTrue("unconverted pose points the wrong way (+Z)", wrong[2] > 1f)

        val right = CameraPose.unprojectToWorld(319, 239, 2f, CameraPose.toPipelineSpace(raw), fx, fy, cx, cy)
        assertTrue("converted pose points in front of the camera (-Z)", right[2] < -1f)
    }

    @Test
    fun pixelRowsMapToTheRightHalfSpace() {
        // Top of the image (v < cy) is ABOVE the camera: world +Y for the
        // level, unrotated pose. Image columns map left->right to world -X/+X.
        val pose = CameraPose.toPipelineSpace(arcorePose(0f, 0f, 0f, 0f, 0f))
        val top = CameraPose.unprojectToWorld(319, 40, 2f, pose, fx, fy, cx, cy)
        val bottom = CameraPose.unprojectToWorld(319, 440, 2f, pose, fx, fy, cx, cy)
        val left = CameraPose.unprojectToWorld(60, 239, 2f, pose, fx, fy, cx, cy)

        assertTrue("top row is above the camera (y > 0), was ${top[1]}", top[1] > 0.1f)
        assertTrue("bottom row is below the camera (y < 0), was ${bottom[1]}", bottom[1] < -0.1f)
        assertTrue("left column is to the camera's left (x < 0), was ${left[0]}", left[0] < -0.1f)
        // Image centre is the camera centre axis at the sampled depth.
        val centre = CameraPose.unprojectToWorld(319, 239, 2f, pose, fx, fy, cx, cy)
        assertEquals(0f, centre[0], 0.05f)
        assertEquals(0f, centre[1], 0.05f)
        assertEquals(0f, centre[2] + 2f, 0.05f)
    }

    @Test
    fun fuserPlacesDepthInFrontOfTheArcCamera() {
        // Integration check through the real mesher: a flat wall 2 m ahead of
        // an ARCore camera must fuse into a plane at z = -2 (the camera looks
        // down -Z), not behind it.
        val w = 16; val h = 16
        val depth = ShortArray(w * h) { 2000.toShort() }
        val frame = DepthFrame(
            depthMm = depth,
            depthWidth = w,
            depthHeight = h,
            pose = CameraPose.toPipelineSpace(arcorePose(0f, 0f, 0f, 0f, 0f)),
            fx = 400f,
            fy = 400f,
            cx = (w - 1) / 2f,
            cy = (h - 1) / 2f
        )

        val fuser = DepthMeshFuser(voxelSize = 0.005f, maxTriangles = 100_000)
        fuser.fuseFrame(frame, minDepthM = 0.2f, maxDepthM = 8f, stride = 1)
        val mesh = fuser.buildMesh()

        assertTrue("wall should produce triangles, got ${mesh.triangleCount}", mesh.triangleCount > 0)
        var maxZDeviation = 0f
        var i = 0
        while (i < mesh.positions.size) {
            maxZDeviation = maxOf(maxZDeviation, abs(mesh.positions[i + 2] + 2f))
            i += 3
        }
        assertTrue(
            "fused wall should sit at z = -2 (in front of the AR camera), max deviation $maxZDeviation",
            maxZDeviation < 0.02f
        )
    }
}
