package com.example.splatter.ui.screens

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.example.splatter.model.ScanSession
import com.example.splatter.processor.panorama.EquirectSampler
import com.example.splatter.processor.panorama.PanoramaReadiness
import com.example.splatter.processor.publish.StreetViewPublish
import com.example.splatter.ui.theme.Amber
import com.example.splatter.ui.theme.Fog
import com.example.splatter.ui.theme.Ink
import com.example.splatter.ui.theme.InkContainer
import com.example.splatter.ui.theme.InkElevated
import com.example.splatter.ui.theme.InkHairline
import com.example.splatter.ui.theme.Mint
import com.example.splatter.ui.theme.Mist
import com.example.splatter.ui.theme.OnMint
import com.example.splatter.ui.theme.Periwinkle
import com.example.splatter.ui.theme.Snow
import com.example.splatter.util.PanoramaExporter
import com.example.splatter.util.StreetViewUploader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.PI

/** Longest edge of the decoded equirectangular source kept in memory. */
private const val VIEWER_SOURCE_MAX_DIM = 2048

/** Fraction of the on-screen box that gets actually ray-cast, then upscaled. */
private const val VIEWER_RENDER_SCALE = 0.5f

private const val MIN_FOV_DEG = 35f
private const val MAX_FOV_DEG = 110f

/**
 * The finished 360° photo, seen from the inside, with everything needed to get
 * it onto Google Maps.
 *
 * The viewer is a real perspective render of the sphere rather than a flat
 * preview: a stitched panorama is only useful if the user can look around it and
 * confirm the seam and the poles are clean before publishing it as a Photo
 * Sphere. The readiness panel comes from reading the written JPEG back off disk,
 * so it reports what the file actually contains.
 */
