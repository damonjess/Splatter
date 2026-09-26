package com.example.splatter.processor.sfm

import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Pure-Kotlin math core for the photo-only SfM ("Photo SfM") pipeline.
 *
 * Conventions used throughout the sfm package:
 *  - 3x3 rotation matrices are row-major FloatArray(9): element (r, c) = m[r * 3 + c]
 *  - Camera poses are 16-float column-major camera-to-world matrices,
 *    exactly like the ARCore poses saved by FrameSaver (camera looks down
 *    +Z in camera space, X right, Y down)
 *  - Cameras project with (u, v) = (fx * x / z + cx, fy * y / z + cy)
 *
 * No Android dependencies — unit-testable on the JVM.
 */
object SfmMath {

    // ---- small 3D vector helpers ----

    fun dot3(a: FloatArray, b: FloatArray): Float = a[0] * b[0] + a[1] * b[1] + a[2] * b[2]

    fun cross3(a: FloatArray, b: FloatArray): FloatArray = floatArrayOf(
        a[1] * b[2] - a[2] * b[1],
        a[2] * b[0] - a[0] * b[2],
        a[0] * b[1] - a[1] * b[0]
    )

    fun norm3(a: FloatArray): Float = sqrt(a[0] * a[0] + a[1] * a[1] + a[2] * a[2])

    fun normalize3(a: FloatArray): FloatArray {
        val n = norm3(a)
        return if (n > 1e-12f) floatArrayOf(a[0] / n, a[1] / n, a[2] / n) else FloatArray(3)
    }

    fun identity3(): FloatArray = floatArrayOf(
        1f, 0f, 0f,
        0f, 1f, 0f,
        0f, 0f, 1f
    )

    fun identityPose(): FloatArray = floatArrayOf(
        1f, 0f, 0f, 0f,
        0f, 1f, 0f, 0f,
        0f, 0f, 1f, 0f,
        0f, 0f, 0f, 1f
    )

    // ---- rotations ----

    /** Rodrigues formula: angle-axis vector (radians, direction = axis) -> 3x3 row-major rotation. */
    fun rotationFromAngleAxis(w: FloatArray): FloatArray {
        val theta = norm3(w)
        if (theta < 1e-12f) return identity3()
        val k = floatArrayOf(w[0] / theta, w[1] / theta, w[2] / theta)
        val c = kotlin.math.cos(theta)
        val s = kotlin.math.sin(theta)
        val c1 = 1f - c
        return floatArrayOf(
            c + k[0] * k[0] * c1, k[0] * k[1] * c1 - k[2] * s, k[0] * k[2] * c1 + k[1] * s,
            k[1] * k[0] * c1 + k[2] * s, c + k[1] * k[1] * c1, k[1] * k[2] * c1 - k[0] * s,
            k[2] * k[0] * c1 - k[1] * s, k[2] * k[1] * c1 + k[0] * s, c + k[2] * k[2] * c1
        )
    }

    /** Inverse of [rotationFromAngleAxis]: 3x3 row-major rotation -> angle-axis vector. */
    fun rotationToAngleAxis(R: FloatArray): FloatArray {
        val cosTheta = (R[0] + R[4] + R[8] - 1f) * 0.5f
        val theta = kotlin.math.acos(cosTheta.coerceIn(-1f, 1f))
        if (theta < 1e-7f) return FloatArray(3)
        val sinTheta = kotlin.math.sin(theta)
        if (abs(sinTheta) < 1e-7f) return FloatArray(3)
        val k = floatArrayOf(
            (R[7] - R[5]) / (2f * sinTheta),
            (R[2] - R[6]) / (2f * sinTheta),
            (R[3] - R[1]) / (2f * sinTheta)
        )
        return floatArrayOf(k[0] * theta, k[1] * theta, k[2] * theta)
    }

    /** Row-major 3x3 matrix product a * b. */
    fun multiply3(a: FloatArray, b: FloatArray): FloatArray {
        val out = FloatArray(9)
        for (r in 0 until 3) {
            for (c in 0 until 3) {
                out[r * 3 + c] = a[r * 3] * b[c] + a[r * 3 + 1] * b[3 + c] + a[r * 3 + 2] * b[6 + c]
            }
        }
        return out
    }

