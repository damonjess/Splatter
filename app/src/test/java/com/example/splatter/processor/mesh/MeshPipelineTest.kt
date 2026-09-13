package com.example.splatter.processor.mesh

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

/**
 * Unit tests for the pure-Kotlin Photo Mesh pipeline
 * (depth fusion, color baking, mesh I/O).
 */
class MeshPipelineTest {

    private val identityPose = floatArrayOf(
        1f, 0f, 0f, 0f,
        0f, 1f, 0f, 0f,
        0f, 0f, 1f, 0f,
        0f, 0f, 0f, 1f
    )

    /** A 16x16 depth image where every pixel is [depthMm] mm away. */
    private fun flatDepth(depthMm: Int, w: Int = 16, h: Int = 16): ShortArray =
        ShortArray(w * h) { depthMm.toShort() }

    private fun flatFrame(depth: ShortArray, w: Int = 16, h: Int = 16, pose: FloatArray = identityPose): DepthFrame =
        DepthFrame(
            depthMm = depth,
            depthWidth = w,
            depthHeight = h,
            pose = pose,
            fx = 100f,
            fy = 100f,
            cx = 7.5f,
            cy = 7.5f
        )

    @Test
    fun `flat wall produces a connected triangle grid`() {
        val fuser = DepthMeshFuser(voxelSize = 0.005f, maxTriangles = 100_000)
        fuser.fuseFrame(flatFrame(flatDepth(1000)), minDepthM = 0.3f, maxDepthM = 5f)
        val mesh = fuser.buildMesh()

        // 15x15 quads -> 450 triangles
        assertEquals(450, mesh.triangleCount)
        // 16x16 unique vertices (10 mm spacing at 1 m with fx=100 vs 5 mm voxel)
        assertEquals(256, mesh.vertexCount)

        // All vertices lie on the z = 1 m plane (identity pose)
        for (i in 0 until mesh.vertexCount) {
            assertEquals(1.0f, mesh.positions[i * 3 + 2], 1e-4f)
        }
    }

    @Test
    fun `refusing the same frame twice adds no triangles`() {
        val fuser = DepthMeshFuser(voxelSize = 0.005f, maxTriangles = 100_000)
        val stats1 = fuser.fuseFrame(flatFrame(flatDepth(1000)), minDepthM = 0.3f, maxDepthM = 5f)
        val stats2 = fuser.fuseFrame(flatFrame(flatDepth(1000)), minDepthM = 0.3f, maxDepthM = 5f)

        assertEquals(450, stats1.trianglesAdded)
        assertEquals(0, stats2.trianglesAdded)
    }

    @Test
    fun `quads across a depth jump are rejected`() {
        val depth = flatDepth(1000)
        // Right half of the wall is 2 m away instead of 1 m
        for (v in 0 until 16) {
            for (u in 8 until 16) {
                depth[v * 16 + u] = 2000.toShort()
            }
        }

        val fuser = DepthMeshFuser(voxelSize = 0.005f, maxTriangles = 100_000)
        fuser.fuseFrame(flatFrame(depth), minDepthM = 0.3f, maxDepthM = 5f)

        // 15 quads per column boundary column pair rejected: 225 - 15 = 210 quads
        assertEquals(420, fuser.triangleCount)
    }

    @Test
    fun `face normals point back toward the camera`() {
        val fuser = DepthMeshFuser(voxelSize = 0.005f, maxTriangles = 100_000)
        fuser.fuseFrame(flatFrame(flatDepth(1000)), minDepthM = 0.3f, maxDepthM = 5f)
        val mesh = fuser.buildMesh()
        mesh.computeNormals()

        // Flat wall at z = 1 viewed from the origin: normals must be (0, 0, -1)
        assertEquals(0f, mesh.normals[0], 1e-3f)
        assertEquals(0f, mesh.normals[1], 1e-3f)
        assertEquals(-1f, mesh.normals[2], 1e-3f)
    }

    @Test
    fun `triangle cap is respected`() {
        val fuser = DepthMeshFuser(voxelSize = 0.005f, maxTriangles = 100)
        fuser.fuseFrame(flatFrame(flatDepth(1000)), minDepthM = 0.3f, maxDepthM = 5f)
        assertTrue(fuser.isFull)
        assertTrue(fuser.triangleCount <= 102) // may slightly overshoot within a quad
    }

    private fun singleTriangleMesh(): TriangleMesh {
        // Winding chosen so the computed normal is (0, 0, -1) — facing the
        // camera at the origin, like fuser-oriented triangles would be
        val positions = floatArrayOf(
            0f, 0f, 1f,
            0f, 0.01f, 1f,
            0.01f, 0f, 1f
        )
        val colors = FloatArray(9) { 0.5f }
        val mesh = TriangleMesh(positions, colors, intArrayOf(0, 1, 2))
        mesh.computeNormals()
        return mesh
    }

    private fun redView(
        color: Int = 0xFFFF0000.toInt(),
        depthMm: ShortArray? = null
    ): PhotoView = PhotoView(
        pixels = IntArray(32 * 32) { color },
        width = 32,
        height = 32,
        pose = identityPose,
        fx = 16f,
        fy = 16f,
        cx = 15.5f,
        cy = 15.5f,
        depthMm = depthMm,
        depthWidth = if (depthMm != null) 32 else 0,
        depthHeight = if (depthMm != null) 32 else 0
    )

