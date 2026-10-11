package com.example.splatter.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
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
import com.example.splatter.ui.theme.Amber
import com.example.splatter.ui.theme.Fog
import com.example.splatter.ui.theme.Ink
import com.example.splatter.ui.theme.InkElevated
import com.example.splatter.ui.theme.Mint
import com.example.splatter.ui.theme.Periwinkle
import com.example.splatter.ui.theme.Snow

@Composable
fun ProcessingScreen(
    currentStep: String,
    progressPercent: Int,
    pointCount: Int,
    isTraining: Boolean = false,
    trainingIteration: Int = 0,
    trainingTotalIterations: Int = 0,
    trainingLoss: Float = 0f,
    titleText: String = "Processing 3D Splat Model",
    pointCountLabel: String = "Gaussians"
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Ink),
        contentAlignment = Alignment.Center
    ) {
        Card(
            modifier = Modifier
                .fillMaxWidth(0.88f)
                .padding(24.dp),
            colors = CardDefaults.cardColors(containerColor = InkElevated),
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
                    color = if (isTraining) Mint else Periwinkle,
                    strokeWidth = 4.dp,
                    modifier = Modifier.padding(bottom = 20.dp)
                )

                Text(
                    text = if (isTraining) "Training on Device" else titleText,
                    color = Snow,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center
                )

                Spacer(modifier = Modifier.height(12.dp))

                Text(
                    text = currentStep,
                    color = Fog.copy(alpha = 0.95f),
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
                    color = if (isTraining) Mint else Periwinkle,
                    trackColor = Color.White.copy(alpha = 0.1f)
                )

                Spacer(modifier = Modifier.height(12.dp))

                Text(
                    text = "$progressPercent% Complete",
                    color = Snow,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold
                )

                if (pointCount > 0) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "$pointCountLabel: %,d".format(pointCount),
                        color = Mint,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium
                    )
                }

                // Training-specific info
                AnimatedVisibility(
                    visible = isTraining && trainingTotalIterations > 0,
                    enter = fadeIn(),
                    exit = fadeOut()
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Spacer(modifier = Modifier.height(16.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = "Iteration",
                                color = Color.White.copy(alpha = 0.6f),
                                fontSize = 12.sp
                            )
                            Text(
                                text = "$trainingIteration / $trainingTotalIterations",
                                color = Mint,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }

                        Spacer(modifier = Modifier.height(6.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = "Loss",
                                color = Color.White.copy(alpha = 0.6f),
                                fontSize = 12.sp
                            )
                            Text(
                                text = "%.4f".format(trainingLoss),
                                color = if (trainingLoss < 0.15f) Mint else Amber,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }

                        Spacer(modifier = Modifier.height(10.dp))

                        // Iteration progress bar
                        if (trainingTotalIterations > 0) {
                            LinearProgressIndicator(
                                progress = { trainingIteration.toFloat() / trainingTotalIterations },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(4.dp),
                                color = Mint.copy(alpha = 0.6f),
                                trackColor = Color.White.copy(alpha = 0.08f)
                            )
                        }
                    }
                }
            }
        }
    }
}
