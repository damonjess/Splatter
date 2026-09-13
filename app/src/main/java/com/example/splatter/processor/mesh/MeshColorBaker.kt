package com.example.splatter.processor.mesh

/**
 * One RGB view prepared for color baking.
 *
 * [pixels] is an ARGB IntArray (Android Bitmap.getPixels layout) of
 * [width] x [height]. [pose] is the column-major camera-to-world matrix.
 * [fx]/[fy]/[cx]/[cy] are intrinsics in the pixel space of [pixels].
 * The optional raw depth map is used for occlusion testing so a vertex is
 * only colored by views that actually see it.
 */
class PhotoView(
    val pixels: IntArray,
    val width: Int,
    val height: Int,
    val pose: FloatArray,
    val fx: Float,
    val fy: Float,
    val cx: Float,
    val cy: Float,
    val depthMm: ShortArray? = null,
    val depthWidth: Int = 0,
    val depthHeight: Int = 0
)

/**
 * Bakes photo colors onto a mesh by projecting every vertex into every view
 * and blending the observed pixel colors, weighted by viewing angle and
 * distance — the "photogrammetry look" half of the pipeline.
 *
 * Pure Kotlin — no Android dependencies. Requires mesh normals to be
 * computed beforehand (TriangleMesh.computeNormals).
 */
class MeshColorBaker(mesh: TriangleMesh) {

    private val positions = mesh.positions
    private val normals = mesh.normals
    private val vertexCount = mesh.vertexCount

    private val colorSumR = FloatArray(vertexCount)
    private val colorSumG = FloatArray(vertexCount)
    private val colorSumB = FloatArray(vertexCount)
    private val weightSum = FloatArray(vertexCount)

    /** Bakes colors from one view into the running weighted average. */
    fun bakeView(view: PhotoView) {
        val m = view.pose
        val camX0 = m[12]; val camY0 = m[13]; val camZ0 = m[14]

        val pixels = view.pixels
        val w = view.width
        val h = view.height
        val fx = view.fx; val fy = view.fy
        val cx = view.cx; val cy = view.cy

        val depth = view.depthMm
        val dW = view.depthWidth
        val dH = view.depthHeight

        for (i in 0 until vertexCount) {
            val p3 = i * 3
            val px = positions[p3]
            val py = positions[p3 + 1]
            val pz = positions[p3 + 2]

            val dx = px - camX0
            val dy = py - camY0
            val dz = pz - camZ0

            // world -> camera (rotate by R^T)
            val camX = m[0] * dx + m[1] * dy + m[2] * dz
            val camY = m[4] * dx + m[5] * dy + m[6] * dz
            val camZ = m[8] * dx + m[9] * dy + m[10] * dz
            if (camZ < 0.05f || camZ > 60f) continue

            // Project into the photo
            val invZ = 1f / camZ
            val u = (fx * camX * invZ + cx).toInt()
            val v = (fy * camY * invZ + cy).toInt()
            if (u < 0 || u >= w || v < 0 || v >= h) continue

            // Occlusion test against the raw depth map: only color the vertex
            // from views whose depth at this pixel agrees with the vertex depth
            if (depth != null && dW > 0 && dH > 0) {
                val du = (u * dW / w).coerceIn(0, dW - 1)
                val dv = (v * dH / h).coerceIn(0, dH - 1)
                val depthMm = (depth[dv * dW + du].toInt() and 0xFFFF)
                if (depthMm > 0) {
                    val depthM = depthMm / 1000f
                    val tolerance = maxOf(0.08f, 0.06f * camZ)
                    if (kotlin.math.abs(depthM - camZ) > tolerance) continue
                }
            }

            // View-dependent weight: facing angle^2 / distance^2.
            // NOTE: normals are world-space, so the facing dot product must use
            // the world-space camera→vertex vector (dx, dy, dz) — not the
            // camera-space projection vector.
            val nx = normals[p3]; val ny = normals[p3 + 1]; val nz = normals[p3 + 2]
            val dist = kotlin.math.sqrt(dx * dx + dy * dy + dz * dz)
            val facing = -(nx * dx + ny * dy + nz * dz) / dist
            if (facing <= 0.05f) continue

            val weight = (facing * facing) / (dist * dist + 0.05f)

            val color = pixels[v * w + u]
            colorSumR[i] += ((color ushr 16) and 0xFF) / 255f * weight
            colorSumG[i] += ((color ushr 8) and 0xFF) / 255f * weight
            colorSumB[i] += (color and 0xFF) / 255f * weight
            weightSum[i] += weight
        }
    }

    /**
     * Writes the blended colors back into the mesh. Vertices never seen by
     * any view keep their existing color.
     */
    fun applyTo(mesh: TriangleMesh) {
        val colors = mesh.colors
        for (i in 0 until vertexCount) {
            val w = weightSum[i]
            if (w > 1e-6f) {
                val p3 = i * 3
                colors[p3] = (colorSumR[i] / w).coerceIn(0f, 1f)
                colors[p3 + 1] = (colorSumG[i] / w).coerceIn(0f, 1f)
                colors[p3 + 2] = (colorSumB[i] / w).coerceIn(0f, 1f)
            }
        }
    }
}
