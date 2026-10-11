package com.example.splatter.processor

import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Converts the camera poses ARCore saves into the camera frame the
 * reconstruction stages expect.
 *
 * ARCore documents `Camera.getPose()` as an OpenGL camera pose — **+X right,
 * +Y up, -Z forward** — and the CPU image is a top-to-bottom row-major array,
 * so a world point projects into it as
 *
 * ```
 * u = cx + fx * X / -Z
 * v = cy - fy * Y / -Z
 * ```
 *
 * Every stage in this app ([DepthMeshFuser], [MeshColorBaker],
 * [PlaneSweepStereo], [GaussianSplatTrainer], ...) instead works in the
 * "pixel-aligned" frame those equations imply — **x right, y down, z
 * forward** — i.e. camera space is the ARCore camera frame rotated 180°
 * about its X axis:
 *
 * ```
 * (x, y, z)pipeline = (X, -Y, -Z)arcore
 * ```
 *
 * Feeding raw ARCore poses into those stages mixes two different camera
 * frames. Each frame's cloud then comes out rotated 180° about *its own* X
 * axis, so clouds from different viewpoints never line up and the fused
 * model scrambles into noise — which is why scans could come out empty or
 * unrecognisable.
 *
 * Rotating the pose itself, `R' = R * diag(1, -1, -1)`, is enough and costs
 * nothing per point. In the flat column-major layout that means negating the
 * camera's Y basis column (indices 4..6) and Z basis column (indices 8..10);
 * the camera centre (12..14) is untouched.
 */
object CameraPose {

    /** Converts one ARCore camera-to-world matrix into pipeline camera space. */
    fun toPipelineSpace(pose: FloatArray): FloatArray {
        if (pose.size < 16) return pose
        val out = pose.copyOf()
        out[4] = -out[4]; out[5] = -out[5]; out[6] = -out[6]      // camera Y axis (up -> down)
        out[8] = -out[8]; out[9] = -out[9]; out[10] = -out[10]    // camera Z axis (backward -> forward)
        return out
    }

    /** Parses the comma-separated pose file written by `FrameSaver`, converting it. */
    fun parsePoseFile(text: String): FloatArray? {
        val parts = text.trim().split(",")
        if (parts.size != 16) return null
        val raw = FloatArray(16)
        for (i in 0 until 16) {
            raw[i] = parts[i].trim().toFloatOrNull() ?: return null
        }
        return toPipelineSpace(raw)
    }

    /**
     * Unprojects one depth pixel into world space using a **pipeline-space**
     * pose (see [toPipelineSpace]). Shared so the depth, photo-mesh and
     * photo-SfM paths can never drift apart on conventions.
     */
    fun unprojectToWorld(
        u: Int,
        v: Int,
        depthMeters: Float,
        pose: FloatArray,
        fx: Float,
        fy: Float,
        cx: Float,
        cy: Float
    ): FloatArray {
        val x = (u - cx) * depthMeters / fx
        val y = (v - cy) * depthMeters / fy
        val z = depthMeters
        return floatArrayOf(
            pose[0] * x + pose[4] * y + pose[8] * z + pose[12],
            pose[1] * x + pose[5] * y + pose[9] * z + pose[13],
            pose[2] * x + pose[6] * y + pose[10] * z + pose[14]
        )
    }

    /** Camera centre in world space (translation column). */
    fun cameraCenter(pose: FloatArray): FloatArray =
        floatArrayOf(pose[12], pose[13], pose[14])

    /** True when the rotation block is a proper rotation (det ≈ +1, no mirroring). */
    fun isProperRotation(pose: FloatArray): Boolean {
        if (pose.size < 16) return false
        val c0 = floatArrayOf(pose[0], pose[1], pose[2])
        val c1 = floatArrayOf(pose[4], pose[5], pose[6])
        val c2 = floatArrayOf(pose[8], pose[9], pose[10])
        for (c in listOf(c0, c1, c2)) {
            if (abs(sqrt(c[0] * c[0] + c[1] * c[1] + c[2] * c[2]) - 1f) > 1e-3f) return false
        }
        val det =
            c0[0] * (c1[1] * c2[2] - c1[2] * c2[1]) -
                c0[1] * (c1[0] * c2[2] - c1[2] * c2[0]) +
                c0[2] * (c1[0] * c2[1] - c1[1] * c2[0])
        return det > 0.99f
    }
}
