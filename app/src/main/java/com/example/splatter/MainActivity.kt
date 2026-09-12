package com.example.splatter

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.YuvImage
import android.media.Image
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.google.ar.core.ArCoreApk
import com.google.ar.core.Config
import com.google.ar.core.Coordinates2d
import com.google.ar.core.Frame
import com.google.ar.core.Session
import com.google.ar.core.TrackingState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

class MainActivity : ComponentActivity() {

    private var session: Session? = null
    private var glSurfaceView: GLSurfaceView? = null
    private var userRequestedInstall = true

    // State indicators
    private val isRecordingState = mutableStateOf(false)
    private val frameCountState = mutableStateOf(0)
    private val trackingStatusText = mutableStateOf("Initializing AR...")
    
    private var lastSavedTimestampMs = 0L
    private val captureIntervalMs = 150L // ~6-7 captures per second

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            var hasCameraPermission by remember {
                mutableStateOf(
                    ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
                        == PackageManager.PERMISSION_GRANTED
                )
            }

            val permissionLauncher = rememberLauncherForActivityResult(
                contract = ActivityResultContracts.RequestPermission()
            ) { isGranted ->
                hasCameraPermission = isGranted
                if (isGranted) checkAndInitAR()
            }

            LaunchedEffect(Unit) {
                if (!hasCameraPermission) {
                    permissionLauncher.launch(Manifest.permission.CAMERA)
                } else {
                    checkAndInitAR()
                }
            }

