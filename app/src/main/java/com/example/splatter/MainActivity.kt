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
import com.google.ar.core.Config
import com.google.ar.core.Frame
import com.google.ar.core.Session
import com.google.ar.core.TrackingState
import com.google.ar.core.exceptions.NotYetAvailableException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.ByteArrayOutputStream
import java.io.File
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

class MainActivity : ComponentActivity() {

    private var session: Session? = null
    private var glSurfaceView: GLSurfaceView? = null

    // Recording & state controls
    private val isRecordingState = mutableStateOf(false)
    private val frameCountState = mutableStateOf(0)
    private var lastSavedTimestampMs = 0L
    private val captureIntervalMs = 150L // ~6-7 captures per second to prevent I/O bottlenecks

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
                if (isGranted) initARSession()
            }

            LaunchedEffect(Unit) {
                if (!hasCameraPermission) {
                    permissionLauncher.launch(Manifest.permission.CAMERA)
                } else {
                    initARSession()
                }
            }

            if (hasCameraPermission) {
                CaptureScreen(
                    isRecording = isRecordingState.value,
                    frameCount = frameCountState.value,
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

    private fun initARSession() {
        if (session != null) return
        try {
            val arSession = Session(this)
            val config = Config(arSession).apply {
                depthMode = if (arSession.isDepthModeSupported(Config.DepthMode.RAW_DEPTH_ONLY)) {
                    Config.DepthMode.RAW_DEPTH_ONLY
                } else {
                    Config.DepthMode.AUTOMATIC
                }
                focusMode = Config.FocusMode.FIXED
                updateMode = Config.UpdateMode.LATEST_CAMERA_IMAGE
            }
            arSession.configure(config)
            session = arSession
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create AR session: ${e.message}")
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
            session?.resume()
            glSurfaceView?.onResume()
        } catch (e: Exception) {
            Log.e(TAG, "Error resuming session: ${e.message}")
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

    // OpenGL background renderer to trigger ARCore frame updates
    private inner class ARCameraRenderer : GLSurfaceView.Renderer {
        private var textureId = -1

        override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
            GLES20.glClearColor(0.1f, 0.1f, 0.1f, 1.0f)
            val textures = IntArray(1)
            GLES20.glGenTextures(1, textures, 0)
            textureId = textures[0]
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId)
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_NEAREST)
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_NEAREST)

            session?.setCameraTextureName(textureId)
        }

        override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
            GLES20.glViewport(0, 0, width, height)
            session?.setDisplayGeometry(0, width, height)
        }

        override fun onDrawFrame(gl: GL10?) {
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_DEPTH_BUFFER_BIT)
            val activeSession = session ?: return

            try {
                val frame = activeSession.update()

                // Record frames if toggled on and interval threshold met
                val now = System.currentTimeMillis()
                if (isRecordingState.value && (now - lastSavedTimestampMs >= captureIntervalMs)) {
                    lastSavedTimestampMs = now
                    processAndSaveFrame(frame, now)
                }
            } catch (_: Exception) {
                // Ignore transient frame skips during camera warmup
            }
        }
    }

    private fun processAndSaveFrame(frame: Frame, timestamp: Long) {
        if (frame.camera.trackingState != TrackingState.TRACKING) return

        // 1. Extract Pose (Column-major 4x4 matrix)
        val poseMatrix = FloatArray(16)
        frame.camera.pose.toMatrix(poseMatrix, 0)

        // 2. Extract Images safely
        var rgbImage: Image? = null
        var depthImage: Image? = null

        try {
            rgbImage = frame.acquireCameraImage()
            depthImage = try {
                frame.acquireRawDepthImage16Bits()
            } catch (_: NotYetAvailableException) {
                null
            }

            if (depthImage == null) {
                rgbImage.close()
                return
            }

            // Extract byte buffers immediately before closing frame handles
            val depthBuffer = depthImage.planes[0].buffer
            val depthBytes = ByteArray(depthBuffer.remaining())
            depthBuffer.get(depthBytes)

            val jpegBytes = convertYuvToJpeg(rgbImage)

            // Increment UI counter
            runOnUiThread { frameCountState.value += 1 }

            // 3. Offload file writes to background I/O
            val targetDir = File(getExternalFilesDir(null), "splat_dataset").apply { mkdirs() }
            CoroutineScope(Dispatchers.IO).launch {
                File(targetDir, "pose_$timestamp.txt").writeText(poseMatrix.joinToString(","))
                File(targetDir, "depth_$timestamp.raw").writeBytes(depthBytes)
                File(targetDir, "rgb_$timestamp.jpg").writeBytes(jpegBytes)
            }

        } catch (e: Exception) {
            Log.e(TAG, "Error acquiring frame data: ${e.message}")
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
    onToggleRecording: () -> Unit,
    glSurfaceViewProvider: () -> GLSurfaceView
) {
    Box(modifier = Modifier.fillMaxSize()) {
        // Camera Preview Feed
        AndroidView(
            factory = { glSurfaceViewProvider() },
            modifier = Modifier.fillMaxSize()
        )

        // Overlay Dashboard
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp),
            verticalArrangement = Arrangement.SpaceBetween,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Stats HUD
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = Color.Black.copy(alpha = 0.6f)
                ),
                shape = CircleShape
            ) {
                Text(
                    text = if (isRecording) "Frames Logged: $frameCount" else "Ready to Capture",
                    color = Color.White,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp)
                )
            }

            // Record / Stop Action Button
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
