package com.example.splatter.ui.screens

import android.opengl.GLSurfaceView
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.example.splatter.model.ScanMode
import com.example.splatter.model.ScanQualityState
import com.example.splatter.ui.theme.Amber
import com.example.splatter.ui.theme.Coral
import androidx.compose.ui.text.style.TextOverflow
import com.example.splatter.ui.theme.Fog
import com.example.splatter.ui.theme.InkContainer
import com.example.splatter.ui.theme.Mint
import com.example.splatter.ui.theme.OnMint
import com.example.splatter.ui.theme.Periwinkle
import com.example.splatter.ui.theme.Sky

@Composable
fun ScanScreen(
    selectedMode: ScanMode,
    onModeSelected: (ScanMode) -> Unit,
    isRecording: Boolean,
    frameCount: Int,
    statusText: String,
    scanQuality: ScanQualityState,
    onToggleRecording: () -> Unit,
    onTakeSinglePhoto: () -> Unit,
    onFinishScan: () -> Unit,
    onBackClicked: () -> Unit,
    glSurfaceViewProvider: () -> GLSurfaceView
) {
    Box(modifier = Modifier.fillMaxSize()) {
        // Live AR Camera Feed
        AndroidView(
            factory = { glSurfaceViewProvider() },
            modifier = Modifier.fillMaxSize()
        )

        // Overlay UI
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
            verticalArrangement = Arrangement.SpaceBetween,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Top Status Header
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Card(
                    colors = CardDefaults.cardColors(containerColor = Color.Black.copy(alpha = 0.6f)),
                    shape = CircleShape,
                    onClick = onBackClicked
                ) {
                    Text(
                        text = "← Back",
                        color = Color.White,
                        fontSize = 14.sp,
                        modifier = Modifier
                            .padding(horizontal = 16.dp, vertical = 8.dp)
                    )
                }

                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = if (isRecording) Coral.copy(alpha = 0.9f) else Color.Black.copy(alpha = 0.65f)
                    ),
                    shape = CircleShape
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(10.dp)
                                .background(
                                    if (isRecording) Color.White else Mint,
                                    CircleShape
                                )
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = when {
                                isRecording -> "RECORDING ($frameCount frames)"
                                frameCount > 0 -> "CAPTURED $frameCount PHOTOS"
                                else -> statusText
                            },
                            color = Color.White,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }
            }

            // Live Quality Feedback Card
            if (isRecording || frameCount > 0) {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 12.dp),
                    colors = CardDefaults.cardColors(containerColor = Color.Black.copy(alpha = 0.72f)),
                    shape = RoundedCornerShape(18.dp)
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(14.dp)
                    ) {
                        Text(
                            text = "LIVE SCAN QUALITY",
                            color = Color.White.copy(alpha = 0.6f),
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 1.sp
                        )

                        Spacer(modifier = Modifier.height(8.dp))

                        val metricColor = if (scanQuality.tracking == "Good") Mint else Amber

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text("Tracking: ${scanQuality.tracking}", color = metricColor, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                            Text("Depth coverage: ${scanQuality.depthCoveragePercent}%", color = Color.White, fontSize = 12.sp)
                        }

                        Spacer(modifier = Modifier.height(6.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text("Motion: ${scanQuality.motion}", color = Color.White, fontSize = 12.sp)
                            Text("Distance: ${"%.1f".format(scanQuality.distanceMeters)} m", color = Color.White, fontSize = 12.sp)
                        }

                        Spacer(modifier = Modifier.height(6.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text("Frames/Photos: $frameCount", color = Color.White, fontSize = 12.sp)
                            Text("Points: ${scanQuality.pointCount}", color = Color.White, fontSize = 12.sp)
                        }

                        if (scanQuality.warnings.isNotEmpty()) {
                            Spacer(modifier = Modifier.height(10.dp))
                            Text(
                                text = scanQuality.warnings.joinToString(" • "),
                                color = Color(0xFFFFB74D),
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Medium
                            )
                        }

                        if (selectedMode == ScanMode.ROOM && scanQuality.coverageCells.isNotEmpty()) {
                            Spacer(modifier = Modifier.height(10.dp))
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .background(Color.White.copy(alpha = 0.06f), RoundedCornerShape(12.dp))
                                    .padding(8.dp)
                            ) {
                                Text(
                                    text = "Coverage map",
                                    color = Color.White.copy(alpha = 0.75f),
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold,
                                    letterSpacing = 0.8.sp
                                )
                                Spacer(modifier = Modifier.height(6.dp))
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                                ) {
                                    val gridCells = List(25) { index ->
                                        val row = index / 5
                                        val col = index % 5
                                        val isCovered = scanQuality.coverageCells.any { cell ->
                                            val rowMatch = ((cell.y * 5f).toInt()) == row
                                            val colMatch = ((cell.x * 5f).toInt()) == col
                                            rowMatch && colMatch
                                        }
                                        Box(
                                            modifier = Modifier
                                                .weight(1f)
                                                .height(12.dp)
                                                .background(
                                                    if (isCovered) Mint else Color.White.copy(alpha = 0.12f),
                                                    RoundedCornerShape(2.dp)
                                                )
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // Bottom Control Card
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = Color.Black.copy(alpha = 0.82f)),
                shape = RoundedCornerShape(24.dp)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(18.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    // Mode Selector Tabs (only editable when not recording and no frames captured)
                    if (!isRecording && frameCount == 0) {
                        Text(
                            text = "SCAN MODE SELECTOR",
                            color = Color.White.copy(alpha = 0.5f),
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 1.sp
                        )

                        Spacer(modifier = Modifier.height(10.dp))

                        // Five modes no longer fit in one segmented row: each
                        // segment got ~70 dp and the labels wrapped or clipped.
                        // Two columns of cards give every mode an icon, its name
                        // and its key parameter on their own lines.
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            ScanMode.entries.chunked(2).forEach { rowModes ->
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    rowModes.forEach { mode ->
                                        ModeCard(
                                            mode = mode,
                                            isSelected = mode == selectedMode,
                                            modifier = Modifier.weight(1f),
                                            onClick = { onModeSelected(mode) }
                                        )
                                    }
                                    // Odd number of modes: keep the last card at
                                    // half width instead of stretching it.
                                    if (rowModes.size == 1) Spacer(Modifier.weight(1f))
                                }
                            }

                            // What the selected mode is actually good for. The
                            // depth range and voxel size already sit on each
                            // card, and a 360 capture has neither.
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .background(Color.White.copy(alpha = 0.08f), RoundedCornerShape(12.dp))
                                    .border(1.dp, Color.White.copy(alpha = 0.12f), RoundedCornerShape(12.dp))
                                    .padding(horizontal = 12.dp, vertical = 10.dp)
                            ) {
                                Text(
                                    text = selectedMode.description,
                                    color = Color.White.copy(alpha = 0.85f),
                                    fontSize = 11.sp,
                                    lineHeight = 15.sp
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(14.dp))
                    } else {
                        // Locked Mode Badge
                        Row(
                            modifier = Modifier
                                .background(Mint.copy(alpha = 0.18f), RoundedCornerShape(12.dp))
                                .border(1.dp, Mint, RoundedCornerShape(12.dp))
                                .padding(horizontal = 16.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = when (selectedMode) {
                                    ScanMode.OBJECT -> "📦 OBJECT MODE ($frameCount frames)"
                                    ScanMode.ROOM -> "🏠 ROOM MODE ($frameCount frames)"
                                    ScanMode.PHOTO -> "📷 PHOTO MESH MODE ($frameCount photos)"
                                    ScanMode.PHOTO_SFM -> "🔭 PHOTO SfM MODE ($frameCount photos)"
                                    ScanMode.PANORAMA -> "🌐 360 PHOTO ($frameCount frames)"
                                },
                                color = Mint,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                        Spacer(modifier = Modifier.height(12.dp))
                    }

                    // Scan Guidance Prompt
                    Text(
                        text = when {
                            selectedMode == ScanMode.PANORAMA && isRecording ->
                                "Stand still and sweep slowly all the way around, then STOP."
                            selectedMode == ScanMode.PANORAMA ->
                                "Stand in one spot and tap REC, then sweep the phone slowly through a full 360°."
                            isRecording -> "Moving around subject... Tap STOP when done."
                            frameCount > 0 -> "Snap more photos from different angles, then tap FINISH."
                            selectedMode == ScanMode.PHOTO_SFM -> "Orbit subject — snap manual photos (8–30 photos with 60%+ overlap) or tap REC."
                            selectedMode == ScanMode.PHOTO -> "Orbit subject — snap manual photos or tap REC."
                            else -> "Point camera at subject and snap photos or tap REC."
                        },
                        color = Color.White.copy(alpha = 0.85f),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium
                    )

                    Spacer(modifier = Modifier.height(14.dp))

                    // Shutter & Recording Control Row
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceEvenly,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // 1. Manual Shutter Button (Polycam style)
                        if (!isRecording) {
                            Button(
                                onClick = onTakeSinglePhoto,
                                colors = ButtonDefaults.buttonColors(containerColor = Mint, contentColor = OnMint),
                                shape = CircleShape,
                                modifier = Modifier.size(68.dp)
                            ) {
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    Text("📸", fontSize = 22.sp)
                                }
                            }
                        }

                        // 2. Auto REC / STOP Button
                        Button(
                            onClick = onToggleRecording,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (isRecording) Coral else Color.White
                            ),
                            shape = CircleShape,
                            modifier = Modifier.size(68.dp)
                        ) {
                            Text(
                                text = if (isRecording) "STOP" else "REC",
                                color = if (isRecording) Color.White else Color.Black,
                                fontWeight = FontWeight.Bold,
                                fontSize = 15.sp
                            )
                        }

                        // 3. Finish & Process Button (Visible when frames captured)
                        if (frameCount > 0 && !isRecording) {
                            Button(
                                onClick = onFinishScan,
                                colors = ButtonDefaults.buttonColors(containerColor = Periwinkle, contentColor = Color(0xFF10121A)),
                                shape = CircleShape,
                                modifier = Modifier.size(68.dp)
                            ) {
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    Text("✓", color = Color(0xFF10121A), fontWeight = FontWeight.Bold, fontSize = 20.sp)
                                    Text("DONE", color = Color(0xFF10121A), fontWeight = FontWeight.Bold, fontSize = 10.sp)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/** Short name for a mode, icon first — the mode cards and the locked badge. */
private fun modeLabel(mode: ScanMode): String = when (mode) {
    ScanMode.OBJECT -> "📦  Object"
    ScanMode.ROOM -> "🏠  Room"
    ScanMode.PHOTO -> "📷  Photo Mesh"
    ScanMode.PHOTO_SFM -> "🔭  Photo SfM"
    ScanMode.PANORAMA -> "🌐  360 Photo"
}

/** The one line that distinguishes a mode from its neighbour on the grid. */
private fun modeHint(mode: ScanMode): String = when (mode) {
    ScanMode.OBJECT -> "0.3–2.5 m · 5 mm splats"
    ScanMode.ROOM -> "0.5–5 m · 15 mm splats"
    ScanMode.PHOTO -> "0.3–5 m · 4 mm mesh"
    ScanMode.PHOTO_SFM -> "depth-free · 12 mm mesh"
    ScanMode.PANORAMA -> "one spot · 2:1 photo"
}

/**
 * One mode in the selector grid. The selected card is filled with the app's
 * accent so the current mode reads at a glance, with the parameter line kept
 * quieter than the name.
 */
@Composable
private fun ModeCard(
    mode: ScanMode,
    isSelected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(if (isSelected) Mint else InkContainer)
            .border(
                1.dp,
                if (isSelected) Mint else Color.White.copy(alpha = 0.10f),
                RoundedCornerShape(12.dp)
            )
            .clickable { onClick() }
            .padding(horizontal = 10.dp, vertical = 9.dp)
    ) {
        Text(
            text = modeLabel(mode),
            color = if (isSelected) OnMint else Color.White.copy(alpha = 0.9f),
            fontSize = 13.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 1
        )
        Spacer(Modifier.height(2.dp))
        Text(
            text = modeHint(mode),
            color = if (isSelected) OnMint.copy(alpha = 0.7f) else Fog,
            fontSize = 10.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}
