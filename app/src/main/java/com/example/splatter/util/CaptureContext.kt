package com.example.splatter.util

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.LocationManager
import android.util.Log
import java.io.File

/**
 * Records the two pieces of context a 360° photo needs beyond its pixels:
 * which way the phone was pointing (compass) and where it was (GPS).
 *
 * ARCore deliberately has no idea where north is — its world frame is arbitrary —
 * but a compass reading taken at the same moment as the first frame supplies the
 * missing reference, and ARCore's world frame is gravity aligned, so the
 * horizontal angle between two frames is meaningful. That is what lets the
 * stitcher write a correct `GPano:PoseHeadingDegrees`, without which Google Maps
 * cannot display the panorama facing the right way.
 *
 * Written to the scan folder as plain text so the processing stage stays
 * synchronous and testable:
 *
 * ```
 * heading.txt   137.5
 * location.txt  51.500729,-0.124625
 * ```
 */
object CaptureContext {

    private const val TAG = "CaptureContext"

    const val HEADING_FILENAME = "heading.txt"
    const val LOCATION_FILENAME = "location.txt"

    /**
     * One-shot compass heading in degrees clockwise from north, or null when the
     * device has no usable rotation-vector/magnetometer reading.
     *
     * The listener is removed immediately after the first event, so this is safe
     * to call while an AR session is running.
     */
    fun readCompassHeading(context: Context, timeoutMs: Long = 250): Float? {
        val sensors = context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager ?: return null
        val sensor = sensors.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
            ?: sensors.getDefaultSensor(Sensor.TYPE_GEOMAGNETIC_ROTATION_VECTOR)
            ?: return null

        var heading: Float? = null
        val rotationMatrix = FloatArray(9)
        val orientation = FloatArray(3)
        val listener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                if (heading != null) return
                if (event.sensor.type != sensor.type) return
                try {
                    SensorManager.getRotationMatrixFromVector(rotationMatrix, event.values)
                    SensorManager.getOrientation(rotationMatrix, orientation)
                    val azimuthDegrees = Math.toDegrees(orientation[0].toDouble()).toFloat()
                    heading = ((azimuthDegrees % 360f) + 360f) % 360f
                } catch (e: Exception) {
                    Log.w(TAG, "Compass read failed: ${e.message}")
                }
            }

            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
        }

        return try {
            sensors.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_FASTEST)
            val deadline = System.currentTimeMillis() + timeoutMs
            while (heading == null && System.currentTimeMillis() < deadline) {
                Thread.sleep(10)
            }
            heading
        } finally {
            sensors.unregisterListener(listener)
        }
    }

    /**
     * Last known location from the platform LocationManager — no Play Services
     * dependency. Best effort: a phone that has not had a fix yet (or a user who
     * denied the permission) simply gets no GPS tags, and the panorama can still
     * be placed by hand when it is contributed to Maps.
     */
    fun readLastLocation(context: Context): Pair<Double, Double>? {
        return try {
            val manager = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
                ?: return null
            val providers = listOf(
                LocationManager.GPS_PROVIDER,
                LocationManager.NETWORK_PROVIDER,
                LocationManager.PASSIVE_PROVIDER
            )
            var best: Pair<Double, Double>? = null
            var bestTime = Long.MIN_VALUE
            for (provider in providers) {
                val location = try {
                    manager.getLastKnownLocation(provider)
                } catch (_: SecurityException) {
                    null
                } catch (_: Exception) {
                    null
                } ?: continue
                if (location.time > bestTime) {
                    bestTime = location.time
                    best = location.latitude to location.longitude
                }
            }
            best
        } catch (e: Exception) {
            Log.w(TAG, "Location read failed: ${e.message}")
            null
        }
    }

    /** Writes the captured context into the scan folder (skipping what is unknown). */
    fun save(datasetDir: File, headingDegrees: Float?, location: Pair<Double, Double>?) {
        try {
            if (headingDegrees != null) {
                File(datasetDir, HEADING_FILENAME).writeText(headingDegrees.toString())
            }
            if (location != null) {
                File(datasetDir, LOCATION_FILENAME).writeText("${location.first},${location.second}")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Could not save capture context: ${e.message}")
        }
    }

    fun readHeading(datasetDir: File): Float? {
        val file = File(datasetDir, HEADING_FILENAME)
        if (!file.exists()) return null
        return try {
            file.readText().trim().toFloatOrNull()
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Reads `location.txt` back as a latitude/longitude pair, or null when the
     * capture had no fix. Kept here next to [save] so the writer and the reader
     * of the file cannot drift apart.
     */
    fun readLocation(datasetDir: File): Pair<Double, Double>? {
        val file = File(datasetDir, LOCATION_FILENAME)
        if (!file.exists()) return null
        return try {
            val parts = file.readText().trim().split(",")
            val latitude = parts.getOrNull(0)?.trim()?.toDoubleOrNull()
            val longitude = parts.getOrNull(1)?.trim()?.toDoubleOrNull()
            if (latitude == null || longitude == null) null else latitude to longitude
        } catch (_: Exception) {
            null
        }
    }
}
