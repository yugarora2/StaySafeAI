package com.staysafeai.app.scanners

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import com.staysafeai.app.models.AudioAnalysis
import com.staysafeai.app.models.FrequencyAnomaly
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.*

class MicrophoneScanner {

    private var audioRecord: AudioRecord? = null
    private var isRecording = false

    companion object {
        const val SAMPLE_RATE = 44100
        const val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
        const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
        val BUFFER_SIZE = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT) * 4

        // Camera-specific frequency signatures (Hz)
        val CAPACITOR_WHINE_RANGE = 8000.0..15000.0      // Power circuit whine
        val LENS_MOTOR_RANGE = 50.0..200.0               // AF motor hum
        val SD_CARD_CLICK_RANGE = 1000.0..3000.0         // Write clicks
        val WIFI_INTERFERENCE_RANGE = 2390.0..2410.0     // 2.4GHz harmonic
        val GSM_BUZZ_FREQ = 217.0                        // GSM transmission

        // Microphone-specific signatures
        val RF_BUG_RANGE = 430.0..436.0                  // 433MHz RF bugs
        val WIFI_BUG_RANGE = 2399.0..2401.0              // Wi-Fi bug harmonic
    }

    // Record ambient baseline before scan
    suspend fun recordBaseline(durationMs: Long = 5000): DoubleArray {
        return withContext(Dispatchers.IO) {
            val samples = recordAudio(durationMs)
            computeFFTMagnitudes(samples)
        }
    }

    // Main scan - subtract baseline and look for anomalies
    suspend fun scanForCameraFrequencies(durationMs: Long = 8000): AudioAnalysis {
        return withContext(Dispatchers.IO) {
            val samples = recordAudio(durationMs)
            val magnitudes = computeFFTMagnitudes(samples)
            analyzeForCameraSignatures(magnitudes, samples)
        }
    }

    private fun recordAudio(durationMs: Long): ShortArray {
        val totalSamples = (SAMPLE_RATE * durationMs / 1000).toInt()
        val allSamples = ShortArray(totalSamples)
        var recordedSamples = 0

        audioRecord = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            SAMPLE_RATE,
            CHANNEL_CONFIG,
            AUDIO_FORMAT,
            BUFFER_SIZE
        )

        audioRecord?.startRecording()
        isRecording = true

        val buffer = ShortArray(BUFFER_SIZE / 2)
        while (isRecording && recordedSamples < totalSamples) {
            val read = audioRecord?.read(buffer, 0, buffer.size) ?: 0
            if (read > 0) {
                val copyCount = minOf(read, totalSamples - recordedSamples)
                System.arraycopy(buffer, 0, allSamples, recordedSamples, copyCount)
                recordedSamples += copyCount
            }
        }

        audioRecord?.stop()
        audioRecord?.release()
        audioRecord = null

        return allSamples
    }

    private fun computeFFTMagnitudes(samples: ShortArray): DoubleArray {
        // Convert to double
        val n = nextPowerOf2(minOf(samples.size, 65536))
        val real = DoubleArray(n)
        val imag = DoubleArray(n)

        for (i in 0 until minOf(n, samples.size)) {
            // Apply Hann window to reduce spectral leakage
            val window = 0.5 * (1 - cos(2 * PI * i / n))
            real[i] = samples[i].toDouble() / 32768.0 * window
        }

        // FFT
        fft(real, imag, n)

        // Return magnitude spectrum (first half)
        return DoubleArray(n / 2) { i ->
            sqrt(real[i] * real[i] + imag[i] * imag[i])
        }
    }

    private fun analyzeForCameraSignatures(
        magnitudes: DoubleArray,
        samples: ShortArray
    ): AudioAnalysis {
        val anomalies = mutableListOf<FrequencyAnomaly>()
        val freqResolution = SAMPLE_RATE.toDouble() / (magnitudes.size * 2)

        // Calculate noise floor
        val noiseFloor = magnitudes.average()
        val threshold = noiseFloor * 3.0 // 3x noise floor = anomaly

        // Scan for camera signatures
        for (i in magnitudes.indices) {
            val freq = i * freqResolution
            val mag = magnitudes[i]

            if (mag < threshold) continue

            val type = when {
                freq in CAPACITOR_WHINE_RANGE -> FrequencyAnomaly.Type.CAPACITOR_WHINE
                freq in LENS_MOTOR_RANGE -> FrequencyAnomaly.Type.LENS_MOTOR
                freq in SD_CARD_CLICK_RANGE -> FrequencyAnomaly.Type.SD_CARD
                freq in WIFI_INTERFERENCE_RANGE -> FrequencyAnomaly.Type.WIFI_INTERFERENCE
                abs(freq - GSM_BUZZ_FREQ) < 5 -> FrequencyAnomaly.Type.GSM_BUZZ
                freq in RF_BUG_RANGE -> FrequencyAnomaly.Type.RF_BUG
                freq in WIFI_BUG_RANGE -> FrequencyAnomaly.Type.WIFI_BUG
                else -> continue
            }

            anomalies.add(
                FrequencyAnomaly(
                    frequency = freq,
                    magnitude = mag,
                    type = type,
                    normalizedStrength = (mag / threshold).coerceIn(0.0, 1.0)
                )
            )
        }

        // Detect rhythmic patterns (SD card writes every few seconds)
        val rhythmicPatterns = detectRhythmicPatterns(samples)

        // Overall audio threat score
        val threatScore = calculateAudioThreatScore(anomalies, rhythmicPatterns)

        return AudioAnalysis(
            anomalies = anomalies,
            rhythmicPatterns = rhythmicPatterns,
            threatScore = threatScore,
            dominantFrequency = magnitudes.indices.maxByOrNull { magnitudes[it] }
                ?.let { it * freqResolution } ?: 0.0,
            hasCapacitorWhine = anomalies.any { it.type == FrequencyAnomaly.Type.CAPACITOR_WHINE },
            hasLensMotor = anomalies.any { it.type == FrequencyAnomaly.Type.LENS_MOTOR },
            hasGSMBuzz = anomalies.any { it.type == FrequencyAnomaly.Type.GSM_BUZZ },
            hasRFBug = anomalies.any { it.type == FrequencyAnomaly.Type.RF_BUG }
        )
    }

    private fun detectRhythmicPatterns(samples: ShortArray): List<Double> {
        // Compute short-time energy
        val frameSize = SAMPLE_RATE / 10 // 100ms frames
        val energies = mutableListOf<Double>()

        var i = 0
        while (i + frameSize < samples.size) {
            val frameEnergy = samples.slice(i until i + frameSize)
                .map { it.toLong() * it.toLong() }
                .average()
            energies.add(frameEnergy)
            i += frameSize
        }

        // Find peaks in energy (loud events = SD card clicks)
        val avgEnergy = energies.average()
        val peaks = energies.mapIndexedNotNull { idx, energy ->
            if (energy > avgEnergy * 5) idx * 100.0 else null // ms timestamp
        }

        // Check for regular intervals (periodic = SD card write pattern)
        if (peaks.size < 2) return emptyList()
        val intervals = peaks.zipWithNext { a, b -> b - a }
        val avgInterval = intervals.average()
        val isRegular = intervals.all { abs(it - avgInterval) < avgInterval * 0.3 }

        return if (isRegular && avgInterval in 500.0..10000.0) intervals else emptyList()
    }

    private fun calculateAudioThreatScore(
        anomalies: List<FrequencyAnomaly>,
        rhythmicPatterns: List<Double>
    ): Int {
        var score = 0
        if (anomalies.any { it.type == FrequencyAnomaly.Type.CAPACITOR_WHINE }) score += 30
        if (anomalies.any { it.type == FrequencyAnomaly.Type.LENS_MOTOR }) score += 25
        if (anomalies.any { it.type == FrequencyAnomaly.Type.GSM_BUZZ }) score += 35
        if (anomalies.any { it.type == FrequencyAnomaly.Type.RF_BUG }) score += 30
        if (anomalies.any { it.type == FrequencyAnomaly.Type.SD_CARD }) score += 20
        if (rhythmicPatterns.isNotEmpty()) score += 15
        return score.coerceIn(0, 100)
    }

    // Cooley-Tukey FFT
    private fun fft(real: DoubleArray, imag: DoubleArray, n: Int) {
        // Bit-reversal permutation
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
            val wReal = cos(angle)
            val wImag = sin(angle)

            var i = 0
            while (i < n) {
                var curReal = 1.0
                var curImag = 0.0
                for (jj in 0 until len / 2) {
                    val uReal = real[i + jj]
                    val uImag = imag[i + jj]
                    val vReal = real[i + jj + len / 2] * curReal - imag[i + jj + len / 2] * curImag
                    val vImag = real[i + jj + len / 2] * curImag + imag[i + jj + len / 2] * curReal
                    real[i + jj] = uReal + vReal
                    imag[i + jj] = uImag + vImag
                    real[i + jj + len / 2] = uReal - vReal
                    imag[i + jj + len / 2] = uImag - vImag
                    val tempReal = curReal * wReal - curImag * wImag
                    curImag = curReal * wImag + curImag * wReal
                    curReal = tempReal
                }
                i += len
            }
            len *= 2
        }
    }

    private fun nextPowerOf2(n: Int): Int {
        var p = 1
        while (p < n) p = p shl 1
        return p
    }

    fun stop() {
        isRecording = false
        audioRecord?.stop()
        audioRecord?.release()
        audioRecord = null
    }
}
