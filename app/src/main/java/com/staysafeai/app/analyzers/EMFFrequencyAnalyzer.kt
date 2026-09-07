package com.staysafeai.app.analyzers

import com.staysafeai.app.models.EMFData

class EMFFrequencyAnalyzer {

    companion object {
        // Screen refresh rates create 50/60Hz EMF — NOT a camera
        val SCREEN_FREQUENCIES = listOf(50.0, 60.0, 100.0, 120.0)
        // Camera DC power circuits have irregular or higher-order frequencies
        val CAMERA_INDICATOR_FREQUENCIES = listOf(217.0, 433.0, 868.0)
    }

    fun isScreenEMF(emfData: EMFData): Boolean {
        if (emfData.detectedFrequencies.isEmpty()) return false
        val dominantFreq = emfData.detectedFrequencies.firstOrNull() ?: return false
        return SCREEN_FREQUENCIES.any { target -> Math.abs(dominantFreq - target) < 3.0 }
    }

    fun isCameraEMF(emfData: EMFData): Boolean {
        return emfData.detectedFrequencies.any { freq ->
            CAMERA_INDICATOR_FREQUENCIES.any { target -> Math.abs(freq - target) < 8.0 }
        }
    }

    fun classify(emfData: EMFData): String {
        return when {
            isCameraEMF(emfData) -> "CAMERA_WIRING"
            isScreenEMF(emfData) -> "SCREEN_DEVICE"
            emfData.peakMagnitude > 50 -> "HIGH_POWER_SOURCE"
            emfData.peakMagnitude > 10 -> "UNKNOWN_DEVICE"
            else -> "BACKGROUND"
        }
    }
}
