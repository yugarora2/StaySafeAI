package com.staysafeai.app.scanners

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import com.staysafeai.app.models.EMFData
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume
import kotlin.math.sqrt

class EMFScanner(private val context: Context) : SensorEventListener {

    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val magnetometer = sensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)

    private val readings = mutableListOf<FloatArray>()
    private var isScanning = false
    private var onReadingCallback: ((FloatArray) -> Unit)? = null

    companion object {
        // Camera wiring: 50–100µT
        const val DANGER_THRESHOLD = 50f
        // Above normal background (0.1–0.3µT)
        const val WARNING_THRESHOLD = 25f
        // Normal background
        const val NORMAL_THRESHOLD = 5f

        // Known camera EMF frequencies (Hz) detected via FFT
        val CAMERA_FREQUENCIES = listOf(
            50.0, 60.0,   // Power supply frequencies
            100.0, 120.0, // Harmonics
            217.0,        // GSM transmission
            433.0         // RF bug transmission
        )
    }

    suspend fun scan(durationMs: Long = 5000): EMFData {
        readings.clear()
        isScanning = true

        sensorManager.registerListener(
            this,
            magnetometer,
            SensorManager.SENSOR_DELAY_UI // Use UI delay to avoid needing HIGH_SAMPLING_RATE_SENSORS if possible, or gracefully fallback
        )

        // Collect readings for duration
        delay(durationMs)

        stop()

        return analyzeReadings()
    }

    private fun analyzeReadings(): EMFData {
        if (readings.isEmpty()) return EMFData()

        // Calculate magnitude for each reading
        val magnitudes = readings.map { r ->
            sqrt(r[0] * r[0] + r[1] * r[1] + r[2] * r[2])
        }

        val peakMagnitude = magnitudes.max()
        val avgMagnitude = magnitudes.average().toFloat()
        val baseline = magnitudes.take(20).average().toFloat() // First readings = baseline

        // FFT analysis to find frequency patterns
        val frequencies = performFFT(magnitudes.map { it.toDouble() }.toDoubleArray())

        // Check for camera-specific frequencies
        val detectedCameraFreqs = frequencies.filter { freq ->
            CAMERA_FREQUENCIES.any { target -> Math.abs(freq - target) < 5.0 }
        }

        // Directional analysis — which axis has highest reading
        val axisReadings = readings.map { r ->
            Triple(Math.abs(r[0]), Math.abs(r[1]), Math.abs(r[2]))
        }
        val dominantAxis = findDominantAxis(axisReadings)

        // Spike detection — sudden jumps indicate active device
        val spikes = detectSpikes(magnitudes)

        val threatLevel = when {
            peakMagnitude > DANGER_THRESHOLD -> "DANGER"
            peakMagnitude > WARNING_THRESHOLD -> "WARNING"
            else -> "NORMAL"
        }

        return EMFData(
            peakMagnitude = peakMagnitude,
            avgMagnitude = avgMagnitude,
            baseline = baseline,
            threatLevel = threatLevel,
            detectedFrequencies = detectedCameraFreqs,
            hasCameraFrequency = detectedCameraFreqs.isNotEmpty(),
            dominantAxis = dominantAxis,
            spikeCount = spikes.size,
            spikes = spikes,
            readingCount = readings.size,
            isAnomalous = peakMagnitude > WARNING_THRESHOLD || detectedCameraFreqs.isNotEmpty()
        )
    }

    // Simple FFT implementation using Cooley-Tukey algorithm
    private fun performFFT(signal: DoubleArray): List<Double> {
        val n = signal.size
        if (n < 2) return emptyList()

        // Normalize to power of 2
        val fftSize = Integer.highestOneBit(n)
        val fftData = signal.take(fftSize).toDoubleArray()

        val real = fftData.copyOf()
        val imag = DoubleArray(fftSize)

        // Cooley-Tukey FFT
        var len = 2
        while (len <= fftSize) {
            val halfLen = len / 2
            val angle = -2 * Math.PI / len
            val wReal = Math.cos(angle)
            val wImag = Math.sin(angle)

            var i = 0
            while (i < fftSize) {
                var curReal = 1.0
                var curImag = 0.0
                for (j in 0 until halfLen) {
                    val uReal = real[i + j]
                    val uImag = imag[i + j]
                    val vReal = real[i + j + halfLen] * curReal - imag[i + j + halfLen] * curImag
                    val vImag = real[i + j + halfLen] * curImag + imag[i + j + halfLen] * curReal
                    real[i + j] = uReal + vReal
                    imag[i + j] = uImag + vImag
                    real[i + j + halfLen] = uReal - vReal
                    imag[i + j + halfLen] = uImag - vImag
                    val tempReal = curReal * wReal - curImag * wImag
                    curImag = curReal * wImag + curImag * wReal
                    curReal = tempReal
                }
                i += len
            }
            len *= 2
        }

        // Find dominant frequencies (peaks in magnitude spectrum)
        val magnitudes = (0 until fftSize / 2).map { i ->
            sqrt(real[i] * real[i] + imag[i] * imag[i])
        }

        val sampleRate = 100.0 // ~100 sensor readings per second
        val threshold = magnitudes.average() * 2

        return magnitudes.mapIndexedNotNull { i, mag ->
            if (mag > threshold) {
                (i * sampleRate / fftSize) // Convert to Hz
            } else null
        }
    }

    private fun findDominantAxis(readings: List<Triple<Float, Float, Float>>): String {
        val xAvg = readings.map { it.first }.average()
        val yAvg = readings.map { it.second }.average()
        val zAvg = readings.map { it.third }.average()

        return when {
            xAvg > yAvg && xAvg > zAvg -> "X (East-West)"
            yAvg > xAvg && yAvg > zAvg -> "Y (North-South)"
            else -> "Z (Vertical)"
        }
    }

    private fun detectSpikes(magnitudes: List<Float>): List<Float> {
        if (magnitudes.size < 10) return emptyList()
        val movingAvg = magnitudes.windowed(5) { it.average().toFloat() }
        val stdDev = calculateStdDev(magnitudes)

        return magnitudes.filterIndexed { i, mag ->
            i < movingAvg.size && mag > movingAvg[i] + (2 * stdDev)
        }
    }

    private fun calculateStdDev(values: List<Float>): Float {
        val mean = values.average()
        val variance = values.map { (it - mean) * (it - mean) }.average()
        return sqrt(variance).toFloat()
    }

    // Live readings for triangulation
    fun startLiveMonitoring(callback: (FloatArray) -> Unit) {
        onReadingCallback = callback
        isScanning = true
        sensorManager.registerListener(this, magnetometer, SensorManager.SENSOR_DELAY_GAME)
    }

    fun stop() {
        isScanning = false
        sensorManager.unregisterListener(this)
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (!isScanning) return
        if (event.sensor.type == Sensor.TYPE_MAGNETIC_FIELD) {
            readings.add(event.values.clone())
            onReadingCallback?.invoke(event.values.clone())
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
}
