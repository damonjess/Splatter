package com.example.splatter.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * The compass heading and location recorded when a 360° sweep starts are the
 * only things that make the finished panorama orientable and placeable on
 * Google Maps, and they travel through two files that the capture side writes
 * and the processor reads. This checks both halves agree — a mismatch here is
 * silent, and shows up as a panorama Maps cannot display facing the right way.
 */
class CaptureContextTest {

    @get:Rule
    val folder = TemporaryFolder()

    @Test
    fun headingSurvivesTheRoundTrip() {
        val dir = folder.newFolder("scan")
        CaptureContext.save(dir, headingDegrees = 137.5f, location = 51.500729 to -0.124625)

        assertEquals(137.5f, CaptureContext.readHeading(dir)!!, 1e-4f)
    }

    @Test
    fun locationSurvivesTheRoundTrip() {
        val dir = folder.newFolder("scan")
        CaptureContext.save(dir, headingDegrees = 0f, location = -33.8688 to 151.2093)

        val read = CaptureContext.readLocation(dir)
        assertEquals(-33.8688, read!!.first, 1e-6)
        assertEquals(151.2093, read.second, 1e-6)
    }

    @Test
    fun nothingIsWrittenWhenNothingIsKnown() {
        val dir = folder.newFolder("scan")
        CaptureContext.save(dir, headingDegrees = null, location = null)

        assertNull(CaptureContext.readHeading(dir))
        assertNull(CaptureContext.readLocation(dir))
        assertEquals(0, dir.listFiles()?.count { it.name == CaptureContext.LOCATION_FILENAME } ?: 0)
    }

    @Test
    fun aTruncatedFileIsReportedAsUnknownRatherThanGuessedAt() {
        val dir = folder.newFolder("scan")
        File(dir, CaptureContext.LOCATION_FILENAME).writeText("51.500729,")
        assertNull(CaptureContext.readLocation(dir))

        File(dir, CaptureContext.HEADING_FILENAME).writeText("north-ish")
        assertNull(CaptureContext.readHeading(dir))
    }

    @Test
    fun theProcessorReadsTheSameFilesTheCaptureWrote() {
        // The panorama processor resolves these by filename, so the names are
        // part of the contract between capture and processing.
        val dir = folder.newFolder("scan")
        CaptureContext.save(dir, 10f, 1.0 to 2.0)
        assertEquals(
            setOf(CaptureContext.HEADING_FILENAME, CaptureContext.LOCATION_FILENAME),
            dir.listFiles()?.map { it.name }?.toSet()
        )
    }
}
