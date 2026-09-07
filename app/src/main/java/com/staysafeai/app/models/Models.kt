package com.staysafeai.app.models

import android.os.Parcelable
import kotlinx.parcelize.Parcelize

// ─── EMF ─────────────────────────────────────────────────────────────────────

data class EMFData(
    val peakMagnitude: Float = 0f,
    val avgMagnitude: Float = 0f,
    val baseline: Float = 0f,
    val threatLevel: String = "NORMAL",
    val detectedFrequencies: List<Double> = emptyList(),
    val hasCameraFrequency: Boolean = false,
    val dominantAxis: String = "",
    val spikeCount: Int = 0,
    val spikes: List<Float> = emptyList(),
    val readingCount: Int = 0,
    val isAnomalous: Boolean = false
)

// ─── IR ──────────────────────────────────────────────────────────────────────

data class IRSource(
    val centerX: Float,
    val centerY: Float,
    val size: Int,
    val maxBrightness: Int,
    val circularity: Float,
    var type: Type,
    var detectionCount: Int,
    val firstSeenMs: Long,
    var lastSeenMs: Long,
    var pulseFrequency: Double = 0.0,
    var isPulsing: Boolean = false
) {
    enum class Type { POINT_SOURCE, DIFFUSE, UNKNOWN }
}

data class IRAnalysis(
    val totalSources: Int = 0,
    val pointSourceCount: Int = 0,
    val diffuseSourceCount: Int = 0,
    val cameraSuspectCount: Int = 0,
    val screenSuspectCount: Int = 0,
    val cameraConfidence: Float = 0f,
    val dominantSource: IRSource? = null,
    val hasCameraPattern: Boolean = false,
    val hasScreenPattern: Boolean = false
)

// ─── Wi-Fi ───────────────────────────────────────────────────────────────────

data class WifiDevice(
    val ssid: String,
    val macAddress: String,
    val signalStrength: Int,
    val vendorName: String,
    val isSuspicious: Boolean,
    val ipAddress: String?,
    val openPorts: List<Int>,
    val frequency: Int,
    val capabilities: String,
    val threatScore: Int
)

// ─── Bluetooth ───────────────────────────────────────────────────────────────

data class BluetoothDeviceInfo(
    val name: String,
    val macAddress: String,
    var rssi: Int,
    val deviceType: String,
    val isSuspicious: Boolean,
    val threatScore: Int,
    val vendorHint: String?,
    val isNameless: Boolean,
    var distanceEstimate: Float,
    val txPower: Int = -59
)

// ─── Lens Glint ──────────────────────────────────────────────────────────────

data class LensGlint(
    val centerX: Float,
    val centerY: Float,
    val size: Int,
    val brightness: Int,
    val circularity: Float,
    val aspectRatio: Float,
    val hasDoubleReflection: Boolean,
    val classification: Classification,
    val isLikelySurface: Boolean,
    val confidence: Float
) {
    enum class Classification {
        CAMERA_LENS,
        TWO_WAY_MIRROR_CAMERA,
        SURFACE,
        UNKNOWN
    }
}

// ─── Audio ───────────────────────────────────────────────────────────────────

data class AudioAnalysis(
    val anomalies: List<FrequencyAnomaly>,
    val rhythmicPatterns: List<Double>,
    val threatScore: Int,
    val dominantFrequency: Double,
    val hasCapacitorWhine: Boolean,
    val hasLensMotor: Boolean,
    val hasGSMBuzz: Boolean,
    val hasRFBug: Boolean
)

data class FrequencyAnomaly(
    val frequency: Double,
    val magnitude: Double,
    val type: Type,
    val normalizedStrength: Double
) {
    enum class Type {
        CAPACITOR_WHINE,
        LENS_MOTOR,
        SD_CARD,
        WIFI_INTERFERENCE,
        GSM_BUZZ,
        RF_BUG,
        WIFI_BUG
    }
}

data class GSMDetectionResult(
    val isDetected: Boolean,
    val burstCount: Int,
    val isRhythmic: Boolean,
    val signalStrength: Double,
    val confidence: Double
)

data class FeedbackLoopResult(
    val bugDetected: Boolean,
    val echoCount: Int,
    val totalTests: Int,
    val confidence: Double
)

// ─── Location ────────────────────────────────────────────────────────────────

data class LocationReading(
    val x: Float,
    val y: Float,
    val signalStrength: Float,
    val timestamp: Long
)

data class TriangulationResult(
    val success: Boolean,
    val estimatedX: Float = 0f,
    val estimatedY: Float = 0f,
    val confidence: Float = 0f,
    val distanceMeters: Float = 0f,
    val directionFromUser: String = "",
    val peakStrength: Float = 0f,
    val readingCount: Int = 0,
    val message: String = ""
)

// ─── AI Evaluation ───────────────────────────────────────────────────────────

data class AIEvaluation(
    val cameraThreatLevel: String,
    val cameraThreatScore: Int,
    val microphoneThreatLevel: String,
    val microphoneThreatScore: Int,
    val overallThreatLevel: String,
    val overallSafetyScore: Int,
    val primarySignal: String = "",
    val locationHint: String = "",
    val suspectedDevice: String = "",
    val hidingSpotSuggestions: List<String> = emptyList(),
    val summary: String,
    val recommendation: String,
    val falsePositiveRisk: String,
    val confidenceStatement: String
)

