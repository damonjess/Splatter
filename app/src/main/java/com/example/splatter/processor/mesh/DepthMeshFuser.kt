package com.example.splatter.processor.mesh

import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * One depth frame prepared for fusion.
 *
 * [depthMm] is a row-major array of [depthWidth] x [depthHeight] millimetre
 * depths (0 = invalid). [pose] is the 16-float column-major OpenGL-style
 * matrix mapping camera space to world space (camera looks down +Z in camera
 * space, X right, Y down — matching ARCore unprojection used elsewhere).
 * [fx], [fy], [cx], [cy] are intrinsics already scaled to the DEPTH image
 * resolution.
 */
class DepthFrame(
    val depthMm: ShortArray,
    val depthWidth: Int,
    val depthHeight: Int,
    val pose: FloatArray,
    val fx: Float,
    val fy: Float,
    val cx: Float,
    val cy: Float
)

/**
 * Fuses depth frames into a single triangle mesh — the geometry half of the
 * "Photo Mesh" (photogrammetry-style) pipeline.
 *
 * For every frame, the depth grid is unprojected to world space and
 * neighbouring depth samples are connected into triangles, subject to:
 *  - depth continuity (no triangles stretched across depth jumps)
 *  - a maximum edge length (no giant sliver triangles)
 *  - coverage suppression (quads whose corners were already meshed by an
 *    earlier frame are skipped, so overlapping views don't produce duplicate
 *    coincident surfaces)
 *
 * Vertices are shared between triangles via a voxel-quantized spatial hash,
 * which stitches neighbouring frames together along shared surfaces.
 *
 * Pure Kotlin — no Android dependencies.
 */
