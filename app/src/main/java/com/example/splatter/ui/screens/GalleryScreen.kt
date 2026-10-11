package com.example.splatter.ui.screens

import android.content.Intent
import android.graphics.BitmapFactory
import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import com.example.splatter.model.ScanMode
import com.example.splatter.model.ScanSession
import com.example.splatter.processor.MeshExporter
import com.example.splatter.processor.PlyExporter
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
import com.example.splatter.util.PanoramaExporter
import com.example.splatter.util.ScanArchiveExporter
import java.io.File

private enum class SortOrder(val label: String) {
    NEWEST("Newest"),
    OLDEST("Oldest"),
    NAME("Name"),
    POINTS("Points")
}

@Composable
fun GalleryScreen(
    sessions: List<ScanSession>,
    onStartNewScan: () -> Unit,
    onOpenSession: (ScanSession) -> Unit,
    onDeleteSession: (ScanSession) -> Unit,
    onRenameSession: (ScanSession, String) -> Unit,
    onToggleFavorite: (ScanSession) -> Unit
) {
    var sessionBeingRenamed by remember { mutableStateOf<ScanSession?>(null) }
    var showAdbDialog by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    var sortOrder by remember { mutableStateOf(SortOrder.NEWEST) }
    var favoritesOnly by remember { mutableStateOf(false) }

    val visible = remember(sessions, query, sortOrder, favoritesOnly) {
        sessions.asSequence()
            .filter { !favoritesOnly || it.isFavorite }
            .filter { query.isBlank() || it.title.contains(query.trim(), ignoreCase = true) }
            .let { seq ->
                when (sortOrder) {
                    SortOrder.NEWEST -> seq.sortedByDescending { it.timestamp }
                    SortOrder.OLDEST -> seq.sortedBy { it.timestamp }
                    SortOrder.NAME -> seq.sortedBy { it.title.lowercase() }
                    SortOrder.POINTS -> seq.sortedByDescending { it.pointCount }
                }
            }
            .toList()
    }

    val totalPoints = sessions.sumOf { it.pointCount.toLong() }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Ink)
    ) {
        Column(modifier = Modifier.fillMaxSize().padding(horizontal = 18.dp)) {
            // ---- Header ----
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 34.dp, bottom = 14.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Splatter",
                        color = Snow,
                        fontSize = 28.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "${sessions.size} scans · %,d points · on-device 3D".format(totalPoints),
                        color = Fog,
                        fontSize = 12.sp
                    )
                }

                Card(
                    colors = CardDefaults.cardColors(containerColor = InkElevated),
                    shape = CircleShape,
                    onClick = { showAdbDialog = true }
                ) {
                    Text(
                        "ⓘ",
                        color = Fog,
                        fontSize = 16.sp,
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp)
                    )
                }

                Spacer(Modifier.width(8.dp))

                Button(
                    onClick = onStartNewScan,
                    colors = ButtonDefaults.buttonColors(containerColor = Mint, contentColor = OnMint),
                    shape = CircleShape,
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp)
                ) {
                    Text("+ New Scan", fontSize = 13.sp, fontWeight = FontWeight.Bold)
                }
            }

            // ---- Search ----
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                singleLine = true,
                placeholder = { Text("Search scans", color = Fog, fontSize = 14.sp) },
                leadingIcon = { Text("⌕", color = Fog, fontSize = 16.sp) },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                shape = RoundedCornerShape(14.dp),
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = InkElevated,
                    unfocusedContainerColor = InkElevated,
                    focusedTextColor = Snow,
                    unfocusedTextColor = Snow,
                    cursorColor = Mint,
                    focusedIndicatorColor = Mint,
                    unfocusedIndicatorColor = InkHairline
                ),
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(Modifier.height(10.dp))

            // ---- Sort + filter chips ----
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                SortOrder.entries.forEach { order ->
                    FilterChip(order.label, sortOrder == order, Modifier.weight(1f)) { sortOrder = order }
                }
                FilterChip("★", favoritesOnly, Modifier.weight(0.55f)) { favoritesOnly = !favoritesOnly }
            }

            Spacer(Modifier.height(12.dp))

            // ---- Content ----
            if (sessions.isEmpty()) {
                EmptyState(onStartNewScan)
            } else if (visible.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("No scans match your search", color = Fog, fontSize = 14.sp)
                }
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Fixed(2),
                    contentPadding = PaddingValues(bottom = 28.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    items(visible, key = { it.id }) { session ->
                        ScanGridCard(
                            session = session,
                            onOpen = { onOpenSession(session) },
                            onDelete = { onDeleteSession(session) },
                            onRenameClick = { sessionBeingRenamed = session },
                            onToggleFavorite = { onToggleFavorite(session) }
                        )
                    }
                }
            }
        }

        sessionBeingRenamed?.let { session ->
            var text by remember(session.id) { mutableStateOf(session.title) }
            AlertDialog(
                onDismissRequest = { sessionBeingRenamed = null },
                title = { Text("Rename scan") },
                text = {
                    OutlinedTextField(
                        value = text,
                        onValueChange = { text = it },
                        singleLine = true
                    )
                },
                confirmButton = {
                    TextButton(onClick = {
                        onRenameSession(session, text.trim().ifBlank { session.title })
                        sessionBeingRenamed = null
                    }) { Text("Save") }
                },
                dismissButton = {
                    TextButton(onClick = { sessionBeingRenamed = null }) { Text("Cancel") }
                }
            )
        }

        if (showAdbDialog) {
            TransferDialog(onDismiss = { showAdbDialog = false })
        }
    }
}