    /** v' = R * v with R row-major 3x3. */
    fun rotate3(R: FloatArray, v: FloatArray): FloatArray = floatArrayOf(
        R[0] * v[0] + R[1] * v[1] + R[2] * v[2],
        R[3] * v[0] + R[4] * v[1] + R[5] * v[2],
        R[6] * v[0] + R[7] * v[1] + R[8] * v[2]
    )

    /** v' = R^T * v (world -> camera for a camera-to-world row-major rotation R). */
    fun rotate3T(R: FloatArray, v: FloatArray): FloatArray = floatArrayOf(
        R[0] * v[0] + R[3] * v[1] + R[6] * v[2],
        R[1] * v[0] + R[4] * v[1] + R[7] * v[2],
        R[2] * v[0] + R[5] * v[1] + R[8] * v[2]
    )

    /** determinant of a row-major 3x3. */
    fun det3(R: FloatArray): Float =
        R[0] * (R[4] * R[8] - R[5] * R[7]) -
            R[1] * (R[3] * R[8] - R[5] * R[6]) +
            R[2] * (R[3] * R[7] - R[4] * R[6])

    // ---- pose helpers (16-float column-major camera-to-world) ----

    /** Camera center in world space = translation column of the pose. */
    fun poseTranslation(pose: FloatArray): FloatArray =
        floatArrayOf(pose[12], pose[13], pose[14])

    /**
     * World -> camera coordinates for a camera-to-world pose:
     * Xc = R^T * (Xw - t).
     */
    fun worldToCamera(pose: FloatArray, xw: Float, yw: Float, zw: Float): FloatArray {
        val dx = xw - pose[12]
        val dy = yw - pose[13]
        val dz = zw - pose[14]
        // R^T * d (rotation block is columns 0..2 of the column-major matrix)
        return floatArrayOf(
            pose[0] * dx + pose[1] * dy + pose[2] * dz,
            pose[4] * dx + pose[5] * dy + pose[6] * dz,
            pose[8] * dx + pose[9] * dy + pose[10] * dz
        )
    }

    // ---- symmetric eigensolver ----

    /**
     * Eigen-decomposition of a symmetric n x n matrix (row-major, n <= 16)
     * via cyclic Jacobi rotations, computed in double precision.
     *
     * Returns eigenvalues in DESCENDING order with the matching eigenvectors
     * as COLUMNS of the returned n x n row-major matrix:
     * eigenvector for eigenvalue i is (v[i], v[n + i], v[2n + i], ...).
     */
    fun jacobiEigen(aIn: DoubleArray, n: Int, maxSweeps: Int = 100): Pair<DoubleArray, DoubleArray> {
        val a = aIn.copyOf()
        val v = DoubleArray(n * n)
        for (i in 0 until n) v[i * n + i] = 1.0

        repeat(maxSweeps) {
            // Off-diagonal energy
            var off = 0.0
            for (p in 0 until n - 1) {
                for (q in p + 1 until n) {
                    off += a[p * n + q] * a[p * n + q]
                }
            }
            if (off < 1e-24) return@repeat

            for (p in 0 until n - 1) {
                for (q in p + 1 until n) {
                    val apq = a[p * n + q]
                    if (abs(apq) < 1e-18) continue
                    val theta = (a[q * n + q] - a[p * n + p]) / (2.0 * apq)
                    val sign = if (theta >= 0.0) 1.0 else -1.0
                    val t = sign / (abs(theta) + sqrt(theta * theta + 1.0))
                    val c = 1.0 / sqrt(t * t + 1.0)
                    val s = t * c

                    // A <- J^T A J with J the Givens rotation in the (p, q)
                    // plane — computed element-wise from saved values (the
                    // naive two-pass in-place variant corrupts rows p/q).
                    val app = a[p * n + p]
                    val aqq = a[q * n + q]
                    a[p * n + p] = c * c * app - 2.0 * s * c * apq + s * s * aqq
                    a[q * n + q] = s * s * app + 2.0 * s * c * apq + c * c * aqq
                    a[p * n + q] = 0.0
                    a[q * n + p] = 0.0
                    for (k in 0 until n) {
                        if (k == p || k == q) continue
                        val akp = a[k * n + p]
                        val akq = a[k * n + q]
                        val nkp = c * akp - s * akq
                        val nkq = s * akp + c * akq
                        a[k * n + p] = nkp
                        a[p * n + k] = nkp // symmetry
                        a[k * n + q] = nkq
                        a[q * n + k] = nkq
                    }
                    for (k in 0 until n) {
                        val vkp = v[k * n + p]
                        val vkq = v[k * n + q]
                        v[k * n + p] = c * vkp - s * vkq
                        v[k * n + q] = s * vkp + c * vkq
                    }
                }
            }
        }

        // Sort descending by diagonal
        data class Eigen(val value: Double, val index: Int)
        val eigs = (0 until n).map { Eigen(a[it * n + it], it) }.sortedByDescending { it.value }
        val values = DoubleArray(n) { eigs[it].value }
        val vectors = DoubleArray(n * n)
        for (i in 0 until n) {
            val src = eigs[i].index
            for (r in 0 until n) {
                vectors[i * n + r] = v[r * n + src]
            }
        }
        return values to vectors
    }

