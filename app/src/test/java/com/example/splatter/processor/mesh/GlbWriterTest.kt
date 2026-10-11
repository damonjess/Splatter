package com.example.splatter.processor.mesh

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Parses the generated .glb back from the bytes (header, both chunks, JSON
 * and BIN payloads) so the test checks the real container layout rather than
 * just that "something was written".
 */
class GlbWriterTest {

    /** Two triangles sharing four vertices, one in each colour channel. */
    private fun quad(): TriangleMesh {
        val positions = floatArrayOf(
            0f, 0f, 0f,
            1f, 0f, 0f,
            1f, 1f, 0f,
            0f, 1f, 0f
        )
        val colors = floatArrayOf(
            1f, 0f, 0f,
            0f, 1f, 0f,
            0f, 0f, 1f,
            1f, 1f, 1f
        )
        val triangles = intArrayOf(0, 1, 2, 0, 2, 3)
        return TriangleMesh(positions, colors, triangles)
    }

    @Test
    fun writesAValidTwoChunkGlbContainer() {
        val glb = GlbWriter.encode(quad())

        val header = ByteBuffer.wrap(glb).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals("magic", 0x46546C67, header.getInt(0))
        assertEquals("version", 2, header.getInt(4))
        assertEquals("declared length == file length", glb.size, header.getInt(8))

        // JSON chunk
        val jsonLength = header.getInt(12)
        assertEquals("JSON chunk type", 0x4E4F534A, header.getInt(16))
        assertEquals("JSON chunk is 4-byte aligned", 0, jsonLength % 4)
        val json = String(glb, 20, jsonLength, Charsets.UTF_8)

        // BIN chunk
        val binOffset = 20 + jsonLength
        val binLength = header.getInt(binOffset)
        assertEquals("BIN chunk type", 0x004E4942, header.getInt(binOffset + 4))
        assertEquals("BIN chunk is 4-byte aligned", 0, binLength % 4)
        assertEquals(
            "chunks exactly fill the file",
            glb.size,
            binOffset + 8 + binLength
        )
        assertEquals("buffer byteLength matches the BIN chunk", binLength, Regex("\"byteLength\":(\\d+)}").find(json)!!.groupValues[1].toInt())

        // Accessors describe the mesh we handed in
        assertTrue(json.contains("\"POSITION\":0"))
        assertTrue(json.contains("\"COLOR_0\":1"))
        assertTrue(json.contains("\"count\":4,\"type\":\"VEC3\",\"min\":[0.0,0.0,0.0],[1.0,1.0,0.0]"))
        assertTrue(json.contains("\"count\":6,\"type\":\"SCALAR\""))
    }

    @Test
    fun binaryChunkCarriesPositionsColorsAndIndices() {
        val mesh = quad()
        val glb = GlbWriter.encode(mesh)

        val header = ByteBuffer.wrap(glb).order(ByteOrder.LITTLE_ENDIAN)
        val jsonLength = header.getInt(12)
        val binOffset = 20 + jsonLength + 8
        val bin = ByteBuffer.wrap(glb, binOffset, glb.size - binOffset)
            .slice()
            .order(ByteOrder.LITTLE_ENDIAN)

        for (i in 0 until mesh.vertexCount * 3) {
            assertEquals("position $i", mesh.positions[i], bin.getFloat(i * 4), 0f)
        }
        val colorOffset = mesh.vertexCount * 3 * 4
        for (i in 0 until mesh.vertexCount * 3) {
            assertEquals("colour $i", mesh.colors[i], bin.getFloat(colorOffset + i * 4), 0f)
        }
        val indexOffset = colorOffset + mesh.vertexCount * 3 * 4
        for (i in mesh.triangles.indices) {
            assertEquals("index $i", mesh.triangles[i], bin.getInt(indexOffset + i * 4))
        }
    }

    @Test
    fun padsChunksSoBothStayFourByteAligned() {
        // A generator name whose JSON length is not a multiple of 4 exercises padding.
        val glb = GlbWriter.encode(quad(), generator = "Splatter test build")
        val header = ByteBuffer.wrap(glb).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(glb.size, header.getInt(8))
        assertEquals(0, header.getInt(12) % 4)
        val binOffset = 20 + header.getInt(12)
        assertEquals(0, header.getInt(binOffset) % 4)
    }
}
