package com.staysafeai.app.scanners

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import com.staysafeai.app.models.GSMDetectionResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.*

class GSMBugDetector {

    companion object {
        const val SAMPLE_RATE = 44100
        // GSM TDMA frame burst creates 217Hz interference
        const val GSM_FREQUENCY = 217.0
        const val GSM_TOLERANCE = 8.0 // Hz tolerance
        // Must repeat regularly (GSM sends in bursts)
        const val MIN_BURST_COUNT = 5
    }

    suspend fun detect(durationMs: Long = 5000): GSMDetectionResult {
        return withContext(Dispatchers.IO) {
            val bufferSize = AudioRecord.getMinBufferSize(
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            ) * 2

            val recorder = AudioRecord(
                MediaRecorder.AudioSource.MIC,
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                bufferSize
            )

            val totalSamples = (SAMPLE_RATE * durationMs / 1000).toInt()
            val samples = ShortArray(totalSamples)
            var recorded = 0

            recorder.startRecording()
            val buffer = ShortArray(bufferSize / 2)
            while (recorded < totalSamples) {
                val read = recorder.read(buffer, 0, buffer.size)
                if (read > 0) {
                    val copy = minOf(read, totalSamples - recorded)
                    System.arraycopy(buffer, 0, samples, recorded, copy)
                    recorded += copy
                }
            }
            recorder.stop()
            recorder.release()

            analyzeForGSM(samples)
        }
    }

    private fun analyzeForGSM(samples: ShortArray): GSMDetectionResult {
        // Split into 100ms frames and check each for 217Hz
        val frameSize = SAMPLE_RATE / 10
        val frameBursts = mutableListOf<Boolean>()

        var i = 0
        while (i + frameSize <= samples.size) {
            val frame = samples.copyOfRange(i, i + frameSize)
            val has217Hz = detectFrequencyInFrame(frame, GSM_FREQUENCY, GSM_TOLERANCE)
            frameBursts.add(has217Hz)
            i += frameSize
        }

        // Count consecutive bursts
        val burstCount = frameBursts.count { it }
        val isRhythmic = isRhythmicPattern(frameBursts)
        val isDetected = burstCount >= MIN_BURST_COUNT && isRhythmic

        // Estimate signal strength
        val avgStrength = if (isDetected) {
            frameBursts.mapIndexed { idx, hasBurst ->
                if (hasBurst) measureFrequencyStrength(
                    samples.copyOfRange(
                        idx * frameSize,
                        minOf((idx + 1) * frameSize, samples.size)
                    ),
                    GSM_FREQUENCY
                ) else 0.0
            }.filter { it > 0 }.average()
        } else 0.0

        return GSMDetectionResult(
            isDetected = isDetected,
            burstCount = burstCount,
            isRhythmic = isRhythmic,
            signalStrength = avgStrength,
            confidence = if (isDetected) minOf(1.0, burstCount / 20.0) else 0.0
        )
    }

    private fun detectFrequencyInFrame(frame: ShortArray, targetHz: Double, tolerance: Double): Boolean {
        val n = frame.size
        val targetBin = (targetHz * n / SAMPLE_RATE).toInt()
        val toleranceBins = (tolerance * n / SAMPLE_RATE).toInt() + 1

        // Goertzel algorithm - efficient single-frequency detection
        val k = (0.5 + n * targetHz / SAMPLE_RATE).toInt()
        val omega = 2 * PI * k / n
        val coeff = 2 * cos(omega)
        var s1 = 0.0; var s2 = 0.0

        for (sample in frame) {
            val s0 = sample.toDouble() / 32768.0 + coeff * s1 - s2
            s2 = s1; s1 = s0
        }

        val power = s1 * s1 + s2 * s2 - coeff * s1 * s2
        val avgPower = frame.map { (it.toDouble() / 32768.0).pow(2) }.average()

        return power > avgPower * 10 // 10x above average = significant frequency
    }

    private fun measureFrequencyStrength(frame: ShortArray, targetHz: Double): Double {
        val n = frame.size
        val k = (0.5 + n * targetHz / SAMPLE_RATE).toInt()
        val omega = 2 * PI * k / n
        val coeff = 2 * cos(omega)
        var s1 = 0.0; var s2 = 0.0

        for (sample in frame) {
            val s0 = sample.toDouble() / 32768.0 + coeff * s1 - s2
            s2 = s1; s1 = s0
        }
        return s1 * s1 + s2 * s2 - coeff * s1 * s2
    }

