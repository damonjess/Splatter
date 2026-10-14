package com.example.splatter.util

import com.example.splatter.model.ScanMode
import com.google.ar.core.TrackingState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ScanQualityEvaluatorTest {
    private fun evaluate(mode: ScanMode, depthCoverage: Int) = ScanQualityEvaluator.evaluateLive(
        trackingState = TrackingState.TRACKING,
        motionMetersPerFrame = 0.01f,
        distanceMeters = 0f,
        depthCoveragePercent = depthCoverage,
        brightness = 100f,
        frameCount = 10,
        elapsedRecordingMs = 1_000L,
        scanMode = mode,
        storageLow = false,
        previouslyScannedAreaDetected = false
    )

    @Test
    fun photoSfmDoesNotRequireDepthCoverage() {
        val quality = evaluate(ScanMode.PHOTO_SFM, depthCoverage = 0)

        assertEquals("Good", quality.tracking)
        assertFalse(quality.warnings.contains("Insufficient depth"))
        assertTrue(quality.coverageCells.isEmpty())
    }

    @Test
    fun panoramaDoesNotRequireDepthCoverage() {
        val quality = evaluate(ScanMode.PANORAMA, depthCoverage = 0)

        assertEquals("Good", quality.tracking)
        assertFalse(quality.warnings.contains("Insufficient depth"))
        assertTrue(quality.coverageCells.isEmpty())
    }

    @Test
    fun objectModeStillWarnsWhenDepthCoverageIsPoor() {
        val quality = evaluate(ScanMode.OBJECT, depthCoverage = 0)

        assertEquals("Limited", quality.tracking)
        assertTrue(quality.warnings.contains("Insufficient depth"))
    }
}
