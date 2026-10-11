package com.example.splatter.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
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
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.example.splatter.model.ScanSession
import com.example.splatter.processor.panorama.EquirectSampler
import com.example.splatter.processor.panorama.PanoramaReadiness
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
import kotlinx.coroutines.Dispatchers
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
