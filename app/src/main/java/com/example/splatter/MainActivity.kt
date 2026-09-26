package com.example.splatter

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.os.Bundle
import android.util.Log
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.example.splatter.model.ScanMode
import com.example.splatter.model.ScanQualityState
import com.example.splatter.model.ScanSession
import com.example.splatter.model.SplatPoint
import com.example.splatter.processor.FrameProcessor
import com.example.splatter.processor.GaussianSplatTrainer
import com.example.splatter.processor.MeshExporter
import com.example.splatter.processor.PhotogrammetryProcessor
import com.example.splatter.processor.SfmProcessor
import com.example.splatter.processor.PlyExporter
import com.example.splatter.processor.RoomReconstructionProcessor
import com.example.splatter.processor.mesh.TriangleMesh
import com.example.splatter.repository.ScanRepository
import com.example.splatter.ui.screens.GalleryScreen
import com.example.splatter.ui.screens.ProcessingScreen
import com.example.splatter.ui.screens.ScanScreen
import com.example.splatter.ui.screens.ViewerScreen
import com.example.splatter.util.FrameSaver
import com.example.splatter.util.ScanQualityEvaluator
import com.google.ar.core.ArCoreApk
import com.google.ar.core.Config
import com.google.ar.core.Coordinates2d
import com.google.ar.core.Frame
import com.google.ar.core.Plane
import com.google.ar.core.Session
import com.google.ar.core.TrackingState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicBoolean
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.sqrt

enum class AppScreen {
    GALLERY,
    SCAN,
    PROCESSING,
    VIEWER
}

class MainActivity : ComponentActivity() {

    private companion object {
        private const val TAG = "MainActivity"
    }

    private var session: Session? = null
    private var glSurfaceView: GLSurfaceView? = null
    private var arCameraRenderer: ARCameraRenderer? = null
    private var userRequestedInstall = true

    private lateinit var repository: ScanRepository

    // State indicators
    private val isRecordingState = mutableStateOf(false)
    private val frameCountState = mutableStateOf(0)
    private val trackingStatusText = mutableStateOf("Initializing AR...")
    private val scanQualityState = mutableStateOf(ScanQualityState())
    private val pendingManualCapture = AtomicBoolean(false)

    private var currentActiveSession: ScanSession? = null
    private var lastCameraPosition: FloatArray? = null
    private var scanStartedAtMs: Long = 0L

    private var lastSavedTimestampMs = 0L
    private val captureIntervalMs = 100L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        repository = ScanRepository(applicationContext)

        setContent {
            var currentScreen by remember { mutableStateOf(AppScreen.GALLERY) }
            var scanSessions by remember { mutableStateOf<List<ScanSession>>(emptyList()) }
            var activeSession by remember { mutableStateOf<ScanSession?>(null) }
            var activeSplatPoints by remember { mutableStateOf<List<SplatPoint>>(emptyList()) }
            var activeMesh by remember { mutableStateOf<TriangleMesh?>(null) }
            var scanPendingName by remember { mutableStateOf<ScanSession?>(null) }
            var selectedScanMode by remember { mutableStateOf(ScanMode.OBJECT) }

            var processingStepText by remember { mutableStateOf("Initializing...") }
            var processingPercent by remember { mutableIntStateOf(0) }
            var processingPointCount by remember { mutableIntStateOf(0) }
            var trainingPhase by remember { mutableStateOf(false) }
            var trainingIteration by remember { mutableIntStateOf(0) }
            var trainingTotalIterations by remember { mutableIntStateOf(0) }
            var trainingLoss by remember { mutableStateOf(0f) }
            var processingIsPhotoMode by remember { mutableStateOf(false) }

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
            }

            LaunchedEffect(Unit) {
                if (!hasCameraPermission) {
                    permissionLauncher.launch(Manifest.permission.CAMERA)
                }
                scanSessions = repository.getScanSessions()
            }

            fun refreshGallery() {
                lifecycleScope.launch {
                    scanSessions = repository.getScanSessions()
                }
            }

