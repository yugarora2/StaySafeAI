package com.staysafeai.app.analyzers

import com.staysafeai.app.models.*

class ThreatScoreEngine {

    fun calculate(result: ScanResult): Int {
        var score = 0

        // EMF scoring
        result.emfData?.let { emf ->
            score += when {
                emf.peakMagnitude > 50 -> 35
                emf.peakMagnitude > 25 -> 20
                emf.peakMagnitude > 10 -> 8
                else -> 0
            }
            if (emf.hasCameraFrequency) score += 15
            if (emf.spikeCount > 5) score += 10
        }

        // IR scoring
        result.irSources.forEach { ir ->
            when (ir.type) {
                IRSource.Type.POINT_SOURCE -> score += 30
                IRSource.Type.UNKNOWN -> score += 10
                else -> {}
            }
            if (ir.isPulsing) score += 15
        }

        // Wi-Fi scoring
        result.wifiDevices.filter { it.isSuspicious }.forEach { dev ->
            score += (dev.threatScore * 0.4).toInt()
        }

        // Bluetooth scoring
        result.bluetoothDevices.filter { it.isSuspicious }.forEach { dev ->
            score += (dev.threatScore * 0.3).toInt()
        }

        // Lens glint scoring
        result.lensGlints.forEach { glint ->
            when (glint.classification) {
                LensGlint.Classification.CAMERA_LENS -> score += 25
                LensGlint.Classification.TWO_WAY_MIRROR_CAMERA -> score += 35
                else -> {}
            }
        }

        // Audio scoring
        result.audioFrequencies?.let { audio ->
            score += (audio.threatScore * 0.3).toInt()
        }

        // ── NEW: Rogue Device (replaces GSM) ─────────────────────────────
        result.rogueDevices.forEach { device ->
            score += when {
                device.hasRTSP                     -> 40  // RTSP = confirmed camera stream
                device.hasONVIF                    -> 35  // ONVIF = IP camera protocol
                device.hasCameraPort               -> 30  // Known camera port open
                device.isCameraVendor              -> 25  // Known manufacturer
                device.riskScore > 40              -> 20
                else -> 5
            }
        }

        // ── NEW: Ultrasonic ──────────────────────────────────────────────
        result.ultrasonicResult?.let { ultrasonic ->
            score += when {
                ultrasonic.hasBeaconSignal  -> 25
                ultrasonic.hasDeviceComm    -> 20
                ultrasonic.hasCameraSync    -> 18
                ultrasonic.detected         -> 12
                else -> 0
            }
        }

        // ── NEW: Static Object Tracker ───────────────────────────────────
        result.staticObjects.filter { it.isCameraCandidate }.forEach { obj ->
            score += (obj.confidence * 30).toInt()
        }

        // Feedback loop scoring
        result.feedbackLoopResult?.let { fb ->
            if (fb.bugDetected) score += 35
        }

        // User confirmation adjustments
        if (result.userConfirmations["glint_surface"] == true) score -= 20
        if (result.userConfirmations["emf_appliance"] == true) score -= 15
        if (result.userConfirmations["ir_device"] == true) score -= 10
        if (result.userConfirmations["wifi_smart"] == true) score -= 10
        if (result.userConfirmations["audio_heard"] == true) score += 10

        return score.coerceIn(0, 100)
    }

    fun generateFallbackEvaluation(score: Int): AIEvaluation {
        val threatLevel = when {
            score >= 70 -> "HIGH"
            score >= 40 -> "MEDIUM"
            score >= 20 -> "LOW"
            else -> "NONE"
        }

        val summary = when (threatLevel) {
            "HIGH" -> "Multiple sensors detected anomalies consistent with hidden devices. Immediate investigation recommended."
            "MEDIUM" -> "Some sensor readings are above normal. Possible hidden device present but not confirmed."
            "LOW" -> "Minor anomalies detected. Likely false positive but worth a manual check."
            else -> "All sensors within normal range. Room appears safe."
        }

        return AIEvaluation(
            cameraThreatLevel = threatLevel,
            cameraThreatScore = score,
            microphoneThreatLevel = if (score > 50) "MEDIUM" else "NONE",
            microphoneThreatScore = score / 2,
            overallThreatLevel = threatLevel,
            overallSafetyScore = 100 - score,
            summary = summary,
            recommendation = when (threatLevel) {
                "HIGH" -> "Do not disturb the device. Photograph the area and contact hotel management or local authorities."
                "MEDIUM" -> "Manually inspect the flagged area. Check for small holes, unusual objects, or misplaced items."
                "LOW" -> "Room appears mostly safe. Consider a manual visual sweep of obvious hiding spots."
                else -> "Room is safe. Enjoy your stay!"
            },
            falsePositiveRisk = when (threatLevel) {
                "HIGH" -> "LOW"
                "MEDIUM" -> "MEDIUM"
                else -> "HIGH"
            },
            confidenceStatement = "Local sensor analysis (AI unavailable)"
        )
    }
}
