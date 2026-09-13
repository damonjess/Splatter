package com.example.splatter.model

import java.io.File

data class ScanSession(
    val id: String,
    val title: String,
    val timestamp: Long,
    var frameCount: Int = 0,
    var pointCount: Int = 0,
    val datasetDirPath: String,
    var plyFilePath: String? = null,
    var splatFilePath: String? = null,
    var thumbnailPath: String? = null
) {
    fun getDatasetDir(): File = File(datasetDirPath)
    fun getPlyFile(): File? = plyFilePath?.let { File(it) }
    fun getSplatFile(): File? = splatFilePath?.let { File(it) }
    fun getThumbnailFile(): File? = thumbnailPath?.let { File(it) }
}
