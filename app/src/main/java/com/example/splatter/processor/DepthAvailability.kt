package com.example.splatter.processor

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Decides whether a captured dataset contains usable raw depth.
 *
 * This matters because a phone that captured depth has *strictly better*
 * geometry available than anything the photo-only pipeline can compute: the
 * depth sensor is metric, dense and per-pixel, whereas plane-sweep stereo has
 * to guess depth from census costs on a coarse grid. A real 411-frame object
 * scan produced ~1.3 M reliable depth samples; the same dataset reconstructed
 * from stereo alone came out as a few thousand disconnected triangle shards.
 *
 * So the pipeline asks this first and fuses depth when it is there, keeping
 * the photo-only route for the case it exists for: subjects the depth sensor
 * cannot read (shiny, thin, dark, transparent) or a device without a depth
 * sensor at all.
 *
 * Pure Kotlin (file + byte reads only) so it is covered by JVM unit tests.
 */
object DepthAvailability {

    /** A frame counts as usable when at least this share of its pixels is valid. */
    const val MIN_VALID_FRACTION = 0.15f

    /** Depth samples are spread across the dataset rather than read frame by frame. */
    const val SAMPLE_COUNT = 12

    /**
     * True when roughly half (and at least two) of the sampled frames carry a
     * meaningful number of valid depth pixels.
     *
     * @param datasetDir the scan directory (contains `depth_*.raw` + `depthdims_*.txt`).
     */
    fun hasUsableDepth(
        datasetDir: File,
        minValidFraction: Float = MIN_VALID_FRACTION,
        sampleCount: Int = SAMPLE_COUNT
    ): Boolean {
        if (!datasetDir.isDirectory) return false

        val stamps = datasetDir
            .listFiles { _, name -> name.startsWith("depth_") && name.endsWith(".raw") }
            ?.mapNotNull { file ->
                file.name.removePrefix("depth_").removeSuffix(".raw").toLongOrNull()
            }
            ?.sorted()
            ?: return false
        if (stamps.isEmpty()) return false

        val stride = (stamps.size / sampleCount).coerceAtLeast(1)
        var sampled = 0
        var usable = 0
        var index = 0
        while (index < stamps.size && sampled < sampleCount) {
            sampled++
            if (frameHasDepth(datasetDir, stamps[index], minValidFraction)) usable++
            index += stride
        }

        return usable >= 2 && usable * 2 >= sampled
    }

    /** Validity of a single depth frame: share of non-zero pixels. */
    private fun frameHasDepth(datasetDir: File, timestamp: Long, minValidFraction: Float): Boolean {
        val file = File(datasetDir, "depth_$timestamp.raw")
        if (!file.exists() || file.length() < 2) return false
        return try {
            val values = ByteBuffer
                .wrap(file.readBytes())
                .order(ByteOrder.LITTLE_ENDIAN)
                .asShortBuffer()

            var valid = 0
            var total = 0
            while (values.hasRemaining()) {
                if ((values.get().toInt() and 0xFFFF) != 0) valid++
                total++
            }
            total > 0 && valid.toFloat() / total >= minValidFraction
        } catch (_: Exception) {
            false
        }
    }
}
