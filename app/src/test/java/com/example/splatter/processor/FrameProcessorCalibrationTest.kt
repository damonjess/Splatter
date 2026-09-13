package com.example.splatter.processor

import org.junit.Assert.assertEquals
import org.junit.Test

class FrameProcessorCalibrationTest {

    @Test
    fun mapDepthToRgbPixel_usesIntrinsicsRotationAndDistortion() {
        val result = FrameProcessor.mapDepthToRgbPixel(
            uDepth = 110,
            vDepth = 90,
            depthWidth = 160,
            depthHeight = 120,
            depthFx = 160f,
            depthFy = 160f,
            depthCx = 80f,
            depthCy = 60f,
            rgbWidth = 1920,
            rgbHeight = 1080,
            rgbFx = 900f,
            rgbFy = 900f,
            rgbCx = 960f,
            rgbCy = 540f,
            rotationDegrees = 90,
            cropX = 0f,
            cropY = 0f,
            distortionK1 = 0.01f
        )

        assertEquals(791, result.first)
        assertEquals(709, result.second)
    }
}
