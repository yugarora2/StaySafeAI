package com.staysafeai.app.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.SurfaceTexture
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.view.Surface
import android.view.TextureView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.staysafeai.app.analyzers.IRClusterAnalyzer
import com.staysafeai.app.analyzers.ThreatScoreEngine
import com.staysafeai.app.databinding.ActivityScanBinding
import com.staysafeai.app.managers.UserConfirmationDialog
import com.staysafeai.app.models.ScanResult
import com.staysafeai.app.scanners.*
import com.staysafeai.app.services.GeminiService
import kotlinx.coroutines.*

class ScanActivity : AppCompatActivity() {

    private lateinit var binding: ActivityScanBinding

    // Scanners
    private lateinit var emfScanner: EMFScanner
    private lateinit var irScanner: IRScanner
    private lateinit var wifiScanner: WifiScanner
    private lateinit var lensScanner: LensReflectionScanner
    private lateinit var micScanner: MicrophoneScanner
    private lateinit var btScanner: BluetoothScanner
    private lateinit var rogueDeviceAnalyzer: RogueDeviceAnalyzer
    private lateinit var ultrasonicScanner: UltrasonicScanner
    private lateinit var staticObjectTracker: StaticObjectTracker
    private lateinit var feedbackLoopTest: AudioFeedbackLoopTest
    private lateinit var sharedCamera: SharedCameraSession

    // Analyzers
    private lateinit var irAnalyzer: IRClusterAnalyzer
    private lateinit var scoreEngine: ThreatScoreEngine
    private lateinit var geminiService: GeminiService

    // Scan state
    private var scanJob: Job? = null
    private val scanResult = ScanResult()

    // Live detection counters
    private var liveIrCount = 0
    private var liveGlintCount = 0
    private var liveSuspectCount = 0

    companion object {
        private const val PERMISSION_REQUEST_CODE = 100
        private val REQUIRED_PERMISSIONS = arrayOf(
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.CAMERA,
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.BLUETOOTH_SCAN,
            Manifest.permission.BLUETOOTH_CONNECT
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityScanBinding.inflate(layoutInflater)
        setContentView(binding.root)

        initializeScanners()
        setupUI()

        if (allPermissionsGranted()) {
            startFullScan()
        } else {
            ActivityCompat.requestPermissions(this, REQUIRED_PERMISSIONS, PERMISSION_REQUEST_CODE)
        }
    }

    private fun allPermissionsGranted() = REQUIRED_PERMISSIONS.all {
        ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == PERMISSION_REQUEST_CODE) {
            if (allPermissionsGranted()) startFullScan()
            else {
                showError("Required permissions denied.")
                binding.btnCancel.postDelayed({ finish() }, 2000)
            }
        }
    }

    private fun initializeScanners() {
        emfScanner = EMFScanner(this)
        irScanner = IRScanner(this)
        wifiScanner = WifiScanner(this)
        lensScanner = LensReflectionScanner(this)
        micScanner = MicrophoneScanner()
        btScanner = BluetoothScanner(this)
        rogueDeviceAnalyzer = RogueDeviceAnalyzer(this)
        ultrasonicScanner = UltrasonicScanner()
        staticObjectTracker = StaticObjectTracker(this)
        feedbackLoopTest = AudioFeedbackLoopTest(this)
        irAnalyzer = IRClusterAnalyzer()
        scoreEngine = ThreatScoreEngine()
        geminiService = GeminiService()
        sharedCamera = SharedCameraSession(this)
    }

