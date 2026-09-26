package com.example.splatter.processor.sfm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.cos
import kotlin.math.sin

/**
 * End-to-end JVM tests for the photo-only pipeline on a synthetic textured
 * scene: a striped block floating in front of a checkered backdrop, rendered
 * analytically from known camera poses. No randomness — deterministic
 * renders, deterministic asserts.
 *
 * World layout:
 *  - backdrop plane at z = 5, checker 0.5 m
 *  - block: x in [-0.6, -0.2], y in [-0.4, 0.4], z in [2.8, 3.2] (front face
 *    at z = 2.8 with vertical stripes)
 *  - cameras on a small arc at z ~ 0, looking down +Z
 */
object SyntheticScene {

    val IMG_W = 192
    val IMG_H = 144
    val FX = 140f
    val FY = 140f
    val CX = (IMG_W - 1) / 2f
    val CY = (IMG_H - 1) / 2f

    fun cameraPose(tx: Float, ty: Float, tz: Float, yawRad: Float): FloatArray {
        val c = cos(yawRad); val s = sin(yawRad)
        // COLUMN-major cam-to-world: yaw about Y, translation last column
        return floatArrayOf(
            c, 0f, -s, 0f,
            0f, 1f, 0f, 0f,
            s, 0f, c, 0f,
            tx, ty, tz, 1f
        )
    }

    /** Four cameras on an arc with mild yaw, all seeing the block. */
    fun poses(): List<FloatArray> = listOf(
        cameraPose(0f, 0f, 0f, 0f),
        cameraPose(0.45f, 0f, 0.05f, 0.10f),
        cameraPose(-0.45f, 0.05f, 0.0f, -0.10f),
        cameraPose(0.15f, -0.1f, 0.35f, 0.04f)
    )

    /** Render one view of the scene (no lighting — albedo only). */
    fun render(pose: FloatArray): IntArray {
        val pixels = IntArray(IMG_W * IMG_H)
        // camera -> world rotation = pose rotation block (column-major)
        val r00 = pose[0]; val r01 = pose[4]; val r02 = pose[8]
        val r10 = pose[1]; val r11 = pose[5]; val r12 = pose[9]
        val r20 = pose[2]; val r21 = pose[6]; val r22 = pose[10]
        val px = pose[12]; val py = pose[13]; val pz = pose[14]

        for (v in 0 until IMG_H) {
            for (u in 0 until IMG_W) {
                val ucam = (u - CX) / FX
                val vcam = (v - CY) / FY
                val dirX = r00 * ucam + r01 * vcam + r02
                val dirY = r10 * ucam + r11 * vcam + r12
                val dirZ = r20 * ucam + r21 * vcam + r22

                var color = 0xFF202020.toInt() // background

                // Block front face z = 2.8, x in [-0.6,-0.2], y in [-0.4,0.4]
                if (dirZ > 1e-6f) {
                    val tb = (2.8f - pz) / dirZ
                    if (tb > 0f) {
                        val xb = px + dirX * tb
                        val yb = py + dirY * tb
                        if (xb in -0.6f..-0.2f && yb in -0.4f..0.4f) {
                            // 2D texture with NON-PERIODIC shading: identical
                            // repeating cells are ambiguous for NCC matching
                            // (which checker is which?) and break track chains
                            val cu = (xb / 0.05f).toInt()
                            val cv = (yb / 0.1f).toInt()
                            val shade = ((cu * 7 + cv * 13) % 4) * 0x20
                            color = if ((cu + cv) % 2 == 0)
                                (0xFFE02020.toInt() - shade) else (0xFFF0F0F0.toInt() - shade)
                        }
                    }
                    // Backdrop plane z = 5 (25 cm checker — fine enough that
                    // sample windows almost always contain texture edges)
                    val td = (5f - pz) / dirZ
                    if (td > 0f && color == 0xFF202020.toInt()) {
                        val xd = px + dirX * td
                        val yd = py + dirY * td
                        val cu = (xd / 0.25f).toInt()
                        val cv = (yd / 0.25f).toInt()
                        // Per-cell pseudo-random brightness kills periodicity
                        val h = ((cu * 31 + cv * 17 + 5) * 2654435761L ushr 28).toInt() % 5
                        val base = 0xB0 + h * 0x10
                        color = (0xFF000000.toInt()) or (base shl 16) or (((base + 40).coerceAtMost(255)) shl 8) or 0xD0
                    }
                }
                pixels[v * IMG_W + u] = color
            }
        }
        return pixels
    }
}

class FeatureDetectorTest {

