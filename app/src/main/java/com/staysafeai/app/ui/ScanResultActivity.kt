package com.staysafeai.app.ui

import android.content.Intent
import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.staysafeai.app.R
import com.staysafeai.app.databinding.ActivityScanResultBinding
import com.staysafeai.app.models.AIEvaluation
import com.staysafeai.app.models.ScanResult
import com.staysafeai.app.services.CommunityDatabaseService
import com.staysafeai.app.utils.ReportExporter
import com.google.android.gms.ads.*
import kotlinx.coroutines.launch

class ScanResultActivity : AppCompatActivity() {

    private lateinit var binding: ActivityScanResultBinding
    private lateinit var scanResult: ScanResult
    private lateinit var communityDb: CommunityDatabaseService
    private var bannerAd: AdView? = null

    private val hotelName by lazy { intent.getStringExtra("hotel_name") ?: "Unknown Hotel" }
    private val city      by lazy { intent.getStringExtra("city") ?: "Unknown City" }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityScanResultBinding.inflate(layoutInflater)
        setContentView(binding.root)

        communityDb = CommunityDatabaseService()
        scanResult  = intent.getParcelableExtra("scan_result") ?: ScanResult()

        renderResults()
        setupButtons()
        loadBannerAd()
        saveToDatabase()
    }

    // ── Banner Ad ────────────────────────────────────────────────────────────

    private fun loadBannerAd() {
        bannerAd = AdView(this).apply {
            adUnitId = com.staysafeai.app.managers.AdMobManager.BANNER_AD_UNIT_ID
            setAdSize(AdSize.BANNER)
        }
        binding.bannerAdContainer.addView(bannerAd)
        bannerAd?.loadAd(AdRequest.Builder().build())
    }

    // ── Save to Firestore ────────────────────────────────────────────────────

    private fun saveToDatabase() {
        val ai = scanResult.aiEvaluation ?: return
        lifecycleScope.launch {
            try {
                communityDb.saveScanResult(
                    scan       = scanResult,
                    ai         = ai,
                    hotelName  = hotelName,
                    roomNumber = intent.getStringExtra("room_number") ?: "",
                    city       = city
                )
            } catch (e: Exception) {
                // Silent — don't interrupt user experience
            }
        }
    }

    private fun renderResults() {
        val ai = scanResult.aiEvaluation ?: return

        // ── Header threat banner ──────────────────────────────────────────────
        renderThreatBanner(ai)

        // ── Camera threat card ────────────────────────────────────────────────
        renderCameraCard(ai)

        // ── Microphone threat card ────────────────────────────────────────────
        renderMicCard(ai)

        // ── Sensor findings list ──────────────────────────────────────────────
        renderSensorFindings()

        // ── AI Evaluation block ───────────────────────────────────────────────
        renderAIBlock(ai)

        // ── Location hint ─────────────────────────────────────────────────────
        if (ai.locationHint.isNotEmpty()) {
            binding.cardLocation.visibility = View.VISIBLE
            binding.tvLocationHint.text = ai.locationHint
            if (ai.hidingSpotSuggestions.isNotEmpty()) {
                binding.tvHidingSpots.text = "Check: ${ai.hidingSpotSuggestions.joinToString(", ")}"
            }
        }

        // ── Room safety score ─────────────────────────────────────────────────
        binding.tvSafetyScore.text = "${ai.overallSafetyScore}/100"
        binding.progressSafety.progress = ai.overallSafetyScore
        binding.progressSafety.progressTintList = ContextCompat.getColorStateList(
            this,
            when {
                ai.overallSafetyScore >= 70 -> R.color.safe_green
                ai.overallSafetyScore >= 40 -> R.color.warning_orange
                else -> R.color.danger_red
            }
        )
    }

    private fun renderThreatBanner(ai: AIEvaluation) {
        val (bgColor, icon, title, subtitle) = when (ai.overallThreatLevel) {
            "HIGH" -> ThreatDisplay(
                R.color.danger_red_bg,
                "⚠️",
                "HIGH THREAT DETECTED",
                ai.summary
            )
            "MEDIUM" -> ThreatDisplay(
                R.color.warning_orange_bg,
                "⚡",
                "MEDIUM THREAT",
                ai.summary
            )
            "LOW" -> ThreatDisplay(
                R.color.caution_yellow_bg,
                "ℹ️",
                "LOW THREAT",
                ai.summary
            )
            else -> ThreatDisplay(
                R.color.safe_green_bg,
                "✅",
                "ROOM APPEARS SAFE",
                ai.summary
            )
        }

        binding.bannerThreat.setBackgroundColor(ContextCompat.getColor(this, bgColor))
        binding.tvThreatIcon.text = icon
        binding.tvThreatTitle.text = title
        binding.tvThreatSubtitle.text = subtitle
    }

    private fun renderCameraCard(ai: AIEvaluation) {
        binding.tvCameraThreatLevel.text = ai.cameraThreatLevel
        binding.tvCameraThreatScore.text = "${ai.cameraThreatScore}/100"
        binding.progressCamera.progress = ai.cameraThreatScore
        binding.tvCameraThreatLevel.setTextColor(
            ContextCompat.getColor(this, threatLevelColor(ai.cameraThreatLevel))
        )

        // Show suspected device if any
        if (ai.suspectedDevice.isNotEmpty()) {
            binding.tvSuspectedDevice.visibility = View.VISIBLE
            binding.tvSuspectedDevice.text = "Suspected: ${ai.suspectedDevice}"
        }
    }

    private fun renderMicCard(ai: AIEvaluation) {
        binding.tvMicThreatLevel.text = ai.microphoneThreatLevel
        binding.tvMicThreatScore.text = "${ai.microphoneThreatScore}/100"
        binding.progressMic.progress = ai.microphoneThreatScore
        binding.tvMicThreatLevel.setTextColor(
            ContextCompat.getColor(this, threatLevelColor(ai.microphoneThreatLevel))
        )
    }

    private fun renderSensorFindings() {
        val findings = mutableListOf<SensorFinding>()

        // EMF
        scanResult.emfData?.let { emf ->
            if (emf.isAnomalous) {
                findings.add(
                    SensorFinding(
                        icon = "📡",
                        color = if (emf.threatLevel == "DANGER") R.color.danger_red else R.color.warning_orange,
                        title = "EMF Spike — ${emf.peakMagnitude.toInt()} µT",
                        detail = "Normal background is 0.1–0.3 µT. This is ${
                            (emf.peakMagnitude / 0.2).toInt()
                        }x above baseline.${if (emf.hasCameraFrequency) " Camera frequency pattern detected." else ""}"
                    )
                )
            }
        }

        // IR
        scanResult.irAnalysis?.let { ir ->
            when {
                ir.hasCameraPattern -> {
                    findings.add(
                        SensorFinding(
                            icon = "🔴",
                            color = R.color.danger_red,
                            title = "${ir.cameraSuspectCount} Pulsing IR Source(s) — 850nm",
                            detail = "IR illuminators detected. Point-source pulsing at ~10Hz — consistent with night-vision camera pattern."
                        )
                    )
                }
                ir.pointSourceCount > 0 -> {
                    findings.add(
                        SensorFinding(
                            icon = "🟡",
                            color = R.color.warning_orange,
                            title = "${ir.pointSourceCount} IR Source(s) Detected",
                            detail = "Infrared light source found. Pulsing pattern not confirmed."
                        )
                    )
                }
                else -> {}
            }
        }

        // Wi-Fi
        val suspiciousWifi = scanResult.wifiDevices.filter { it.isSuspicious }
        if (suspiciousWifi.isNotEmpty()) {
            val top = suspiciousWifi.first()
            findings.add(
                SensorFinding(
                    icon = "📶",
                    color = R.color.danger_red,
                    title = "${suspiciousWifi.size} Suspicious Wi-Fi Device(s)",
                    detail = "Devices with hidden SSIDs or open ports (RTSP/ONVIF) detected on this network. Could be IP cameras. MAC: ${top.macAddress}."
                )
            )
        }

        // Bluetooth
        val suspiciousBt = scanResult.bluetoothDevices.filter { it.isSuspicious }
        if (suspiciousBt.isNotEmpty()) {
            val top = suspiciousBt.first()
            findings.add(
                SensorFinding(
                    icon = "🔵",
                    color = R.color.warning_orange,
                    title = "Bluetooth Bug — ${top.name}",
                    detail = "Suspicious BT device. Est. distance: ${
                        String.format("%.1f", top.distanceEstimate)
                    }m. ${top.vendorHint ?: "Unknown vendor"}."
                )
            )
        }

        // Lens
        val cameraGlints = scanResult.lensGlints.filter {
            it.classification.name.contains("CAMERA")
        }
        if (cameraGlints.isNotEmpty()) {
            val g = cameraGlints.first()
            findings.add(
                SensorFinding(
                    icon = "🔦",
                    color = R.color.danger_red,
                    title = "Lens Reflection Detected",
                    detail = "Circular glint consistent with camera lens. " +
                        if (g.hasDoubleReflection) "Double reflection detected — possible two-way mirror." else ""
                )
            )
        }

        // Audio
        scanResult.audioFrequencies?.let { audio ->
            if (audio.threatScore > 20) {
                val types = mutableListOf<String>()
                if (audio.hasCapacitorWhine) types.add("capacitor whine")
                if (audio.hasLensMotor) types.add("motor hum")
                if (audio.hasGSMBuzz) types.add("GSM buzz")
                if (audio.hasRFBug) types.add("RF signal")

                findings.add(
                    SensorFinding(
                        icon = "🎙️",
                        color = if (audio.threatScore > 50) R.color.danger_red else R.color.warning_orange,
                        title = "Audio Anomaly Detected",
                        detail = "Unusual frequencies: ${types.joinToString(", ")}. Score: ${audio.threatScore}/100."
                    )
                )
            }
        }

        // Rogue network devices (replaces GSM)
        val highRiskDevices = scanResult.rogueDevices.filter { it.riskScore > 30 }
        if (highRiskDevices.isNotEmpty()) {
            val top = highRiskDevices.first()
            val detail = buildString {
                if (top.hasRTSP)  append("RTSP stream port open — live camera feed confirmed. ")
                if (top.hasONVIF) append("ONVIF protocol detected — standard IP camera. ")
                if (top.isCameraVendor) append("Known camera manufacturer: ${top.vendorName}. ")
                append("Risk score: ${top.riskScore}/100.")
            }
            findings.add(
                SensorFinding(
                    icon = "🌐",
                    color = if (top.riskScore > 60) R.color.danger_red else R.color.warning_orange,
                    title = "Rogue IP Device — ${top.ipAddress}",
                    detail = detail
                )
            )
        }

        // Ultrasonic
        scanResult.ultrasonicResult?.let { u ->
            if (u.detected && u.threatScore > 20) {
                val types = mutableListOf<String>()
                if (u.hasBeaconSignal) types.add("ultrasonic beacon (17–18.5kHz)")
                if (u.hasDeviceComm) types.add("device communication (18.5–20kHz)")
                if (u.hasCameraSync) types.add("camera sync signal (20–22kHz)")
                findings.add(
                    SensorFinding(
                        icon = "🔉",
                        color = if (u.threatScore > 50) R.color.danger_red else R.color.warning_orange,
                        title = "Ultrasonic Signal Detected",
                        detail = "Inaudible frequency anomaly: ${types.joinToString(", ")}. Score: ${u.threatScore}/100. Dominant: ${u.dominantFrequency?.toInt() ?: "-"}Hz."
                    )
                )
            }
        }

        // Static Object Tracker
        val staticCandidates = scanResult.staticObjects.filter { it.isCameraCandidate }
        if (staticCandidates.isNotEmpty()) {
            val top = staticCandidates.first()
            findings.add(
                SensorFinding(
                    icon = "👁️",
                    color = if (top.confidence > 0.7f) R.color.danger_red else R.color.warning_orange,
                    title = "Persistent Bright Point — Camera Candidate",
                    detail = "Stationary reflective point tracked across ${top.detectionCount} frames (${(top.persistenceRatio * 100).toInt()}% persistence). " +
                        "Position variance: ${String.format("%.1f", top.positionVariance)}px. Confidence: ${(top.confidence * 100).toInt()}%."
                )
            )
        }

        // Feedback loop
        scanResult.feedbackLoopResult?.let { fb ->
            if (fb.bugDetected) {
                findings.add(
                    SensorFinding(
                        icon = "🔊",
                        color = R.color.danger_red,
                        title = "Audio Feedback Loop — Bug Confirmed",
                        detail = "18kHz test tone echoed back ${fb.echoCount}/${fb.totalTests} times — hidden microphone is actively transmitting."
                    )
                )
            }
        }

        // Render findings into RecyclerView or LinearLayout
        renderFindingsToView(findings)
    }

    private fun renderFindingsToView(findings: List<SensorFinding>) {
        binding.layoutFindings.removeAllViews()

        if (findings.isEmpty()) {
            val noFindings = layoutInflater.inflate(
                R.layout.item_finding_empty, binding.layoutFindings, false
            )
            binding.layoutFindings.addView(noFindings)
            return
        }

        findings.forEach { finding ->
            val view = layoutInflater.inflate(R.layout.item_finding, binding.layoutFindings, false)
            view.findViewById<android.widget.TextView>(R.id.tvFindingIcon).text = finding.icon
            view.findViewById<android.widget.TextView>(R.id.tvFindingTitle).text = finding.title
            view.findViewById<android.widget.TextView>(R.id.tvFindingDetail).text = finding.detail
            view.findViewById<View>(R.id.viewFindingDot).backgroundTintList =
                ContextCompat.getColorStateList(this, finding.color)
            binding.layoutFindings.addView(view)
        }
    }

    private fun renderAIBlock(ai: AIEvaluation) {
        binding.tvAISummary.text = ai.summary
        binding.tvAIRecommendation.text = "Recommendation: ${ai.recommendation}"
        binding.tvAIConfidence.text = ai.confidenceStatement
        binding.tvFalsePositiveRisk.text = "False positive risk: ${ai.falsePositiveRisk}"
    }

    private fun setupButtons() {
        val ai = scanResult.aiEvaluation

        // What to do now
        binding.btnWhatToDo.setOnClickListener {
            showWhatToDoDialog(ai?.overallThreatLevel ?: "NONE")
        }

        // Full sweep checklist
        binding.btnSweepChecklist.setOnClickListener {
            startActivity(Intent(this, MirrorTestActivity::class.java))
        }

        // Report it
        binding.btnReport.setOnClickListener {
            shareReport()
        }

        // Find device (location tracking)
        if ((scanResult.localThreatScore) > 30) {
            binding.btnFindDevice.visibility = View.VISIBLE
            binding.btnFindDevice.setOnClickListener {
                startActivity(Intent(this, AROverlayActivity::class.java).apply {
                    putExtra("scan_result", scanResult)
                })
            }
        }

        // Export PDF
        binding.btnExportPDF.setOnClickListener {
            exportPDF()
        }

        // Rescan
        binding.btnRescan.setOnClickListener {
            finish()
            startActivity(Intent(this, ScanActivity::class.java))
        }

        // Back to home
        binding.btnHome.setOnClickListener {
            startActivity(Intent(this, MainActivity::class.java))
            finishAffinity()
        }
    }

    private fun showWhatToDoDialog(threatLevel: String) {
        val steps = when (threatLevel) {
            "HIGH" -> arrayOf(
                "1. Do NOT touch or move the suspected device",
                "2. Photograph the area with your phone",
                "3. Note the exact location (wall, object)",
                "4. Contact hotel management immediately",
                "5. Request a different room",
                "6. If refused, contact local police",
                "7. File a complaint with hotel chain HQ",
                "8. Report to local consumer protection authority"
            )
            "MEDIUM" -> arrayOf(
                "1. Visually inspect the flagged area",
                "2. Check for small holes or misplaced objects",
                "3. Use the mirror test (fingernail gap test)",
                "4. Check smoke detectors for extra weight",
                "5. Unplug any unknown USB chargers",
                "6. Request hotel staff check the room",
                "7. Consider requesting a different room"
            )
            else -> arrayOf(
                "1. Room appears safe from sensor readings",
                "2. Do a quick visual sweep of obvious spots",
                "3. Check smoke detectors and clock radios",
                "4. Ensure mirrors pass the fingernail test",
                "5. Unplug unknown USB chargers"
            )
        }

        android.app.AlertDialog.Builder(this)
            .setTitle("What To Do Now")
            .setItems(steps, null)
            .setPositiveButton("OK", null)
            .show()
    }

    private fun shareReport() {
        val ai = scanResult.aiEvaluation ?: return
        val report = buildTextReport(ai)

        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, "CamGuard Room Scan Report")
            putExtra(Intent.EXTRA_TEXT, report)
        }
        startActivity(Intent.createChooser(intent, "Share Report"))
    }

    private fun buildTextReport(ai: AIEvaluation): String {
        return """
CamGuard Room Scan Report
=========================
Date: ${java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.getDefault()).format(java.util.Date(scanResult.scanTimestamp))}

OVERALL THREAT: ${ai.overallThreatLevel}
Safety Score: ${ai.overallSafetyScore}/100

CAMERA THREAT: ${ai.cameraThreatLevel} (${ai.cameraThreatScore}/100)
MICROPHONE THREAT: ${ai.microphoneThreatLevel} (${ai.microphoneThreatScore}/100)

SUMMARY:
${ai.summary}

RECOMMENDATION:
${ai.recommendation}

LOCATION HINT:
${ai.locationHint.ifEmpty { "No specific location identified" }}

HIDING SPOTS TO CHECK:
${ai.hidingSpotSuggestions.joinToString("\n") { "• $it" }}

SENSOR READINGS:
• EMF Peak: ${scanResult.emfData?.peakMagnitude?.toInt() ?: 0} µT
• IR Sources: ${scanResult.irSources.size}
• Wi-Fi Devices: ${scanResult.wifiDevices.size} (${scanResult.wifiDevices.count { it.isSuspicious }} suspicious)
• Bluetooth Devices: ${scanResult.bluetoothDevices.size} (${scanResult.bluetoothDevices.count { it.isSuspicious }} suspicious)
• Lens Glints: ${scanResult.lensGlints.size}
• Audio Score: ${scanResult.audioFrequencies?.threatScore ?: 0}/100
• Network Rogue Devices: ${if (scanResult.rogueDevices.isNotEmpty()) "DETECTED" else "None"}
• Feedback Loop: ${if (scanResult.feedbackLoopResult?.bugDetected == true) "BUG CONFIRMED" else "Clear"}

Scanned with CamGuard — Advanced Hidden Camera & Bug Detector
        """.trimIndent()
    }

    private fun exportPDF() {
        android.widget.Toast.makeText(this, "Generating PDF report...", android.widget.Toast.LENGTH_SHORT).show()
        // PDF export via ReportExporter util
        val ai = scanResult.aiEvaluation ?: return
        try {
            val pdfFile = ReportExporter.exportToPDF(this, scanResult, ai)
            val uri = androidx.core.content.FileProvider.getUriForFile(
                this, "${packageName}.provider", pdfFile
            )
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/pdf")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(intent)
        } catch (e: Exception) {
            android.widget.Toast.makeText(this, "PDF export failed: ${e.message}", android.widget.Toast.LENGTH_LONG).show()
        }
    }

    private fun threatLevelColor(level: String) = when (level) {
        "HIGH" -> R.color.danger_red
        "MEDIUM" -> R.color.warning_orange
        "LOW" -> R.color.caution_yellow
        else -> R.color.safe_green
    }

    // Data class for findings
    data class SensorFinding(
        val icon: String,
        val color: Int,
        val title: String,
        val detail: String
    )

    data class ThreatDisplay(
        val bgColor: Int,
        val icon: String,
        val title: String,
        val subtitle: String
    )
}