    private fun setupUI() {
        binding.btnCancel.setOnClickListener { cancelScan() }

        // Wire live camera preview to TextureView
        binding.textureView.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(surface: SurfaceTexture, w: Int, h: Int) {
                sharedCamera.setPreviewSurface(Surface(surface))
            }
            override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, w: Int, h: Int) {}
            override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean {
                sharedCamera.setPreviewSurface(null); return true
            }
            override fun onSurfaceTextureUpdated(surface: SurfaceTexture) {}
        }

        // Wire live detection callbacks to overlay
        sharedCamera.onIRDetected = { sources ->
            liveIrCount = sources.size
            val detections = sources.map { src ->
                DetectionOverlayView.Detection(
                    cx = src.centerX / 1280f,
                    cy = src.centerY / 720f,
                    type = DetectionOverlayView.Detection.Type.IR,
                    confidence = (src.maxBrightness / 255f).coerceIn(0f, 1f),
                    label = if (src.isPulsing) "IR Pulsing" else "IR"
                )
            }
            runOnUiThread {
                binding.tvIrCount.text = liveIrCount.toString()
                updateOverlay()
            }
            liveIrDetections = detections.toMutableList()
        }

        sharedCamera.onGlintDetected = { glints ->
            liveGlintCount = glints.size
            val detections = glints.map { g ->
                DetectionOverlayView.Detection(
                    cx = g.centerX / 1280f,
                    cy = g.centerY / 720f,
                    type = DetectionOverlayView.Detection.Type.GLINT,
                    confidence = g.confidence,
                    label = when (g.classification) {
                        com.staysafeai.app.models.LensGlint.Classification.CAMERA_LENS -> "Lens!"
                        com.staysafeai.app.models.LensGlint.Classification.TWO_WAY_MIRROR_CAMERA -> "Mirror Cam!"
                        else -> "Glint"
                    }
                )
            }
            runOnUiThread {
                binding.tvGlintCount.text = liveGlintCount.toString()
                updateOverlay()
            }
            liveGlintDetections = detections.toMutableList()
        }

        sharedCamera.onSuspectDetected = { objects ->
            liveSuspectCount = objects.size
            val detections = objects.map { obj ->
                DetectionOverlayView.Detection(
                    cx = obj.centerX / 1280f,
                    cy = obj.centerY / 720f,
                    type = DetectionOverlayView.Detection.Type.SUSPECT,
                    confidence = obj.confidence,
                    label = "Suspect"
                )
            }
            runOnUiThread {
                binding.tvSuspectCount.text = liveSuspectCount.toString()
                updateOverlay()
            }
            liveSuspectDetections = detections.toMutableList()
        }
    }

    private var liveIrDetections = mutableListOf<DetectionOverlayView.Detection>()
    private var liveGlintDetections = mutableListOf<DetectionOverlayView.Detection>()
    private var liveSuspectDetections = mutableListOf<DetectionOverlayView.Detection>()

    private fun updateOverlay() {
        val all = liveIrDetections + liveGlintDetections + liveSuspectDetections
        binding.detectionOverlay.updateDetections(all)
        // Flash red border if suspects found
        if (liveSuspectCount > 0 || liveGlintCount > 0) {
            binding.tvSuspectCount.setTextColor(android.graphics.Color.parseColor("#FF4444"))
        }
    }

    private fun startFullScan() {
        scanJob = lifecycleScope.launch {
            try {
                updatePhase("Passive Scan", 0)
                updateInstruction("Keep still — recording baseline readings")
                runPassiveScan()

                updatePhase("Active Scan", 25)
                updateInstruction("Slowly scan every corner, wall, vent & object")
                runActiveScan()

                updatePhase("Feedback Loop Test", 55)
                updateInstruction("Stay silent — playing test tone")
                runFeedbackLoopTest()

                updatePhase("User Confirmation", 75)
                updateInstruction("Answer questions about what you see")
                runUserConfirmation()

                updatePhase("AI Evaluation", 88)
                updateInstruction("AI is analyzing all findings...")
                runAIEvaluation()

                updateProgress(100)
                binding.detectionOverlay.clearDetections()
                navigateToResults()

            } catch (e: CancellationException) {
                // cancelled
            } catch (e: Exception) {
                showError(e.message ?: "Scan failed")
            }
        }
    }

    // ─── PHASE 1: Passive Scan ───────────────────────────────────────────────

    private suspend fun runPassiveScan() = coroutineScope {
        updateStatus("Scanning electromagnetic field...")
        val emfJob = async { emfScanner.scan(durationMs = 5000) }

        updateStatus("Scanning Wi-Fi for unknown devices...")
        val wifiJob = async { wifiScanner.scan() }

        updateStatus("Scanning Bluetooth for hidden bugs...")
        val btJob = async { btScanner.scan(durationMs = 5000) }

        updateStatus("Recording audio baseline...")
        val micJob = async { micScanner.recordBaseline(durationMs = 5000) }

        scanResult.emfData = emfJob.await()
        scanResult.wifiDevices = wifiJob.await()
        scanResult.bluetoothDevices = btJob.await()
        scanResult.audioBaseline = micJob.await()

        updateProgress(20)
    }

    // ─── PHASE 2: Active Scan ────────────────────────────────────────────────

    private suspend fun runActiveScan() = coroutineScope {
        // Open shared camera once for all visual scanners
        sharedCamera.open(withFlash = true)

        updateStatus("Flash ON — scanning for lens reflections...")
        val lensJob = async {
            lensScanner.scan(sharedCamera, onGlintFound = { glint ->
                updateStatus("⚡ Glint detected! Analyzing...")
            })
        }

        updateStatus("Scanning for IR illuminators...")
        val irJob = async { irScanner.scan(sharedCamera, durationMs = 6000) }

        updateStatus("Tracking persistent bright objects...")
        val staticJob = async { staticObjectTracker.track(sharedCamera) }

        updateStatus("Analyzing microphone frequencies...")
        val micJob = async { micScanner.scanForCameraFrequencies(durationMs = 6000) }
        val ultrasonicJob = async { ultrasonicScanner.scan() }

        updateStatus("Deep network scan — checking camera ports...")
        val rogueJob = async { rogueDeviceAnalyzer.analyze() }

        scanResult.lensGlints = lensJob.await()
        scanResult.irSources = irJob.await()
        scanResult.staticObjects = staticJob.await()

        val irAnalysis = irAnalyzer.analyze(scanResult.irSources)
        scanResult.irAnalysis = irAnalysis

        scanResult.audioFrequencies = micJob.await()
        scanResult.ultrasonicResult = ultrasonicJob.await()
        scanResult.rogueDevices = rogueJob.await()

        scanResult.lensGlints = crossValidateGlints(scanResult.lensGlints, scanResult.staticObjects)

        sharedCamera.close()
        updateProgress(55)
    }

    private fun crossValidateGlints(
        glints: List<com.staysafeai.app.models.LensGlint>,
        staticObjects: List<com.staysafeai.app.models.StaticObject>
    ): List<com.staysafeai.app.models.LensGlint> {
        return glints.map { glint ->
            val confirmed = staticObjects.any { obj ->
                val dx = glint.centerX - obj.centerX
                val dy = glint.centerY - obj.centerY
                kotlin.math.sqrt(dx * dx + dy * dy) < 30f && obj.isCameraCandidate
            }
            if (confirmed) glint.copy(confidence = (glint.confidence + 0.25f).coerceIn(0f, 1f))
            else glint
        }
    }

    // ─── PHASE 3: Feedback Loop Test ─────────────────────────────────────────

    private suspend fun runFeedbackLoopTest() {
        updateStatus("Please stay silent for 10 seconds...")
        val feedbackResult = feedbackLoopTest.run(
            onTonePlayed = { updateStatus("🔊 Playing 18kHz test tone...") },
            onBurstDetected = { updateStatus("⚠️ Transmission burst detected!") }
        )
        scanResult.feedbackLoopResult = feedbackResult
        updateProgress(75)
    }

    // ─── PHASE 4: User Confirmation ──────────────────────────────────────────

    private suspend fun runUserConfirmation() {
        val dialog = UserConfirmationDialog(this@ScanActivity)

        if (scanResult.hasHighEMF()) {
            scanResult.userConfirmations["emf_appliance"] = dialog.ask(
                "High electromagnetic field detected.",
                "Is there an AC unit, power socket, or large appliance nearby?"
            )
        }
        if (scanResult.hasLensGlint()) {
            val answer = dialog.ask(
                "Reflective surface detected.",
                "Is there a mirror, TV screen, glass frame, or shiny surface here?"
            )
            scanResult.userConfirmations["glint_surface"] = answer
            if (answer) scanResult.lensGlints = scanResult.lensGlints.filter { !it.isLikelySurface }
        }
        if (scanResult.hasIRSource()) {
            scanResult.userConfirmations["ir_device"] = dialog.ask(
                "Infrared light source detected.",
                "Are there smoke detectors, remote controls, or IR sensors visible?"
            )
        }
        if (scanResult.hasUnknownWifi()) {
            scanResult.userConfirmations["wifi_smart"] = dialog.ask(
                "Unknown Wi-Fi device found.",
                "Did the hotel mention smart room controls, Alexa, or Google devices?"
            )
        }
        if (scanResult.hasAudioAnomaly()) {
            scanResult.userConfirmations["audio_heard"] = dialog.ask(
                "Unusual audio frequency detected.",
                "Can you hear any faint high-pitched sound or periodic clicking?"
            )
        }
        updateProgress(88)
    }

    // ─── PHASE 5: AI Evaluation ──────────────────────────────────────────────

    private suspend fun runAIEvaluation() {
        updateStatus("AI is analyzing all signals...")
        val localScore = scoreEngine.calculate(scanResult)
        scanResult.localThreatScore = localScore
        try {
            scanResult.aiEvaluation = geminiService.evaluate(scanResult)
        } catch (e: Exception) {
            scanResult.aiEvaluation = scoreEngine.generateFallbackEvaluation(localScore)
        }
        if (scanResult.aiEvaluation?.overallThreatLevel == "HIGH") vibrateAlert()
        updateProgress(100)
    }

    // ─── Navigation ──────────────────────────────────────────────────────────

    private fun navigateToResults() {
        startActivity(Intent(this, ScanResultActivity::class.java).apply {
            putExtra("scan_result", scanResult)
        })
        finish()
    }

    // ─── UI Helpers ──────────────────────────────────────────────────────────

    private fun updatePhase(phase: String, progress: Int) {
        runOnUiThread {
            binding.tvPhase.text = phase
            binding.progressBar.progress = progress
        }
    }

    private fun updateInstruction(message: String) {
        runOnUiThread { binding.tvInstruction.text = message }
    }

    private fun updateStatus(message: String) {
        runOnUiThread { binding.tvStatus.text = message }
    }

    private fun updateProgress(progress: Int) {
        runOnUiThread {
            binding.progressBar.progress = progress
            binding.tvProgress.text = "$progress%"
        }
    }

    private fun showError(message: String) {
        runOnUiThread { binding.tvStatus.text = "Error: $message" }
    }

    private fun vibrateAlert() {
        getSystemService(Vibrator::class.java)?.vibrate(
            VibrationEffect.createWaveform(longArrayOf(0, 500, 200, 500, 200, 1000), -1)
        )
    }

    private fun cancelScan() {
        scanJob?.cancel()
        emfScanner.stop(); irScanner.stop(); micScanner.stop()
        lensScanner.stop(); ultrasonicScanner.stop(); staticObjectTracker.stop()
        if (sharedCamera.isOpen) sharedCamera.close()
        finish()
    }

    override fun onDestroy() {
        super.onDestroy()
        cancelScan()
    }
}
