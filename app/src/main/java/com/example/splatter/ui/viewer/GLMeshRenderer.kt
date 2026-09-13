package com.example.splatter.ui.viewer

import android.opengl.GLES30
import android.opengl.GLSurfaceView
import android.opengl.Matrix
import android.util.Log
import com.example.splatter.processor.mesh.TriangleMesh
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.nio.IntBuffer
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.cos
import kotlin.math.sin

/**
 * Renders a [TriangleMesh] with per-vertex colors, simple headlight shading
 * and an optional wireframe overlay. Same orbit-camera controls as
 * GLSplatRenderer so gestures behave identically.
 */
class GLMeshRenderer : GLSurfaceView.Renderer {

    private var programHandle = 0
    private var mvpMatrixHandle = 0
    private var lightDirHandle = 0
    private var wireframeHandle = 0
    private var posAttribHandle = 0
    private var colorAttribHandle = 0
    private var normalAttribHandle = 0

    private val mvpMatrix = FloatArray(16)
    private val mvMatrix = FloatArray(16)
    private val viewMatrix = FloatArray(16)
    private val projectionMatrix = FloatArray(16)

    // Camera orbit controls (mirrors GLSplatRenderer)
    var pitchDegrees = 20f
    var yawDegrees = 45f
    var cameraDistance = 2.5f
    var targetX = 0f
    var targetY = 0f
    var targetZ = 0f

    /** When true, draws only a wireframe instead of the shaded surface. */
    var wireframeMode = false

    private var vertexBuffer: FloatBuffer? = null
    private var colorBuffer: FloatBuffer? = null
    private var normalBuffer: FloatBuffer? = null
    private var indexBuffer: IntBuffer? = null
    private var lineIndexBuffer: IntBuffer? = null
    private var lineIndexCount = 0
    private var indexCount = 0

    @Synchronized
    fun setMesh(mesh: TriangleMesh) {
        val vertexCount = mesh.vertexCount
        if (vertexCount == 0 || mesh.triangleCount == 0) {
            vertexBuffer = null
            indexBuffer = null
            lineIndexBuffer = null
            indexCount = 0
            lineIndexCount = 0
            return
        }

        // Auto-center the orbit target on the mesh bounding box
        val bb = mesh.boundingBox()
        targetX = (bb[0] + bb[3]) * 0.5f
        targetY = (bb[1] + bb[4]) * 0.5f
        targetZ = (bb[2] + bb[5]) * 0.5f
        val sizeX = bb[3] - bb[0]
        val sizeY = bb[4] - bb[1]
        val sizeZ = bb[5] - bb[2]
        val maxDim = maxOf(sizeX, sizeY, sizeZ).coerceAtLeast(0.05f)
        cameraDistance = maxDim * 1.8f

        vertexBuffer = toFloatBuffer(mesh.positions)
        colorBuffer = toFloatBuffer(mesh.colors)
        normalBuffer = toFloatBuffer(mesh.normals)

        indexCount = mesh.triangles.size
        indexBuffer = ByteBuffer.allocateDirect(indexCount * 4)
            .order(ByteOrder.nativeOrder()).asIntBuffer().apply { put(mesh.triangles); position(0) }

        // Dedicated edge index buffer for wireframe mode (3 edges per triangle)
        val t = mesh.triangles
        val lines = IntArray(mesh.triangleCount * 6)
        var li = 0
        for (f in t.indices step 3) {
            val a = t[f]; val b = t[f + 1]; val c = t[f + 2]
            lines[li++] = a; lines[li++] = b
            lines[li++] = b; lines[li++] = c
            lines[li++] = c; lines[li++] = a
        }
        lineIndexCount = lines.size
        lineIndexBuffer = ByteBuffer.allocateDirect(lines.size * 4)
            .order(ByteOrder.nativeOrder()).asIntBuffer().apply { put(lines); position(0) }

        Log.i(TAG, "Uploaded mesh: $vertexCount vertices, ${mesh.triangleCount} triangles")
    }

    private fun toFloatBuffer(array: FloatArray): FloatBuffer =
        ByteBuffer.allocateDirect(array.size * 4)
            .order(ByteOrder.nativeOrder()).asFloatBuffer().apply { put(array); position(0) }

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        GLES30.glClearColor(0.08f, 0.09f, 0.12f, 1.0f)
        GLES30.glEnable(GLES30.GL_DEPTH_TEST)
        GLES30.glDepthFunc(GLES30.GL_LEQUAL)

        val vertexShaderCode = """
            #version 300 es
            uniform mat4 u_MVPMatrix;
            uniform mat4 u_MVMatrix;
            in vec3 a_Position;
            in vec3 a_Normal;
            in vec3 a_Color;
            out vec3 v_Color;
            out vec3 v_Normal;

            void main() {
                gl_Position = u_MVPMatrix * vec4(a_Position, 1.0);
                v_Normal = mat3(u_MVMatrix) * a_Normal;
                v_Color = a_Color;
            }
        """.trimIndent()

