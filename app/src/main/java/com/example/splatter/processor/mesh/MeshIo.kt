package com.example.splatter.processor.mesh

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale

/**
 * Mesh file I/O: binary PLY (vertices + faces) and OBJ, plus a reader for the
 * PLY variant this app writes. Pure Kotlin — no Android dependencies.
 */
object MeshIo {

    private const val PLY_VERTEX_RECORD_BYTES = 27 // 6 floats + 3 uchar

    /** Writes a binary little-endian PLY with normals and vertex colors. */
    fun writePly(mesh: TriangleMesh, out: OutputStream) {
        val header = StringBuilder().apply {
            append("ply\n")
            append("format binary_little_endian 1.0\n")
            append("comment Splatter Photo Mesh\n")
            append("element vertex ${mesh.vertexCount}\n")
            append("property float x\n")
            append("property float y\n")
            append("property float z\n")
            append("property float nx\n")
            append("property float ny\n")
            append("property float nz\n")
            append("property uchar red\n")
            append("property uchar green\n")
            append("property uchar blue\n")
            append("element face ${mesh.triangleCount}\n")
            append("property list uchar int vertex_indices\n")
            append("end_header\n")
        }

        BufferedOutputStream(out).use { bos ->
            bos.write(header.toString().toByteArray(Charsets.US_ASCII))

            val vb = ByteBuffer.allocate(PLY_VERTEX_RECORD_BYTES).order(ByteOrder.LITTLE_ENDIAN)
            val p = mesh.positions
            val n = mesh.normals
            val c = mesh.colors
            for (i in 0 until mesh.vertexCount) {
                vb.clear()
                vb.putFloat(p[i * 3])
                vb.putFloat(p[i * 3 + 1])
                vb.putFloat(p[i * 3 + 2])
                vb.putFloat(n[i * 3])
                vb.putFloat(n[i * 3 + 1])
                vb.putFloat(n[i * 3 + 2])
                vb.put((c[i * 3].coerceIn(0f, 1f) * 255f).toInt().toByte())
                vb.put((c[i * 3 + 1].coerceIn(0f, 1f) * 255f).toInt().toByte())
                vb.put((c[i * 3 + 2].coerceIn(0f, 1f) * 255f).toInt().toByte())
                bos.write(vb.array())
            }

            val fb = ByteBuffer.allocate(13).order(ByteOrder.LITTLE_ENDIAN)
            val t = mesh.triangles
            for (f in 0 until mesh.triangleCount) {
                fb.clear()
                fb.put(3.toByte())
                fb.putInt(t[f * 3])
                fb.putInt(t[f * 3 + 1])
                fb.putInt(t[f * 3 + 2])
                bos.write(fb.array())
            }
            bos.flush()
        }
    }

    /**
     * Reads a mesh PLY written by [writePly]. Returns null if the stream is
     * not in the expected format.
     */
    fun readPly(input: InputStream): TriangleMesh? {
        BufferedInputStream(input).use { bis ->
            val headerLines = mutableListOf<String>()
            val line = StringBuilder()
            while (true) {
                val b = bis.read()
                if (b == -1) return null
                if (b == '\n'.code) {
                    val text = line.toString().trim()
                    headerLines.add(text)
                    line.setLength(0)
                    if (text == "end_header") break
                } else {
                    line.append(b.toChar())
                }
            }

            var vertexCount = 0
            var faceCount = 0
            var isBinary = false
            for (l in headerLines) {
                if (l.startsWith("element vertex")) {
                    vertexCount = l.substringAfter("element vertex").trim().toIntOrNull() ?: 0
                } else if (l.startsWith("element face")) {
                    faceCount = l.substringAfter("element face").trim().toIntOrNull() ?: 0
                } else if (l.startsWith("format binary_little_endian")) {
                    isBinary = true
                }
            }
            if (vertexCount == 0 || !isBinary) return null

            val positions = FloatArray(vertexCount * 3)
            val colors = FloatArray(vertexCount * 3)
            val normals = FloatArray(vertexCount * 3)

            val record = ByteArray(PLY_VERTEX_RECORD_BYTES)
            val vb = ByteBuffer.wrap(record).order(ByteOrder.LITTLE_ENDIAN)
            for (i in 0 until vertexCount) {
                if (bis.readNBytes(record, 0, PLY_VERTEX_RECORD_BYTES) < PLY_VERTEX_RECORD_BYTES) return null
                vb.position(0)
                positions[i * 3] = vb.float
                positions[i * 3 + 1] = vb.float
                positions[i * 3 + 2] = vb.float
                normals[i * 3] = vb.float
                normals[i * 3 + 1] = vb.float
                normals[i * 3 + 2] = vb.float
                colors[i * 3] = (vb.get().toInt() and 0xFF) / 255f
                colors[i * 3 + 1] = (vb.get().toInt() and 0xFF) / 255f
                colors[i * 3 + 2] = (vb.get().toInt() and 0xFF) / 255f
            }

            val triangles = IntArray(faceCount * 3)
            val faceRecord = ByteArray(13)
            val fb = ByteBuffer.wrap(faceRecord).order(ByteOrder.LITTLE_ENDIAN)
            for (f in 0 until faceCount) {
                if (bis.readNBytes(faceRecord, 0, 13) < 13) return null
                fb.position(0)
                val count = fb.get().toInt()
                if (count != 3) return null
                triangles[f * 3] = fb.int
                triangles[f * 3 + 1] = fb.int
                triangles[f * 3 + 2] = fb.int
            }

            return TriangleMesh(positions, colors, triangles, normals)
        }
    }

    /** Blocks until exactly [length] bytes are read; false on early EOF. */
    private fun readFully(input: InputStream, buffer: ByteArray, length: Int): Boolean {
        var offset = 0
        while (offset < length) {
            val read = input.read(buffer, offset, length - offset)
            if (read == -1) return false
            offset += read
        }
        return true
    }

    /**
     * Writes a Wavefront OBJ. Colors are stored per-vertex using the common
     * "v x y z r g b" extension supported by MeshLab, Blender (via plugin)
     * and most modern viewers.
     */
    fun writeObj(mesh: TriangleMesh, out: OutputStream) {
        BufferedOutputStream(out).use { bos ->
            val writer = bos.writer(Charsets.US_ASCII)
            writer.append("# Splatter Photo Mesh\n")
            val p = mesh.positions
            val c = mesh.colors
            val n = mesh.normals
            for (i in 0 until mesh.vertexCount) {
                writer.append(
                    "v %.4f %.4f %.4f %.3f %.3f %.3f\n".format(
                        Locale.US,
                        p[i * 3], p[i * 3 + 1], p[i * 3 + 2],
                        c[i * 3], c[i * 3 + 1], c[i * 3 + 2]
                    )
                )
            }
            for (i in 0 until mesh.vertexCount) {
                writer.append(
                    "vn %.4f %.4f %.4f\n".format(Locale.US, n[i * 3], n[i * 3 + 1], n[i * 3 + 2])
                )
            }
            val t = mesh.triangles
            for (f in 0 until mesh.triangleCount) {
                val a = t[f * 3] + 1
                val b = t[f * 3 + 1] + 1
                val cIdx = t[f * 3 + 2] + 1
                writer.append("f $a//$a $b//$b $cIdx//$cIdx\n")
            }
            writer.flush()
        }
    }
}
