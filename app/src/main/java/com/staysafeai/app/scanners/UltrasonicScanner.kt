package com.staysafeai.app.scanners

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import com.staysafeai.app.models.UltrasonicResult
import com.staysafeai.app.models.UltrasonicAnomaly
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.*

class UltrasonicScanner {

    companion object {
        const val SAMPLE_RATE = 44100       // Max standard Android rate
        const val FFT_SIZE = 65536          // High resolution for ultrasonic range
        const val SCAN_DURATION_MS = 8000L

        // Ultrasonic range of interest (Hz)
        const val ULTRASONIC_LOW  = 16000.0
        const val ULTRASONIC_HIGH = 22000.0  // Nyquist limit at 44100Hz

        // Sub-bands with different threat signatures
        val BEACON_RANGE     = 17000.0..18500.0   // Ultrasonic beacon/tracking
        val DEVICE_COMM_RANGE = 18500.0..20000.0  // Device-to-device communication
        val CAMERA_SYNC_RANGE = 20000.0..22000.0  // Some cameras sync at 20kHz+

        // A genuine device emission is persistent and periodic, not one-off
        const val MIN_DETECTION_FRAMES = 5
        const val ANOMALY_THRESHOLD_MULTIPLIER = 4.0  // 4x noise floor = anomaly
    }

    private var audioRecord: AudioRecord? = null
    private var isScanning = false

    suspend fun scan(): UltrasonicResult = withContext(Dispatchers.IO) {
        val bufferSize = AudioRecord.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        ).coerceAtLeast(8192)

