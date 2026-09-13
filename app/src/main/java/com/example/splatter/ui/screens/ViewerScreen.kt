package com.example.splatter.ui.screens

import android.content.Intent
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
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
import androidx.core.content.FileProvider
import com.example.splatter.model.ScanSession
import com.example.splatter.model.SplatPoint
import com.example.splatter.processor.PlyExporter
import com.example.splatter.ui.viewer.SplatView
import java.io.File

@Composable
fun ViewerScreen(
    session: ScanSession,
    points: List<SplatPoint>,
    onBackClicked: () -> Unit
) {
    val context = LocalContext.current
    var splatSizeMultiplier by remember { mutableFloatStateOf(1.0f) }
    var splatViewRef: SplatView? by remember { mutableStateOf(null) }

    LaunchedEffect(points) {
        splatViewRef?.setPoints(points)
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF0D0E12))
    ) {
        // 3D OpenGL Splat Viewport
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

        // Top Navigation Header
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 40.dp, start = 16.dp, end = 16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Card(
                colors = CardDefaults.cardColors(containerColor = Color.Black.copy(alpha = 0.7f)),
                shape = CircleShape,
                onClick = onBackClicked
            ) {
                Text(
                    text = "← Back",
                    color = Color.White,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp)
                )
            }

            Card(
                colors = CardDefaults.cardColors(containerColor = Color.Black.copy(alpha = 0.7f)),
                shape = CircleShape
            ) {
                Text(
                    text = "${session.title} (${points.size} pts)",
                    color = Color.White,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp)
                )
            }

            Card(
                colors = CardDefaults.cardColors(containerColor = Color(0xFF03DAC6)),
                shape = CircleShape,
                onClick = {
                    session.getPlyFile()?.let { file ->
                        if (file.exists()) {
                            PlyExporter.sharePlyFile(context, file, session.title)
                        } else {
                            Toast.makeText(context, "PLY file not found", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            ) {
                Text(
                    text = "Export PLY",
                    color = Color.Black,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp)
                )
            }
        }

        // Bottom Controls Overlay Card
        Card(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .padding(20.dp),
            colors = CardDefaults.cardColors(containerColor = Color.Black.copy(alpha = 0.8f)),
            shape = RoundedCornerShape(20.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Splat Scale Size",
                        color = Color.White,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium
                    )

                    Button(
                        onClick = {
                            splatSizeMultiplier = 1.0f
                            splatViewRef?.setSplatSizeMultiplier(1.0f)
                            splatViewRef?.resetCamera()
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = Color.White.copy(alpha = 0.15f)),
                        shape = CircleShape,
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                    ) {
                        Text("Reset View", color = Color.White, fontSize = 12.sp)
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                Button(
                    onClick = {
                        session.getPlyFile()?.let { file ->
                            if (file.exists()) {
                                val success = PlyExporter.exportPlyToDownloads(context, file, session.title)
                                if (success) {
                                    Toast.makeText(context, "Saved PLY to Downloads/Splatter3D", Toast.LENGTH_LONG).show()
                                } else {
                                    Toast.makeText(context, "Failed to save PLY", Toast.LENGTH_SHORT).show()
                                }
                            } else {
                                Toast.makeText(context, "PLY file not found", Toast.LENGTH_SHORT).show()
                            }
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF03DAC6)),
                    shape = CircleShape,
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("💾 Save PLY", color = Color.Black, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                }

                Spacer(modifier = Modifier.height(8.dp))

                Slider(
                    value = splatSizeMultiplier,
                    onValueChange = { newValue ->
                        splatSizeMultiplier = newValue
                        splatViewRef?.setSplatSizeMultiplier(newValue)
                    },
                    valueRange = 0.3f..3.0f,
                    colors = SliderDefaults.colors(
                        thumbColor = Color(0xFFBB86FC),
                        activeTrackColor = Color(0xFFBB86FC),
                        inactiveTrackColor = Color.White.copy(alpha = 0.2f)
                    )
                )
            }
        }
    }
}
