package com.example.splatter.ui.screens

import android.opengl.GLSurfaceView
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.example.splatter.model.ScanMode
import com.example.splatter.model.ScanQualityState

@Composable
fun ScanScreen(
    selectedMode: ScanMode,
    onModeSelected: (ScanMode) -> Unit,
    isRecording: Boolean,
    frameCount: Int,
    statusText: String,
    scanQuality: ScanQualityState,
    onToggleRecording: () -> Unit,
    onBackClicked: () -> Unit,
    glSurfaceViewProvider: () -> GLSurfaceView
) {
    Box(modifier = Modifier.fillMaxSize()) {
        // Live AR Camera Feed
        AndroidView(
            factory = { glSurfaceViewProvider() },
            modifier = Modifier.fillMaxSize()
        )

        // Top Status Header Overlay
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
            verticalArrangement = Arrangement.SpaceBetween,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
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
                        containerColor = if (isRecording) Color(0xFFE53935).copy(alpha = 0.85f) else Color.Black.copy(alpha = 0.65f)
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
                                    if (isRecording) Color.White else Color(0xFF4CAF50),
                                    CircleShape
                                )
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = if (isRecording) "RECORDING ($frameCount frames)" else statusText,
                            color = Color.White,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }
            }

            if (isRecording) {
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

                        val metricColor = if (scanQuality.tracking == "Good") Color(0xFF4CAF50) else Color(0xFFFFC107)

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
                            Text("Frames: ${scanQuality.frameCount}", color = Color.White, fontSize = 12.sp)
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
                                                    if (isCovered) Color(0xFF4CAF50) else Color.White.copy(alpha = 0.12f),
                                                    RoundedCornerShape(2.dp)
                                                )
                                        )
                                    }
                                    gridCells.forEach { it }
                                }
                            }
                        }
                    }
                }
            }

            // Bottom Recording & Mode Controls Card
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
                    // Mode Selector Tabs (only editable when not recording)
                    if (!isRecording) {
                        Text(
                            text = "SCAN MODE SELECTOR",
                            color = Color.White.copy(alpha = 0.5f),
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 1.sp
                        )

                        Spacer(modifier = Modifier.height(10.dp))

                        // Segmented Control
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(Color(0xFF1E222D), RoundedCornerShape(14.dp))
                                .padding(4.dp),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            ScanMode.entries.forEach { mode ->
                                val isSelected = mode == selectedMode
                                Box(
                                    modifier = Modifier
                                        .weight(1f)
                                        .clip(RoundedCornerShape(10.dp))
                                        .background(
                                            if (isSelected) Color(0xFF6200EE) else Color.Transparent
                                        )
                                        .clickable { onModeSelected(mode) }
                                        .padding(vertical = 10.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text(
                                            text = if (mode == ScanMode.OBJECT) "📦 Object" else "🏠 Room",
                                            color = if (isSelected) Color.White else Color.White.copy(alpha = 0.6f),
                                            fontSize = 14.sp,
                                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium
                                        )
                                    }
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(12.dp))

                        // Selected Mode Settings Summary Card
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(Color.White.copy(alpha = 0.08f), RoundedCornerShape(12.dp))
                                .border(1.dp, Color.White.copy(alpha = 0.12f), RoundedCornerShape(12.dp))
                                .padding(12.dp)
                        ) {
                            Column {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text(
                                        text = "Depth: ${selectedMode.minDepthMeters}–${selectedMode.maxDepthMeters} m",
                                        color = Color(0xFF03DAC6),
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                    Text(
                                        text = "Voxel: ${if (selectedMode == ScanMode.OBJECT) "4–6 mm" else "10–20 mm"}",
                                        color = Color(0xFFBB86FC),
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                }

                                Spacer(modifier = Modifier.height(4.dp))

                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text(
                                        text = if (selectedMode == ScanMode.OBJECT) "Higher detail • Shorter duration" else "Floor/Wall detection • Large point limit",
                                        color = Color.White.copy(alpha = 0.85f),
                                        fontSize = 11.sp
                                    )
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(14.dp))
                    } else {
                        // Locked Mode Badge during recording
                        Row(
                            modifier = Modifier
                                .background(Color(0xFF6200EE).copy(alpha = 0.4f), RoundedCornerShape(12.dp))
                                .border(1.dp, Color(0xFF6200EE), RoundedCornerShape(12.dp))
                                .padding(horizontal = 16.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = if (selectedMode == ScanMode.OBJECT) "📦 OBJECT MODE (4–6mm Voxel)" else "🏠 ROOM MODE (Floor/Wall Detection)",
                                color = Color.White,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                        Spacer(modifier = Modifier.height(12.dp))
                    }

                    // Scan Guidance Prompt
                    Text(
                        text = if (isRecording) {
                                if (selectedMode == ScanMode.OBJECT) "Move slowly around target object..."
                                else "Slowly scan walls, floor & room layout..."
                            } else {
                                if (selectedMode == ScanMode.OBJECT) "Point at object (0.3–2.5m) and tap REC"
                                else "Point around room (0.5–5.0m) and tap REC"
                            },
                            color = Color.White.copy(alpha = 0.85f),
                            fontSize = 13.sp
                        )

                    // Record / Stop Action Button
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center
                    ) {
                        Button(
                            onClick = onToggleRecording,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (isRecording) Color(0xFFE53935) else Color.White
                            ),
                            shape = CircleShape,
                            modifier = Modifier.size(72.dp)
                        ) {
                            Text(
                                text = if (isRecording) "STOP" else "REC",
                                color = if (isRecording) Color.White else Color.Black,
                                fontWeight = FontWeight.Bold,
                                fontSize = 16.sp
                            )
                        }
                    }
                }
            }
        }
    }
}