            fun finishAndProcessScan() {
                isRecordingState.value = false
                val recordingSession = currentActiveSession ?: return
                val capturedCount = frameCountState.value

                if (capturedCount == 0) {
                    Toast.makeText(this@MainActivity, "No photos captured yet!", Toast.LENGTH_SHORT).show()
                    return
                }

                if (recordingSession.scanMode == ScanMode.PHOTO_SFM && capturedCount < 6) {
                    Toast.makeText(
                        this@MainActivity,
                        "Photo SfM requires at least 6 photos (captured $capturedCount). Snap a few more with 60%+ overlap!",
                        Toast.LENGTH_LONG
                    ).show()
                    return
                }

                currentScreen = AppScreen.PROCESSING
                val photoMode = recordingSession.scanMode == ScanMode.PHOTO
                val photoSfmMode = recordingSession.scanMode == ScanMode.PHOTO_SFM
                processingIsPhotoMode = photoMode || photoSfmMode

                if (photoSfmMode) {
                    lifecycleScope.launch(Dispatchers.Default) {
                        val datasetDir = File(recordingSession.datasetDirPath)
                        val mesh = SfmProcessor.process(
                            datasetDir = datasetDir,
                            scanMode = recordingSession.scanMode,
                            onProgress = { progress ->
                                runOnUiThread {
                                    processingStepText = progress.currentStep
                                    processingPercent = progress.progressPercent
                                    processingPointCount = progress.pointCount
                                }
                            }
                        )

                        if (mesh == null) {
                            runOnUiThread {
                                currentScreen = AppScreen.GALLERY
                                scanPendingName = null
                                processingIsPhotoMode = false
                                Toast.makeText(
                                    this@MainActivity,
                                    "Photo SfM failed — need more texture/overlap: orbit subject with 60%+ overlap",
                                    Toast.LENGTH_LONG
                                ).show()
                                refreshGallery()
                            }
                            return@launch
                        }

                        val meshPlyFile = MeshExporter.exportMeshFiles(mesh, datasetDir)
                        makeThumbnail(datasetDir)?.let { thumbFile ->
                            recordingSession.thumbnailPath = thumbFile.absolutePath
                        }

                        recordingSession.meshFilePath = meshPlyFile?.absolutePath
                        recordingSession.pointCount = mesh.vertexCount

                        runOnUiThread {
                            activeMesh = mesh
                            activeSplatPoints = emptyList()
                            activeSession = recordingSession
                            currentScreen = AppScreen.VIEWER
                            scanPendingName = recordingSession
                            processingIsPhotoMode = false
                        }
                    }
                } else if (photoMode) {
                    lifecycleScope.launch(Dispatchers.Default) {
                        val datasetDir = File(recordingSession.datasetDirPath)
                        val mesh = PhotogrammetryProcessor.process(
                            datasetDir = datasetDir,
                            scanMode = recordingSession.scanMode,
                            onProgress = { progress ->
                                runOnUiThread {
                                    processingStepText = progress.currentStep
                                    processingPercent = progress.progressPercent
                                    processingPointCount = progress.pointCount
                                }
                            }
                        )

                        if (mesh == null) {
                            runOnUiThread {
                                currentScreen = AppScreen.GALLERY
                                scanPendingName = null
                                processingIsPhotoMode = false
                                Toast.makeText(this@MainActivity, "Photo mesh failed — not enough depth data captured", Toast.LENGTH_LONG).show()
                                refreshGallery()
                            }
                            return@launch
                        }

                        val meshPlyFile = MeshExporter.exportMeshFiles(mesh, datasetDir)
                        makeThumbnail(datasetDir)?.let { thumbFile ->
                            recordingSession.thumbnailPath = thumbFile.absolutePath
                        }

                        recordingSession.meshFilePath = meshPlyFile?.absolutePath
                        recordingSession.pointCount = mesh.vertexCount

                        runOnUiThread {
                            activeMesh = mesh
                            activeSplatPoints = emptyList()
                            activeSession = recordingSession
                            currentScreen = AppScreen.VIEWER
                            scanPendingName = recordingSession
                            processingIsPhotoMode = false
                        }
                    }
                } else {
                    lifecycleScope.launch(Dispatchers.Default) {
                        val points = FrameProcessor.processDataset(
                            datasetDir = File(recordingSession.datasetDirPath),
                            scanMode = recordingSession.scanMode,
                            onProgress = { progress ->
                                runOnUiThread {
                                    processingStepText = progress.currentStep
                                    processingPercent = progress.progressPercent
                                    processingPointCount = progress.pointCount
                                }
                            }
                        )

                        runOnUiThread { trainingPhase = true }
                        val trainedPoints = try {
                            GaussianSplatTrainer.trainOnDevice(
                                initialPoints = points,
                                datasetDir = File(recordingSession.datasetDirPath),
                                scanMode = recordingSession.scanMode,
                                onProgress = { progress ->
                                    runOnUiThread {
                                        processingStepText = progress.currentStep
                                        processingPercent = progress.progressPercent
                                        processingPointCount = progress.pointCount
                                        trainingIteration = progress.iteration
                                        trainingTotalIterations = progress.totalIterations
                                        trainingLoss = progress.loss
                                    }
                                }
                            )
                        } catch (e: Exception) {
                            Log.e("MainActivity", "Training failed, using untrained points", e)
                            points.toList()
                        } finally {
                            runOnUiThread { trainingPhase = false }
                        }

                        val plyFile = File(recordingSession.datasetDirPath, "model.ply")
                        PlyExporter.exportToPly(trainedPoints, plyFile)

                        val splatFile = File(recordingSession.datasetDirPath, "model.splat")
                        PlyExporter.exportToSplat(trainedPoints, splatFile)

                        val reconstructedPlanes = RoomReconstructionProcessor.extractPlanes(trainedPoints)
                        val planeObj = File(recordingSession.datasetDirPath, "room_planes.obj")
                        planeObj.writeText(RoomReconstructionProcessor.exportPlanesAsObj(reconstructedPlanes))

                        val datasetDir = File(recordingSession.datasetDirPath)
                        makeThumbnail(datasetDir)?.let { thumbFile ->
                            recordingSession.thumbnailPath = thumbFile.absolutePath
                        }

                        recordingSession.plyFilePath = plyFile.absolutePath
                        recordingSession.splatFilePath = splatFile.absolutePath
                        recordingSession.pointCount = trainedPoints.size

                        runOnUiThread {
                            activeSplatPoints = trainedPoints
                            activeMesh = null
                            activeSession = recordingSession
                            currentScreen = AppScreen.VIEWER
                            scanPendingName = recordingSession
                        }
                    }
                }
            }