@Composable
private fun FilterChip(
    label: String,
    active: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .background(if (active) Mint else InkElevated)
            .border(1.dp, if (active) Mint else InkHairline, RoundedCornerShape(10.dp))
            .clickable { onClick() }
            .padding(vertical = 8.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            label,
            color = if (active) OnMint else Fog,
            fontSize = 11.sp,
            fontWeight = if (active) FontWeight.Bold else FontWeight.Medium
        )
    }
}

@Composable
private fun EmptyState(onStartNewScan: () -> Unit) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text("◍", fontSize = 46.sp, color = Mint)
            Spacer(Modifier.height(12.dp))
            Text("No 3D scans yet", color = Snow, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(6.dp))
            Text(
                "Capture RGB-D frames and stitch them\ninto a model you can orbit in 360°.",
                color = Fog,
                fontSize = 13.sp,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center
            )
            Spacer(Modifier.height(22.dp))
            Button(
                onClick = onStartNewScan,
                colors = ButtonDefaults.buttonColors(containerColor = Mint, contentColor = OnMint),
                shape = RoundedCornerShape(12.dp)
            ) {
                Text("Start your first scan", fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
private fun ScanGridCard(
    session: ScanSession,
    onOpen: () -> Unit,
    onDelete: () -> Unit,
    onRenameClick: () -> Unit,
    onToggleFavorite: () -> Unit
) {
    val context = LocalContext.current
    var menuOpen by remember { mutableStateOf(false) }
    val meshFile = remember(session.id) { session.getMeshFile() }
    val plyFile = remember(session.id) { session.getMeshFile() ?: session.getPlyFile() }
    val hasModel = plyFile != null && plyFile.exists()
    val isMesh = meshFile != null && meshFile.exists()
    val panoramaFile = remember(session.id) {
        session.getPanoramaFile()?.takeIf { it.exists() }
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onOpen() },
        colors = CardDefaults.cardColors(containerColor = InkElevated),
        shape = RoundedCornerShape(18.dp)
    ) {
        Column(Modifier.fillMaxWidth()) {
            // Thumbnail
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(1f)
                    .background(InkContainer)
            ) {
                session.getThumbnailFile()?.let { thumb ->
                    val bmp = remember(thumb.path) {
                        BitmapFactory.decodeFile(thumb.absolutePath)?.asImageBitmap()
                    }
                    bmp?.let {
                        Image(
                            bitmap = it,
                            contentDescription = null,
                            modifier = Modifier.fillMaxSize(),
                            contentScale = ContentScale.Crop
                        )
                    }
                }

                // Mode badge
                Box(
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(8.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(Ink.copy(alpha = 0.7f))
                        .padding(horizontal = 7.dp, vertical = 3.dp)
                ) {
                    Text(modeLabel(session.scanMode), color = Snow, fontSize = 10.sp, fontWeight = FontWeight.SemiBold)
                }

                // Favorite heart
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(6.dp)
                        .size(30.dp)
                        .clip(CircleShape)
                        .background(Ink.copy(alpha = 0.65f))
                        .clickable { onToggleFavorite() },
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        if (session.isFavorite) "★" else "☆",
                        color = if (session.isFavorite) Amber else Snow,
                        fontSize = 15.sp
                    )
                }

                // Overflow menu
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(6.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(30.dp)
                            .clip(CircleShape)
                            .background(Ink.copy(alpha = 0.65f))
                            .clickable { menuOpen = true },
                        contentAlignment = Alignment.Center
                    ) {
                        Text("⋮", color = Snow, fontSize = 18.sp)
                    }

                    DropdownMenu(
                        expanded = menuOpen,
                        onDismissRequest = { menuOpen = false },
                        containerColor = InkElevated
                    ) {
                        DropdownMenuItem(
                            text = { Text("Open in 360°", color = Snow) },
                            onClick = { menuOpen = false; onOpen() }
                        )
                        DropdownMenuItem(
                            text = { Text(if (session.isFavorite) "Remove favorite" else "Add favorite", color = Snow) },
                            onClick = { menuOpen = false; onToggleFavorite() }
                        )
                        DropdownMenuItem(
                            text = { Text("Rename", color = Snow) },
                            onClick = { menuOpen = false; onRenameClick() }
                        )
                        if (panoramaFile != null) {
                            DropdownMenuItem(
                                text = { Text("Save 360° to Photos", color = Snow) },
                                onClick = {
                                    menuOpen = false
                                    val uri = PanoramaExporter.saveToPictures(context, panoramaFile, session.title)
                                    Toast.makeText(
                                        context,
                                        if (uri != null) "Saved to Pictures/Splatter360" else "Could not save the 360° photo",
                                        Toast.LENGTH_LONG
                                    ).show()
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("Share 360° photo", color = Snow) },
                                onClick = {
                                    menuOpen = false
                                    PanoramaExporter.share(context, panoramaFile, session.title)
                                }
                            )
                        }
                        if (panoramaFile == null) {
                            DropdownMenuItem(
                                text = { Text("Save to Downloads", color = Snow) },
                                onClick = {
                                    menuOpen = false
                                    val ok = if (hasModel && plyFile != null) {
                                        PlyExporter.exportPlyToDownloads(context, plyFile, session.title)
                                    } else false
                                    Toast.makeText(
                                        context,
                                        if (ok) "Saved to Downloads/Splatter3D" else "Model file not found",
                                        Toast.LENGTH_LONG
                                    ).show()
                                }
                            )
                        }
                        if (isMesh && File(session.datasetDirPath, MeshExporter.MESH_GLB_FILENAME).exists()) {
                            DropdownMenuItem(
                                text = { Text("Share GLB (3D file)", color = Snow) },
                                onClick = {
                                    menuOpen = false
                                    MeshExporter.shareFile(
                                        context,
                                        File(session.datasetDirPath, MeshExporter.MESH_GLB_FILENAME),
                                        session.title
                                    )
                                }
                            )
                        }
                        DropdownMenuItem(
                            text = { Text("Share ZIP", color = Snow) },
                            onClick = {
                                menuOpen = false
                                shareArchive(context, session)
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("Delete", color = Coral) },
                            onClick = { menuOpen = false; onDelete() }
                        )
                    }
                }
            }

            // Text + primary action
            Column(Modifier.fillMaxWidth().padding(10.dp)) {
                Text(
                    text = session.title,
                    color = Snow,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1
                )
                Spacer(Modifier.height(3.dp))
                Text(
                    text = when {
                        panoramaFile != null -> "equirect 360° · %d frames".format(session.frameCount)
                        isMesh -> "%,d verts · %d frames".format(session.pointCount, session.frameCount)
                        else -> "%,d points · %d frames".format(session.pointCount, session.frameCount)
                    },
                    color = Fog,
                    fontSize = 10.sp,
                    maxLines = 1
                )
                // What the reconstruction actually did — so a scan that fell back
                // to AR tracking or to a point cloud explains itself.
                session.processingNote?.takeIf { it.isNotBlank() }?.let { note ->
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = note,
                        color = Mist,
                        fontSize = 9.sp,
                        lineHeight = 11.sp,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Spacer(Modifier.height(8.dp))
                Button(
                    onClick = onOpen,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (hasModel) Periwinkle.copy(alpha = 0.18f) else Amber.copy(alpha = 0.18f),
                        contentColor = if (hasModel) Periwinkle else Amber
                    ),
                    shape = RoundedCornerShape(10.dp),
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp),
                    modifier = Modifier.fillMaxWidth().height(32.dp)
                ) {
                    Text(
                        when {
                            panoramaFile != null -> "🌐 Open 360°"
                            hasModel -> "View 360°"
                            else -> "⚡ Process"
                        },
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
    }
}

private fun modeLabel(mode: ScanMode): String = when (mode) {
    ScanMode.OBJECT -> "📦 Object"
    ScanMode.ROOM -> "🏠 Room"
    ScanMode.PHOTO -> "📷 Photo Mesh"
    ScanMode.PHOTO_SFM -> "🔭 Photo SfM"
    ScanMode.PANORAMA -> "🌐 360 Photo"
}

private fun shareArchive(context: android.content.Context, session: ScanSession) {
    try {
        val archiveFile = ScanArchiveExporter.createArchiveForSession(context, session)
        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            archiveFile
        )
        val shareIntent = Intent(Intent.ACTION_SEND).apply {
            type = "application/zip"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(shareIntent, "Share scan ZIP archive"))
    } catch (e: Exception) {
        Toast.makeText(context, "Could not create archive", Toast.LENGTH_SHORT).show()
    }
}

@Composable
private fun TransferDialog(onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Transfer 3D models to PC") },
        text = {
            Column {
                Text("One-click ADB export to your PC:", fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(6.dp))
                Card(
                    colors = CardDefaults.cardColors(containerColor = Ink),
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = "./export_to_pc.sh",
                        color = Mint,
                        fontSize = 13.sp,
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier.padding(10.dp)
                    )
                }
                Spacer(Modifier.height(10.dp))
                Text(
                    "On-device: open a scan and tap Share/Save, or use the ⋮ menu to save to Downloads.",
                    fontSize = 13.sp
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Got it") }
        }
    )
}