        audioRecord = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            bufferSize * 4
        )

        val totalSamples = (SAMPLE_RATE * SCAN_DURATION_MS / 1000).toInt()
        val allSamples = ShortArray(totalSamples)
        var recorded = 0

        audioRecord?.startRecording()
        isScanning = true

        val frameBuffer = ShortArray(bufferSize / 2)
        while (isScanning && recorded < totalSamples) {
            val read = audioRecord?.read(frameBuffer, 0, frameBuffer.size) ?: break
            if (read > 0) {
                val copy = minOf(read, totalSamples - recorded)
                System.arraycopy(frameBuffer, 0, allSamples, recorded, copy)
                recorded += copy
            }
        }

        audioRecord?.stop()
        audioRecord?.release()
        audioRecord = null

        analyzeUltrasonic(allSamples, recorded)
    }

    private fun analyzeUltrasonic(samples: ShortArray, count: Int): UltrasonicResult {
        // Split into overlapping frames for temporal analysis
        val frameSize = FFT_SIZE.coerceAtMost(count)
        val hopSize = frameSize / 4  // 75% overlap
        val frames = mutableListOf<DoubleArray>()

        var start = 0
        while (start + frameSize <= count) {
            val frame = samples.copyOfRange(start, start + frameSize)
            val magnitudes = computeFFTMagnitudes(frame)
            frames.add(magnitudes)
            start += hopSize
        }

        if (frames.isEmpty()) return UltrasonicResult(detected = false)

        // Frequency resolution: SAMPLE_RATE / FFT_SIZE Hz per bin
        val freqResolution = SAMPLE_RATE.toDouble() / frameSize

        // Find ultrasonic bin range
        val lowBin  = (ULTRASONIC_LOW  / freqResolution).toInt()
        val highBin = (ULTRASONIC_HIGH / freqResolution).toInt().coerceAtMost(frameSize / 2 - 1)

        // Calculate noise floor from audio band (1kHz–8kHz, where room noise lives)
        val noiseFloorBins = ((1000.0 / freqResolution).toInt())..(8000.0 / freqResolution).toInt()
        val noiseFloor = frames.map { frame ->
            frame.slice(noiseFloorBins).average()
        }.average()

        val anomalyThreshold = noiseFloor * ANOMALY_THRESHOLD_MULTIPLIER

        // For each ultrasonic frequency bin, check persistence across frames
        val persistentAnomalies = mutableListOf<UltrasonicAnomaly>()

        for (bin in lowBin..highBin) {
            val freq = bin * freqResolution
            val binMagnitudes = frames.map { it[bin] }
            val avgMagnitude = binMagnitudes.average()
            val detectionCount = binMagnitudes.count { it > anomalyThreshold }

            // Only flag if persistent (not transient noise)
            if (detectionCount >= MIN_DETECTION_FRAMES && avgMagnitude > anomalyThreshold) {
                val isPeriodic = detectPeriodicity(binMagnitudes)
                val subBandType = classifySubBand(freq)

                persistentAnomalies.add(
                    UltrasonicAnomaly(
                        frequency = freq,
                        averageMagnitude = avgMagnitude,
                        peakMagnitude = binMagnitudes.max(),
                        detectionFrames = detectionCount,
                        totalFrames = frames.size,
                        isPeriodic = isPeriodic,
                        subBandType = subBandType,
                        confidence = calculateConfidence(detectionCount, frames.size, isPeriodic, avgMagnitude, noiseFloor)
                    )
                )
            }
        }

        // Merge adjacent bins into single anomaly peaks
        val mergedAnomalies = mergeAdjacentBins(persistentAnomalies, freqResolution)

        val threatScore = calculateThreatScore(mergedAnomalies)

        return UltrasonicResult(
            detected = mergedAnomalies.isNotEmpty(),
            anomalies = mergedAnomalies,
            threatScore = threatScore,
            noiseFloor = noiseFloor,
            anomalyThreshold = anomalyThreshold,
            frameCount = frames.size,
            hasBeaconSignal = mergedAnomalies.any { it.subBandType == "BEACON" },
            hasDeviceComm = mergedAnomalies.any { it.subBandType == "DEVICE_COMM" },
            hasCameraSync = mergedAnomalies.any { it.subBandType == "CAMERA_SYNC" },
            dominantFrequency = mergedAnomalies.maxByOrNull { it.averageMagnitude }?.frequency
        )
    }

    // ── Periodicity Detection ─────────────────────────────────────────────────

    private fun detectPeriodicity(magnitudes: List<Double>): Boolean {
        if (magnitudes.size < 10) return false

        // Autocorrelation — periodic signals have strong autocorrelation at lag > 0
        val mean = magnitudes.average()
        val centered = magnitudes.map { it - mean }
        val variance = centered.map { it * it }.average()
        if (variance < 1e-10) return false

        // Check autocorrelation at several lags
        val maxLag = magnitudes.size / 3
        for (lag in 2..maxLag) {
            var correlation = 0.0
            for (i in 0 until magnitudes.size - lag) {
                correlation += centered[i] * centered[i + lag]
            }
            correlation /= (magnitudes.size - lag) * variance

            // Strong autocorrelation = periodic
            if (correlation > 0.6) return true
        }
        return false
    }

    private fun classifySubBand(freq: Double): String = when (freq) {
        in BEACON_RANGE      -> "BEACON"
        in DEVICE_COMM_RANGE -> "DEVICE_COMM"
        in CAMERA_SYNC_RANGE -> "CAMERA_SYNC"
        else -> "UNKNOWN"
    }

    private fun calculateConfidence(
        detectionFrames: Int,
        totalFrames: Int,
        isPeriodic: Boolean,
        avgMagnitude: Double,
        noiseFloor: Double
    ): Float {
        var confidence = (detectionFrames.toFloat() / totalFrames) * 0.4f
        if (isPeriodic) confidence += 0.3f
        val snr = if (noiseFloor > 0) avgMagnitude / noiseFloor else 1.0
        confidence += (minOf(snr / 10.0, 0.3)).toFloat()
        return confidence.coerceIn(0f, 1f)
    }

    private fun mergeAdjacentBins(
        anomalies: List<UltrasonicAnomaly>,
        freqResolution: Double
    ): List<UltrasonicAnomaly> {
        if (anomalies.isEmpty()) return emptyList()

        val sorted = anomalies.sortedBy { it.frequency }
        val merged = mutableListOf<UltrasonicAnomaly>()
        var current = sorted.first()

        for (i in 1 until sorted.size) {
            val next = sorted[i]
            // Merge if within 200Hz of each other
            if (next.frequency - current.frequency <= freqResolution * 4) {
                // Keep the stronger one as representative
                current = if (next.averageMagnitude > current.averageMagnitude) next else current
            } else {
                merged.add(current)
                current = next
            }
        }
        merged.add(current)
        return merged
    }

    private fun calculateThreatScore(anomalies: List<UltrasonicAnomaly>): Int {
        var score = 0
        anomalies.forEach { anomaly ->
            score += when (anomaly.subBandType) {
                "BEACON"      -> if (anomaly.isPeriodic) 35 else 20
                "DEVICE_COMM" -> if (anomaly.isPeriodic) 30 else 15
                "CAMERA_SYNC" -> if (anomaly.isPeriodic) 25 else 12
                else -> 10
            }
            score += (anomaly.confidence * 20).toInt()
        }
        return score.coerceIn(0, 100)
    }

    // ── FFT ──────────────────────────────────────────────────────────────────

    private fun computeFFTMagnitudes(samples: ShortArray): DoubleArray {
        val n = nextPowerOf2(samples.size)
        val real = DoubleArray(n)
        val imag = DoubleArray(n)

        for (i in samples.indices.take(n)) {
            // Hann window to reduce spectral leakage
            val window = 0.5 * (1 - cos(2 * PI * i / n))
            real[i] = (samples[i].toDouble() / 32768.0) * window
        }

        fft(real, imag, n)

        return DoubleArray(n / 2) { i -> sqrt(real[i] * real[i] + imag[i] * imag[i]) }
    }

    private fun fft(real: DoubleArray, imag: DoubleArray, n: Int) {
        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while (j and bit != 0) { j = j xor bit; bit = bit shr 1 }
            j = j xor bit
            if (i < j) {
                real[i] = real[j].also { real[j] = real[i] }
                imag[i] = imag[j].also { imag[j] = imag[i] }
            }
        }
        var len = 2
        while (len <= n) {
            val angle = -2 * PI / len
            val wReal = cos(angle); val wImag = sin(angle)
            var i = 0
            while (i < n) {
                var cr = 1.0; var ci = 0.0
                for (jj in 0 until len / 2) {
                    val ur = real[i + jj]; val ui = imag[i + jj]
                    val vr = real[i + jj + len/2] * cr - imag[i + jj + len/2] * ci
                    val vi = real[i + jj + len/2] * ci + imag[i + jj + len/2] * cr
                    real[i + jj] = ur + vr; imag[i + jj] = ui + vi
                    real[i + jj + len/2] = ur - vr; imag[i + jj + len/2] = ui - vi
                    val tr = cr * wReal - ci * wImag; ci = cr * wImag + ci * wReal; cr = tr
                }
                i += len
            }
            len *= 2
        }
    }

    private fun nextPowerOf2(n: Int): Int { var p = 1; while (p < n) p = p shl 1; return p }

    fun stop() {
        isScanning = false
        audioRecord?.stop()
        audioRecord?.release()
        audioRecord = null
    }
}