// ─── Scan Result (master object) ─────────────────────────────────────────────

@Parcelize
class ScanResult : Parcelable {
    var emfData: EMFData? = null
    var irSources: List<IRSource> = emptyList()
    var irAnalysis: IRAnalysis? = null
    var wifiDevices: List<WifiDevice> = emptyList()
    var bluetoothDevices: List<BluetoothDeviceInfo> = emptyList()
    var lensGlints: List<LensGlint> = emptyList()
    var audioBaseline: DoubleArray = doubleArrayOf()
    var audioFrequencies: AudioAnalysis? = null
    var feedbackLoopResult: FeedbackLoopResult? = null
    // New scanners — replace GSMBugDetector
    var rogueDevices: List<RogueDevice> = emptyList()
    var ultrasonicResult: UltrasonicResult? = null
    var staticObjects: List<StaticObject> = emptyList()
    var userConfirmations: MutableMap<String, Boolean> = mutableMapOf()
    var localThreatScore: Int = 0
    var aiEvaluation: AIEvaluation? = null
    var scanTimestamp: Long = System.currentTimeMillis()

    // Convenience checks for ScanActivity
    fun hasHighEMF() = (emfData?.peakMagnitude ?: 0f) > 25f
    fun hasLensGlint() = lensGlints.any { it.classification != LensGlint.Classification.SURFACE }
    fun hasIRSource() = irSources.any { it.type == IRSource.Type.POINT_SOURCE }
    fun hasUnknownWifi() = wifiDevices.any { it.isSuspicious }
    fun hasAudioAnomaly() = (audioFrequencies?.threatScore ?: 0) > 20
    fun hasRogueDevice() = rogueDevices.any { it.riskScore > 40 }
    fun hasUltrasonicAnomaly() = (ultrasonicResult?.threatScore ?: 0) > 20
    fun hasStaticCameraCandidate() = staticObjects.any { it.isCameraCandidate }
    fun hasBugDetected() = feedbackLoopResult?.bugDetected == true ||
                            rogueDevices.any { it.hasCameraPort } ||
                            bluetoothDevices.any { it.isSuspicious }
}

// ─── Rogue Device (replaces GSM) ─────────────────────────────────────────────

data class RogueDevice(
    val ipAddress: String,
    val macAddress: String,
    val vendorName: String,
    val isCameraVendor: Boolean,
    val openPorts: Map<Int, String>,
    val hasCameraPort: Boolean,
    val hasRTSP: Boolean,
    val hasONVIF: Boolean,
    val riskScore: Int,
    val riskLevel: String,
    val riskReason: List<String>,
    val streamUrl: String?
)

data class RogueDeviceRisk(
    val score: Int,
    val level: String,
    val reasons: List<String>
)

// ─── Ultrasonic ──────────────────────────────────────────────────────────────

data class UltrasonicResult(
    val detected: Boolean,
    val anomalies: List<UltrasonicAnomaly> = emptyList(),
    val threatScore: Int = 0,
    val noiseFloor: Double = 0.0,
    val anomalyThreshold: Double = 0.0,
    val frameCount: Int = 0,
    val hasBeaconSignal: Boolean = false,
    val hasDeviceComm: Boolean = false,
    val hasCameraSync: Boolean = false,
    val dominantFrequency: Double? = null
)

data class UltrasonicAnomaly(
    val frequency: Double,
    val averageMagnitude: Double,
    val peakMagnitude: Double,
    val detectionFrames: Int,
    val totalFrames: Int,
    val isPeriodic: Boolean,
    val subBandType: String,
    val confidence: Float
)

// ─── Static Object Tracker ───────────────────────────────────────────────────

data class TrackedPoint(
    val initialX: Float,
    val initialY: Float,
    var currentX: Float,
    var currentY: Float,
    val firstFrameSeen: Int,
    var lastFrameSeen: Int,
    var detectionCount: Int,
    val positionHistory: MutableList<Pair<Float, Float>>,
    val brightness: Int,
    val size: Int
)

data class StaticObject(
    val centerX: Float,
    val centerY: Float,
    val isStatic: Boolean,
    val persistenceRatio: Float,
    val detectionCount: Int,
    val totalFrames: Int,
    val positionVariance: Float,
    val brightness: Int,
    val size: Int,
    val isCameraCandidate: Boolean,
    val confidence: Float
)

// ─── Community Database ───────────────────────────────────────────────────────

data class CommunityLocationSummary(
    val hotelName: String,
    val city: String,
    val totalScans: Int,
    val highThreatCount: Int,
    val mediumThreatCount: Int,
    val avgSafetyScore: Int,
    val flaggedByUsers: Int,
    val lastScanned: Long,
    val riskLevel: String   // HIGH / MEDIUM / LOW / UNKNOWN
)

data class CommunityScanRecord(
    val timestamp: Long,
    val threatLevel: String,
    val safetyScore: Int,
    val summary: String,
    val hidingSpots: List<String>,
    val hasRTSP: Boolean,
    val ultrasonicHit: Boolean,
    val month: String
)

// ─── Scan History ────────────────────────────────────────────────────────────

data class ScanHistoryItem(
    val id: String,
    val timestamp: Long,
    val location: String,
    val threatLevel: String,
    val safetyScore: Int,
    val summary: String
)