@Composable
fun PanoramaScreen(
    session: ScanSession,
    onBackClicked: () -> Unit
) {
    val context = LocalContext.current
    val panoramaFile = remember(session.id) { session.getPanoramaFile() }

    var sampler by remember(session.id) { mutableStateOf<EquirectSampler?>(null) }
    var report by remember(session.id) { mutableStateOf<PanoramaReadiness.Report?>(null) }
    var loading by remember(session.id) { mutableStateOf(true) }
    var showMapsHelp by remember { mutableStateOf(false) }

    var yawDegrees by remember(session.id) { mutableFloatStateOf(0f) }
    var pitchDegrees by remember(session.id) { mutableFloatStateOf(0f) }
    var fovDegrees by remember(session.id) { mutableFloatStateOf(75f) }
    var boxSize by remember { mutableStateOf(IntSize(720, 405)) }
    var saveAfterGrant by remember { mutableStateOf(false) }

    fun exportPanorama() {
        val file = panoramaFile ?: return
        val uri = PanoramaExporter.saveToPictures(context, file, session.title)
        Toast.makeText(
            context,
            if (uri != null) {
                "Saved to Pictures/Splatter360 — now Contribute → Add photo in Google Maps"
            } else {
                "Could not save the 360° photo"
            },
            Toast.LENGTH_LONG
        ).show()
    }

    // On API 26–28 the shared Pictures collection still needs the legacy
    // storage permission; from API 29 the MediaStore insert needs nothing.
    val storagePermission = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted && saveAfterGrant) exportPanorama()
        saveAfterGrant = false
    }

    LaunchedEffect(session.id) {
        val loaded = withContext(Dispatchers.Default) { loadPanorama(panoramaFile) }
        sampler = loaded?.first
        report = loaded?.second
        loading = false
    }

    // ---- Publishing to Google Street View (which is what puts it on Maps) ----
    //
    // The app has no account of its own: this asks Google for authorization to
    // the streetviewpublish scope on the account already on the device, and the
    // contribution is credited to that account. No token is stored — Google
    // remembers the grant, so a later publish either returns a token silently or
    // shows the consent screen again.
    val scope = rememberCoroutineScope()
    val activity = remember(context) { context.findActivity() }
    var publishing by remember { mutableStateOf(false) }
    var publishStatus by remember { mutableStateOf<String?>(null) }
    var publishError by remember { mutableStateOf<String?>(null) }
    var publishedLink by remember { mutableStateOf<String?>(null) }
    var showPublishSetup by remember { mutableStateOf(false) }

    fun failPublish(message: String) {
        publishing = false
        publishStatus = null
        publishError = message
    }

    fun runUpload(token: String) {
        val file = panoramaFile
        if (file == null) return failPublish("No 360° image on this scan")
        scope.launch {
            val outcome = StreetViewUploader.publish(
                token = token,
                panoramaFile = file,
                headingDegrees = report?.metadata?.poseHeadingDegrees,
                onProgress = { step -> publishStatus = step }
            )
            publishing = false
            publishStatus = null
            when (outcome) {
                is StreetViewPublish.Outcome.Success -> {
                    publishedLink = outcome.published.shareLink
                    // A photo can be uploaded and still not be live on Maps.
                    publishError = if (outcome.published.mapsPublishStatus == "REJECTED_UNKNOWN") {
                        "Google took the upload but has not published it (REJECTED_UNKNOWN). " +
                            "Check the photo against the rules above."
                    } else {
                        null
                    }
                }

                StreetViewPublish.Outcome.NoLocation -> publishError =
                    "This photo has no GPS position, and Google rejects a photo sphere without " +
                        "one. Re-shoot the sweep with location permission granted."

                is StreetViewPublish.Outcome.NotPublishable -> publishError =
                    "Google would reject this file (${outcome.reason}) — see the checks above."

                is StreetViewPublish.Outcome.NotAuthorized -> publishError =
                    "The sign-in was not accepted (${outcome.detail}). Tap Publish to try again."

                is StreetViewPublish.Outcome.Forbidden -> publishError = outcome.detail

                is StreetViewPublish.Outcome.QuotaExceeded -> publishError = outcome.detail

                is StreetViewPublish.Outcome.Rejected -> publishError =
                    "Google rejected it: ${outcome.detail}"

                is StreetViewPublish.Outcome.Transport -> publishError =
                    "Could not reach Google: ${outcome.detail}"
            }
        }
    }

    val consentLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        if (result.resultCode != Activity.RESULT_OK) {
            failPublish("Sign-in cancelled")
            return@rememberLauncherForActivityResult
        }
        val token = activity?.let { StreetViewUploader.authorizationResult(it, result.data)?.accessToken }
        if (token.isNullOrBlank()) failPublish("Google did not return an access token") else runUpload(token)
    }

    fun beginPublish() {
        val act = activity ?: return failPublish("Publishing needs an Activity context")
        val file = panoramaFile
        if (file == null || !file.exists()) return failPublish("No 360° image to publish")

        // Refuse locally rather than uploading the file and letting Google fail
        // it: the position is the one requirement the API checks after upload.
        val metadata = report?.metadata
        if (metadata?.latitude == null || metadata.longitude == null) {
            return failPublish(
                "This photo has no GPS position — Google rejects a photo sphere without one. " +
                    "Re-shoot with location permission granted."
            )
        }
        val blocking = report?.blocking.orEmpty().map { it.label }
        if (blocking.isNotEmpty()) {
            return failPublish("Google would reject this file (${blocking.joinToString(", ").lowercase()}).")
        }

        publishing = true
        publishError = null
        publishedLink = null
        publishStatus = "Asking Google for permission..."
        StreetViewUploader.authorizationClient(act)
            .authorize(StreetViewUploader.authorizationRequest())
            .addOnSuccessListener { auth ->
                if (auth.hasResolution()) {
                    val pending = auth.pendingIntent
                    if (pending == null) {
                        failPublish("Google asked for consent but returned nothing to show")
                    } else {
                        consentLauncher.launch(
                            IntentSenderRequest.Builder(pending.intentSender).build()
                        )
                    }
                } else {
                    val token = auth.accessToken
                    if (token.isNullOrBlank()) {
                        failPublish("Google did not return an access token")
                    } else {
                        runUpload(token)
                    }
                }
            }
            .addOnFailureListener { e -> failPublish("Sign-in failed: ${e.message}") }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Ink)
    ) {
        // ---- Header ----
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 12.dp, end = 18.dp, top = 30.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            TextButton(onClick = onBackClicked) {
                Text("← Back", color = Mint, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            }
            Spacer(Modifier.size(4.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = session.title,
                    color = Snow,
                    fontSize = 17.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1
                )
                Text(
                    text = session.processingNote ?: "360° equirectangular photo",
                    color = Fog,
                    fontSize = 11.sp,
                    maxLines = 2
                )
            }
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(10.dp))
                    .background(InkContainer)
                    .padding(horizontal = 9.dp, vertical = 5.dp)
            ) {
                Text("🌐 360", color = Periwinkle, fontSize = 11.sp, fontWeight = FontWeight.Bold)
            }
        }

        // ---- Viewer ----
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .background(InkContainer)
                .onSizeChanged { boxSize = it }
                .pointerInput(session.id) {
                    detectTransformGestures { _, pan, zoom, _ ->
                        val degreesPerPixel = fovDegrees / size.width.toFloat()
                        yawDegrees = normaliseDegrees(yawDegrees + pan.x * degreesPerPixel)
                        pitchDegrees = (pitchDegrees + pan.y * degreesPerPixel)
                            .coerceIn(-89f, 89f)
                        fovDegrees = (fovDegrees / zoom).coerceIn(MIN_FOV_DEG, MAX_FOV_DEG)
                    }
                }
        ) {
            val active = sampler
            if (active != null) {
                val renderWidth = (boxSize.width * VIEWER_RENDER_SCALE).toInt().coerceIn(160, 960)
                val renderHeight = (renderWidth.toFloat() * boxSize.height / boxSize.width)
                    .toInt().coerceIn(90, 720)

                val frame = remember(active, renderWidth, renderHeight, yawDegrees, pitchDegrees, fovDegrees) {
                    renderFrame(active, renderWidth, renderHeight, yawDegrees, pitchDegrees, fovDegrees)
                }

                Canvas(modifier = Modifier.fillMaxSize()) {
                    frame?.let {
                        // The frame is ray-cast at half the box size and scaled up
                        // here, which is half the cost for no visible difference.
                        drawImage(
                            image = it,
                            dstSize = IntSize(size.width.toInt(), size.height.toInt())
                        )
                    }
                }

                // Look-direction readout: a stitched sphere has no obvious "up".
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .padding(10.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(Ink.copy(alpha = 0.7f))
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                ) {
                    Text(
                        "yaw %.0f°  pitch %.0f°  fov %.0f°".format(
                            yawDegrees, pitchDegrees, fovDegrees
                        ),
                        color = Fog,
                        fontSize = 10.sp
                    )
                }
            } else {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        if (loading) "Loading 360° photo…" else "No panorama image found",
                        color = Fog,
                        fontSize = 13.sp
                    )
                }
            }
        }

        Text(
            "Drag to look around · pinch to zoom",
            color = Mist,
            fontSize = 11.sp,
            modifier = Modifier.padding(start = 18.dp, top = 8.dp)
        )

        // ---- Readiness + export ----
        ReportPanel(
            report = report,
            loading = loading,
            hasFile = panoramaFile?.exists() == true,
            onSaveToPhotos = {
                val needsLegacyGrant = Build.VERSION.SDK_INT < Build.VERSION_CODES.Q &&
                    ContextCompat.checkSelfPermission(
                        context, Manifest.permission.WRITE_EXTERNAL_STORAGE
                    ) != PackageManager.PERMISSION_GRANTED
                if (needsLegacyGrant) {
                    saveAfterGrant = true
                    storagePermission.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                } else {
                    exportPanorama()
                }
            },
            onShare = {
                panoramaFile?.let { PanoramaExporter.share(context, it, session.title) }
            },
            onMapsHelp = { showMapsHelp = true }
        )

        PublishPanel(
            publishing = publishing,
            status = publishStatus,
            error = publishError,
            publishedLink = publishedLink,
            canPublish = panoramaFile?.exists() == true,
            onPublish = { beginPublish() },
            onSetup = { showPublishSetup = true },
            onOpenLink = { link ->
                runCatching {
                    context.startActivity(
                        android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(link))
                    )
                }.onFailure { failPublish("No app can open $link") }
            }
        )
    }

    if (showPublishSetup) {
        PublishSetupDialog(
            packageName = StreetViewUploader.packageName(context),
            sha1 = StreetViewUploader.signingSha1(context),
            onOpenCloud = {
                runCatching {
                    context.startActivity(
                        android.content.Intent(
                            android.content.Intent.ACTION_VIEW,
                            android.net.Uri.parse(
                                "https://console.cloud.google.com/apis/library/streetviewpublish.googleapis.com"
                            )
                        )
                    )
                }
            },
            onDismiss = { showPublishSetup = false }
        )
    }

    if (showMapsHelp) {
        MapsHelpDialog(
            report = report,
            onDismiss = { showMapsHelp = false },
            onOpenMaps = {
                val metadata = report?.metadata
                val lat = metadata?.latitude
                val lon = metadata?.longitude
                if (lat != null && lon != null) {
                    PanoramaExporter.openInMaps(context, lat, lon)
                } else {
                    Toast.makeText(
                        context,
                        "No GPS position on this photo — pick the place in Maps by hand",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        )
    }
}

/** Decodes the JPEG and reads its metadata back. Returns null when unusable. */
private fun loadPanorama(file: File?): Pair<EquirectSampler, PanoramaReadiness.Report?>? {
    if (file == null || !file.exists()) return null
    return try {
        val bytes = file.readBytes()
        val report = PanoramaReadiness.readMetadata(bytes)?.let { PanoramaReadiness.evaluate(it) }

        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0) return null

        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= VIEWER_SOURCE_MAX_DIM) {
            sample *= 2
        }
        val bitmap = BitmapFactory.decodeByteArray(
            bytes, 0, bytes.size,
            BitmapFactory.Options().apply { inSampleSize = sample }
        ) ?: return null

        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        val sampler = EquirectSampler(pixels, bitmap.width, bitmap.height)
        bitmap.recycle()
        sampler to report
    } catch (e: OutOfMemoryError) {
        System.gc()
        null
    } catch (_: Exception) {
        null
    }
}

