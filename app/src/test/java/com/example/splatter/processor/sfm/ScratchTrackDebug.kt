package com.example.splatter.processor.sfm

import org.junit.Test

class ScratchTrackDebug {
    @Test
    fun checkTracks() {
        val poses = SyntheticScene.poses()
        val frames = poses.mapIndexed { i, pose ->
            SfmFrame(i, SyntheticScene.render(pose), SyntheticScene.IMG_W, SyntheticScene.IMG_H,
                SyntheticScene.FX, SyntheticScene.FY, SyntheticScene.CX, SyntheticScene.CY, pose)
        }
        // Detect + match consecutively + chain (mirror of reconstructor)
        val features = frames.map { f ->
            FeatureDetector.detect(
                ImageOps.boxBlur3x3(ImageOps.toGrayscale(f.pixels), f.width, f.height),
                f.width, f.height, maxFeatures = 400, qualityThreshold = 0.06f, gridCells = 8
            )
        }
        val trackIds = Array(frames.size) { IntArray(features[it].count) { -1 } }
        var next = 0
        for (f in 0 until frames.size - 1) {
            val ms = FeatureMatcher.match(features[f], features[f + 1], radius = 60f, censusMaxBits = 2, minNcc = 0.75f)
            println("match $f->${f + 1}: " + ms.size)
            for (m in ms) {
                val e = trackIds[f][m.i]
                if (e >= 0) trackIds[f + 1][m.j] = e else {
                    trackIds[f][m.i] = next; trackIds[f + 1][m.j] = next; next++
                }
            }
        }
        val obs = HashMap<Int, MutableList<Track.Obs>>()
        for (f in frames.indices) for (i in trackIds[f].indices) {
            val id = trackIds[f][i]
            if (id >= 0) obs.getOrPut(id) { mutableListOf() }.add(Track.Obs(f, features[f].x(i), features[f].y(i)))
        }

        // For tracks visible in frames 0,1,2: triangulate from 0,1 with GT poses,
        // reproject into 2 with GT pose, compare with tracked position
        var checked = 0
        var good = 0
        var errSum = 0f
        for ((_, o) in obs) {
            if (o.size < 3) continue
            val o01 = o.filter { it.frame <= 1 }
            val o2 = o.firstOrNull { it.frame == 2 } ?: continue
            if (o01.size < 2) continue
            val mats = o01.map { SfmMath.projectionMatrix(poses[it.frame], SyntheticScene.FX, SyntheticScene.FY, SyntheticScene.CX, SyntheticScene.CY) }
            val X = SfmMath.triangulate(mats, FloatArray(o01.size) { o01[it].x }, FloatArray(o01.size) { o01[it].y }) ?: continue
            val uv2 = SfmMath.projectWithPose(poses[2], SyntheticScene.FX, SyntheticScene.FY, SyntheticScene.CX, SyntheticScene.CY, X[0], X[1], X[2]) ?: continue
            val dx = uv2[0] - o2.x; val dy = uv2[1] - o2.y
            val err = kotlin.math.sqrt(dx * dx + dy * dy)
            checked++
            if (err < 2f) good++
            errSum += err
        }
        println("tracks checked=$checked consistent(<2px)=$good meanErr=" + errSum / checked.coerceAtLeast(1))
    }
}