    /** Float convenience wrapper for [jacobiEigen]. */
    fun jacobiEigen(aIn: FloatArray, n: Int, maxSweeps: Int = 100): Pair<FloatArray, FloatArray> {
        val (values, vectors) = jacobiEigen(DoubleArray(aIn.size) { aIn[it].toDouble() }, n, maxSweeps)
        return FloatArray(values.size) { values[it].toFloat() } to
            FloatArray(vectors.size) { vectors[it].toFloat() }
    }

    /** Solve a small symmetric positive-definite system A x = b via the eigensolver. */
    fun solveSymmetric(a: FloatArray, b: FloatArray, n: Int): FloatArray? {
        val (values, vectors) = jacobiEigen(a, n)
        val x = FloatArray(n)
        for (i in 0 until n) {
            if (abs(values[i]) < 1e-12f) continue
            // x = sum_i (v_i . b / lambda_i) * v_i
            var dot = 0f
            for (r in 0 until n) dot += vectors[i * n + r] * b[r]
            val coeff = dot / values[i]
            for (r in 0 until n) x[r] += coeff * vectors[i * n + r]
        }
        return x
    }

    // ---- projection / triangulation ----

    /**
     * Build the 3x4 row-major projection matrix P = K [R^T | -R^T t] for a
     * camera-to-world [pose] (column-major 16 floats) and pixel intrinsics.
     */
    fun projectionMatrix(pose: FloatArray, fx: Float, fy: Float, cx: Float, cy: Float): FloatArray {
        val P = FloatArray(12)
        // world->camera rotation rows: R^T[r][c] = pose[r*4 + c]
        // (columns of the column-major pose rotation block are the rows of R^T)
        val r00 = pose[0]; val r01 = pose[1]; val r02 = pose[2]
        val r10 = pose[4]; val r11 = pose[5]; val r12 = pose[6]
        val r20 = pose[8]; val r21 = pose[9]; val r22 = pose[10]
        val tx = pose[12]; val ty = pose[13]; val tz = pose[14]
        // t_cam = -R^T t
        val tcx = -(r00 * tx + r01 * ty + r02 * tz)
        val tcy = -(r10 * tx + r11 * ty + r12 * tz)
        val tcz = -(r20 * tx + r21 * ty + r22 * tz)

        // Row 0: fx * R^T[0,*] + cx * R^T[2,*]
        P[0] = fx * r00 + cx * r20; P[1] = fx * r01 + cx * r21; P[2] = fx * r02 + cx * r22; P[3] = fx * tcx + cx * tcz
        // Row 1: fy * R^T[1,*] + cy * R^T[2,*]
        P[4] = fy * r10 + cy * r20; P[5] = fy * r11 + cy * r21; P[6] = fy * r12 + cy * r22; P[7] = fy * tcy + cy * tcz
        // Row 2: R^T[2,*]
        P[8] = r20; P[9] = r21; P[10] = r22; P[11] = tcz
        return P
    }

