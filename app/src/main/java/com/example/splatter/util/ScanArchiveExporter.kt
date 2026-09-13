package com.example.splatter.util

import android.content.Context
import com.example.splatter.model.ScanSession
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

object ScanArchiveExporter {

    fun createArchiveForSession(context: Context, session: ScanSession): File {
        val sourceDir = File(session.datasetDirPath)
        val exportDir = File(context.cacheDir, "scan_exports").apply { mkdirs() }
        val archiveFile = File(exportDir, "${session.id}.zip")
        createArchive(sourceDir, archiveFile)
        return archiveFile
    }

    fun createArchive(sourceDir: File, archiveFile: File): File {
        if (!sourceDir.exists() || !sourceDir.isDirectory) {
            throw IllegalArgumentException("Scan directory does not exist: ${sourceDir.absolutePath}")
        }

        archiveFile.parentFile?.mkdirs()

        ZipOutputStream(FileOutputStream(archiveFile)).use { zipOut ->
            sourceDir.walkTopDown().filter { it != sourceDir }.forEach { file ->
                val relativePath = if (file.isDirectory) {
                    val dirName = file.relativeTo(sourceDir).path
                    "$dirName/"
                } else {
                    file.relativeTo(sourceDir).path
                }

                val zipEntryName = "${sourceDir.name}/$relativePath"
                if (file.isDirectory) {
                    zipOut.putNextEntry(ZipEntry(zipEntryName))
                    zipOut.closeEntry()
                    return@forEach
                }

                zipOut.putNextEntry(ZipEntry(zipEntryName))
                file.inputStream().use { input ->
                    input.copyTo(zipOut)
                }
                zipOut.closeEntry()
            }
        }

        return archiveFile
    }
}