/** Ray-casts one perspective frame of the sphere into a drawable bitmap. */
private fun renderFrame(
    sampler: EquirectSampler,
    width: Int,
    height: Int,
    yawDegrees: Float,
    pitchDegrees: Float,
    fovDegrees: Float
): androidx.compose.ui.graphics.ImageBitmap? {
    if (width <= 0 || height <= 0) return null
    return try {
        val dest = IntArray(width * height)
        sampler.render(
            dest = dest,
            destWidth = width,
            destHeight = height,
            yawRadians = yawDegrees / 180f * PI.toFloat(),
            pitchRadians = pitchDegrees / 180f * PI.toFloat(),
            horizontalFovRadians = fovDegrees / 180f * PI.toFloat()
        )
        Bitmap.createBitmap(dest, width, height, Bitmap.Config.ARGB_8888).asImageBitmap()
    } catch (e: OutOfMemoryError) {
        System.gc()
        null
    } catch (_: Exception) {
        null
    }
}

private fun normaliseDegrees(degrees: Float): Float {
    var value = degrees % 360f
    if (value > 180f) value -= 360f
    if (value < -180f) value += 360f
    return value
}

@Composable
private fun ReportPanel(
    report: PanoramaReadiness.Report?,
    loading: Boolean,
    hasFile: Boolean,
    onSaveToPhotos: () -> Unit,
    onShare: () -> Unit,
    onMapsHelp: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 18.dp, vertical = 12.dp)
            .verticalScroll(rememberScrollState())
    ) {
        if (report != null) {
            val accent = if (report.ready) Mint else Amber
            Card(
                colors = CardDefaults.cardColors(containerColor = InkElevated),
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(Modifier.padding(14.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = if (report.ready) "✓" else "!",
                            color = accent,
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(Modifier.size(8.dp))
                        Text(
                            text = report.summary,
                            color = Snow,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                    Spacer(Modifier.height(10.dp))
                    report.checks.forEach { check ->
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
                            verticalAlignment = Alignment.Top
                        ) {
                            Text(
                                text = if (check.ok) "✓" else "✕",
                                color = if (check.ok) Mint else Amber,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold
                            )
                            Spacer(Modifier.size(8.dp))
                            Column {
                                Text(check.label, color = Snow, fontSize = 12.sp)
                                Text(check.detail, color = Fog, fontSize = 10.sp)
                            }
                        }
                    }
                }
            }
        } else {
            Text(
                text = if (loading) "Reading the stitched image…" else "No 360° image to check",
                color = Fog,
                fontSize = 12.sp
            )
        }

        Spacer(Modifier.height(12.dp))

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = onSaveToPhotos,
                enabled = hasFile,
                colors = ButtonDefaults.buttonColors(
                    containerColor = Mint,
                    contentColor = OnMint,
                    disabledContainerColor = InkContainer,
                    disabledContentColor = Mist
                ),
                shape = RoundedCornerShape(12.dp),
                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 10.dp)
            ) {
                Text("Save to Photos", fontSize = 12.sp, fontWeight = FontWeight.Bold)
            }
            Button(
                onClick = onShare,
                enabled = hasFile,
                colors = ButtonDefaults.buttonColors(
                    containerColor = InkContainer,
                    contentColor = Snow,
                    disabledContainerColor = InkContainer,
                    disabledContentColor = Mist
                ),
                shape = RoundedCornerShape(12.dp),
                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 10.dp)
            ) {
                Text("Share 360°", fontSize = 12.sp, fontWeight = FontWeight.Bold)
            }
            Button(
                onClick = onMapsHelp,
                colors = ButtonDefaults.buttonColors(
                    containerColor = Periwinkle,
                    contentColor = Ink
                ),
                shape = RoundedCornerShape(12.dp),
                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 10.dp)
            ) {
                Text("Google Maps", fontSize = 12.sp, fontWeight = FontWeight.Bold)
            }
        }

        Spacer(Modifier.height(16.dp))
    }
}