            fun processExistingSession(session: ScanSession) {
                val datasetDir = File(session.datasetDirPath)
                val rgbCount = datasetDir.listFiles { _, name -> name.startsWith("rgb_") && name.endsWith(".jpg") }?.size ?: 0
                if (rgbCount == 0) {
                    Toast.makeText(this@MainActivity, "No captured frame data found for this scan", Toast.LENGTH_SHORT).show()
                    return
                }

                activeSession = session
                currentScreen = AppScreen.PROCESSING
                val photoMode = session.scanMode == ScanMode.PHOTO
                val photoSfmMode = session.scanMode == ScanMode.PHOTO_SFM
                processingIsPhotoMode = photoMode || photoSfmMode

                if (photoSfmMode) {
                    lifecycleScope.launch(Dispatchers.Default) {
                        val mesh = SfmProcessor.process(
                            datasetDir = datasetDir,
                            scanMode = session.scanMode,
                            onProgress = { progress ->
                                runOnUiThread {
                                    processingStepText = progress.currentStep
                                    processingPercent = progress.progressPercent
                                    processingPointCount = progress.pointCount
                                }
                            }
                        )

                        if (mesh == null) {
                            runOnUiThread {
                                currentScreen = AppScreen.GALLERY
                                processingIsPhotoMode = false
                                Toast.makeText(
                                    this@MainActivity,
                                    "Photo SfM failed — orbit slower with 60%+ overlap and good lighting",
                                    Toast.LENGTH_LONG
                                ).show()
                                refreshGallery()
                            }
                            return@launch
                        }

                        val meshPlyFile = MeshExporter.exportMeshFiles(mesh, datasetDir)
                        makeThumbnail(datasetDir)?.let { thumbFile ->
                            session.thumbnailPath = thumbFile.absolutePath
                        }
                        session.meshFilePath = meshPlyFile?.absolutePath
                        session.pointCount = mesh.vertexCount

                        runOnUiThread {
                            activeMesh = mesh
                            activeSplatPoints = emptyList()
                            activeSession = session
                            currentScreen = AppScreen.VIEWER
                            processingIsPhotoMode = false
                            refreshGallery()
                        }
                    }
                } else if (photoMode) {
                    lifecycleScope.launch(Dispatchers.Default) {
                        val mesh = PhotogrammetryProcessor.process(
                            datasetDir = datasetDir,
                            scanMode = session.scanMode,
                            onProgress = { progress ->
                                runOnUiThread {
                                    processingStepText = progress.currentStep
                                    processingPercent = progress.progressPercent
                                    processingPointCount = progress.pointCount
                                }
                            }
                        )

                        if (mesh == null) {
                            runOnUiThread {
                                currentScreen = AppScreen.GALLERY
                                processingIsPhotoMode = false
                                Toast.makeText(this@MainActivity, "Photo mesh failed — not enough depth data captured", Toast.LENGTH_LONG).show()
                                refreshGallery()
                            }
                            return@launch
                        }

                        val meshPlyFile = MeshExporter.exportMeshFiles(mesh, datasetDir)
                        makeThumbnail(datasetDir)?.let { thumbFile ->
                            session.thumbnailPath = thumbFile.absolutePath
                        }
                        session.meshFilePath = meshPlyFile?.absolutePath
                        session.pointCount = mesh.vertexCount

                        runOnUiThread {
                            activeMesh = mesh
                            activeSplatPoints = emptyList()
                            activeSession = session
                            currentScreen = AppScreen.VIEWER
                            processingIsPhotoMode = false
                            refreshGallery()
                        }
                    }
                } else {
                    lifecycleScope.launch(Dispatchers.Default) {
                        val points = FrameProcessor.processDataset(
                            datasetDir = datasetDir,
                            scanMode = session.scanMode,
                            onProgress = { progress ->
                                runOnUiThread {
                                    processingStepText = progress.currentStep
                                    processingPercent = progress.progressPercent
                                    processingPointCount = progress.pointCount
                                }
                            }
                        )

                        runOnUiThread { trainingPhase = true }
                        val trainedPoints = try {
                            GaussianSplatTrainer.trainOnDevice(
                                initialPoints = points,
                                datasetDir = datasetDir,
                                scanMode = session.scanMode,
                                onProgress = { progress ->
                                    runOnUiThread {
                                        processingStepText = progress.currentStep
                                        processingPercent = progress.progressPercent
                                        processingPointCount = progress.pointCount
                                        trainingIteration = progress.iteration
                                        trainingTotalIterations = progress.totalIterations
                                        trainingLoss = progress.loss
                                    }
                                }
                            )
                        } catch (e: Exception) {
                            Log.e("MainActivity", "Training failed, using untrained points", e)
                            points.toList()
                        } finally {
                            runOnUiThread { trainingPhase = false }
                        }

                        val plyFile = File(session.datasetDirPath, "model.ply")
                        PlyExporter.exportToPly(trainedPoints, plyFile)

                        val splatFile = File(session.datasetDirPath, "model.splat")
                        PlyExporter.exportToSplat(trainedPoints, splatFile)

                        makeThumbnail(datasetDir)?.let { thumbFile ->
                            session.thumbnailPath = thumbFile.absolutePath
                        }
                        session.plyFilePath = plyFile.absolutePath
                        session.splatFilePath = splatFile.absolutePath
                        session.pointCount = trainedPoints.size

                        runOnUiThread {
                            activeSplatPoints = trainedPoints
                            activeMesh = null
                            activeSession = session
                            currentScreen = AppScreen.VIEWER
                            refreshGallery()
                        }
                    }
                }
            }

