package com.example.splatter.ui.screens

import android.widget.Toast
import androidx.compose.foundation.background
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.example.splatter.model.ScanMode
import com.example.splatter.model.ScanSession
import com.example.splatter.model.SplatPoint
import com.example.splatter.processor.MeshExporter
import com.example.splatter.processor.PlyExporter
import com.example.splatter.processor.mesh.TriangleMesh
import com.example.splatter.ui.theme.Amber
import com.example.splatter.ui.theme.Coral
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
import com.example.splatter.ui.viewer.MeshView
import com.example.splatter.ui.viewer.SplatView
import java.io.File

@Composable
fun ViewerScreen(
    session: ScanSession,
    points: List<SplatPoint>,
    onBackClicked: () -> Unit,
    mesh: TriangleMesh? = null
) {
    if (mesh != null) {
        MeshViewerScreen(session = session, mesh = mesh, onBackClicked = onBackClicked)
    } else {
        SplatViewerScreen(session = session, points = points, onBackClicked = onBackClicked)
    }
}

private val BackgroundNames = listOf("Studio", "Grey", "Light")

/** Model measurements + file details, the "info" panel of the viewer. */
private data class ModelStats(
    val sizeLine: String,
    val countLine: String,
    val fileLines: List<String>
)

/** Bounding-box dimensions of a mesh, in metres, as "W × H × D". */
private fun meshSizeLine(mesh: TriangleMesh): String {
    var minX = Float.MAX_VALUE; var minY = Float.MAX_VALUE; var minZ = Float.MAX_VALUE
    var maxX = -Float.MAX_VALUE; var maxY = -Float.MAX_VALUE; var maxZ = -Float.MAX_VALUE
    var i = 0
    while (i < mesh.positions.size) {
        val x = mesh.positions[i]; val y = mesh.positions[i + 1]; val z = mesh.positions[i + 2]
        if (x < minX) minX = x; if (x > maxX) maxX = x
        if (y < minY) minY = y; if (y > maxY) maxY = y
        if (z < minZ) minZ = z; if (z > maxZ) maxZ = z
        i += 3
    }
    return "%s × %s × %s".format(
        formatLength(maxX - minX),
        formatLength(maxY - minY),
        formatLength(maxZ - minZ)
    )
}

/** Bounding-box dimensions of a point cloud, in metres. */
private fun pointsSizeLine(points: List<SplatPoint>): String {
    var minX = Float.MAX_VALUE; var minY = Float.MAX_VALUE; var minZ = Float.MAX_VALUE
    var maxX = -Float.MAX_VALUE; var maxY = -Float.MAX_VALUE; var maxZ = -Float.MAX_VALUE
    for (p in points) {
        if (p.x < minX) minX = p.x; if (p.x > maxX) maxX = p.x
        if (p.y < minY) minY = p.y; if (p.y > maxY) maxY = p.y
        if (p.z < minZ) minZ = p.z; if (p.z > maxZ) maxZ = p.z
    }
    if (minX > maxX) return "—"
    return "%s × %s × %s".format(
        formatLength(maxX - minX),
        formatLength(maxY - minY),
        formatLength(maxZ - minZ)
    )
}

/** Real-world scale (ARCore poses and the SfM gauge are both metric). */
private fun formatLength(meters: Float): String =
    if (meters < 1f) "%.1f cm".format(meters * 100f) else "%.2f m".format(meters)

private fun fileLine(datasetDirPath: String, name: String): String? {
    val file = File(datasetDirPath, name)
    if (!file.exists()) return null
    val kb = file.length() / 1024f
    val size = if (kb < 1024f) "%.0f KB".format(kb) else "%.1f MB".format(kb / 1024f)
    return "$name  ·  $size"
}

private fun meshStats(session: ScanSession, mesh: TriangleMesh): ModelStats = ModelStats(
    sizeLine = meshSizeLine(mesh),
    countLine = "%,d triangles · %,d vertices · %d frames".format(
        mesh.triangleCount, mesh.vertexCount, session.frameCount
    ),
    fileLines = listOfNotNull(
        fileLine(session.datasetDirPath, MeshExporter.MESH_GLB_FILENAME),
        fileLine(session.datasetDirPath, MeshExporter.MESH_PLY_FILENAME),
        fileLine(session.datasetDirPath, MeshExporter.MESH_OBJ_FILENAME)
    )
)

