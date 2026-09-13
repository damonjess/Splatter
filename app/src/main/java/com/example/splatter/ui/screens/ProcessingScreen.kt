package com.example.splatter.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun ProcessingScreen(
    currentStep: String,
    progressPercent: Int,
    pointCount: Int
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF0F1117)),
        contentAlignment = Alignment.Center
    ) {
        Card(
            modifier = Modifier
                .fillMaxWidth(0.88f)
                .padding(24.dp),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF1E222D)),
            shape = RoundedCornerShape(24.dp),
            elevation = CardDefaults.cardElevation(defaultElevation = 8.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(28.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                CircularProgressIndicator(
                    color = Color(0xFF6200EE),
                    strokeWidth = 4.dp,
                    modifier = Modifier.padding(bottom = 20.dp)
                )

                Text(
                    text = "Processing 3D Splat Model",
                    color = Color.White,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center
                )

                Spacer(modifier = Modifier.height(12.dp))

                Text(
                    text = currentStep,
                    color = Color.White.copy(alpha = 0.7f),
                    fontSize = 14.sp,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.height(40.dp)
                )

                Spacer(modifier = Modifier.height(20.dp))

                LinearProgressIndicator(
                    progress = { progressPercent / 100.0f },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(8.dp),
                    color = Color(0xFFBB86FC),
                    trackColor = Color.White.copy(alpha = 0.1f)
                )

                Spacer(modifier = Modifier.height(12.dp))

                Text(
                    text = "$progressPercent% Complete",
                    color = Color.White,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold
                )

                if (pointCount > 0) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "Gaussians generated: %,d".format(pointCount),
                        color = Color(0xFF03DAC6),
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium
                    )
                }
            }
        }
    }
}
