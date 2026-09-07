package com.staysafeai.app.scanners

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

class AudioDirectionFinder {

    companion object {
        const val SAMPLE_RATE = 44100
    }

    private var audioRecord: AudioRecord? = null
    private var isRunning = false

    // Track frequency strength as user rotates phone
    private val directionSamples = mutableListOf<Pair<Float, Double>>() // (angle, strength)

    fun startDirectionTracking(
        targetFrequency: Double,
        onUpdate: (strength: Double, guidance: String) -> Unit
    ) {
        isRunning = true
        val bufferSize = AudioRecord.getMinBufferSize(
            SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
        )

        audioRecord = AudioRecord(
            MediaRecorder.AudioSource.MIC, SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, bufferSize * 2
        )
        audioRecord?.startRecording()

        Thread {
            val buffer = ShortArray(bufferSize / 2)
            while (isRunning) {
                val read = audioRecord?.read(buffer, 0, buffer.size) ?: break
                if (read > 0) {
                    val strength = goertzel(buffer.copyOf(read), targetFrequency)
                    val guidance = generateAudioGuidance(strength)
                    onUpdate(strength, guidance)
                }
            }
        }.start()
    }

    fun recordDirectionSample(angle: Float, targetFrequency: Double) {
        val bufferSize = 4096
        val buffer = ShortArray(bufferSize)
        val read = audioRecord?.read(buffer, 0, bufferSize) ?: return
        if (read > 0) {
            val strength = goertzel(buffer.copyOf(read), targetFrequency)
            directionSamples.add(Pair(angle, strength))
        }
    }

    fun getBestDirection(): Float? {
        if (directionSamples.isEmpty()) return null
        return directionSamples.maxByOrNull { it.second }?.first
    }

    fun stop() {
        isRunning = false
        audioRecord?.stop()
        audioRecord?.release()
        audioRecord = null
    }

    // Goertzel algorithm for efficient single-frequency detection
    private fun goertzel(samples: ShortArray, targetHz: Double): Double {
        val n = samples.size
        val k = (0.5 + n * targetHz / SAMPLE_RATE).toInt()
        val omega = 2 * Math.PI * k / n
        val coeff = 2 * cos(omega)
        var s1 = 0.0; var s2 = 0.0

        for (sample in samples) {
            val s0 = sample.toDouble() / 32768.0 + coeff * s1 - s2
            s2 = s1; s1 = s0
        }
        return sqrt(s1 * s1 + s2 * s2 - coeff * s1 * s2)
    }

    private fun generateAudioGuidance(strength: Double): String {
        val maxStrength = directionSamples.maxOfOrNull { it.second } ?: strength
        val ratio = if (maxStrength > 0) strength / maxStrength else 0.0
        return when {
            ratio > 0.9 -> "🔴 Very strong — source is very close!"
            ratio > 0.7 -> "🔥 Getting warmer — keep going this direction"
            ratio > 0.5 -> "☀️ Signal strengthening"
            ratio > 0.3 -> "🌡️ Weak signal detected"
            else -> "❄️ Signal fading — try another direction"
        }
    }
}
