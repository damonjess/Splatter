package com.example.splatter.util

import com.example.splatter.model.CoverageCell
import com.example.splatter.model.ScanMode
import com.example.splatter.model.ScanQualityState
import com.google.ar.core.TrackingState

object ScanQualityEvaluator {

    fun evaluateLive(
        trackingState: TrackingState,
        motionMetersPerFrame: Float,
        distanceMeters: Float,
        depthCoveragePercent: Int,
        brightness: Float,
        frameCount: Int,
        elapsedRecordingMs: Long,
        scanMode: ScanMode,
        storageLow: Boolean,
        previouslyScannedAreaDetected: Boolean
    ): ScanQualityState {
        val warnings = mutableListOf<String>()

        val isTooClose = distanceMeters > 0f && distanceMeters < scanMode.minDepthMeters * 1.10f
        val isTooFar = distanceMeters > 0f && distanceMeters > scanMode.maxDepthMeters * 0.82f

        val trackingText = when (trackingState) {
            TrackingState.TRACKING -> {
                if (depthCoveragePercent < 22 || motionMetersPerFrame > 0.25f) "Limited"
                else if (isTooFar || isTooClose) "Limited"
                else "Good"
            }
            TrackingState.PAUSED -> "Searching"
            TrackingState.STOPPED -> "Lost"
        }

        if (trackingState != TrackingState.TRACKING) {
            warnings += "Tracking lost"
        }

        if (motionMetersPerFrame > 0.30f) {
            warnings += "Moving too quickly"
        }

        if (isTooFar) {
            warnings += "Too far from the subject"
        }

        if (isTooClose) {
            warnings += "Too close"
        }

        if (brightness < 32f) {
            warnings += "Low light"
        }

        if (depthCoveragePercent < 28) {
            warnings += "Insufficient depth"
        }

        if (motionMetersPerFrame > 0.12f && depthCoveragePercent > 45) {
            warnings += "Excessive motion blur"
        }

        if (previouslyScannedAreaDetected) {
            warnings += "Previously scanned area detected"
        }

        if (elapsedRecordingMs > 180_000 && frameCount > 600) {
            warnings += "Phone overheating"
        }

        if (storageLow) {
            warnings += "Storage becoming low"
        }

        val coverageCells = buildCoverageCells(depthCoveragePercent, scanMode)
        val estimatedPointCount = estimatePointCount(scanMode, depthCoveragePercent, frameCount)

        return ScanQualityState(
            tracking = trackingText,
            depthCoveragePercent = depthCoveragePercent.coerceIn(0, 100),
            motion = when {
                motionMetersPerFrame > 0.30f -> "Too fast"
                motionMetersPerFrame > 0.12f -> "Fast"
                else -> "Stable"
            },
            distanceMeters = distanceMeters,
            frameCount = frameCount,
            pointCount = estimatedPointCount,
            warnings = warnings.distinct(),
            coverageCells = coverageCells
        )
    }

    private fun estimatePointCount(scanMode: ScanMode, depthCoveragePercent: Int, frameCount: Int): Int {
        val basePoints = when (scanMode) {
            ScanMode.OBJECT -> 180_000
            ScanMode.ROOM -> 550_000
        }
        val coverageBoost = (depthCoveragePercent / 100f) * 1.4f
        val frameBoost = (frameCount.coerceAtMost(1200) / 1200f) * 0.8f
        return ((basePoints * coverageBoost * (1f + frameBoost))).toInt()
    }

    private fun buildCoverageCells(depthCoveragePercent: Int, scanMode: ScanMode): List<CoverageCell> {
        val gridSize = if (scanMode == ScanMode.ROOM) 5 else 3
        val activeCells = ((depthCoveragePercent / 100f) * (gridSize * gridSize)).toInt().coerceAtLeast(1)
        val cells = mutableListOf<CoverageCell>()

        for (row in 0 until gridSize) {
            for (col in 0 until gridSize) {
                val index = row * gridSize + col
                val active = index < activeCells
                if (active) {
                    cells += CoverageCell(
                        x = (col + 1) / (gridSize + 1f),
                        y = (row + 1) / (gridSize + 1f)
                    )
                }
            }
        }

        return cells
    }
}