    @Test
    fun `corners land on the block edges and checker vertices`() {
        val pixels = SyntheticScene.render(SyntheticScene.poses()[0])
        val gray = ImageOps.toGrayscale(pixels)
        val features = FeatureDetector.detect(gray, SyntheticScene.IMG_W, SyntheticScene.IMG_H, maxFeatures = 300)

        assertTrue("expected plenty of corners, got ${features.count}", features.count >= 120)

        // Features must be inside the image and off the border margin
        for (i in 0 until features.count) {
            assertTrue(features.x(i) in 5f..(SyntheticScene.IMG_W - 6).toFloat())
            assertTrue(features.y(i) in 5f..(SyntheticScene.IMG_H - 6).toFloat())
        }
    }

    @Test
    fun `grid spreads features across the image`() {
        val pixels = SyntheticScene.render(SyntheticScene.poses()[0])
        val gray = ImageOps.toGrayscale(pixels)
        val features = FeatureDetector.detect(gray, SyntheticScene.IMG_W, SyntheticScene.IMG_H, maxFeatures = 128, gridCells = 8)

        val cells = HashSet<Int>()
        for (i in 0 until features.count) {
            val cu = (features.x(i) / (SyntheticScene.IMG_W / 8f)).toInt().coerceIn(0, 7)
            val cv = (features.y(i) / (SyntheticScene.IMG_H / 8f)).toInt().coerceIn(0, 7)
            cells.add(cv * 8 + cu)
        }
        // The scene has texture almost everywhere; expect wide coverage
        assertTrue("covered ${cells.size}/64 cells", cells.size >= 40)
    }
}

class FeatureMatcherTest {

    @Test
    fun `matching between rendered views recovers the dominant shift`() {
        val poses = SyntheticScene.poses()
        val f0 = FeatureDetector.detect(
            ImageOps.toGrayscale(SyntheticScene.render(poses[0])),
            SyntheticScene.IMG_W, SyntheticScene.IMG_H, maxFeatures = 256
        )
        val f1 = FeatureDetector.detect(
            ImageOps.toGrayscale(SyntheticScene.render(poses[1])),
            SyntheticScene.IMG_W, SyntheticScene.IMG_H, maxFeatures = 256
        )
        val matches = FeatureMatcher.match(f0, f1, radius = 40f, censusMaxBits = 2, minNcc = 0.75f)
        assertTrue("expected matches between neighbouring views, got ${matches.size}", matches.size >= 60)

        // All matches must be mutual-best by construction and 1:1 in j
        val js = matches.map { it.j }
        assertEquals(js.size, js.toSet().size)
    }
}

/**
 * Full photo-only reconstruction of the synthetic scene: renders -> SfM ->
 * plane-sweep -> fused mesh, all in-process on the JVM.
 */
class SfmEndToEndTest {

    private fun buildFrames(): List<SfmFrame> {
        return SyntheticScene.poses().mapIndexed { i, pose ->
            SfmFrame(
                index = i,
                pixels = SyntheticScene.render(pose),
                width = SyntheticScene.IMG_W,
                height = SyntheticScene.IMG_H,
                fx = SyntheticScene.FX,
                fy = SyntheticScene.FY,
                cx = SyntheticScene.CX,
                cy = SyntheticScene.CY,
                arPose = pose
            )
        }
    }

    @Test
    fun `sfm registers all four frames and triangulates the block`() {
        val frames = buildFrames()
        val result = SfmReconstructor(
            maxFeatures = 400,
            matchRadius = 60f,
            ransacIterations = 200,
            maxReprojErrorPx = 1.5f
        ).reconstruct(frames)

        assertNotNull("SfM should succeed on the synthetic scene", result)
        result!!

        // All 4 frames registered
        assertEquals(4, result.poses.count { it != null })

        // Plenty of triangulated points. (Measured yield for this 192x144
        // scene with the census/NCC feature pipeline: ~60 consistent points
        // — the bar sits below that; the correctness assertions below are
        // the real check.)
        assertTrue("points=${result.pointsXyz.size}", result.pointsXyz.size >= 50)

        // The recovered camera positions should be spread by roughly the
        // true baseline (up to the arbitrary SfM scale): the distance between
        // camera 0 and camera 1 must be a plausible fraction of the scene size.
        val p0 = result.poses[0]!!
        val p1 = result.poses[1]!!
        val dx = p0[12] - p1[12]; val dy = p0[13] - p1[13]; val dz = p0[14] - p1[14]
        val baseline = kotlin.math.sqrt(dx * dx + dy * dy + dz * dz)

        // Scene extent from points — robust (10th..90th percentile of camera
        // distance), because the salvage pass can admit a few far outliers
        // that would dominate a min/max extent
        val c0 = floatArrayOf(p0[12], p0[13], p0[14])
        val dists2 = result.pointsXyz.map { p ->
            val d = floatArrayOf(p[0] - c0[0], p[1] - c0[1], p[2] - c0[2])
            kotlin.math.sqrt(SfmMath.dot3(d, d))
        }.sorted()
        val depthExtent = dists2[dists2.size * 9 / 10] - dists2[dists2.size / 10]
        assertTrue(
            "baseline $baseline vs depth extent $depthExtent",
            baseline > depthExtent * 0.05f && baseline < depthExtent * 2f
        )

        // The world block (x in [-0.6,-0.2]) and backdrop (z=5) are at
        // different depths in the scene; SfM's arbitrary scale preserves the
        // RATIO of block-depth to backdrop-depth (~2.8/5 = 0.56).
        // Nearest quartile should be clearly closer than the farthest octile.
        val dists = dists2
        val near = dists[dists.size / 4]
        val far = dists[dists.size - 1 - dists.size / 8]
        assertTrue("near=$near far=$far", far > near * 1.4f)
    }

