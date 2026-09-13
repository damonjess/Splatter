package com.example.splatter.ui.screens

import android.opengl.GLSurfaceView
import androidx.compose.foundation.background
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
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView

@Composable
fun ScanScreen(
    isRecording: Boolean,
    frameCount: Int,
    statusText: String,
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

            // Bottom Recording Controls
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = Color.Black.copy(alpha = 0.75f)),
                shape = RoundedCornerShape(24.dp)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(20.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = if (isRecording) "Move camera slowly around target object..." else "Point camera at target and press REC",
                        color = Color.White.copy(alpha = 0.8f),
                        fontSize = 13.sp
                    )

                    Spacer(modifier = Modifier.height(16.dp))

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
