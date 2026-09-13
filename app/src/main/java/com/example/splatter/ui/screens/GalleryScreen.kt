package com.example.splatter.ui.screens

import android.content.Intent
import android.graphics.BitmapFactory
import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import com.example.splatter.model.ScanMode
import com.example.splatter.model.ScanSession
import com.example.splatter.processor.PlyExporter
import com.example.splatter.util.ScanArchiveExporter

@Composable
fun GalleryScreen(
    sessions: List<ScanSession>,
    onStartNewScan: () -> Unit,
    onOpenSession: (ScanSession) -> Unit,
    onDeleteSession: (ScanSession) -> Unit,
    onRenameSession: (ScanSession, String) -> Unit
) {
    var sessionBeingRenamed by remember { mutableStateOf<ScanSession?>(null) }
    var showAdbDialog by remember { mutableStateOf(false) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF0F1117))
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(20.dp)
        ) {
            // Header
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 28.dp, bottom = 20.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Splatter 3D",
                        color = Color.White,
                        fontSize = 28.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "On-Device 3D Gaussian Splatting",
                        color = Color.White.copy(alpha = 0.6f),
                        fontSize = 13.sp
                    )
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedButton(
                        onClick = { showAdbDialog = true },
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFF03DAC6)),
                        shape = CircleShape,
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp)
                    ) {
                        Text("💻 PC ADB", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    }

                    Spacer(modifier = Modifier.width(8.dp))

                    Button(
                        onClick = onStartNewScan,
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF6200EE)),
                        shape = CircleShape,
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                    ) {
                        Text(
                            text = "+ New Scan",
                            color = Color.White,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }

            if (sessions.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .weight(1f),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Text(
                            text = "No 3D Scans Yet",
                            color = Color.White.copy(alpha = 0.9f),
                            fontSize = 18.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "Tap '+ New Scan' to capture RGB-D frames\nand stitch a 3D Gaussian Splat model.",
                            color = Color.White.copy(alpha = 0.5f),
                            fontSize = 14.sp,
                            modifier = Modifier.padding(horizontal = 32.dp)
                        )
                        Spacer(modifier = Modifier.height(24.dp))
                        Button(
                            onClick = onStartNewScan,
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF6200EE)),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Text("Start Your First Scan", color = Color.White)
                        }
                    }
                }
            } else {
                LazyColumn(
                    contentPadding = PaddingValues(bottom = 24.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    items(sessions, key = { it.id }) { session ->
                        ScanItemCard(
                            session = session,
                            onOpen = { onOpenSession(session) },
                            onDelete = { onDeleteSession(session) },
                            onRenameClick = { sessionBeingRenamed = session }
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
            AlertDialog(
                onDismissRequest = { showAdbDialog = false },
                title = { Text("Transfer 3D PLY Models to PC") },
                text = {
                    Column {
                        Text(
                            text = "To open PLY files in MeshLab on PC:\n",
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Medium
                        )
                        Text(
                            text = "1️⃣ One-Click ADB Command on PC:\nRun in your PC terminal:\n",
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Card(
                            colors = CardDefaults.cardColors(containerColor = Color.Black),
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                text = "./export_to_pc.sh",
                                color = Color(0xFF03DAC6),
                                fontSize = 13.sp,
                                fontFamily = FontFamily.Monospace,
                                modifier = Modifier.padding(10.dp)
                            )
                        }
                        Spacer(modifier = Modifier.height(10.dp))
                        Text(
                            text = "This automatically extracts all .ply model files straight to your Desktop/Splatter3D_Models folder!\n\n" +
                                   "2️⃣ On-Device:\n" +
                                   "Tap 'Export PLY' on any scan to QuickShare/Email to PC, or tap '💾 Save PLY' to save to Downloads.",
                            fontSize = 13.sp,
                            color = Color.Black.copy(alpha = 0.8f)
                        )
                    }
                },
                confirmButton = {
                    TextButton(onClick = { showAdbDialog = false }) {
                        Text("Got it")
                    }
                }
            )
        }
    }
}

@Composable
fun ScanItemCard(
    session: ScanSession,
    onOpen: () -> Unit,
    onDelete: () -> Unit,
    onRenameClick: () -> Unit
) {
    val context = LocalContext.current
    val plyFile = remember(session.id) { session.getPlyFile() }
    val hasPly = plyFile != null && plyFile.exists()

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF1E222D)),
        shape = RoundedCornerShape(16.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            // Header: Thumbnail, Title, Frames
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                session.getThumbnailFile()?.let { thumbFile ->
                    val bmp = remember(thumbFile.path) {
                        BitmapFactory.decodeFile(thumbFile.absolutePath)?.asImageBitmap()
                    }
                    bmp?.let {
                        Image(
                            bitmap = it,
                            contentDescription = null,
                            modifier = Modifier
                                .size(52.dp)
                                .clip(RoundedCornerShape(10.dp)),
                            contentScale = ContentScale.Crop
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                    }
                }

                Row(
                    modifier = Modifier
                        .weight(1f)
                        .clickable { onRenameClick() },
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = session.title,
                        color = Color.White,
                        fontSize = 17.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "✏️",
                        fontSize = 12.sp,
                        color = Color.White.copy(alpha = 0.6f)
                    )
                }

                Spacer(modifier = Modifier.width(8.dp))

                Text(
                    text = "${session.frameCount} frames",
                    color = Color.White.copy(alpha = 0.6f),
                    fontSize = 13.sp
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Metadata Badges Row
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Card(
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF03DAC6).copy(alpha = 0.15f)),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text(
                        text = if (session.scanMode == ScanMode.PHOTO) "%,d Vertices".format(session.pointCount) else "%,d Gaussians".format(session.pointCount),
                        color = Color(0xFF03DAC6),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                    )
                }

                Spacer(modifier = Modifier.width(6.dp))

                Card(
                    colors = CardDefaults.cardColors(containerColor = Color(0xFFBB86FC).copy(alpha = 0.15f)),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text(
                        text = when (session.scanMode) {
                            ScanMode.OBJECT -> "📦 Object"
                            ScanMode.ROOM -> "🏠 Room"
                            ScanMode.PHOTO -> "📷 Photo Mesh"
                        },
                        color = Color(0xFFBB86FC),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                    )
                }

                if (hasPly) {
                    Spacer(modifier = Modifier.width(6.dp))
                    Card(
                        colors = CardDefaults.cardColors(containerColor = Color(0xFF4CAF50).copy(alpha = 0.15f)),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Text(
                            text = "📄 PLY Ready",
                            color = Color(0xFF81C784),
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            // Action Row 1: Primary Actions (View 3D, Export PLY for MeshLab)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = onOpen,
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFBB86FC)),
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier
                        .weight(1f)
                        .height(38.dp)
                ) {
                    Text("View 3D", color = Color.Black, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                }

                Button(
                    onClick = {
                        if (hasPly && plyFile != null) {
                            PlyExporter.sharePlyFile(context, plyFile, session.title)
                        } else {
                            Toast.makeText(context, "PLY file not found for this scan", Toast.LENGTH_SHORT).show()
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF03DAC6)),
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier
                        .weight(1f)
                        .height(38.dp)
                ) {
                    Text("Export PLY (MeshLab)", color = Color.Black, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Action Row 2: Secondary Actions (Save to Downloads, Export ZIP, Delete)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedButton(
                    onClick = {
                        if (hasPly && plyFile != null) {
                            val success = PlyExporter.exportPlyToDownloads(context, plyFile, session.title)
                            if (success) {
                                Toast.makeText(
                                    context,
                                    "Saved PLY to Downloads/Splatter3D folder",
                                    Toast.LENGTH_LONG
                                ).show()
                            } else {
                                Toast.makeText(context, "Failed to save PLY to Downloads", Toast.LENGTH_SHORT).show()
                            }
                        } else {
                            Toast.makeText(context, "PLY file not found", Toast.LENGTH_SHORT).show()
                        }
                    },
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFF03DAC6)),
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier
                        .weight(1.2f)
                        .height(36.dp),
                    contentPadding = PaddingValues(horizontal = 4.dp, vertical = 0.dp)
                ) {
                    Text("💾 Save PLY", fontSize = 11.sp, fontWeight = FontWeight.Medium)
                }

                OutlinedButton(
                    onClick = {
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
                    },
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White.copy(alpha = 0.8f)),
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier
                        .weight(1.1f)
                        .height(36.dp),
                    contentPadding = PaddingValues(horizontal = 4.dp, vertical = 0.dp)
                ) {
                    Text("📦 ZIP", fontSize = 11.sp, fontWeight = FontWeight.Medium)
                }

                OutlinedButton(
                    onClick = onDelete,
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFFCF6679)),
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier
                        .weight(0.9f)
                        .height(36.dp),
                    contentPadding = PaddingValues(horizontal = 4.dp, vertical = 0.dp)
                ) {
                    Text("Delete", fontSize = 11.sp)
                }
            }
        }
    }
}