private fun splatStats(session: ScanSession, points: List<SplatPoint>): ModelStats = ModelStats(
    sizeLine = pointsSizeLine(points),
    countLine = "%,d points · %d frames".format(points.size, session.frameCount),
    fileLines = listOfNotNull(
        fileLine(session.datasetDirPath, "model.splat"),
        fileLine(session.datasetDirPath, "model.ply")
    )
)

@Composable
private fun ScanStatsCard(
    session: ScanSession,
    stats: ModelStats,
    modifier: Modifier = Modifier
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = InkElevated.copy(alpha = 0.94f)),
        shape = RoundedCornerShape(18.dp),
        modifier = modifier
    ) {
        Column(Modifier.padding(14.dp)) {
            Text(
                "MEASUREMENTS",
                color = Fog,
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.4.sp
            )
            Spacer(Modifier.height(6.dp))
            Text(stats.sizeLine, color = Snow, fontSize = 20.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(2.dp))
            Text("bounding box", color = Mist, fontSize = 10.sp)
            Spacer(Modifier.height(10.dp))
            Text(stats.countLine, color = Fog, fontSize = 11.sp)
            Text(session.scanMode.displayName + " mode", color = Fog, fontSize = 11.sp)
            session.processingNote?.takeIf { it.isNotBlank() }?.let { note ->
                Spacer(Modifier.height(6.dp))
                Text(note, color = Amber, fontSize = 10.sp, lineHeight = 13.sp)
            }
            if (stats.fileLines.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                stats.fileLines.forEach { Text(it, color = Mist, fontSize = 10.sp) }
            }
        }
    }
}

/** Floating top bar: back button, title, and the info + share actions. */
@Composable
private fun ViewerTopBar(
    title: String,
    subtitle: String,
    shareLabel: String,
    onBack: () -> Unit,
    onShare: () -> Unit,
    onInfo: (() -> Unit)? = null
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 44.dp, start = 16.dp, end = 16.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Card(
            colors = CardDefaults.cardColors(containerColor = Ink.copy(alpha = 0.82f)),
            shape = CircleShape,
            onClick = onBack
        ) {
            Text(
                text = "← Back",
                color = Snow,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp)
            )
        }

        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = title,
                color = Snow,
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1
            )
            Text(text = subtitle, color = Fog, fontSize = 11.sp)
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (onInfo != null) {
                Card(
                    colors = CardDefaults.cardColors(containerColor = InkContainer),
                    shape = CircleShape,
                    onClick = onInfo
                ) {
                    Text(
                        text = "ⓘ",
                        color = Snow,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(horizontal = 13.dp, vertical = 10.dp)
                    )
                }
            }
            Card(
                colors = CardDefaults.cardColors(containerColor = Mint),
                shape = CircleShape,
                onClick = onShare
            ) {
                Text(
                    text = shareLabel,
                    color = OnMint,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp)
                )
            }
        }
    }
}

/** Toggle / action pill used inside the bottom control sheet. */
@Composable
private fun ControlChip(
    label: String,
    active: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Button(
        onClick = onClick,
        colors = ButtonDefaults.buttonColors(
            containerColor = if (active) Mint else Snow.copy(alpha = 0.08f),
            contentColor = if (active) OnMint else Snow
        ),
        shape = RoundedCornerShape(12.dp),
        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp),
        modifier = modifier
    ) {
        Text(label, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
    }
}

/** Solid action button used for save/export rows. */
@Composable
private fun ActionButton(
    label: String,
    container: Color,
    content: Color,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Button(
        onClick = onClick,
        colors = ButtonDefaults.buttonColors(containerColor = container, contentColor = content),
        shape = RoundedCornerShape(12.dp),
        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp),
        modifier = modifier.height(40.dp)
    ) {
        Text(label, fontSize = 11.sp, fontWeight = FontWeight.Bold, maxLines = 1)
    }
}