        val fragmentShaderCode = """
            #version 300 es
            precision mediump float;
            in vec3 v_Color;
            in vec3 v_Normal;
            uniform vec3 u_LightDir;
            uniform float u_Wireframe;
            out vec4 fragColor;

            void main() {
                if (u_Wireframe > 0.5) {
                    fragColor = vec4(0.15, 0.85, 0.85, 1.0);
                    return;
                }
                vec3 n = normalize(v_Normal);
                // Headlight + soft fill from above
                float diff = max(dot(n, u_LightDir), 0.0);
                float fill = 0.35 + 0.25 * max(n.y, 0.0);
                vec3 shaded = v_Color * (0.35 + 0.75 * diff) + v_Color * fill * 0.3;
                fragColor = vec4(clamp(shaded, 0.0, 1.0), 1.0);
            }
        """.trimIndent()

        val vShader = loadShader(GLES30.GL_VERTEX_SHADER, vertexShaderCode)
        val fShader = loadShader(GLES30.GL_FRAGMENT_SHADER, fragmentShaderCode)

        programHandle = GLES30.glCreateProgram().apply {
            GLES30.glAttachShader(this, vShader)
            GLES30.glAttachShader(this, fShader)
            GLES30.glLinkProgram(this)
        }

        mvpMatrixHandle = GLES30.glGetUniformLocation(programHandle, "u_MVPMatrix")
        lightDirHandle = GLES30.glGetUniformLocation(programHandle, "u_LightDir")
        wireframeHandle = GLES30.glGetUniformLocation(programHandle, "u_Wireframe")
        posAttribHandle = GLES30.glGetAttribLocation(programHandle, "a_Position")
        colorAttribHandle = GLES30.glGetAttribLocation(programHandle, "a_Color")
        normalAttribHandle = GLES30.glGetAttribLocation(programHandle, "a_Normal")
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        GLES30.glViewport(0, 0, width, height)
        val aspectRatio = width.toFloat() / height.coerceAtLeast(1)
        Matrix.perspectiveM(projectionMatrix, 0, 45f, aspectRatio, 0.05f, 200f)
    }

    override fun onDrawFrame(gl: GL10?) {
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT or GLES30.GL_DEPTH_BUFFER_BIT)
        if (indexCount == 0 || vertexBuffer == null) return

        val pitchRad = Math.toRadians(pitchDegrees.toDouble()).toFloat()
        val yawRad = Math.toRadians(yawDegrees.toDouble()).toFloat()

        val camX = targetX + cameraDistance * cos(pitchRad) * sin(yawRad)
        val camY = targetY + cameraDistance * sin(pitchRad)
        val camZ = targetZ + cameraDistance * cos(pitchRad) * cos(yawRad)

        Matrix.setLookAtM(viewMatrix, 0, camX, camY, camZ, targetX, targetY, targetZ, 0f, 1f, 0f)
        // Model matrix is identity, so MV = view
        System.arraycopy(viewMatrix, 0, mvMatrix, 0, 16)
        Matrix.multiplyMM(mvpMatrix, 0, projectionMatrix, 0, viewMatrix, 0)

        GLES30.glUseProgram(programHandle)

        GLES30.glUniformMatrix4fv(mvpMatrixHandle, 1, false, mvpMatrix, 0)
        GLES30.glUniformMatrix4fv(GLES30.glGetUniformLocation(programHandle, "u_MVMatrix"), 1, false, mvMatrix, 0)
        GLES30.glUniform3f(lightDirHandle, 0.35f, 0.5f, 0.8f)
        GLES30.glUniform1f(wireframeHandle, 0f)
        vertexBuffer?.let {
            GLES30.glEnableVertexAttribArray(posAttribHandle)
            GLES30.glVertexAttribPointer(posAttribHandle, 3, GLES30.GL_FLOAT, false, 0, it)
        }
        colorBuffer?.let {
            GLES30.glEnableVertexAttribArray(colorAttribHandle)
            GLES30.glVertexAttribPointer(colorAttribHandle, 3, GLES30.GL_FLOAT, false, 0, it)
        }
        normalBuffer?.let {
            GLES30.glEnableVertexAttribArray(normalAttribHandle)
            GLES30.glVertexAttribPointer(normalAttribHandle, 3, GLES30.GL_FLOAT, false, 0, it)
        }
        // Shaded surface (skipped in wireframe-only mode)
        if (!wireframeMode) {
            indexBuffer?.let { idx ->
                idx.position(0)
                GLES30.glDrawElements(GLES30.GL_TRIANGLES, indexCount, GLES30.GL_UNSIGNED_INT, idx)
            }
        }

        // Wireframe mode: draw mesh edges as cyan lines (no fill underneath,
        // so no z-fighting)
        if (wireframeMode && lineIndexCount > 0) {
            GLES30.glUniform1f(wireframeHandle, 1f)
            GLES30.glLineWidth(2f)
            lineIndexBuffer?.let { idx ->
                idx.position(0)
                GLES30.glDrawElements(GLES30.GL_LINES, lineIndexCount, GLES30.GL_UNSIGNED_INT, idx)
            }
        }

        GLES30.glDisableVertexAttribArray(posAttribHandle)
        GLES30.glDisableVertexAttribArray(colorAttribHandle)
        GLES30.glDisableVertexAttribArray(normalAttribHandle)
    }

    private fun loadShader(type: Int, shaderCode: String): Int {
        return GLES30.glCreateShader(type).also { shader ->
            GLES30.glShaderSource(shader, shaderCode)
            GLES30.glCompileShader(shader)
        }
    }

    companion object {
        private const val TAG = "GLMeshRenderer"
    }
}
