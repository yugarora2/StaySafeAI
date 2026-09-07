package com.staysafeai.app.analyzers

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import com.staysafeai.app.models.LocationReading
import com.staysafeai.app.models.TriangulationResult
import kotlin.math.sqrt

class SignalTriangulator(private val context: Context) : SensorEventListener {

    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val magnetometer = sensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)

    // Stores (x, y, signalStrength) readings as user walks around
    private val emfReadings = mutableListOf<LocationReading>()
    private var currentX = 0f
    private var currentY = 0f

    // Walking direction tracking
    private var isTracking = false
    private var onStrengthUpdate: ((Float, String) -> Unit)? = null

    // Stores peak reading location
    private var peakReading: LocationReading? = null

    fun startTracking(callback: (strength: Float, guidance: String) -> Unit) {
        onStrengthUpdate = callback
        isTracking = true
        emfReadings.clear()

        sensorManager.registerListener(
            this,
            magnetometer,
            SensorManager.SENSOR_DELAY_GAME
        )
    }

    fun updateUserPosition(x: Float, y: Float) {
        currentX = x
        currentY = y
    }

    fun stopTracking(): TriangulationResult {
        isTracking = false
        sensorManager.unregisterListener(this)
        return triangulate()
    }

    private fun triangulate(): TriangulationResult {
        if (emfReadings.size < 3) {
            return TriangulationResult(
                success = false,
                message = "Need more readings. Walk around the room slowly."
            )
        }

        // Find peak reading
        val peak = emfReadings.maxByOrNull { it.signalStrength } ?: return TriangulationResult(false)

        // Check convergence — do nearby readings also show high strength?
        val nearbyReadings = emfReadings.filter { reading ->
            distance(reading.x, reading.y, peak.x, peak.y) < 0.5f
        }

        val convergenceScore = nearbyReadings.map { it.signalStrength }.average() / peak.signalStrength

        // Estimate distance from current position to source
        val distanceToPeak = distance(currentX, currentY, peak.x, peak.y)

        // Direction from current position to peak
        val angle = Math.toDegrees(
            Math.atan2(
                (peak.y - currentY).toDouble(),
                (peak.x - currentX).toDouble()
            )
        ).toFloat()

        val directionLabel = angleToDirection(angle)

        return TriangulationResult(
            success = true,
            estimatedX = peak.x,
            estimatedY = peak.y,
            confidence = convergenceScore.toFloat().coerceIn(0f, 1f),
            distanceMeters = distanceToPeak,
            directionFromUser = directionLabel,
            peakStrength = peak.signalStrength,
            readingCount = emfReadings.size,
            message = "Signal peaks ${directionLabel.lowercase()}. Device likely within ${
                String.format("%.1f", distanceToPeak)
            }m."
        )
    }

    private fun generateGuidance(currentStrength: Float): String {
        val peak = peakReading
        return when {
            peak == null -> "Walk slowly around the room"
            currentStrength > peak.signalStrength * 0.9f -> "🔴 STOP — Device is very close!"
            currentStrength > peak.signalStrength * 0.7f -> "🔥 Getting very warm — keep going"
            currentStrength > peak.signalStrength * 0.5f -> "☀️ Warmer — continue this direction"
            currentStrength > peak.signalStrength * 0.3f -> "🌡️ Signal detected — move around"
            else -> "❄️ Cold — try a different direction"
        }
    }

    private fun angleToDirection(angle: Float): String {
        return when {
            angle in -22.5f..22.5f -> "East →"
            angle in 22.5f..67.5f -> "Northeast ↗"
            angle in 67.5f..112.5f -> "North ↑"
            angle in 112.5f..157.5f -> "Northwest ↖"
            angle > 157.5f || angle < -157.5f -> "West ←"
            angle in -157.5f..-112.5f -> "Southwest ↙"
            angle in -112.5f..-67.5f -> "South ↓"
            else -> "Southeast ↘"
        }
    }

    private fun distance(x1: Float, y1: Float, x2: Float, y2: Float): Float {
        val dx = x1 - x2
        val dy = y1 - y2
        return sqrt(dx * dx + dy * dy)
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (!isTracking) return
        if (event.sensor.type != Sensor.TYPE_MAGNETIC_FIELD) return

        val strength = sqrt(
            event.values[0] * event.values[0] +
            event.values[1] * event.values[1] +
            event.values[2] * event.values[2]
        )

        val reading = LocationReading(currentX, currentY, strength, System.currentTimeMillis())
        emfReadings.add(reading)

        // Update peak
        if (peakReading == null || strength > (peakReading?.signalStrength ?: 0f)) {
            peakReading = reading
        }

        val guidance = generateGuidance(strength)
        onStrengthUpdate?.invoke(strength, guidance)
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
}
