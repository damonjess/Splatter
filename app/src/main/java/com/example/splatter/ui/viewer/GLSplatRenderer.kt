package com.example.splatter.ui.viewer

import android.opengl.GLES30
import android.opengl.GLSurfaceView
import android.opengl.Matrix
import android.util.Log
import com.example.splatter.model.SplatPoint
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.cos
import kotlin.math.sin

class GLSplatRenderer : GLSurfaceView.Renderer {

    private var programHandle = 0
    private var mvpMatrixHandle = 0
    private var pointSizeMultHandle = 0
    private var posAttribHandle = 0
    private var colorAttribHandle = 0
    private var scaleAttribHandle = 0

    private val mvpMatrix = FloatArray(16)
    private val viewMatrix = FloatArray(16)
    private val projectionMatrix = FloatArray(16)
    private val modelMatrix = FloatArray(16)

    // Camera Orbit Controls
    var pitchDegrees = 20f
    var yawDegrees = 45f
    var cameraDistance = 2.5f
    var targetX = 0f
    var targetY = 0f
    var targetZ = 0f

    var pointSizeMultiplier = 1.0f

    private var vertexBuffer: FloatBuffer? = null
    private var colorBuffer: FloatBuffer? = null
    private var scaleBuffer: FloatBuffer? = null
    private var pointCount = 0

    @Synchronized
    fun setSplatPoints(points: List<SplatPoint>) {
        pointCount = points.size
        if (pointCount == 0) {
            vertexBuffer = null
            colorBuffer = null
            scaleBuffer = null
            return
        }

        // Calculate center of points for auto-centering camera
        var sumX = 0f; var sumY = 0f; var sumZ = 0f
        val posArray = FloatArray(pointCount * 3)
        val colArray = FloatArray(pointCount * 4)
        val sclArray = FloatArray(pointCount)

        for (i in 0 until pointCount) {
            val p = points[i]
            posArray[i * 3] = p.x
            posArray[i * 3 + 1] = p.y
            posArray[i * 3 + 2] = p.z

            sumX += p.x
            sumY += p.y
            sumZ += p.z

            colArray[i * 4] = p.r
            colArray[i * 4 + 1] = p.g
            colArray[i * 4 + 2] = p.b
            colArray[i * 4 + 3] = p.alpha

            sclArray[i] = p.scaleX.coerceAtLeast(0.005f)
        }

        targetX = sumX / pointCount
        targetY = sumY / pointCount
        targetZ = sumZ / pointCount

        vertexBuffer = ByteBuffer.allocateDirect(posArray.size * 4)
            .order(ByteOrder.nativeOrder()).asFloatBuffer().apply { put(posArray); position(0) }

        colorBuffer = ByteBuffer.allocateDirect(colArray.size * 4)
            .order(ByteOrder.nativeOrder()).asFloatBuffer().apply { put(colArray); position(0) }

        scaleBuffer = ByteBuffer.allocateDirect(sclArray.size * 4)
            .order(ByteOrder.nativeOrder()).asFloatBuffer().apply { put(sclArray); position(0) }

        Log.i(TAG, "Uploaded $pointCount Gaussian splat points to GPU buffer.")
    }

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        GLES30.glClearColor(0.08f, 0.09f, 0.12f, 1.0f)
        GLES30.glEnable(GLES30.GL_DEPTH_TEST)
        GLES30.glEnable(GLES30.GL_BLEND)
        GLES30.glBlendFunc(GLES30.GL_SRC_ALPHA, GLES30.GL_ONE_MINUS_SRC_ALPHA)

        val vertexShaderCode = """
            #version 300 es
            uniform mat4 u_MVPMatrix;
            uniform float u_PointSizeMult;
            in vec3 a_Position;
            in vec4 a_Color;
            in float a_Scale;
            out vec4 v_Color;
            
            void main() {
                gl_Position = u_MVPMatrix * vec4(a_Position, 1.0);
                float dist = max(gl_Position.w, 0.1);
                gl_PointSize = clamp((a_Scale * u_PointSizeMult * 4800.0) / dist, 6.0, 320.0);
                v_Color = a_Color;
            }
        """.trimIndent()

