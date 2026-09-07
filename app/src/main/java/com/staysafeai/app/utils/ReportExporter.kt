package com.staysafeai.app.utils

import android.content.Context
import android.graphics.*
import android.graphics.pdf.PdfDocument
import com.staysafeai.app.models.AIEvaluation
import com.staysafeai.app.models.ScanResult
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

object ReportExporter {

    fun exportToPDF(context: Context, scan: ScanResult, ai: AIEvaluation): File {
        val doc = PdfDocument()
        val pageInfo = PdfDocument.PageInfo.Builder(595, 842, 1).create() // A4
        val page = doc.startPage(pageInfo)
        val canvas = page.canvas

        val titlePaint = Paint().apply {
            textSize = 22f; isFakeBoldText = true; color = Color.BLACK
        }
        val headingPaint = Paint().apply {
            textSize = 14f; isFakeBoldText = true; color = Color.parseColor("#B00020")
        }
        val bodyPaint = Paint().apply {
            textSize = 11f; color = Color.DKGRAY
        }
        val smallPaint = Paint().apply {
            textSize = 9f; color = Color.GRAY
        }

        var y = 60f
        val margin = 40f
        val lineH = 18f

        fun line(text: String, paint: Paint, indent: Float = 0f) {
            canvas.drawText(text, margin + indent, y, paint)
            y += lineH
        }

        fun gap(h: Float = 8f) { y += h }

        fun divider() {
            val divPaint = Paint().apply { color = Color.LTGRAY; strokeWidth = 1f }
            canvas.drawLine(margin, y, 595 - margin, y, divPaint)
            gap(8f)
        }

        // Header
        val headerPaint = Paint().apply { color = Color.parseColor("#1A1A2E"); style = Paint.Style.FILL }
        canvas.drawRect(0f, 0f, 595f, 80f, headerPaint)
        val hPaint = Paint().apply { textSize = 24f; isFakeBoldText = true; color = Color.WHITE }
        canvas.drawText("CamGuard — Room Scan Report", margin, 50f, hPaint)
        y = 100f

        // Date & threat
        val sdf = SimpleDateFormat("MMMM dd, yyyy  HH:mm", Locale.getDefault())
        line("Scan Date: ${sdf.format(Date(scan.scanTimestamp))}", bodyPaint)
        gap()
        divider()

        // Threat summary
        line("OVERALL THREAT: ${ai.overallThreatLevel}", headingPaint)
        line("Room Safety Score: ${ai.overallSafetyScore}/100", bodyPaint)
        line("Camera Threat: ${ai.cameraThreatLevel} (${ai.cameraThreatScore}/100)", bodyPaint)
        line("Microphone Threat: ${ai.microphoneThreatLevel} (${ai.microphoneThreatScore}/100)", bodyPaint)
        gap(); divider()

        // AI Summary
        line("AI EVALUATION", headingPaint)
        wrapText(canvas, ai.summary, margin, y, 595 - margin * 2, bodyPaint).also { y = it }
        gap()
        line("Recommendation:", Paint().apply { textSize = 11f; isFakeBoldText = true; color = Color.BLACK })
        wrapText(canvas, ai.recommendation, margin, y, 595 - margin * 2, bodyPaint).also { y = it }
        gap()
        if (ai.locationHint.isNotEmpty()) {
            line("Location: ${ai.locationHint}", bodyPaint)
        }
        if (ai.hidingSpotSuggestions.isNotEmpty()) {
            line("Check: ${ai.hidingSpotSuggestions.joinToString(", ")}", bodyPaint)
        }
        gap(); divider()

        // Sensor readings
        line("SENSOR READINGS", headingPaint)
        line("EMF Peak:       ${scan.emfData?.peakMagnitude?.toInt() ?: 0} µT  (normal: 0.1–0.3 µT)", bodyPaint)
        line("IR Sources:     ${scan.irSources.size}  (point sources: ${scan.irSources.count { it.type.name == "POINT_SOURCE" }})", bodyPaint)
        line("Wi-Fi Devices:  ${scan.wifiDevices.size}  (suspicious: ${scan.wifiDevices.count { it.isSuspicious }})", bodyPaint)
        line("BT Devices:     ${scan.bluetoothDevices.size}  (suspicious: ${scan.bluetoothDevices.count { it.isSuspicious }})", bodyPaint)
        line("Lens Glints:    ${scan.lensGlints.size}", bodyPaint)
        line("Audio Score:    ${scan.audioFrequencies?.threatScore ?: 0}/100", bodyPaint)
        line("Rogue Devices:  ${scan.rogueDevices.size}  (RTSP: ${scan.rogueDevices.count { it.hasRTSP }}, ONVIF: ${scan.rogueDevices.count { it.hasONVIF }})", bodyPaint)
        line("Ultrasonic:     ${if ((scan.ultrasonicResult?.threatScore ?: 0) > 20) "ANOMALY DETECTED (${scan.ultrasonicResult?.threatScore}/100)" else "Clear"}", bodyPaint)
        line("Static Objects: ${scan.staticObjects.count { it.isCameraCandidate }} camera candidate(s)", bodyPaint)
        line("Feedback Loop:  ${if (scan.feedbackLoopResult?.bugDetected == true) "BUG CONFIRMED" else "Clear"}", bodyPaint)
        gap(); divider()

        // Suspicious devices
        val suspWifi = scan.wifiDevices.filter { it.isSuspicious }
        val suspBt = scan.bluetoothDevices.filter { it.isSuspicious }
        val highRogueDevices = scan.rogueDevices.filter { it.riskScore > 30 }
        if (suspWifi.isNotEmpty() || suspBt.isNotEmpty() || highRogueDevices.isNotEmpty()) {
            line("SUSPICIOUS DEVICES", headingPaint)
            suspWifi.forEach { dev ->
                line("Wi-Fi: ${dev.ssid}  MAC: ${dev.macAddress}", bodyPaint, 10f)
                line("Vendor: ${dev.vendorName}  Threat: ${dev.threatScore}/100", smallPaint, 20f)
                gap(4f)
            }
            suspBt.forEach { dev ->
                line("BT: ${dev.name}  MAC: ${dev.macAddress}", bodyPaint, 10f)
                line("Distance: ~${String.format("%.1f", dev.distanceEstimate)}m  ${dev.vendorHint ?: ""}", smallPaint, 20f)
                gap(4f)
            }
            highRogueDevices.forEach { dev ->
                line("Network: ${dev.ipAddress}  ${dev.vendorName}", bodyPaint, 10f)
                line("RTSP=${dev.hasRTSP}  ONVIF=${dev.hasONVIF}  Risk: ${dev.riskScore}/100", smallPaint, 20f)
                dev.riskReason.take(2).forEach { reason -> line("• $reason", smallPaint, 30f); gap(2f) }
                gap(4f)
            }
            gap(); divider()
        }

        // Footer
        val footerPaint = Paint().apply { color = Color.GRAY; textSize = 9f }
        canvas.drawText(
            "Generated by CamGuard — Advanced Hidden Camera & Bug Detector",
            margin, 820f, footerPaint
        )
        canvas.drawText("False positive risk: ${ai.falsePositiveRisk}  |  ${ai.confidenceStatement}", margin, 832f, footerPaint)

        doc.finishPage(page)

        val file = File(context.getExternalFilesDir(null), "CamGuard_Report_${System.currentTimeMillis()}.pdf")
        doc.writeTo(file.outputStream())
        doc.close()
        return file
    }

    // Word-wrap helper
    private fun wrapText(
        canvas: Canvas, text: String,
        x: Float, startY: Float, maxWidth: Float,
        paint: Paint
    ): Float {
        var y = startY
        val words = text.split(" ")
        var line = ""
        for (word in words) {
            val test = if (line.isEmpty()) word else "$line $word"
            if (paint.measureText(test) > maxWidth) {
                canvas.drawText(line, x, y, paint)
                y += 16f
                line = word
            } else {
                line = test
            }
        }
        if (line.isNotEmpty()) { canvas.drawText(line, x, y, paint); y += 16f }
        return y
    }
}