class DepthMeshFuser(
    private val voxelSize: Float,
    private val maxTriangles: Int,
    private val maxEdgeLengthFactor: Float = 10f,
    /** Connected components with fewer triangles than this are dropped as noise. */
    private val minComponentTriangles: Int = 15
) {
    private val maxEdgeLength = voxelSize * maxEdgeLengthFactor

    private val vertexIndexByVoxel = HashMap<Long, Int>()
    private val voxelCoveredByFrame = HashMap<Long, Int>()
    private val positions = FloatList(1 shl 16)
    private val triangles = IntList(1 shl 16)

    private var currentFrame = -1

    data class FuseStats(val trianglesAdded: Int, val totalTriangles: Int, val totalVertices: Int)

    val isFull: Boolean get() = triangles.size / 3 >= maxTriangles

    /** Current number of fused triangles (live progress reporting). */
    val triangleCount: Int get() = triangles.size / 3

    /** Current number of unique vertices. */
    val vertexCount: Int get() = positions.size / 3

    /**
     * Fuse one frame. [minDepthM]/[maxDepthM] clip the accepted depth range;
     * [stride] skips depth samples so one quad edge spans roughly the voxel
     * size (see DepthFilters.suggestStride). Returns stats for this frame.
     */
    fun fuseFrame(
        frame: DepthFrame,
        minDepthM: Float,
        maxDepthM: Float,
        stride: Int = 1
    ): FuseStats {
        currentFrame++
        val trianglesBefore = triangles.size / 3

        val d = frame.depthMm
        val w = frame.depthWidth
        val h = frame.depthHeight
        val m = frame.pose
        val fx = frame.fx; val fy = frame.fy
        val cx = frame.cx; val cy = frame.cy

        for (v in 0 until h - 1 step stride) {
            if (isFull) break
            for (u in 0 until w - 1 step stride) {
                if (isFull) break

                val i00 = v * w + u
                val i10 = i00 + stride
                val i01 = i00 + stride * w
                val i11 = i01 + stride
                if (i11 >= d.size) continue

                val z00 = (d[i00].toInt() and 0xFFFF) / 1000f
                val z10 = (d[i10].toInt() and 0xFFFF) / 1000f
                val z01 = (d[i01].toInt() and 0xFFFF) / 1000f
                val z11 = (d[i11].toInt() and 0xFFFF) / 1000f
                if (z00 <= 0f || z10 <= 0f || z01 <= 0f || z11 <= 0f) continue
                if (z00 < minDepthM || z00 > maxDepthM) continue
                if (z10 < minDepthM || z10 > maxDepthM) continue
                if (z01 < minDepthM || z01 > maxDepthM) continue
                if (z11 < minDepthM || z11 > maxDepthM) continue

                // Depth continuity: reject quads spanning a depth discontinuity.
                // Raw depth is noisy (±1–3 cm between neighbours), so the
                // tolerance must stay comfortably above sensor noise or curved
                // surfaces get shredded into holes.
                val zMin = minOf(z00, z10, z01, z11)
                val zMax = maxOf(z00, z10, z01, z11)
                val zAvg = (z00 + z10 + z01 + z11) * 0.25f
                val tolerance = max(0.025f, 0.06f * zAvg)
                if (zMax - zMin > tolerance) continue

                // Camera-space unprojection (x right, y down, z forward)
                val x00 = (u - cx) * z00 / fx; val y00 = (v - cy) * z00 / fy
                val u10 = u + stride; val v01 = v + stride
                val x10 = (u10 - cx) * z10 / fx; val y10 = (v - cy) * z10 / fy
                val x01 = (u - cx) * z01 / fx; val y01 = (v01 - cy) * z01 / fy
                val x11 = (u10 - cx) * z11 / fx; val y11 = (v01 - cy) * z11 / fy

                // World-space positions
                val wx00 = m[0] * x00 + m[4] * y00 + m[8] * z00 + m[12]
                val wy00 = m[1] * x00 + m[5] * y00 + m[9] * z00 + m[13]
                val wz00 = m[2] * x00 + m[6] * y00 + m[10] * z00 + m[14]
                val wx10 = m[0] * x10 + m[4] * y10 + m[8] * z10 + m[12]
                val wy10 = m[1] * x10 + m[5] * y10 + m[9] * z10 + m[13]
                val wz10 = m[2] * x10 + m[6] * y10 + m[10] * z10 + m[14]
                val wx01 = m[0] * x01 + m[4] * y01 + m[8] * z01 + m[12]
                val wy01 = m[1] * x01 + m[5] * y01 + m[9] * z01 + m[13]
                val wz01 = m[2] * x01 + m[6] * y01 + m[10] * z01 + m[14]
                val wx11 = m[0] * x11 + m[4] * y11 + m[8] * z11 + m[12]
                val wy11 = m[1] * x11 + m[5] * y11 + m[9] * z11 + m[13]
                val wz11 = m[2] * x11 + m[6] * y11 + m[10] * z11 + m[14]

                // Coverage suppression: if every corner voxel was already meshed
                // by an EARLIER frame, this frame sees an already-covered surface
                val k00 = voxelKey(wx00, wy00, wz00)
                val k10 = voxelKey(wx10, wy10, wz10)
                val k01 = voxelKey(wx01, wy01, wz01)
                val k11 = voxelKey(wx11, wy11, wz11)
                val c00 = voxelCoveredByFrame[k00]
                val c10 = voxelCoveredByFrame[k10]
                val c01 = voxelCoveredByFrame[k01]
                val c11 = voxelCoveredByFrame[k11]
                if (c00 != null && c00 < currentFrame &&
                    c10 != null && c10 < currentFrame &&
                    c01 != null && c01 < currentFrame &&
                    c11 != null && c11 < currentFrame
                ) continue

                // Shared vertex indices (voxel-quantized dedup stitches frames)
                val vi00 = getOrCreateVertex(k00, wx00, wy00, wz00)
                val vi10 = getOrCreateVertex(k10, wx10, wy10, wz10)
                val vi01 = getOrCreateVertex(k01, wx01, wy01, wz01)
                val vi11 = getOrCreateVertex(k11, wx11, wy11, wz11)

                // Two triangles per quad, oriented so the face normal points
                // back toward the capturing camera (normal.z < 0 in camera space).
                // Returns whether anything was actually emitted so coverage is
                // only marked for genuinely meshed surfaces.
                val added1 = addOrientedTriangle(vi00, vi01, vi11, x00, y00, z00, x01, y01, z01, x11, y11, z11)
                val added2 = addOrientedTriangle(vi00, vi11, vi10, x00, y00, z00, x11, y11, z11, x10, y10, z10)

                if (added1 || added2) {
                    voxelCoveredByFrame[k00] = currentFrame
                    voxelCoveredByFrame[k10] = currentFrame
                    voxelCoveredByFrame[k01] = currentFrame
                    voxelCoveredByFrame[k11] = currentFrame
                }
            }
        }

        return FuseStats(
            trianglesAdded = triangles.size / 3 - trianglesBefore,
            totalTriangles = triangles.size / 3,
            totalVertices = positions.size / 3
        )
    }

    private fun addOrientedTriangle(
        ia: Int, ib: Int, ic: Int,
        ax: Float, ay: Float, az: Float,
        bx: Float, by: Float, bz: Float,
        cx: Float, cy: Float, cz: Float
    ): Boolean {
        if (ia == ib || ib == ic || ia == ic) return false

        // Reject long sliver triangles (world-space edge lengths)
        val pos = positions
        val pa = ia * 3; val pb = ib * 3; val pc = ic * 3
        val e1 = dist(pos, pa, pos, pb)
        if (e1 > maxEdgeLength) return false
        val e2 = dist(pos, pa, pos, pc)
        if (e2 > maxEdgeLength) return false
        val e3 = dist(pos, pb, pos, pc)
        if (e3 > maxEdgeLength) return false

        // Orient so the camera sees the front face
        val e1x = bx - ax; val e1y = by - ay; val e1z = bz - az
        val e2x = cx - ax; val e2y = cy - ay; val e2z = cz - az
        val nz = e1x * e2y - e1y * e2x
        if (nz > 0f) {
            triangles.add(ia, ic, ib)
        } else {
            triangles.add(ia, ib, ic)
        }
        return true
    }

    private fun dist(p1: FloatList, o1: Int, p2: FloatList, o2: Int): Float {
        val dx = p1[o1] - p2[o2]
        val dy = p1[o1 + 1] - p2[o2 + 1]
        val dz = p1[o1 + 2] - p2[o2 + 2]
        return sqrt(dx * dx + dy * dy + dz * dz)
    }

    private fun getOrCreateVertex(key: Long, x: Float, y: Float, z: Float): Int {
        val existing = vertexIndexByVoxel[key]
        if (existing != null) return existing
        val index = positions.size / 3
        positions.add(x, y, z)
        vertexIndexByVoxel[key] = index
        return index
    }

    private fun voxelKey(x: Float, y: Float, z: Float): Long {
        val vx = (x / voxelSize).roundToInt().toLong()
        val vy = (y / voxelSize).roundToInt().toLong()
        val vz = (z / voxelSize).roundToInt().toLong()
        return (vx and 0x1FFFFFL) or ((vy and 0x1FFFFFL) shl 21) or ((vz and 0x1FFFFFL) shl 42)
    }

    /**
     * Build the final mesh with per-vertex colors initialized to neutral grey.
     * Vertices not referenced by any triangle are compacted away, and indices
     * are remapped accordingly. Callers should bake photo colors afterwards.
     */
    fun buildMesh(): TriangleMesh {
        if (triangles.size == 0) {
            return TriangleMesh(FloatArray(0), FloatArray(0), IntArray(0))
        }

        // Drop tiny disconnected fragments (leftover depth noise) before
        // compaction, so their vertices disappear entirely.
        val tri: IntArray = if (minComponentTriangles > 1) {
            filterSmallComponents()
        } else {
            IntArray(triangles.size).also { copy -> for (i in 0 until triangles.size) copy[i] = triangles[i] }
        }
        if (tri.isEmpty()) {
            return TriangleMesh(FloatArray(0), FloatArray(0), IntArray(0))
        }
        val oldCount = positions.size / 3

        // Remap used vertices to a dense range
        val remap = IntArray(oldCount) { -1 }
        var newCount = 0
        for (f in 0 until tri.size) {
            val idx = tri[f]
            if (remap[idx] == -1) remap[idx] = newCount++
        }

        val newPositions = FloatArray(newCount * 3)
        for (i in 0 until oldCount) {
            val ni = remap[i]
            if (ni == -1) continue
            newPositions[ni * 3] = positions[i * 3]
            newPositions[ni * 3 + 1] = positions[i * 3 + 1]
            newPositions[ni * 3 + 2] = positions[i * 3 + 2]
        }

        val newTriangles = IntArray(tri.size)
        for (f in 0 until tri.size) {
            newTriangles[f] = remap[tri[f]]
        }

        val colors = FloatArray(newCount * 3) { 0.5f }
        return TriangleMesh(newPositions, colors, newTriangles)
    }

    /**
     * Union-find over triangle connectivity; returns the subset of triangles
     * that belong to components of at least [minComponentTriangles] faces.
     */
    private fun filterSmallComponents(): IntArray {
        val vertexCount = positions.size / 3
        val parent = IntArray(vertexCount) { it }

        fun find(x: Int): Int {
            var root = x
            while (parent[root] != root) root = parent[root]
            var cur = x
            while (parent[cur] != cur) {
                val next = parent[cur]
                parent[cur] = root
                cur = next
            }
            return root
        }

        for (f in 0 until triangles.size step 3) {
            val ra = find(triangles[f])
            val rb = find(triangles[f + 1])
            if (ra != rb) parent[ra] = rb
            val rc = find(triangles[f + 2])
            val rr = find(triangles[f + 1])
            if (rr != rc) parent[rc] = rr
        }

        val triPerRoot = HashMap<Int, Int>()
        for (f in 0 until triangles.size step 3) {
            val r = find(triangles[f])
            triPerRoot[r] = (triPerRoot[r] ?: 0) + 1
        }

        val kept = IntArray(triangles.size)
        var n = 0
        for (f in 0 until triangles.size step 3) {
            if ((triPerRoot[find(triangles[f])] ?: 0) >= minComponentTriangles) {
                kept[n++] = triangles[f]
                kept[n++] = triangles[f + 1]
                kept[n++] = triangles[f + 2]
            }
        }
        return if (n == kept.size) kept else kept.copyOf(n)
    }
}