            if (hasCameraPermission) {
                CaptureScreen(
                    isRecording = isRecordingState.value,
                    frameCount = frameCountState.value,
                    statusText = trackingStatusText.value,
                    onToggleRecording = {
                        val willRecord = !isRecordingState.value
                        if (willRecord) {
                            frameCountState.value = 0
                        }
                        isRecordingState.value = willRecord
                    },
                    glSurfaceViewProvider = { getOrCreateGLSurfaceView() }
                )
            } else {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("Camera permission is required for AR capture.")
                }
            }
        }
    }

    private fun checkAndInitAR() {
        try {
            if (session == null) {
                // Ensure Google Play Services for AR is installed and compatible
                when (ArCoreApk.getInstance().requestInstall(this, userRequestedInstall)) {
                    ArCoreApk.InstallStatus.INSTALL_REQUESTED -> {
                        userRequestedInstall = false
                        return
                    }
                    ArCoreApk.InstallStatus.INSTALLED -> {
                        initARSession()
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "ARCore installation or initialization failed", e)
            Toast.makeText(this, "ARCore Error: ${e.localizedMessage}", Toast.LENGTH_LONG).show()
        }
    }

    private fun initARSession() {
        if (session != null) return
        try {
            val arSession = Session(this)
            val config = Config(arSession).apply {
                // Safe depth mode cascade to prevent UnsupportedConfigurationException
                depthMode = when {
                    arSession.isDepthModeSupported(Config.DepthMode.RAW_DEPTH_ONLY) -> Config.DepthMode.RAW_DEPTH_ONLY
                    arSession.isDepthModeSupported(Config.DepthMode.AUTOMATIC) -> Config.DepthMode.AUTOMATIC
                    else -> Config.DepthMode.DISABLED
                }
                focusMode = Config.FocusMode.FIXED
                updateMode = Config.UpdateMode.LATEST_CAMERA_IMAGE
            }
            arSession.configure(config)
            arSession.resume()
            session = arSession
            Log.i(TAG, "AR Session created successfully with depthMode: ${config.depthMode}")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create AR session: ${e.message}", e)
            trackingStatusText.value = "Failed: ${e.message}"
        }
    }

    private fun getOrCreateGLSurfaceView(): GLSurfaceView {
        if (glSurfaceView == null) {
            glSurfaceView = GLSurfaceView(this).apply {
                setEGLContextClientVersion(2)
                setRenderer(ARCameraRenderer())
                renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY
            }
        }
        return glSurfaceView!!
    }

    override fun onResume() {
        super.onResume()
        try {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
                checkAndInitAR()
                session?.resume()
            }
            glSurfaceView?.onResume()
        } catch (e: Exception) {
            Log.e(TAG, "Error resuming session: ${e.message}", e)
        }
    }

    override fun onPause() {
        super.onPause()
        glSurfaceView?.onPause()
        session?.pause()
    }

    override fun onDestroy() {
        super.onDestroy()
        session?.close()
        session = null
    }

    private inner class ARCameraRenderer : GLSurfaceView.Renderer {
        private var textureId = -1
        private var isTextureBoundToSession = false
        private var quadProgram = 0
        private var positionAttrib = 0
        private var texCoordAttrib = 0

        private val vertices = floatArrayOf(
            -1f, -1f,  1f, -1f,
            -1f,  1f,  1f,  1f
        )

        private val vertexBuffer = ByteBuffer.allocateDirect(vertices.size * 4)
            .order(ByteOrder.nativeOrder()).asFloatBuffer().apply { put(vertices); position(0) }
        private val transformedTexCoordBuffer = ByteBuffer.allocateDirect(vertices.size * 4)
            .order(ByteOrder.nativeOrder()).asFloatBuffer()

        override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
            val textures = IntArray(1)
            GLES20.glGenTextures(1, textures, 0)
            textureId = textures[0]
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId)

            // CRITICAL: Set texture filters or GL_TEXTURE_EXTERNAL_OES will render black
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)

            val vShader = GLES20.glCreateShader(GLES20.GL_VERTEX_SHADER).apply {
                GLES20.glShaderSource(this, "attribute vec4 p; attribute vec2 t; varying vec2 v; void main(){ gl_Position=p; v=t; }")
                GLES20.glCompileShader(this)
            }

            val fShader = GLES20.glCreateShader(GLES20.GL_FRAGMENT_SHADER).apply {
                GLES20.glShaderSource(this, "#extension GL_OES_EGL_image_external : require\nprecision mediump float; varying vec2 v; uniform samplerExternalOES tex; void main(){ gl_FragColor=texture2D(tex, v); }")
                GLES20.glCompileShader(this)
            }

            quadProgram = GLES20.glCreateProgram().apply {
                GLES20.glAttachShader(this, vShader)
                GLES20.glAttachShader(this, fShader)
                GLES20.glLinkProgram(this)
            }

            positionAttrib = GLES20.glGetAttribLocation(quadProgram, "p")
            texCoordAttrib = GLES20.glGetAttribLocation(quadProgram, "t")
            isTextureBoundToSession = false
        }

        override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
            GLES20.glViewport(0, 0, width, height)
            @Suppress("DEPRECATION")
            val displayRotation = windowManager.defaultDisplay.rotation
            session?.setDisplayGeometry(displayRotation, width, height)
        }

        override fun onDrawFrame(gl: GL10?) {
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_DEPTH_BUFFER_BIT)
            val activeSession = session ?: return

            // CRITICAL: Bind texture ID as soon as both session and texture exist
            if (!isTextureBoundToSession && textureId != -1) {
                try {
                    activeSession.setCameraTextureName(textureId)
                    isTextureBoundToSession = true
                    Log.i(TAG, "Camera texture bound to AR session successfully.")
                } catch (e: Exception) {
                    Log.e(TAG, "Failed binding camera texture: ${e.message}")
                    return
                }
            }

            try {
                val frame = activeSession.update()

                // Transform quad coordinates to display-oriented texture coordinates
                frame.transformCoordinates2d(
                    Coordinates2d.OPENGL_NORMALIZED_DEVICE_COORDINATES,
                    vertexBuffer,
                    Coordinates2d.TEXTURE_NORMALIZED,
                    transformedTexCoordBuffer
                )

                // Render camera background quad
                GLES20.glUseProgram(quadProgram)
                GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
                GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId)

                GLES20.glEnableVertexAttribArray(positionAttrib)
                GLES20.glVertexAttribPointer(positionAttrib, 2, GLES20.GL_FLOAT, false, 0, vertexBuffer)

                GLES20.glEnableVertexAttribArray(texCoordAttrib)
                GLES20.glVertexAttribPointer(texCoordAttrib, 2, GLES20.GL_FLOAT, false, 0, transformedTexCoordBuffer)

                GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)

                GLES20.glDisableVertexAttribArray(positionAttrib)
                GLES20.glDisableVertexAttribArray(texCoordAttrib)

                // Update UI tracking state
                val trackingState = frame.camera.trackingState
                runOnUiThread {
                    trackingStatusText.value = when (trackingState) {
                        TrackingState.TRACKING -> if (isRecordingState.value) "Capturing..." else "Ready (Tracking Locked)"
                        TrackingState.PAUSED -> "Searching for features... (Move slowly)"
                        TrackingState.STOPPED -> "Tracking Stopped"
                    }
                }

                // Record frame if active and threshold met
                val now = System.currentTimeMillis()
                if (isRecordingState.value && (now - lastSavedTimestampMs >= captureIntervalMs)) {
                    lastSavedTimestampMs = now
                    processAndSaveFrame(frame, now)
                }
            } catch (e: Exception) {
                Log.w(TAG, "Frame render loop skipped: ${e.message}")
            }
        }
    }

    private fun processAndSaveFrame(frame: Frame, timestamp: Long) {
        if (frame.camera.trackingState != TrackingState.TRACKING) return

        val poseMatrix = FloatArray(16)
        frame.camera.pose.toMatrix(poseMatrix, 0)

        var rgbImage: Image? = null
        var depthImage: Image? = null

        try {
            rgbImage = frame.acquireCameraImage()

            // Safe depth acquisition matching session config
            depthImage = try {
                when (session?.config?.depthMode) {
                    Config.DepthMode.RAW_DEPTH_ONLY -> frame.acquireRawDepthImage16Bits()
                    Config.DepthMode.AUTOMATIC -> frame.acquireDepthImage16Bits()
                    else -> null
                }
            } catch (_: Exception) {
                null
            }

            val depthBytes: ByteArray? = depthImage?.let { depth ->
                val buffer = depth.planes[0].buffer
                val bytes = ByteArray(buffer.remaining())
                buffer.get(bytes)
                bytes
            }

            val jpegBytes = convertYuvToJpeg(rgbImage)

            runOnUiThread { frameCountState.value += 1 }

            val targetDir = File(getExternalFilesDir(null), "splat_dataset").apply { mkdirs() }
            CoroutineScope(Dispatchers.IO).launch {
                File(targetDir, "pose_$timestamp.txt").writeText(poseMatrix.joinToString(","))
                File(targetDir, "rgb_$timestamp.jpg").writeBytes(jpegBytes)
                if (depthBytes != null) {
                    File(targetDir, "depth_$timestamp.raw").writeBytes(depthBytes)
                }
            }

        } catch (e: Exception) {
            Log.e(TAG, "Error acquiring frame data: ${e.message}", e)
        } finally {
            rgbImage?.close()
            depthImage?.close()
        }
    }

    private fun convertYuvToJpeg(image: Image): ByteArray {
        val yBuffer = image.planes[0].buffer
        val uBuffer = image.planes[1].buffer
        val vBuffer = image.planes[2].buffer

        val ySize = yBuffer.remaining()
        val uSize = uBuffer.remaining()
        val vSize = vBuffer.remaining()

        val nv21 = ByteArray(ySize + uSize + vSize)
        yBuffer.get(nv21, 0, ySize)
        vBuffer.get(nv21, ySize, vSize)
        uBuffer.get(nv21, ySize + vSize, uSize)

        val yuvImage = YuvImage(nv21, ImageFormat.NV21, image.width, image.height, null)
        val out = ByteArrayOutputStream()
        yuvImage.compressToJpeg(Rect(0, 0, image.width, image.height), 92, out)
        return out.toByteArray()
    }

    companion object {
        private const val TAG = "MainActivity"
    }
}

// ---------------- UI Layer (Compose) ----------------

@Composable
fun CaptureScreen(
    isRecording: Boolean,
    frameCount: Int,
    statusText: String,
    onToggleRecording: () -> Unit,
    glSurfaceViewProvider: () -> GLSurfaceView
) {
    Box(modifier = Modifier.fillMaxSize()) {
        AndroidView(
            factory = { glSurfaceViewProvider() },
            modifier = Modifier.fillMaxSize()
        )

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp),
            verticalArrangement = Arrangement.SpaceBetween,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = Color.Black.copy(alpha = 0.65f)
                ),
                shape = CircleShape
            ) {
                Text(
                    text = if (isRecording) "Frames Logged: $frameCount" else statusText,
                    color = Color.White,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp)
                )
            }

            Button(
                onClick = onToggleRecording,
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (isRecording) Color.Red else Color.White
                ),
                modifier = Modifier
                    .size(80.dp)
                    .background(Color.Transparent)
            ) {
                Text(
                    text = if (isRecording) "STOP" else "REC",
                    color = if (isRecording) Color.White else Color.Black
                )
            }
        }
    }
}