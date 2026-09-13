package com.example.splatter.model

data class CoverageCell(
    val x: Float,
    val y: Float
)

data class ScanQualityState(
    val tracking: String = "Searching",
    val depthCoveragePercent: Int = 0,
    val motion: String = "Stable",
    val distanceMeters: Float = 0f,
    val frameCount: Int = 0,
    val pointCount: Int = 0,
    val warnings: List<String> = emptyList(),
    val coverageCells: List<CoverageCell> = emptyList()
)