    @Test
    fun `plane sweep recovers block and backdrop depths`() {
        val frames = buildFrames()
        val poses = SyntheticScene.poses()
        val sweep = PlaneSweepStereo(
            gridWidth = 96,
            gridHeight = 72,
            minDepthM = 1.5f,
            maxDepthM = 6.5f,
            depthSteps = 60,
            maxCost = 0.30f
        )

        val ref = PlaneSweepStereo.SweepFrame(
            frames[0].pixels, frames[0].width, frames[0].height,
            poses[0], SyntheticScene.FX, SyntheticScene.FY, SyntheticScene.CX, SyntheticScene.CY
        )
        val supports = (1 until 4).map { i ->
            PlaneSweepStereo.SweepFrame(
                frames[i].pixels, frames[i].width, frames[i].height,
                poses[i], SyntheticScene.FX, SyntheticScene.FY, SyntheticScene.CX, SyntheticScene.CY
            )
        }
        val depth = sweep.sweep(ref, supports)
        assertTrue("validFraction=${depth.validFraction}", depth.validFraction > 0.25f)

        // Sample the depth under the block (left-centre of the image) and in
        // a clear backdrop region (right edge). Block pixels: the block spans
        // x [-0.6,-0.2] at depth 2.8 => image u around CX + FX*(-0.4/2.8) ~ 70
        fun medianDepthAt(uc: Int, vc: Int): Float {
            val vals = ArrayList<Int>()
            for (dv in -4..4) {
                for (du in -4..4) {
                    val u = (uc + du).coerceIn(0, depth.width - 1)
                    val v = (vc + dv).coerceIn(0, depth.height - 1)
                    val d = depth.depthMm[v * depth.width + u].toInt() and 0xFFFF
                    if (d > 0) vals.add(d)
                }
            }
            if (vals.isEmpty()) return 0f
            vals.sort()
            return vals[vals.size / 2] / 1000f
        }

        // Block centre projects to u = CX + FX * (-0.4 / 2.8), v = CY.
        // medianDepthAt samples the GRID (depth.width x depth.height), so
        // convert image pixels to grid cells first.
        val blockU = (SyntheticScene.CX + SyntheticScene.FX * (-0.4f / 2.8f)).toInt()
        val blockGu = blockU * depth.width / SyntheticScene.IMG_W
        val blockGv = SyntheticScene.CY.toInt() * depth.height / SyntheticScene.IMG_H
        val blockDepth = medianDepthAt(blockGu, blockGv)
        // Backdrop sample: far right side at mid height, clear of the block
        val backDepth = medianDepthAt(depth.width - 8, depth.height / 2)

        assertTrue("block depth $blockDepth not found", blockDepth in 2.4f..3.3f)
        assertTrue("backdrop depth $backDepth not found", backDepth in 4.2f..6.0f)
        assertTrue("backdrop must be farther than block", backDepth > blockDepth + 1f)
    }

    @Test
    fun `sfm failure returns null instead of garbage`() {
        // Flat, textureless frames must fail cleanly
        val frames = listOf(
            SfmFrame(0, IntArray(64 * 48) { 0xFF808080.toInt() }, 64, 48, 60f, 60f, 31.5f, 23.5f),
            SfmFrame(1, IntArray(64 * 48) { 0xFF808080.toInt() }, 64, 48, 60f, 60f, 31.5f, 23.5f),
            SfmFrame(2, IntArray(64 * 48) { 0xFF808080.toInt() }, 64, 48, 60f, 60f, 31.5f, 23.5f)
        )
        val result = SfmReconstructor().reconstruct(frames)
        org.junit.Assert.assertNull(result)
    }
}
