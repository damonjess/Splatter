package com.example.splatter.model

enum class ScanMode(
    val id: String,
    val displayName: String,
    val minDepthMeters: Float,
    val maxDepthMeters: Float,
    val voxelSizeMeters: Float,
    val maxPointLimit: Int,
    val enablePlaneDetection: Boolean,
    val detailLevelText: String,
    val scanDurationText: String,
    val description: String
) {
    OBJECT(
        id = "OBJECT",
        displayName = "Object",
        minDepthMeters = 0.3f,
        maxDepthMeters = 2.5f,
        voxelSizeMeters = 0.005f, // 5 mm voxel size
        maxPointLimit = 350_000,
        enablePlaneDetection = false,
        detailLevelText = "4–6 mm voxel (high detail)",
        scanDurationText = "Shorter scan duration",
        description = "0.3–2.5 m depth • 5 mm voxel size • Higher detail • Shorter scan"
    ),
    ROOM(
        id = "ROOM",
        displayName = "Room",
        minDepthMeters = 0.5f,
        maxDepthMeters = 5.0f,
        voxelSizeMeters = 0.015f, // 15 mm voxel size
        maxPointLimit = 1_200_000,
        enablePlaneDetection = true,
        detailLevelText = "10–20 mm voxel (larger scale)",
        scanDurationText = "Longer scan duration",
        description = "0.5–5.0 m depth • 15 mm voxel size • Floor/wall detection • Larger point limit"
    ),
    PHOTO(
        id = "PHOTO",
        displayName = "Photo Mesh",
        minDepthMeters = 0.3f,
        maxDepthMeters = 5.0f,
        voxelSizeMeters = 0.004f, // 4 mm fusion voxel — must stay near raw depth
        // pixel spacing at object distance, or triangles collapse into
        // degenerates and the mesh gets shredded into holes
        maxPointLimit = 600_000, // triangle cap for the fused mesh
        enablePlaneDetection = false,
        detailLevelText = "Textured triangle mesh (photogrammetry-style)",
        scanDurationText = "Orbit slowly for full coverage",
        description = "0.3–5.0 m depth • 4 mm fusion voxel • Textured photo mesh • PLY + OBJ export"
    );

    companion object {
        fun fromId(id: String?): ScanMode {
            return entries.find { it.id.equals(id, ignoreCase = true) } ?: OBJECT
        }
    }
}