            when (currentScreen) {
                AppScreen.GALLERY -> {
                    GalleryScreen(
                        sessions = scanSessions,
                        onStartNewScan = {
                            lifecycleScope.launch {
                                val newSession = repository.createNewScanSession(scanMode = selectedScanMode)
                                currentActiveSession = newSession
                                activeSession = newSession
                                frameCountState.value = 0
                                isRecordingState.value = false
                                checkAndInitAR()
                                currentScreen = AppScreen.SCAN
                            }
                        },
                        onOpenSession = { selectedSession ->
                            val meshFile = selectedSession.getMeshFile()
                            if (meshFile != null && meshFile.exists()) {
                                activeSession = selectedSession
                                processingIsPhotoMode = true
                                currentScreen = AppScreen.PROCESSING
                                processingStepText = "Loading Photo Mesh..."
                                processingPercent = 40
                                processingPointCount = selectedSession.pointCount

                                lifecycleScope.launch(Dispatchers.Default) {
                                    val mesh = MeshExporter.loadMeshPly(meshFile)
                                    runOnUiThread {
                                        if (mesh != null) {
                                            activeMesh = mesh
                                            activeSplatPoints = emptyList()
                                            currentScreen = AppScreen.VIEWER
                                        } else {
                                            currentScreen = AppScreen.GALLERY
                                            Toast.makeText(this@MainActivity, "Failed to load mesh", Toast.LENGTH_SHORT).show()
                                        }
                                        processingIsPhotoMode = false
                                    }
                                }
                            } else {
                                val plyFile = selectedSession.getPlyFile()
                                if (plyFile != null && plyFile.exists()) {
                                    activeSession = selectedSession
                                    processingIsPhotoMode = false
                                    activeMesh = null
                                    currentScreen = AppScreen.PROCESSING
                                    processingStepText = "Loading 3D Gaussian Splat model..."
                                    processingPercent = 40
                                    processingPointCount = selectedSession.pointCount

                                    lifecycleScope.launch(Dispatchers.Default) {
                                        val points = PlyExporter.loadPlyFile(plyFile)
                                        activeSplatPoints = points
                                        runOnUiThread {
                                            currentScreen = AppScreen.VIEWER
                                        }
                                    }
                                } else {
                                    processExistingSession(selectedSession)
                                }
                            }
                        },
                        onDeleteSession = { sessionToDelete ->
                            lifecycleScope.launch {
                                repository.deleteScanSession(sessionToDelete)
                                refreshGallery()
                            }
                        },
                        onRenameSession = { sessionToRename, newTitle ->
                            lifecycleScope.launch {
                                repository.renameScanSession(sessionToRename, newTitle)
                                refreshGallery()
                            }
                        }
                    )
                }

                AppScreen.SCAN -> {
                    ScanScreen(
                        selectedMode = selectedScanMode,
                        onModeSelected = { mode ->
                            selectedScanMode = mode
                            currentActiveSession?.let { current ->
                                current.scanMode = mode
                                lifecycleScope.launch(Dispatchers.IO) {
                                    File(current.datasetDirPath, "mode.txt").writeText(mode.id)
                                }
                            }
                        },
                        isRecording = isRecordingState.value,
                        frameCount = frameCountState.value,
                        statusText = trackingStatusText.value,
                        scanQuality = scanQualityState.value,
                        onTakeSinglePhoto = {
                            lifecycleScope.launch {
                                if (currentActiveSession == null) {
                                    val newSession = repository.createNewScanSession(scanMode = selectedScanMode)
                                    currentActiveSession = newSession
                                    activeSession = newSession
                                    frameCountState.value = 0
                                    isRecordingState.value = false
                                    scanStartedAtMs = System.currentTimeMillis()
                                    lastCameraPosition = null
                                    scanQualityState.value = ScanQualityState(frameCount = 0)
                                    checkAndInitAR()
                                }
                                pendingManualCapture.set(true)
                            }
                        },
                        onToggleRecording = {
                            val willRecord = !isRecordingState.value
                            if (willRecord) {
                                if (currentActiveSession == null) {
                                    lifecycleScope.launch {
                                        val newSession = repository.createNewScanSession(scanMode = selectedScanMode)
                                        currentActiveSession = newSession
                                        activeSession = newSession
                                        frameCountState.value = 0
                                        scanStartedAtMs = System.currentTimeMillis()
                                        lastCameraPosition = null
                                        scanQualityState.value = ScanQualityState(frameCount = 0)
                                        isRecordingState.value = true
                                        checkAndInitAR()
                                    }
                                } else {
                                    scanStartedAtMs = System.currentTimeMillis()
                                    isRecordingState.value = true
                                }
                            } else {
                                finishAndProcessScan()
                            }
                        },
                        onFinishScan = {
                            finishAndProcessScan()
                        },
                        onBackClicked = {
                            isRecordingState.value = false
                            currentScreen = AppScreen.GALLERY
                            refreshGallery()
                        },
                        glSurfaceViewProvider = { getOrCreateGLSurfaceView() }
                    )
                }

                AppScreen.PROCESSING -> {
                    ProcessingScreen(
                        currentStep = processingStepText,
                        progressPercent = processingPercent,
                        pointCount = processingPointCount,
                        isTraining = trainingPhase,
                        trainingIteration = trainingIteration,
                        trainingTotalIterations = trainingTotalIterations,
                        trainingLoss = trainingLoss,
                        titleText = if (processingIsPhotoMode) "Building Photo Mesh" else "Processing 3D Splat Model",
                        pointCountLabel = if (processingIsPhotoMode) "Vertices" else "Gaussians"
                    )
                }

                AppScreen.VIEWER -> {
                    val sessionToView = activeSession
                    if (sessionToView != null) {
                        ViewerScreen(
                            session = sessionToView,
                            points = activeSplatPoints,
                            mesh = activeMesh,
                            onBackClicked = {
                                currentScreen = AppScreen.GALLERY
                                refreshGallery()
                            }
                        )
                    } else {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Text("Error: Session not found")
                        }
                    }
                }
            }

            scanPendingName?.let { sessionToName ->
                var text by remember(sessionToName.id) { mutableStateOf("") }
                AlertDialog(
                    onDismissRequest = { scanPendingName = null },
                    title = { Text("Name this scan") },
                    text = {
                        OutlinedTextField(
                            value = text,
                            onValueChange = { text = it },
                            placeholder = { Text(sessionToName.title) },
                            singleLine = true
                        )
                    },
                    confirmButton = {
                        TextButton(onClick = {
                            if (text.isNotBlank()) {
                                lifecycleScope.launch {
                                    repository.renameScanSession(sessionToName, text.trim())
                                    refreshGallery()
                                }
                            }
                            scanPendingName = null
                        }) { Text("Save") }
                    },
                    dismissButton = {
                        TextButton(onClick = { scanPendingName = null }) { Text("Skip") }
                    }
                )
            }
        }
    }

    /** Creates thumbnail.jpg from the first captured RGB frame; returns null on failure. */
    private fun makeThumbnail(datasetDir: File): File? {
        return try {
            val firstRgbFile = datasetDir.listFiles { _, name -> name.startsWith("rgb_") && name.endsWith(".jpg") }
                ?.minByOrNull { it.name.removePrefix("rgb_").removeSuffix(".jpg").toLongOrNull() ?: Long.MAX_VALUE }
                ?: return null

            val thumbFile = File(datasetDir, "thumbnail.jpg")
            val bitmap = BitmapFactory.decodeFile(firstRgbFile.absolutePath) ?: return null
            val orientedBmp = if (bitmap.width > bitmap.height) {
                val matrix = Matrix().apply { postRotate(90f) }
                val rotated = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
                bitmap.recycle()
                rotated
            } else {
                bitmap
            }

            val targetWidth = 320
            val targetHeight = (targetWidth.toFloat() * orientedBmp.height / orientedBmp.width).toInt()
            val scaled = Bitmap.createScaledBitmap(orientedBmp, targetWidth, targetHeight, true)
            if (scaled != orientedBmp) orientedBmp.recycle()

            FileOutputStream(thumbFile).use { out -> scaled.compress(Bitmap.CompressFormat.JPEG, 85, out) }
            scaled.recycle()
            thumbFile
        } catch (e: Exception) {
            Log.w(TAG, "Failed to create thumbnail: ${e.message}")
            null
        }
    }

    private fun checkAndInitAR() {
        try {
            if (session == null) {
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
                depthMode = when {
                    arSession.isDepthModeSupported(Config.DepthMode.RAW_DEPTH_ONLY) -> Config.DepthMode.RAW_DEPTH_ONLY
                    arSession.isDepthModeSupported(Config.DepthMode.AUTOMATIC) -> Config.DepthMode.AUTOMATIC
                    else -> Config.DepthMode.DISABLED
                }
                planeFindingMode = Config.PlaneFindingMode.HORIZONTAL_AND_VERTICAL
                focusMode = try {
                    Config.FocusMode.AUTO
                } catch (_: Exception) {
                    Config.FocusMode.FIXED
                }
                updateMode = Config.UpdateMode.LATEST_CAMERA_IMAGE
            }
            arSession.configure(config)
            arSession.resume()
            session = arSession
            arCameraRenderer?.resetTextureBinding()
            Log.i(TAG, "AR Session created successfully with depthMode: ${config.depthMode}")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create AR session: ${e.message}", e)
            trackingStatusText.value = "Failed: ${e.message}"
        }
    }

    private fun getOrCreateGLSurfaceView(): GLSurfaceView {
        if (glSurfaceView == null) {
            val renderer = ARCameraRenderer().also { arCameraRenderer = it }
            glSurfaceView = GLSurfaceView(this).apply {
                setEGLContextClientVersion(2)
                setRenderer(renderer)
                renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY
            }
        }
        (glSurfaceView?.parent as? ViewGroup)?.removeView(glSurfaceView)
        return glSurfaceView!!
    }

    override fun onResume() {
        super.onResume()
        try {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
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
        @Volatile
        private var isTextureBoundToSession = false
        private var quadProgram = 0
        private var positionAttrib = 0
        private var texCoordAttrib = 0
        private var texUniform = -1

        fun resetTextureBinding() {
            isTextureBoundToSession = false
        }

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
            texUniform = GLES20.glGetUniformLocation(quadProgram, "tex")
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

                // Crucial fix: rewind FloatBuffer positions before transformCoordinates2d
                vertexBuffer.position(0)
                transformedTexCoordBuffer.position(0)

                frame.transformCoordinates2d(
                    Coordinates2d.OPENGL_NORMALIZED_DEVICE_COORDINATES,
                    vertexBuffer,
                    Coordinates2d.TEXTURE_NORMALIZED,
                    transformedTexCoordBuffer
                )

                // Rewind FloatBuffers after transformation so glVertexAttribPointer reads from index 0!
                vertexBuffer.position(0)
                transformedTexCoordBuffer.position(0)

                GLES20.glUseProgram(quadProgram)
                GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
                GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId)
                if (texUniform != -1) {
                    GLES20.glUniform1i(texUniform, 0)
                }

                GLES20.glEnableVertexAttribArray(positionAttrib)
                GLES20.glVertexAttribPointer(positionAttrib, 2, GLES20.GL_FLOAT, false, 0, vertexBuffer)

                GLES20.glEnableVertexAttribArray(texCoordAttrib)
                GLES20.glVertexAttribPointer(texCoordAttrib, 2, GLES20.GL_FLOAT, false, 0, transformedTexCoordBuffer)

                GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)

                GLES20.glDisableVertexAttribArray(positionAttrib)
                GLES20.glDisableVertexAttribArray(texCoordAttrib)

                val trackingState = frame.camera.trackingState
                val planeCount = try {
                    activeSession.getAllTrackables(Plane::class.java).count { it.trackingState == TrackingState.TRACKING }
                } catch (_: Exception) {
                    0
                }

                val depthCoveragePercent = estimateDepthCoveragePercent(frame)
                val brightness = estimateBrightness(frame)
                val distanceMeters = estimateDistanceMeters(frame)
                val currentPose = frame.camera.pose.translation
                val motionMetersPerFrame = if (lastCameraPosition != null) {
                    val dx = currentPose[0] - lastCameraPosition!![0]
                    val dy = currentPose[1] - lastCameraPosition!![1]
                    val dz = currentPose[2] - lastCameraPosition!![2]
                    sqrt((dx * dx + dy * dy + dz * dz).toDouble()).toFloat()
                } else 0f
                lastCameraPosition = currentPose.clone()

                val quality = ScanQualityEvaluator.evaluateLive(
                    trackingState = trackingState,
                    motionMetersPerFrame = motionMetersPerFrame,
                    distanceMeters = distanceMeters,
                    depthCoveragePercent = depthCoveragePercent,
                    brightness = brightness,
                    frameCount = frameCountState.value,
                    elapsedRecordingMs = System.currentTimeMillis() - scanStartedAtMs,
                    scanMode = currentActiveSession?.scanMode ?: ScanMode.OBJECT,
                    storageLow = false,
                    previouslyScannedAreaDetected = false
                )

                runOnUiThread {
                    trackingStatusText.value = when (trackingState) {
                        TrackingState.TRACKING -> {
                            if (isRecordingState.value) {
                                val modeName = currentActiveSession?.scanMode?.displayName ?: "Scan"
                                if (planeCount > 0) "Capturing $modeName ($planeCount planes tracked)" else "Capturing $modeName..."
                            } else if (frameCountState.value > 0) {
                                "Captured ${frameCountState.value} photos"
                            } else {
                                if (planeCount > 0) "Ready ($planeCount floor/wall planes detected)" else "Ready (Tracking Locked)"
                            }
                        }
                        TrackingState.PAUSED -> "Searching for features... (Move slowly)"
                        TrackingState.STOPPED -> "Tracking Stopped"
                    }
                    scanQualityState.value = quality.copy(
                        frameCount = frameCountState.value,
                        pointCount = (quality.pointCount.coerceAtLeast(0))
                    )
                }

                val now = System.currentTimeMillis()
                val isManual = pendingManualCapture.compareAndSet(true, false)
                val isAutoRec = isRecordingState.value && (now - lastSavedTimestampMs >= captureIntervalMs)

                if ((isManual || isAutoRec) && trackingState == TrackingState.TRACKING) {
                    lastSavedTimestampMs = now
                    currentActiveSession?.let { activeScan ->
                        val targetDir = File(activeScan.datasetDirPath)
                        val saved = FrameSaver.saveFrameData(frame, now, targetDir)
                        if (saved) {
                            runOnUiThread {
                                frameCountState.value += 1
                                if (isManual) {
                                    Toast.makeText(this@MainActivity, "📸 Photo ${frameCountState.value} captured!", Toast.LENGTH_SHORT).show()
                                }
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Frame render loop skipped: ${e.message}")
            }
        }
    }

    private fun estimateDepthCoveragePercent(frame: Frame): Int {
        return try {
            val depthImage = frame.acquireRawDepthImage16Bits()
            val depthBuffer = depthImage.planes[0].buffer
            val totalPixels = depthImage.width * depthImage.height
            val shortBuffer = depthBuffer.order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
            var validPixels = 0
            for (i in 0 until shortBuffer.remaining()) {
                val depthMm = shortBuffer.get(i).toInt() and 0xFFFF
                if (depthMm > 0) validPixels++
            }
            depthImage.close()
            ((validPixels.toFloat() / totalPixels.toFloat()) * 100f).toInt().coerceIn(0, 100)
        } catch (_: Exception) {
            0
        }
    }

    private fun estimateBrightness(frame: Frame): Float {
        return try {
            val cameraImage = frame.acquireCameraImage()
            val plane = cameraImage.planes[0].buffer
            var totalBrightness = 0L
            val pixelCount = plane.remaining()
            val sampleStep = maxOf(1, pixelCount / 1000)
            var samples = 0
            var i = 0
            while (i < pixelCount) {
                totalBrightness += (plane.get(i).toInt() and 0xFF)
                samples++
                i += sampleStep
            }
            cameraImage.close()
            if (samples > 0) (totalBrightness.toFloat() / samples.toFloat()) else 128f
        } catch (_: Exception) {
            128f
        }
    }

    private fun estimateDistanceMeters(frame: Frame): Float {
        return try {
            val depthImage = frame.acquireRawDepthImage16Bits()
            val depthBuffer = depthImage.planes[0].buffer
            val shortBuffer = depthBuffer.order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
            var sumDepthMm = 0L
            var validPixels = 0
            val pixelCount = shortBuffer.remaining()
            val sampleStep = maxOf(1, pixelCount / 1000)
            var i = 0
            while (i < pixelCount) {
                val depthMm = shortBuffer.get(i).toInt() and 0xFFFF
                if (depthMm in 1..9999) {
                    sumDepthMm += depthMm
                    validPixels++
                }
                i += sampleStep
            }
            depthImage.close()
            if (validPixels > 0) (sumDepthMm.toFloat() / validPixels) / 1000f else 1.5f
        } catch (_: Exception) {
            1.5f
        }
    }
}