@Composable
private fun MapsHelpDialog(
    report: PanoramaReadiness.Report?,
    onDismiss: () -> Unit,
    onOpenMaps: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = InkElevated,
        title = { Text("Publish to Google Maps", color = Snow, fontSize = 16.sp) },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .border(1.dp, InkHairline, RoundedCornerShape(12.dp))
                    .padding(12.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                StepText("1", "Tap \"Save to Photos\" — the 360° JPEG is written to Pictures/Splatter360 with its GPano and GPS tags intact.")
                Spacer(Modifier.height(8.dp))
                StepText("2", "Open Google Maps → Contribute → Add photo → choose this photo. A 2:1 image with GPano tags is accepted as a Photo Sphere.")
                Spacer(Modifier.height(8.dp))
                StepText("3", "Place the pin where you stood. The photo carries its own position, so Maps should already be close.")
                Spacer(Modifier.height(8.dp))
                StepText("?", "Google Photos also works: share the photo to Photos, then Share → Add to Maps.")

                val blocking = report?.blocking.orEmpty()
                if (blocking.isNotEmpty()) {
                    Spacer(Modifier.height(12.dp))
                    Text(
                        "Fix before uploading: " + blocking.joinToString(", ") { it.label },
                        color = Amber,
                        fontSize = 11.sp
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onOpenMaps(); onDismiss() }) {
                Text(
                    if (report?.metadata?.latitude != null) "Open Maps here" else "Open Maps",
                    color = Mint,
                    fontWeight = FontWeight.Bold
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Close", color = Fog) }
        }
    )
}

/**
 * Publishing, kept separate from the manual export above it because the two are
 * genuinely different routes: this one hands the photo to Google from the app,
 * the one above puts a file where Maps' own picker can find it. The panel says
 * which account the photo will be credited to, because that is the part people
 * reasonably want to know before pressing it.
 */
@Composable
private fun PublishPanel(
    publishing: Boolean,
    status: String?,
    error: String?,
    publishedLink: String?,
    canPublish: Boolean,
    onPublish: () -> Unit,
    onSetup: () -> Unit,
    onOpenLink: (String) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 18.dp)
            .padding(bottom = 18.dp)
    ) {
        Card(
            colors = CardDefaults.cardColors(containerColor = InkElevated),
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(Modifier.padding(14.dp)) {
                Text(
                    "Publish to Google Maps",
                    color = Snow,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    "Uploads this 360° photo to Google Street View from inside the app, using " +
                        "the Google account you sign in with. Nothing is sent until you press it.",
                    color = Fog,
                    fontSize = 11.sp
                )

                Spacer(Modifier.height(10.dp))

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Button(
                        onClick = onPublish,
                        enabled = canPublish && !publishing,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Periwinkle,
                            contentColor = Ink,
                            disabledContainerColor = InkContainer,
                            disabledContentColor = Mist
                        ),
                        shape = RoundedCornerShape(12.dp),
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp)
                    ) {
                        Text(
                            when {
                                publishing -> "Publishing..."
                                publishedLink != null -> "Publish again"
                                else -> "Publish"
                            },
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    Spacer(Modifier.width(8.dp))
                    TextButton(onClick = onSetup) {
                        Text("Setup", color = Fog, fontSize = 12.sp)
                    }
                }

                status?.let {
                    Spacer(Modifier.height(8.dp))
                    Text(it, color = Mist, fontSize = 11.sp)
                }

                if (publishedLink != null) {
                    Spacer(Modifier.height(8.dp))
                    Text("✓ Uploaded and published", color = Mint, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    Text(
                        "Google may take up to 4 days to show it on Maps.",
                        color = Fog,
                        fontSize = 10.sp
                    )
                    Spacer(Modifier.height(6.dp))
                    Button(
                        onClick = { onOpenLink(publishedLink) },
                        colors = ButtonDefaults.buttonColors(containerColor = InkContainer, contentColor = Snow),
                        shape = RoundedCornerShape(12.dp),
                        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp)
                    ) {
                        Text("View on Google Maps", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    }
                }

                error?.let {
                    Spacer(Modifier.height(8.dp))
                    Text(it, color = Amber, fontSize = 11.sp, lineHeight = 15.sp)
                }
            }
        }
        Spacer(Modifier.height(10.dp))
    }
}

