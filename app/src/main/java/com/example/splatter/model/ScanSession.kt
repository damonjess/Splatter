package com.example.splatter.model

import java.io.File

data class ScanSession(
    val id: String,
    var title: String,
    val timestamp: Long,
    var frameCount: Int = 0,
    var pointCount: Int = 0,
    val datasetDirPath: String,
    var scanMode: ScanMode = ScanMode.OBJECT,
    var plyFilePath: String? = null,
    var splatFilePath: String? = null,
    var meshFilePath: String? = null,
    var thumbnailPath: String? = null,
    var isFavorite: Boolean = false,
    /** One-line summary of how this scan was reconstructed (set after processing). */
    var processingNote: String? = null,
    /** 360° panorama JPEG, when this scan was captured in 360 Photo mode. */
    var panoramaFilePath: String? = null
) {
    fun getDatasetDir(): File = File(datasetDirPath)
    fun getPanoramaFile(): File? = panoramaFilePath?.let { File(it) }
    fun getPlyFile(): File? = plyFilePath?.let { File(it) }
    fun getSplatFile(): File? = splatFilePath?.let { File(it) }
    fun getMeshFile(): File? = meshFilePath?.let { File(it) }
    fun getThumbnailFile(): File? = thumbnailPath?.let { File(it) }
}