@Composable
fun MeshViewerScreen(
    session: ScanSession,
    mesh: TriangleMesh,
    onBackClicked: () -> Unit
) {
    val context = LocalContext.current
    var wireframe by remember { mutableStateOf(false) }
    var autoRotate by remember { mutableStateOf(false) }
    var background by remember { mutableIntStateOf(0) }
    var showStats by remember { mutableStateOf(false) }
    var meshViewRef by remember { mutableStateOf<MeshView?>(null) }

    LaunchedEffect(mesh) { meshViewRef?.setMesh(mesh) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Ink)
    ) {
        AndroidView(
            factory = { ctx ->
                MeshView(ctx).also { view ->
                    meshViewRef = view
                    view.setWireframe(wireframe)
                    view.setMesh(mesh)
                }
            },
            modifier = Modifier.fillMaxSize()
        )

        ViewerTopBar(
            title = session.title,
            subtitle = "%,d triangles · %s".format(mesh.triangleCount, session.scanMode.displayName),
            shareLabel = "Share",
            onBack = onBackClicked,
            onInfo = { showStats = !showStats },
            onShare = {
                session.getMeshFile()?.let { file ->
                    if (file.exists()) MeshExporter.shareFile(context, file, session.title)
                    else Toast.makeText(context, "Mesh file not found", Toast.LENGTH_SHORT).show()
                }
            }
        )

        if (showStats) {
            ScanStatsCard(
                session = session,
                stats = meshStats(session, mesh),
                modifier = Modifier.align(Alignment.TopCenter).padding(top = 104.dp)
            )
        }

        Card(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .padding(16.dp),
            colors = CardDefaults.cardColors(containerColor = InkElevated.copy(alpha = 0.96f)),
            shape = RoundedCornerShape(22.dp)
        ) {
            Column(modifier = Modifier.fillMaxWidth().padding(14.dp)) {
                Text(
                    "MESH VIEWER",
                    color = Fog,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.4.sp
                )
                Spacer(Modifier.height(10.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    ControlChip("⟳ Spin", autoRotate, Modifier.weight(1f)) {
                        autoRotate = !autoRotate
                        meshViewRef?.setAutoRotate(autoRotate)
                    }
                    ControlChip("▦ Wire", wireframe, Modifier.weight(1f)) {
                        wireframe = !wireframe
                        meshViewRef?.setWireframe(wireframe)
                    }
                    ControlChip("◐ ${BackgroundNames[background]}", false, Modifier.weight(1f)) {
                        background = (background + 1) % BackgroundNames.size
                        meshViewRef?.setBackground(background)
                    }
                    ControlChip("⟲ Reset", false, Modifier.weight(1f)) {
                        autoRotate = false
                        meshViewRef?.setAutoRotate(false)
                        meshViewRef?.resetCamera()
                    }
                }

                Spacer(Modifier.height(10.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    val plyFile = session.getMeshFile()
                    ActionButton("Save PLY", Mint, OnMint, Modifier.weight(1f)) {
                        if (plyFile != null && plyFile.exists()) {
                            val ok = MeshExporter.exportFileToDownloads(context, plyFile, session.title)
                            Toast.makeText(
                                context,
                                if (ok) "Saved ${plyFile.name} to Downloads/Splatter3D" else "Failed to save PLY",
                                Toast.LENGTH_LONG
                            ).show()
                        } else Toast.makeText(context, "Mesh PLY not found", Toast.LENGTH_SHORT).show()
                    }
                    val objFile = File(session.datasetDirPath, MeshExporter.MESH_OBJ_FILENAME)
                    ActionButton("Save OBJ", Periwinkle, Ink, Modifier.weight(1f)) {
                        if (objFile.exists()) {
                            val ok = MeshExporter.exportFileToDownloads(context, objFile, session.title)
                            Toast.makeText(
                                context,
                                if (ok) "Saved OBJ to Downloads/Splatter3D" else "Failed to save OBJ",
                                Toast.LENGTH_LONG
                            ).show()
                        } else Toast.makeText(context, "OBJ file not found", Toast.LENGTH_SHORT).show()
                    }
                }

                Spacer(Modifier.height(8.dp))

                // GLB carries the baked vertex colours, so it opens with the
                // scan's real look in 3D viewers, Blender, Windows 3D Viewer,
                // three.js — and re-imports into Polycam.
                val glbFile = File(session.datasetDirPath, MeshExporter.MESH_GLB_FILENAME)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    ActionButton("Save GLB", Amber, Ink, Modifier.weight(1f)) {
                        if (glbFile.exists()) {
                            val ok = MeshExporter.exportFileToDownloads(context, glbFile, session.title)
                            Toast.makeText(
                                context,
                                if (ok) "Saved ${session.title}.glb to Downloads/Splatter3D" else "Failed to save GLB",
                                Toast.LENGTH_LONG
                            ).show()
                        } else Toast.makeText(context, "GLB file not found", Toast.LENGTH_SHORT).show()
                    }
                    ActionButton("Share GLB", Mint, OnMint, Modifier.weight(1f)) {
                        if (glbFile.exists()) {
                            MeshExporter.shareFile(context, glbFile, session.title)
                        } else Toast.makeText(context, "GLB file not found", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
    }
}

@Composable
fun SplatViewerScreen(
    session: ScanSession,
    points: List<SplatPoint>,
    onBackClicked: () -> Unit
) {
    val context = LocalContext.current
    var splatSizeMultiplier by remember { mutableFloatStateOf(1.0f) }
    var autoRotate by remember { mutableStateOf(false) }
    var background by remember { mutableIntStateOf(0) }
    var showStats by remember { mutableStateOf(false) }
    var splatViewRef by remember { mutableStateOf<SplatView?>(null) }

    LaunchedEffect(points) { splatViewRef?.setPoints(points) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Ink)
    ) {
        AndroidView(
            factory = { ctx ->
                SplatView(ctx).also { view ->
                    splatViewRef = view
                    view.setSplatSizeMultiplier(splatSizeMultiplier)
                    view.setPoints(points)
                }
            },
            modifier = Modifier.fillMaxSize()
        )

        ViewerTopBar(
            title = session.title,
            subtitle = "%,d gaussians · %s".format(points.size, session.scanMode.displayName),
            shareLabel = "Share",
            onBack = onBackClicked,
            onInfo = { showStats = !showStats },
            onShare = {
                session.getPlyFile()?.let { file ->
                    if (file.exists()) PlyExporter.sharePlyFile(context, file, session.title)
                    else Toast.makeText(context, "PLY file not found", Toast.LENGTH_SHORT).show()
                }
            }
        )

        if (showStats) {
            ScanStatsCard(
                session = session,
                stats = splatStats(session, points),
                modifier = Modifier.align(Alignment.TopCenter).padding(top = 104.dp)
            )
        }

        Card(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .padding(16.dp),
            colors = CardDefaults.cardColors(containerColor = InkElevated.copy(alpha = 0.96f)),
            shape = RoundedCornerShape(22.dp)
        ) {
            Column(modifier = Modifier.fillMaxWidth().padding(14.dp)) {
                Text(
                    "SPLAT VIEWER",
                    color = Fog,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.4.sp
                )
                Spacer(Modifier.height(10.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    ControlChip("⟳ Spin", autoRotate, Modifier.weight(1f)) {
                        autoRotate = !autoRotate
                        splatViewRef?.setAutoRotate(autoRotate)
                    }
                    ControlChip("◐ ${BackgroundNames[background]}", false, Modifier.weight(1f)) {
                        background = (background + 1) % BackgroundNames.size
                        splatViewRef?.setBackground(background)
                    }
                    ControlChip("⟲ Reset", false, Modifier.weight(1f)) {
                        autoRotate = false
                        splatViewRef?.setAutoRotate(false)
                        splatSizeMultiplier = 1.0f
                        splatViewRef?.setSplatSizeMultiplier(1.0f)
                        splatViewRef?.resetCamera()
                    }
                }

                Spacer(Modifier.height(10.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Splat size", color = Fog, fontSize = 11.sp)
                    Slider(
                        value = splatSizeMultiplier,
                        onValueChange = { newValue ->
                            splatSizeMultiplier = newValue
                            splatViewRef?.setSplatSizeMultiplier(newValue)
                        },
                        valueRange = 0.3f..3.0f,
                        modifier = Modifier.weight(1f).padding(horizontal = 10.dp),
                        colors = SliderDefaults.colors(
                            thumbColor = Mint,
                            activeTrackColor = Mint,
                            inactiveTrackColor = Snow.copy(alpha = 0.15f)
                        )
                    )
                    Text("%.1fx".format(splatSizeMultiplier), color = Snow, fontSize = 11.sp)
                }

                Spacer(Modifier.height(6.dp))

                ActionButton("Save PLY", Mint, OnMint, Modifier.fillMaxWidth()) {
                    session.getPlyFile()?.let { file ->
                        if (file.exists()) {
                            val ok = PlyExporter.exportPlyToDownloads(context, file, session.title)
                            Toast.makeText(
                                context,
                                if (ok) "Saved PLY to Downloads/Splatter3D" else "Failed to save PLY",
                                Toast.LENGTH_LONG
                            ).show()
                        } else Toast.makeText(context, "PLY file not found", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
    }
}