/**
 * The one-time Cloud setup, with the two values that must match this exact build
 * shown read off the installed app rather than described: the package name and
 * the SHA-1 of the certificate it was signed with. A debug build and a release
 * build have different fingerprints, and a mismatch is the usual cause of a
 * silent-looking 403 on the first publish.
 */
@Composable
private fun PublishSetupDialog(
    packageName: String,
    sha1: String?,
    onOpenCloud: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = InkElevated,
        title = { Text("Publish setup (once)", color = Snow, fontSize = 16.sp) },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
            ) {
                StepText("1", "In Google Cloud, create a project and enable the Street View Publish API.")
                Spacer(Modifier.height(8.dp))
                StepText("2", "Configure the OAuth consent screen (External is fine) and add your own Google account as a test user.")
                Spacer(Modifier.height(8.dp))
                StepText("3", "Create an OAuth client ID of type Android, with exactly these two values:")

                Spacer(Modifier.height(8.dp))
                SelectionContainer {
                    Column {
                        Text("Package name", color = Mist, fontSize = 10.sp)
                        Text(packageName, color = Mint, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                        Spacer(Modifier.height(6.dp))
                        Text("SHA-1 of this build", color = Mist, fontSize = 10.sp)
                        Text(
                            sha1 ?: "could not be read",
                            color = if (sha1 != null) Mint else Amber,
                            fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                }

                Spacer(Modifier.height(10.dp))
                StepText("4", "Tap Publish and sign in. Google warns that the app is unverified — that is expected for a personal build, and you are the only user.")
                Spacer(Modifier.height(8.dp))
                Text(
                    "No client secret is used or needed: an Android OAuth client is identified by " +
                        "the package name and signature above. Free tier is " +
                        "${StreetViewPublish.DAILY_QUOTA} publish requests per day.",
                    color = Fog,
                    fontSize = 10.sp,
                    lineHeight = 14.sp
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onOpenCloud(); onDismiss() }) {
                Text("Open Cloud console", color = Mint, fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Close", color = Fog) }
        }
    )
}

/** Unwraps the Activity from a Compose [LocalContext]. */
private fun Context.findActivity(): Activity? {
    var current: Context? = this
    while (current is ContextWrapper) {
        if (current is Activity) return current
        current = current.baseContext
    }
    return null
}

@Composable
private fun StepText(badge: String, text: String) {
    Row(verticalAlignment = Alignment.Top) {
        Box(
            modifier = Modifier
                .size(20.dp)
                .clip(CircleShape)
                .background(InkContainer),
            contentAlignment = Alignment.Center
        ) {
            Text(badge, color = Mint, fontSize = 11.sp, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.size(8.dp))
        Text(text, color = Fog, fontSize = 12.sp)
    }
}