    @Test
    fun `color baker projects vertex into photo and tints it`() {
        val mesh = singleTriangleMesh()
        val baker = MeshColorBaker(mesh)
        baker.bakeView(redView())
        baker.applyTo(mesh)

        // Every vertex should now be red
        for (i in 0 until mesh.vertexCount) {
            assertTrue(mesh.colors[i * 3] > 0.9f)
            assertTrue(mesh.colors[i * 3 + 1] < 0.1f)
            assertTrue(mesh.colors[i * 3 + 2] < 0.1f)
        }
    }

    @Test
    fun `color baker rejects occluded vertices via depth test`() {
        val mesh = singleTriangleMesh()
        val baker = MeshColorBaker(mesh)

        // Depth map says the surface is 0.5 m away, but the vertex is at 1 m:
        // the vertex is occluded in this view, so no color is baked
        baker.bakeView(redView(depthMm = ShortArray(32 * 32) { 500.toShort() }))
        baker.applyTo(mesh)

        for (i in 0 until mesh.vertexCount) {
            assertEquals(0.5f, mesh.colors[i * 3], 1e-4f)
        }
    }

    @Test
    fun `color baker blends multiple views`() {
        val mesh = singleTriangleMesh()
        val baker = MeshColorBaker(mesh)
        baker.bakeView(redView(color = 0xFFFF0000.toInt()))

        // Second view from a camera translated 0.2 m to the right, colored blue
        val translatedPose = identityPose.copyOf().also { it[12] = 0.2f }
        baker.bakeView(
            PhotoView(
                pixels = IntArray(32 * 32) { 0xFF0000FF.toInt() },
                width = 32,
                height = 32,
                pose = translatedPose,
                fx = 16f,
                fy = 16f,
                cx = 15.5f,
                cy = 15.5f
            )
        )
        baker.applyTo(mesh)

        // Both red and blue contributed — purple-ish mix
        assertTrue(mesh.colors[0] > 0.1f)
        assertTrue(mesh.colors[2] > 0.1f)
    }

    @Test
    fun `color baker handles rotated cameras in world space`() {
        // Camera rotated 90° about Y (looking down world +X), at the origin.
        // A wall at x = 1 facing the camera would be tinted; the legacy bug
        // computed the facing dot product in camera space and rejected it.
        val rotatedPose = floatArrayOf(
            0f, 0f, -1f, 0f,
            0f, 1f, 0f, 0f,
            1f, 0f, 0f, 0f,
            0f, 0f, 0f, 1f
        )

        // Triangle on the x = 1 plane, normal (-1, 0, 0) toward the camera
        val positions = floatArrayOf(
            1f, 0f, 0f,
            1f, 0f, 0.01f,
            1f, 0.01f, 0f
        )
        val mesh = TriangleMesh(positions, FloatArray(9) { 0.5f }, intArrayOf(0, 1, 2))
        mesh.computeNormals()
        assertEquals(-1f, mesh.normals[0], 1e-3f)

        val baker = MeshColorBaker(mesh)
        baker.bakeView(
            PhotoView(
                pixels = IntArray(32 * 32) { 0xFF00FF00.toInt() },
                width = 32,
                height = 32,
                pose = rotatedPose,
                fx = 16f,
                fy = 16f,
                cx = 15.5f,
                cy = 15.5f
            )
        )
        baker.applyTo(mesh)

        // The camera looks straight at the wall — vertices must be green
        for (i in 0 until mesh.vertexCount) {
            assertTrue("expected green, got r=${mesh.colors[i * 3]}", mesh.colors[i * 3] < 0.1f)
            assertTrue(mesh.colors[i * 3 + 1] > 0.9f)
            assertTrue(mesh.colors[i * 3 + 2] < 0.1f)
        }
    }

    @Test
    fun `ply round trip preserves geometry and colors`() {
        val fuser = DepthMeshFuser(voxelSize = 0.005f, maxTriangles = 100_000)
        fuser.fuseFrame(flatFrame(flatDepth(1000)), minDepthM = 0.3f, maxDepthM = 5f)
        val mesh = fuser.buildMesh()
        mesh.computeNormals()
        for (i in 0 until mesh.vertexCount) {
            mesh.colors[i * 3] = 1f
            mesh.colors[i * 3 + 1] = 0.25f
            mesh.colors[i * 3 + 2] = 0.5f
        }

        val out = ByteArrayOutputStream()
        MeshIo.writePly(mesh, out)

        val loaded = MeshIo.readPly(ByteArrayInputStream(out.toByteArray()))
        assertNotNull(loaded)
        loaded!!
        assertEquals(mesh.vertexCount, loaded.vertexCount)
        assertEquals(mesh.triangleCount, loaded.triangleCount)
        for (i in mesh.positions.indices) {
            assertEquals(mesh.positions[i], loaded.positions[i], 1e-5f)
        }
        // 8-bit quantization tolerance
        for (i in mesh.colors.indices) {
            assertEquals(mesh.colors[i], loaded.colors[i], 1f / 255f + 1e-4f)
        }
        for (i in mesh.triangles.indices) {
            assertEquals(mesh.triangles[i], loaded.triangles[i])
        }
    }

    @Test
    fun `obj writer emits vertices and faces with dot decimals`() {
        val mesh = singleTriangleMesh()
        val out = ByteArrayOutputStream()
        MeshIo.writeObj(mesh, out)
        val text = out.toString("UTF-8")

        assertTrue(text.contains("v 0.0000 0.0000 1.0000"))
        assertTrue(text.contains("vn "))
        assertTrue(text.contains("f 1//1 2//2 3//3"))
    }
}
