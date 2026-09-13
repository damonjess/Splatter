package com.example.splatter.processor.mesh

/**
 * A triangle mesh with per-vertex positions, colors and (computed) normals.
 *
 * Pure Kotlin — no Android dependencies — so the geometry pipeline can be
 * unit tested on the JVM.
 *
 * Layout:
 *  - [positions]: 3 floats per vertex (x, y, z), world space
 *  - [colors]:    3 floats per vertex (r, g, b), linear 0..1
 *  - [normals]:   3 floats per vertex, computed by [computeNormals]
 *  - [triangles]: 3 ints per face (vertex indices)
 */
class TriangleMesh(
    positions: FloatArray,
    colors: FloatArray,
    triangles: IntArray,
    normals: FloatArray? = null
) {
    var positions: FloatArray = positions
        private set
    var colors: FloatArray = colors
        private set
    var normals: FloatArray = normals ?: FloatArray(positions.size)
        private set
    var triangles: IntArray = triangles
        private set

    val vertexCount: Int get() = positions.size / 3
    val triangleCount: Int get() = triangles.size / 3

    /** Compute per-vertex normals as the area-weighted average of face normals. */
    fun computeNormals() {
        val n = normals
        java.util.Arrays.fill(n, 0f)
        val t = triangles
        val p = positions
        for (f in t.indices step 3) {
            val ia = t[f] * 3
            val ib = t[f + 1] * 3
            val ic = t[f + 2] * 3
            val ax = p[ia]; val ay = p[ia + 1]; val az = p[ia + 2]
            val bx = p[ib]; val by = p[ib + 1]; val bz = p[ib + 2]
            val cx = p[ic]; val cy = p[ic + 1]; val cz = p[ic + 2]
            // Cross product (b - a) x (c - a)
            val e1x = bx - ax; val e1y = by - ay; val e1z = bz - az
            val e2x = cx - ax; val e2y = cy - ay; val e2z = cz - az
            val nx = e1y * e2z - e1z * e2y
            val ny = e1z * e2x - e1x * e2z
            val nz = e1x * e2y - e1y * e2x
            n[ia] += nx; n[ia + 1] += ny; n[ia + 2] += nz
            n[ib] += nx; n[ib + 1] += ny; n[ib + 2] += nz
            n[ic] += nx; n[ic + 1] += ny; n[ic + 2] += nz
        }
        for (i in n.indices step 3) {
            val len = kotlin.math.sqrt(n[i] * n[i] + n[i + 1] * n[i + 1] + n[i + 2] * n[i + 2])
            if (len > 1e-12f) {
                val inv = 1f / len
                n[i] *= inv; n[i + 1] *= inv; n[i + 2] *= inv
            } else {
                n[i] = 0f; n[i + 1] = 1f; n[i + 2] = 0f
            }
        }
    }

    /** Axis-aligned bounding box: returns [minX, minY, minZ, maxX, maxY, maxZ]. */
    fun boundingBox(): FloatArray {
        if (vertexCount == 0) return FloatArray(6)
        val p = positions
        var minX = p[0]; var minY = p[1]; var minZ = p[2]
        var maxX = p[0]; var maxY = p[1]; var maxZ = p[2]
        for (i in 3 until p.size step 3) {
            if (p[i] < minX) minX = p[i]
            if (p[i + 1] < minY) minY = p[i + 1]
            if (p[i + 2] < minZ) minZ = p[i + 2]
            if (p[i] > maxX) maxX = p[i]
            if (p[i + 1] > maxY) maxY = p[i + 1]
            if (p[i + 2] > maxZ) maxZ = p[i + 2]
        }
        return floatArrayOf(minX, minY, minZ, maxX, maxY, maxZ)
    }

    /** Simple Laplacian smoothing to reduce jagged noise from raw depth maps. */
    fun laplacianSmooth(iterations: Int = 2, alpha: Float = 0.5f) {
        if (iterations <= 0 || vertexCount == 0 || triangleCount == 0) return

        val head = IntArray(vertexCount) { -1 }
        val next = IntArray(triangleCount * 6)
        val to = IntArray(triangleCount * 6)
        var edgeCount = 0

        val t = triangles
        for (f in 0 until triangleCount) {
            val a = t[f * 3]
            val b = t[f * 3 + 1]
            val cIdx = t[f * 3 + 2]

            to[edgeCount] = b; next[edgeCount] = head[a]; head[a] = edgeCount++
            to[edgeCount] = a; next[edgeCount] = head[b]; head[b] = edgeCount++
            to[edgeCount] = cIdx; next[edgeCount] = head[b]; head[b] = edgeCount++
            to[edgeCount] = b; next[edgeCount] = head[cIdx]; head[cIdx] = edgeCount++
            to[edgeCount] = a; next[edgeCount] = head[cIdx]; head[cIdx] = edgeCount++
            to[edgeCount] = cIdx; next[edgeCount] = head[a]; head[a] = edgeCount++
        }

        val p = positions
        val newP = FloatArray(p.size)

        for (iter in 0 until iterations) {
            for (i in 0 until vertexCount) {
                var sumX = 0f; var sumY = 0f; var sumZ = 0f
                var count = 0
                var e = head[i]
                while (e != -1) {
                    val neighbor = to[e]
                    sumX += p[neighbor * 3]
                    sumY += p[neighbor * 3 + 1]
                    sumZ += p[neighbor * 3 + 2]
                    count++
                    e = next[e]
                }

                if (count > 0) {
                    val inv = 1f / count
                    newP[i * 3] = p[i * 3] + alpha * (sumX * inv - p[i * 3])
                    newP[i * 3 + 1] = p[i * 3 + 1] + alpha * (sumY * inv - p[i * 3 + 1])
                    newP[i * 3 + 2] = p[i * 3 + 2] + alpha * (sumZ * inv - p[i * 3 + 2])
                } else {
                    newP[i * 3] = p[i * 3]; newP[i * 3 + 1] = p[i * 3 + 1]; newP[i * 3 + 2] = p[i * 3 + 2]
                }
            }
            System.arraycopy(newP, 0, p, 0, p.size)
        }
    }
}

/** Minimal growable FloatArray — avoids boxing and repeated full copies. */
internal class FloatList(initialCapacity: Int = 1024) {
    private var data = FloatArray(initialCapacity)
    var size = 0
        private set

    operator fun get(index: Int): Float = data[index]

    fun add(value: Float) {
        if (size == data.size) data = data.copyOf(data.size * 2)
        data[size++] = value
    }

    fun add(a: Float, b: Float, c: Float) {
        if (size + 3 > data.size) data = data.copyOf(maxOf(data.size * 2, size + 3))
        data[size] = a; data[size + 1] = b; data[size + 2] = c
        size += 3
    }

    fun trim(): FloatArray = data.copyOf(size)
}

/** Minimal growable IntArray. */
internal class IntList(initialCapacity: Int = 1024) {
    private var data = IntArray(initialCapacity)
    var size = 0
        private set

    operator fun get(index: Int): Int = data[index]

    fun add(value: Int) {
        if (size == data.size) data = data.copyOf(data.size * 2)
        data[size++] = value
    }

    fun add(a: Int, b: Int, c: Int) {
        if (size + 3 > data.size) data = data.copyOf(maxOf(data.size * 2, size + 3))
        data[size] = a; data[size + 1] = b; data[size + 2] = c
        size += 3
    }

    fun trim(): IntArray = data.copyOf(size)
}
