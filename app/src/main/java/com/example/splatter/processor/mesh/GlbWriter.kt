package com.example.splatter.processor.mesh

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Writes a [TriangleMesh] as a binary glTF 2.0 file (.glb) — the format
 * AR/3D viewers, Blender, Windows 3D Viewer, three.js and Polycam itself
 * consume, unlike the raw PLY/OBJ the app exported before.
 *
 * The container is the standard two-chunk GLB layout:
 *
 * ```
 * header   12 bytes  magic "glTF", version 2, total length
 * chunk 0   JSON     (space-padded to 4 bytes)
 * chunk 1   BIN      (zero-padded to 4 bytes): POSITION, COLOR_0, indices
 * ```
 *
 * Geometry is emitted as float32 POSITION + float32 COLOR_0 vertex colours
 * (so the model keeps the colours baked from the captured photos) and
 * uint32 indices. Y-up conversion is *not* applied: the app's world is
 * Y-up/down depending on the capture, but glTF's Y-up convention is the
 * closest match, so the mesh is written as-is.
 *
 * Pure Kotlin — no Android APIs — so it can be unit tested on the JVM.
 */
object GlbWriter {

    private const val MAGIC = 0x46546C67 // "glTF"
    private const val VERSION = 2
    private const val CHUNK_JSON = 0x4E4F534A // "JSON"
    private const val CHUNK_BIN = 0x004E4942  // "BIN\0"

    private const val FLOAT = 5126
    private const val UINT = 5125
    private const val ARRAY_BUFFER = 34962
    private const val ELEMENT_ARRAY_BUFFER = 34963

    /** Encodes [mesh] into a complete .glb file. */
    fun encode(mesh: TriangleMesh, generator: String = "Splatter"): ByteArray {
        val vertexCount = mesh.vertexCount
        val indexCount = mesh.triangles.size
        require(vertexCount > 0) { "mesh has no vertices" }

        // ---- binary chunk ----
        val positionsBytes = vertexCount * 3 * 4
        val colorsBytes = vertexCount * 3 * 4
        val indicesBytes = indexCount * 4
        val bin = ByteBuffer.allocate(positionsBytes + colorsBytes + indicesBytes)
            .order(ByteOrder.LITTLE_ENDIAN)

        var minX = Float.MAX_VALUE; var minY = Float.MAX_VALUE; var minZ = Float.MAX_VALUE
        var maxX = -Float.MAX_VALUE; var maxY = -Float.MAX_VALUE; var maxZ = -Float.MAX_VALUE
        for (i in 0 until vertexCount) {
            val x = mesh.positions[i * 3]
            val y = mesh.positions[i * 3 + 1]
            val z = mesh.positions[i * 3 + 2]
            bin.putFloat(x); bin.putFloat(y); bin.putFloat(z)
            if (x < minX) minX = x; if (x > maxX) maxX = x
            if (y < minY) minY = y; if (y > maxY) maxY = y
            if (z < minZ) minZ = z; if (z > maxZ) maxZ = z
        }

        val hasColors = mesh.colors.size >= vertexCount * 3
        for (i in 0 until vertexCount) {
            if (hasColors) {
                bin.putFloat(mesh.colors[i * 3].coerceIn(0f, 1f))
                bin.putFloat(mesh.colors[i * 3 + 1].coerceIn(0f, 1f))
                bin.putFloat(mesh.colors[i * 3 + 2].coerceIn(0f, 1f))
            } else {
                bin.putFloat(0.8f); bin.putFloat(0.8f); bin.putFloat(0.8f)
            }
        }

        for (i in 0 until indexCount) {
            bin.putInt(mesh.triangles[i])
        }

        val binBytes = bin.array()

        // ---- JSON chunk ----
        val json = buildString {
            append("{\"asset\":{\"version\":\"2.0\",\"generator\":\"")
            append(escape(generator))
            append("\"},\"scene\":0,\"scenes\":[{\"nodes\":[0]}],\"nodes\":[{\"mesh\":0}],")
            append("\"meshes\":[{\"name\":\"SplatterScan\",\"primitives\":[{")
            append("\"attributes\":{\"POSITION\":0,\"COLOR_0\":1},\"indices\":2,\"mode\":4}]}],")
            append("\"buffers\":[{\"byteLength\":").append(binBytes.size).append("}],")
            append("\"bufferViews\":[")
            append("{\"buffer\":0,\"byteOffset\":0,\"byteLength\":").append(positionsBytes)
            append(",\"target\":").append(ARRAY_BUFFER).append("},")
            append("{\"buffer\":0,\"byteOffset\":").append(positionsBytes)
            append(",\"byteLength\":").append(colorsBytes)
            append(",\"target\":").append(ARRAY_BUFFER).append("},")
            append("{\"buffer\":0,\"byteOffset\":").append(positionsBytes + colorsBytes)
            append(",\"byteLength\":").append(indicesBytes)
            append(",\"target\":").append(ELEMENT_ARRAY_BUFFER).append("}],")
            append("\"accessors\":[")
            append("{\"bufferView\":0,\"componentType\":").append(FLOAT)
            append(",\"count\":").append(vertexCount).append(",\"type\":\"VEC3\",\"min\":")
            append(bounds(minX, minY, minZ, maxX, maxY, maxZ)).append("},")
            append("{\"bufferView\":1,\"componentType\":").append(FLOAT)
            append(",\"count\":").append(vertexCount).append(",\"type\":\"VEC3\"},")
            append("{\"bufferView\":2,\"componentType\":").append(UINT)
            append(",\"count\":").append(indexCount).append(",\"type\":\"SCALAR\"}")
            append("]}")
        }

        val jsonBytes = json.toByteArray(Charsets.UTF_8)
        val jsonPadded = padTo4(jsonBytes, 0x20)   // JSON chunks pad with spaces
        val binPadded = padTo4(binBytes, 0x00)

        val total = 12 + 8 + jsonPadded.size + 8 + binPadded.size
        val out = ByteBuffer.allocate(total).order(ByteOrder.LITTLE_ENDIAN)
        out.putInt(MAGIC)
        out.putInt(VERSION)
        out.putInt(total)
        out.putInt(jsonPadded.size)
        out.putInt(CHUNK_JSON)
        out.put(jsonPadded)
        out.putInt(binPadded.size)
        out.putInt(CHUNK_BIN)
        out.put(binPadded)
        return out.array()
    }

    private fun padTo4(bytes: ByteArray, pad: Int): ByteArray {
        val remainder = bytes.size % 4
        if (remainder == 0) return bytes
        val padded = ByteArray(bytes.size + (4 - remainder)) { pad.toByte() }
        System.arraycopy(bytes, 0, padded, 0, bytes.size)
        return padded
    }

    /** glTF requires min/max on POSITION, one value per component of the VEC3. */
    private fun bounds(
        minX: Float, minY: Float, minZ: Float,
        maxX: Float, maxY: Float, maxZ: Float
    ): String = "[$minX,$minY,$minZ],[$maxX,$maxY,$maxZ]"

    private fun escape(value: String): String =
        value.replace("\\", "\\\\").replace("\"", "\\\"")
}
