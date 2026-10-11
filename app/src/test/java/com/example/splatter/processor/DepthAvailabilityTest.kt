package com.example.splatter.processor

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files

/**
 * The depth probe decides which dense stage runs, so it is worth pinning down:
 * a wrong `true` fuses garbage, a wrong `false` throws away the 411 depth maps
 * that were the difference between a solid model and triangle shards.
 */
class DepthAvailabilityTest {

    private val frames = 260
    private val width = 160
    private val height = 120

    private fun dataset(
        writeDepthFiles: Boolean = true,
        depthPerFrame: (Int) -> Float   // valid fraction per frame
    ): File {
        val dir = Files.createTempDirectory("depth-avail").toFile()
        for (i in 0 until frames) {
            val ts = 1000L + i * 100L
            if (!writeDepthFiles) continue
            val pixels = width * height
            val valid = (pixels * depthPerFrame(i)).toInt()
            val buf = ByteBuffer.allocate(pixels * 2).order(ByteOrder.LITTLE_ENDIAN)
            for (p in 0 until pixels) {
                buf.putShort(if (p < valid) 1500.toShort() else 0.toShort())
            }
            File(dir, "depth_$ts.raw").writeBytes(buf.array())
            File(dir, "depthdims_$ts.txt").writeText("$width,$height")
            File(dir, "pose_$ts.txt").writeText("1,0,0,0,0,1,0,0,0,0,1,0,0,0,0,1")
        }
        return dir
    }

    @Test
    fun trueForADatasetLikeTheRealOne() {
        // 411-frame object scan measured ~94% valid pixels per frame.
        val dir = dataset { 0.94f }
        assertTrue(DepthAvailability.hasUsableDepth(dir))
    }

    @Test
    fun falseWhenDepthIsMostlyEmpty() {
        // Depth sensor present but nothing in range (too close / too far).
        val dir = dataset { 0.01f }
        assertFalse(DepthAvailability.hasUsableDepth(dir))
    }

    @Test
    fun falseWhenTheSensorCapturedNothingAtAll() {
        val dir = dataset { 0f }
        assertFalse(DepthAvailability.hasUsableDepth(dir))
    }

    @Test
    fun falseWhenThereAreNoDepthFiles() {
        val dir = dataset(writeDepthFiles = false) { 0.9f }
        assertFalse(
            "a photo-only dataset must fall through to plane-sweep stereo",
            DepthAvailability.hasUsableDepth(dir)
        )
    }

    @Test
    fun falseWhenOnlyOddFramesHaveDepth() {
        // Sparse depth must not be fused as if it covered the scan: 3 of 12
        // samples usable fails the "half the sampled frames" rule.
        val dir = dataset { i -> if (i % 4 == 0) 0.9f else 0f }
        assertFalse(DepthAvailability.hasUsableDepth(dir))
    }

    @Test
    fun trueWhenMostFramesHaveDepthDespiteAQuarterBeingBlind() {
        // A few blind frames (shiny patch) shouldn't disqualify the whole scan.
        val dir = dataset { i -> if (i % 5 == 0) 0f else 0.8f }
        assertTrue(DepthAvailability.hasUsableDepth(dir))
    }

    @Test
    fun falseForAMissingDirectory() {
        assertFalse(DepthAvailability.hasUsableDepth(File("/definitely/not/a/scan")))
    }
}