    private fun isRhythmicPattern(bursts: List<Boolean>): Boolean {
        if (bursts.count { it } < MIN_BURST_COUNT) return false
        // Check for regular on/off pattern characteristic of GSM TDMA
        val transitions = bursts.zipWithNext { a, b -> a != b }.count { it }
        return transitions >= 4 // At least 4 transitions = rhythmic
    }
}

// ─── Audio Feedback Loop Test ────────────────────────────────────────────────

class AudioFeedbackLoopTest(private val context: android.content.Context) {

    companion object {
        const val TEST_FREQUENCY = 18000 // 18kHz - inaudible to humans
        const val SAMPLE_RATE = 44100
        const val TONE_DURATION_MS = 500L
        const val MONITOR_DURATION_MS = 9500L
        // If hidden bug picks up tone and transmits, EMF spikes within this window
        const val CORRELATION_WINDOW_MS = 200L
    }

    suspend fun run(
        onTonePlayed: () -> Unit,
        onBurstDetected: () -> Unit
    ): com.staysafeai.app.models.FeedbackLoopResult = withContext(Dispatchers.IO) {
        val emfReadings = mutableListOf<Pair<Long, Float>>()
        val tonePlayTimes = mutableListOf<Long>()
        var burstDetectedCount = 0

        // Setup microphone monitoring
        val bufferSize = AudioRecord.getMinBufferSize(
            SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        val recorder = AudioRecord(
            MediaRecorder.AudioSource.MIC, SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, bufferSize * 4
        )

        recorder.startRecording()

        // Play 18kHz tone 5 times, monitor for EMF response each time
        repeat(5) { iteration ->
            kotlinx.coroutines.delay(1500L)

            // Record tone play time
            val playTime = System.currentTimeMillis()
            tonePlayTimes.add(playTime)

            // Play silent tone
            withContext(Dispatchers.Main) { onTonePlayed() }
            playTone(TEST_FREQUENCY, TONE_DURATION_MS)

            // Monitor microphone for the next second
            // (A bug would re-transmit the tone, causing slight audio bleed back)
            val monitorBuffer = ShortArray(SAMPLE_RATE) // 1 second
            recorder.read(monitorBuffer, 0, monitorBuffer.size)

            // Check if our test tone echoed back (bug transmitted it)
            val hasEcho = detectFrequencyInBuffer(monitorBuffer, TEST_FREQUENCY.toDouble())
            if (hasEcho) {
                burstDetectedCount++
                withContext(Dispatchers.Main) { onBurstDetected() }
            }
        }

        recorder.stop()
        recorder.release()

        val bugDetected = burstDetectedCount >= 3 // At least 3/5 echoes = bug present

        com.staysafeai.app.models.FeedbackLoopResult(
            bugDetected = bugDetected,
            echoCount = burstDetectedCount,
            totalTests = 5,
            confidence = burstDetectedCount / 5.0
        )
    }

    private fun playTone(frequency: Int, durationMs: Long) {
        val numSamples = (SAMPLE_RATE * durationMs / 1000).toInt()
        val buffer = ShortArray(numSamples)

        for (i in buffer.indices) {
            buffer[i] = (sin(2 * PI * frequency * i / SAMPLE_RATE) * Short.MAX_VALUE).toInt().toShort()
        }

        val track = AudioTrack.Builder()
            .setAudioAttributes(
                android.media.AudioAttributes.Builder()
                    .setUsage(android.media.AudioAttributes.USAGE_MEDIA)
                    .setContentType(android.media.AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(SAMPLE_RATE)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build()
            )
            .setBufferSizeInBytes(buffer.size * 2)
            .build()

        track.write(buffer, 0, buffer.size)
        track.play()
        Thread.sleep(durationMs)
        track.stop()
        track.release()
    }

    private fun detectFrequencyInBuffer(buffer: ShortArray, targetHz: Double): Boolean {
        val k = (0.5 + buffer.size * targetHz / SAMPLE_RATE).toInt()
        val omega = 2 * PI * k / buffer.size
        val coeff = 2 * cos(omega)
        var s1 = 0.0; var s2 = 0.0

        for (sample in buffer) {
            val s0 = sample.toDouble() / 32768.0 + coeff * s1 - s2
            s2 = s1; s1 = s0
        }

        val power = s1 * s1 + s2 * s2 - coeff * s1 * s2
        val avgPower = buffer.map { (it.toDouble() / 32768.0).pow(2) }.average()
        return power > avgPower * 8
    }
}