    /**
     * DLT triangulation of a 3D point from two or more views.
     *
     * [mats] are 3x4 row-major projection matrices and [us]/[vs] the pixel
     * observations; both lists must have the same length (>= 2). Solves the
     * homogeneous system via the 4x4 normal equations and returns the world
     * point, or null when the system is degenerate.
     */
    fun triangulate(
        mats: List<FloatArray>,
        us: FloatArray,
        vs: FloatArray,
        maxReprojErrorPx: Float = Float.MAX_VALUE
    ): FloatArray? {
        if (mats.size < 2 || mats.size != us.size || mats.size != vs.size) return null

        val ata = DoubleArray(16)
        for (k in mats.indices) {
            val P = mats[k]
            val u = us[k].toDouble(); val v = vs[k].toDouble()
            // Rows: u * P2 - P0, v * P2 - P1
            val rows = arrayOf(
                doubleArrayOf(u * P[8] - P[0], u * P[9] - P[1], u * P[10] - P[2], u * P[11] - P[3]),
                doubleArrayOf(v * P[8] - P[4], v * P[9] - P[5], v * P[10] - P[6], v * P[11] - P[7])
            )
            for (row in rows) {
                for (i in 0 until 4) {
                    for (j in 0 until 4) {
                        ata[i * 4 + j] += row[i] * row[j]
                    }
                }
            }
        }

        val (values, vectors) = jacobiEigen(ata, 4)
        // Eigenvector for the smallest eigenvalue (descending order => index 3)
        val h = DoubleArray(4)
        for (i in 0 until 4) h[i] = vectors[3 * 4 + i]
        // Rank must be >= 3. With rank <= 2 (identical cameras, parallel
        // rays, ...) the null space is 2D+ and the recovered point is an
        // arbitrary mix of the ray family — not a unique intersection.
        // (The smallest eigenvalue itself is NOT a degeneracy signal:
        // well-conditioned systems are full rank 4.)
        if (values[2] <= 1e-10 * kotlin.math.abs(values[0]) + 1e-14) return null
        if (abs(h[3]) < 1e-12) return null

        val x = (h[0] / h[3]).toFloat()
        val y = (h[1] / h[3]).toFloat()
        val z = (h[2] / h[3]).toFloat()
        if (!x.isFinite() || !y.isFinite() || !z.isFinite()) return null
        if (abs(x) > 1e5f || abs(y) > 1e5f || abs(z) > 1e5f) return null
        if (maxReprojErrorPx.isFinite()) {
            for (k in mats.indices) {
                val p = project(mats[k], floatArrayOf(x, y, z, 1f)) ?: return null
                val dx = p[0] - us[k]
                val dy = p[1] - vs[k]
                if (dx * dx + dy * dy > maxReprojErrorPx * maxReprojErrorPx) return null
            }
        }
        return floatArrayOf(x, y, z)
    }

    /** Project a homogeneous world point with a 3x4 row-major matrix; null if behind the camera. */
    fun project(P: FloatArray, X: FloatArray): FloatArray? {
        val z = P[8] * X[0] + P[9] * X[1] + P[10] * X[2] + P[11]
        if (z <= 1e-6f) return null
        val u = (P[0] * X[0] + P[1] * X[1] + P[2] * X[2] + P[3]) / z
        val v = (P[4] * X[0] + P[5] * X[1] + P[6] * X[2] + P[7]) / z
        return floatArrayOf(u, v)
    }

    /**
     * Reproject a world point into a camera given its camera-to-world pose and
     * intrinsics; null if the point is behind the camera.
     */
    fun projectWithPose(
        pose: FloatArray,
        fx: Float, fy: Float, cx: Float, cy: Float,
        x: Float, y: Float, z: Float
    ): FloatArray? {
        val c = worldToCamera(pose, x, y, z)
        if (c[2] <= 1e-4f) return null
        return floatArrayOf(fx * c[0] / c[2] + cx, fy * c[1] / c[2] + cy)
    }
}