        val fragmentShaderCode = """
            #version 300 es
            precision mediump float;
            in vec4 v_Color;
            out vec4 fragColor;
            
            void main() {
                vec2 coord = gl_PointCoord - vec2(0.5);
                float distSq = dot(coord, coord);
                if (distSq > 0.25) {
                    discard;
                }
                // Soft Gaussian radial falloff
                float alpha = exp(-distSq * 6.0) * v_Color.a;
                fragColor = vec4(v_Color.rgb, alpha);
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
        pointSizeMultHandle = GLES30.glGetUniformLocation(programHandle, "u_PointSizeMult")
        posAttribHandle = GLES30.glGetAttribLocation(programHandle, "a_Position")
        colorAttribHandle = GLES30.glGetAttribLocation(programHandle, "a_Color")
        scaleAttribHandle = GLES30.glGetAttribLocation(programHandle, "a_Scale")

        Matrix.setIdentityM(modelMatrix, 0)
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        GLES30.glViewport(0, 0, width, height)
        val aspectRatio = width.toFloat() / height.coerceAtLeast(1)
        Matrix.perspectiveM(projectionMatrix, 0, 45f, aspectRatio, 0.1f, 100f)
    }

    override fun onDrawFrame(gl: GL10?) {
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT or GLES30.GL_DEPTH_BUFFER_BIT)

        if (pointCount == 0 || vertexBuffer == null) return

        // Compute Camera Position from Pitch/Yaw Spherical Coordinates
        val pitchRad = Math.toRadians(pitchDegrees.toDouble()).toFloat()
        val yawRad = Math.toRadians(yawDegrees.toDouble()).toFloat()

        val camX = targetX + cameraDistance * cos(pitchRad) * sin(yawRad)
        val camY = targetY + cameraDistance * sin(pitchRad)
        val camZ = targetZ + cameraDistance * cos(pitchRad) * cos(yawRad)

        Matrix.setLookAtM(viewMatrix, 0, camX, camY, camZ, targetX, targetY, targetZ, 0f, 1f, 0f)
        Matrix.multiplyMM(mvpMatrix, 0, projectionMatrix, 0, viewMatrix, 0)

        GLES30.glUseProgram(programHandle)

        GLES30.glUniformMatrix4fv(mvpMatrixHandle, 1, false, mvpMatrix, 0)
        GLES30.glUniform1f(pointSizeMultHandle, pointSizeMultiplier)

        // Bind Vertex Buffers
        vertexBuffer?.let {
            GLES30.glEnableVertexAttribArray(posAttribHandle)
            GLES30.glVertexAttribPointer(posAttribHandle, 3, GLES30.GL_FLOAT, false, 0, it)
        }

        colorBuffer?.let {
            GLES30.glEnableVertexAttribArray(colorAttribHandle)
            GLES30.glVertexAttribPointer(colorAttribHandle, 4, GLES30.GL_FLOAT, false, 0, it)
        }

        scaleBuffer?.let {
            GLES30.glEnableVertexAttribArray(scaleAttribHandle)
            GLES30.glVertexAttribPointer(scaleAttribHandle, 1, GLES30.GL_FLOAT, false, 0, it)
        }

        // Disable depth writing for smooth alpha Gaussian blending
        GLES30.glDepthMask(false)
        GLES30.glDrawArrays(GLES30.GL_POINTS, 0, pointCount)
        GLES30.glDepthMask(true)

        GLES30.glDisableVertexAttribArray(posAttribHandle)
        GLES30.glDisableVertexAttribArray(colorAttribHandle)
        GLES30.glDisableVertexAttribArray(scaleAttribHandle)
    }

    private fun loadShader(type: Int, shaderCode: String): Int {
        return GLES30.glCreateShader(type).also { shader ->
            GLES30.glShaderSource(shader, shaderCode)
            GLES30.glCompileShader(shader)
        }
    }

    companion object {
        private const val TAG = "GLSplatRenderer"
    }
}
