package com.example.splatter.processor.sfm

import org.junit.Test
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

class ScratchPnpDebug {
    @Test
    fun checkPnp() {
        val fx = 140f; val fy = 140f; val cx = 95.5f; val cy = 71.5f
        // Ground-truth camera like SyntheticScene camera 3
        val poseTrue = SyntheticScene.cameraPose(0.15f, -0.1f, 0.35f, 0.04f)
        // Points in general position (a line or plane is degenerate for DLT)
        val pts = ArrayList<FloatArray>()
        for (i in 0 until 80) {
            val gx = (i % 8) - 4
            val gy = ((i / 8) % 5) - 2
            val gz = i % 7
            pts.add(floatArrayOf(gx * 0.12f, gy * 0.18f, 2.6f + gz * 0.35f))
        }
        val xs = ArrayList<Float>(); val ys = ArrayList<Float>()
        for (p in pts) {
            val uv = SfmMath.projectWithPose(poseTrue, fx, fy, cx, cy, p[0], p[1], p[2])!!
            xs.add(uv[0]); ys.add(uv[1])
        }
        val rec = SfmReconstructor()
        val pose = rec.estimatePoseRansac(xs, ys, pts, fx, fy, cx, cy)
        if (pose == null) { println("PNP NULL"); return }
        fun fmt(m: FloatArray) = m.map { "%.4f".format(it) }.joinToString(",")
        println("poseTrue = " + fmt(poseTrue))
        println("poseEst  = " + fmt(pose))
        var maxErr = 0f
        for (i in 0 until 12) maxErr = maxOf(maxErr, abs(pose[i] - poseTrue[i]))
        // also check reprojected pixel error
        var px = 0f
        for (k in pts.indices) {
            val uv = SfmMath.projectWithPose(pose, fx, fy, cx, cy, pts[k][0], pts[k][1], pts[k][2])!!
            val dx = uv[0] - xs[k]; val dy = uv[1] - ys[k]
            px = maxOf(px, kotlin.math.sqrt(dx * dx + dy * dy))
        }
        println("max matrix diff = " + maxErr + "  max reproj px = " + px)
    }
}
