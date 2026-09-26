package com.example.splatter.processor.sfm

/**
 * Dense depth recovery by plane sweeping — the photo-only replacement for
 * the ARCore raw-depth maps used by the depth-assisted pipeline.
 *
 * For a reference frame, a coarse grid of pixels is unprojected into rays.
 * Each ray is sampled at [depthSteps] depths between [minDepthM] and
 * [maxDepthM]; the sample is projected into every supporting view and scored
 * by census-signature disagreement (photometric-consistency proxy). The
 * depth with the lowest average cost wins; high-cost rays are marked invalid.
 *
 * The output is a per-reference-frame pseudo depth map in exactly the
 * [DepthFrame] shape consumed by the existing, unit-tested
 * DepthMeshFuser — so mesh stitching, dedup, and noise filtering are reused.
 *
 * Pure Kotlin — no Android dependencies.
 */
class PlaneSweepStereo(
    private val gridWidth: Int = 128,
    private val gridHeight: Int = 96,
    private val minDepthM: Float,
    private val maxDepthM: Float,
    private val depthSteps: Int = 40,
    private val maxSupportViews: Int = 6,
    /** Cells whose mean census disagreement exceeds this are marked invalid (0..1). */
    private val maxCost: Float = 0.55f,
    /** Minimum (meanCost - bestCost) for a depth minimum to count as real texture. */
    private val flatnessMargin: Float = 0.02f
) {

    /** One view participating in a sweep (reference or support). */
    class SweepFrame(
        val pixels: IntArray,
        val width: Int,
        val height: Int,
        val pose: FloatArray,
        val fx: Float,
        val fy: Float,
        val cx: Float,
        val cy: Float
    )

    /** Sweep result: a depth map in the reference frame's ray space. */
    class PseudoDepth(
        val depthMm: ShortArray,
        val width: Int,
        val height: Int,
        val pose: FloatArray,
        val fx: Float,
        val fy: Float,
        val cx: Float,
        val cy: Float,
        /** Fraction of cells with valid depth — a quality signal for the caller. */
        val validFraction: Float
    )

    /**
     * Run the sweep with [frame] as reference and [supports] as comparison
     * views. Supports should be frames with camera poses estimated by SfM.
     */
    fun sweep(frame: SweepFrame, supports: List<SweepFrame>): PseudoDepth {
        val w = frame.width
        val h = frame.height
        val gray = ImageOps.boxBlur3x3(ImageOps.toGrayscale(frame.pixels), w, h)
        val census = ImageOps.census3x3(gray, w, h)

        val used = supports.take(maxSupportViews)
        val supportData = used.map { s ->
            val sg = ImageOps.boxBlur3x3(ImageOps.toGrayscale(s.pixels), s.width, s.height)
            Triple(s, sg, ImageOps.census3x3(sg, s.width, s.height))
        }

        // Precompute the 9-bit Hamming lookup (512 x 512)
        val hamming = IntArray(512 * 512)
        for (s1 in 0 until 512) {
            for (s2 in 0 until 512) {
                hamming[s1 * 512 + s2] = (s1 xor s2).countOneBits()
            }
        }

        val depthMm = ShortArray(gridWidth * gridHeight)
        var valid = 0
        val pose = frame.pose
        // Camera basis in world space (columns of the rotation block)
        val rx = floatArrayOf(pose[0], pose[1], pose[2])
        val ry = floatArrayOf(pose[4], pose[5], pose[6])
        val rz = floatArrayOf(pose[8], pose[9], pose[10])
        val cx0 = pose[12]; val cy0 = pose[13]; val cz0 = pose[14]

        val depths = FloatArray(depthSteps)
        for (s in 0 until depthSteps) {
            depths[s] = minDepthM + (maxDepthM - minDepthM) * s / (depthSteps - 1).coerceAtLeast(1)
        }

        for (gv in 0 until gridHeight) {
            val v = ((gv + 0.5f) * h / gridHeight).toInt().coerceIn(0, h - 1)
            for (gu in 0 until gridWidth) {
                val u = ((gu + 0.5f) * w / gridWidth).toInt().coerceIn(0, w - 1)
                val ci = v * w + u
                val sig = census[ci]
                // A flat reference pixel (all-zero census signature) matches
                // ANY flat support pixel at ANY consistent depth — it carries
                // no depth information and only fabricates surfaces. Reject
                // it up front; the median-filtered fusion fills such holes
                // from neighbouring textured cells.
                if (sig == 0) continue

                // Ray direction in world space
                val ucam = (u - frame.cx) / frame.fx
                val vcam = (v - frame.cy) / frame.fy
                val dirX = rx[0] * ucam + ry[0] * vcam + rz[0]
                val dirY = rx[1] * ucam + ry[1] * vcam + rz[1]
                val dirZ = rx[2] * ucam + ry[2] * vcam + rz[2]

                var bestCost = Float.MAX_VALUE
                var bestDepth = 0f
                var costSum = 0f

                for (s in 0 until depthSteps) {
                    val z = depths[s]
                    val X = cx0 + dirX * z
                    val Y = cy0 + dirY * z
                    val Z = cz0 + dirZ * z

                    var cost = 0f
                    var views = 0
                    for ((sup, _, sc) in supportData) {
                        // world -> camera
                        val dx = X - sup.pose[12]
                        val dy = Y - sup.pose[13]
                        val dz = Z - sup.pose[14]
                        val camZ = sup.pose[8] * dx + sup.pose[9] * dy + sup.pose[10] * dz
                        if (camZ < 0.05f) { cost += 0.7f; views++; continue }
                        val camX = sup.pose[0] * dx + sup.pose[1] * dy + sup.pose[2] * dz
                        val camY = sup.pose[4] * dx + sup.pose[5] * dy + sup.pose[6] * dz
                        val su = (sup.fx * camX / camZ + sup.cx).toInt()
                        val sv = (sup.fy * camY / camZ + sup.cy).toInt()
                        if (su < 1 || su >= sup.width - 1 || sv < 1 || sv >= sup.height - 1) {
                            cost += 0.7f
                        } else {
                            cost += hamming[sig * 512 + sc[sv * sup.width + su]] / 9f
                        }
                        views++
                    }
                    if (views == 0) break
                    val mean = cost / views
                    costSum += mean
                    if (mean < bestCost) {
                        bestCost = mean
                        bestDepth = z
                    }
                }

                val gi = gv * gridWidth + gu
                // A near-FLAT cost curve means the minimum is no more
                // informative than any other depth — reject it too.
                val meanCost = costSum / depthSteps
                val flat = (meanCost - bestCost) < flatnessMargin
                val atBoundary = bestDepth <= depths[0] + 1e-6f ||
                    bestDepth >= depths[depthSteps - 1] - 1e-6f
                if (bestCost <= maxCost && bestDepth > 0f && !flat && !atBoundary) {
                    depthMm[gi] = (bestDepth * 1000f).toInt().coerceIn(1, 65000).toShort()
                    valid++
                } else {
                    depthMm[gi] = 0
                }
            }
        }

        // Intrinsics scaled to the grid resolution so DepthMeshFuser can
        // unproject the pseudo depth map exactly like a real depth map
        val scaleX = gridWidth.toFloat() / w
        val scaleY = gridHeight.toFloat() / h
        return PseudoDepth(
            depthMm = depthMm,
            width = gridWidth,
            height = gridHeight,
            pose = pose,
            fx = frame.fx * scaleX,
            fy = frame.fy * scaleY,
            cx = frame.cx * scaleX,
            cy = frame.cy * scaleY,
            validFraction = valid.toFloat() / (gridWidth * gridHeight)
        )
    }
}
