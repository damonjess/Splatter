package com.example.splatter.processor

import com.example.splatter.model.SplatPoint
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.sqrt

object RoomReconstructionProcessor {

    enum class PlaneType {
        FLOOR,
        CEILING,
        WALL,
        TABLE,
        OTHER
    }

    data class Plane(
        val a: Float,
        val b: Float,
        val c: Float,
        val d: Float,
        val inlierCount: Int,
        val type: PlaneType,
        val centerX: Float,
        val centerY: Float,
        val centerZ: Float,
        val normalX: Float,
        val normalY: Float,
        val normalZ: Float,
        val points: List<SplatPoint>
    )

    fun extractPlanes(points: List<SplatPoint>, maxPlanes: Int = 8): List<Plane> {
        if (points.isEmpty()) return emptyList()

        val candidates = mutableListOf<Plane>()
        val sampled = points.shuffled().take(minOf(points.size, 2000))
        val planeSeeds = listOf(
            floatArrayOf(0f, 1f, 0f, 0f),
            floatArrayOf(1f, 0f, 0f, 0f),
            floatArrayOf(0f, 0f, 1f, 0f),
            floatArrayOf(0f, 0f, 0f, 1f)
        )

        for (seed in planeSeeds) {
            val inliers = sampled.filter { point ->
                val distance = abs(seed[0] * point.x + seed[1] * point.y + seed[2] * point.z + seed[3])
                distance < 0.12f
            }

            if (inliers.size >= 3) {
                val plane = fitPlane(inliers)
                if (plane != null) {
                    candidates += plane
                }
            }
        }

        val merged = mutableListOf<Plane>()
        for (candidate in candidates) {
            val near = merged.any { existing ->
                val dot = abs(existing.normalX * candidate.normalX + existing.normalY * candidate.normalY + existing.normalZ * candidate.normalZ)
                val centerDist = sqrt(
                    (existing.centerX - candidate.centerX).pow(2f) +
                        (existing.centerY - candidate.centerY).pow(2f) +
                        (existing.centerZ - candidate.centerZ).pow(2f)
                )
                dot > 0.95f && centerDist < 0.35f
            }
            if (!near) merged += candidate
        }

        return merged.take(maxPlanes).map { plane ->
            val type = classifyPlane(plane)
            plane.copy(type = type)
        }
    }

    fun exportPlanesAsObj(planes: List<Plane>): String {
        val lines = mutableListOf<String>()
        lines += "# Splatter room planes export"

        var vertexIndex = 1
        for (plane in planes) {
            val corners = generatePlaneCorners(plane)
            for (corner in corners) {
                lines += "v ${corner.x} ${corner.y} ${corner.z}"
            }
            val start = vertexIndex
            val end = start + 3
            lines += "f $start $start $start"
            vertexIndex += 4
        }

        return lines.joinToString("\n")
    }

    private fun fitPlane(points: List<SplatPoint>): Plane? {
        if (points.size < 3) return null

        val meanX = points.map { it.x }.average().toFloat()
        val meanY = points.map { it.y }.average().toFloat()
        val meanZ = points.map { it.z }.average().toFloat()

        val centered = points.map { point ->
            Triple(point.x - meanX, point.y - meanY, point.z - meanZ)
        }

        val xx = centered.sumOf { it.first.toDouble().pow(2.0) }.toFloat()
        val yy = centered.sumOf { it.second.toDouble().pow(2.0) }.toFloat()
        val zz = centered.sumOf { it.third.toDouble().pow(2.0) }.toFloat()
        val xy = centered.sumOf { (it.first * it.second).toDouble() }.toFloat()
        val xz = centered.sumOf { (it.first * it.third).toDouble() }.toFloat()
        val yz = centered.sumOf { (it.second * it.third).toDouble() }.toFloat()

        val covariance = arrayOf(
            floatArrayOf(xx, xy, xz),
            floatArrayOf(xy, yy, yz),
            floatArrayOf(xz, yz, zz)
        )

        val normal = principalAxisNormal(covariance)
        if (normal == null) return null

        val a = normal[0]
        val b = normal[1]
        val c = normal[2]
        val d = -(a * meanX + b * meanY + c * meanZ)

        val centerX = meanX
        val centerY = meanY
        val centerZ = meanZ

        return Plane(
            a = a,
            b = b,
            c = c,
            d = d,
            inlierCount = points.size,
            type = PlaneType.OTHER,
            centerX = centerX,
            centerY = centerY,
            centerZ = centerZ,
            normalX = a,
            normalY = b,
            normalZ = c,
            points = points
        )
    }

    private fun principalAxisNormal(covariance: Array<FloatArray>): FloatArray? {
        val values = mutableListOf<Float>()
        for (i in 0 until 3) {
            val sum = covariance[i].sum()
            values += sum
        }
        if (values.all { it == 0f }) return null

        val x = if (abs(covariance[0][1]) > abs(covariance[0][2])) 1f else 0f
        val y = if (x == 1f) 0f else 1f
        val z = 1f

        val nx = if (abs(covariance[0][0]) > abs(covariance[1][1])) x else y
        val ny = if (nx == x) y else z
        val nz = if (nx == y) z else 0f

        val length = sqrt(nx * nx + ny * ny + nz * nz)
        if (length == 0f) return null
        return floatArrayOf(nx / length, ny / length, nz / length)
    }

    private fun classifyPlane(plane: Plane): PlaneType {
        val nY = plane.normalY
        val nX = abs(plane.normalX)
        val nZ = abs(plane.normalZ)

        return when {
            nY > 0.8f -> PlaneType.FLOOR
            nY < -0.8f -> PlaneType.CEILING
            nX > 0.8f || nZ > 0.8f -> PlaneType.WALL
            plane.centerY < 0.8f -> PlaneType.TABLE
            else -> PlaneType.OTHER
        }
    }

    private fun generatePlaneCorners(plane: Plane): List<SplatPoint> {
        val base = listOf(
            SplatPoint(plane.centerX, plane.centerY, plane.centerZ, 1f, 1f, 1f),
            SplatPoint(plane.centerX + 0.5f, plane.centerY, plane.centerZ, 1f, 1f, 1f),
            SplatPoint(plane.centerX + 0.5f, plane.centerY + 0.1f, plane.centerZ + 0.5f, 1f, 1f, 1f),
            SplatPoint(plane.centerX, plane.centerY + 0.1f, plane.centerZ + 0.5f, 1f, 1f, 1f)
        )
        return base
    }
}
