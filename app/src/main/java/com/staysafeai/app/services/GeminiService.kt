package com.staysafeai.app.services

import com.staysafeai.app.BuildConfig
import com.staysafeai.app.models.*
import com.staysafeai.app.analyzers.ThreatScoreEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

// Gemini-powered AI evaluation — replaces Gemini
class GeminiService {

    companion object {
        private const val API_KEY  = BuildConfig.GEMINI_API_KEY
        private const val MODEL    = "gemini-1.5-flash"
        private const val ENDPOINT =
            "https://generativelanguage.googleapis.com/v1beta/models/$MODEL:generateContent?key=$API_KEY"
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
        .readTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
        .build()

    suspend fun evaluate(scanResult: ScanResult): AIEvaluation = withContext(Dispatchers.IO) {
        try {
            val prompt = buildPrompt(scanResult)

            val body = JSONObject().apply {
                put("contents", JSONArray().apply {
                    put(JSONObject().apply {
                        put("parts", JSONArray().apply {
                            put(JSONObject().apply { put("text", prompt) })
                        })
                    })
                })
                put("generationConfig", JSONObject().apply {
                    put("temperature", 0.1)
                    put("maxOutputTokens", 1000)
                })
            }

            val request = Request.Builder()
                .url(ENDPOINT)
                .post(body.toString().toRequestBody("application/json".toMediaType()))
                .build()

            val response = client.newCall(request).execute()
            val responseText = response.body?.string() ?: throw Exception("Empty response")
            val json = JSONObject(responseText)

            val text = json
                .getJSONArray("candidates")
                .getJSONObject(0)
                .getJSONObject("content")
                .getJSONArray("parts")
                .getJSONObject(0)
                .getString("text")

            parseResponse(text)

        } catch (e: Exception) {
            // Fallback if Gemini unavailable
            ThreatScoreEngine().generateFallbackEvaluation(scanResult.localThreatScore)
        }
    }

    private fun buildPrompt(result: ScanResult): String {
        val sb = StringBuilder()
        sb.appendLine("You are a professional security expert analyzing a hotel room for hidden cameras and microphones.")
        sb.appendLine("Analyze the sensor data below and respond ONLY with a valid JSON object. No markdown, no backticks, no extra text.")
        sb.appendLine()
        sb.appendLine("=== SENSOR DATA ===")

        result.emfData?.let {
            sb.appendLine("EMF: peak=${it.peakMagnitude}µT, cameraFreq=${it.hasCameraFrequency}, spikes=${it.spikeCount}")
        }
        if (result.irSources.isNotEmpty())
            sb.appendLine("IR sources: ${result.irSources.size}, cameraPattern=${result.irAnalysis?.hasCameraPattern}")

        val suspWifi = result.wifiDevices.filter { it.isSuspicious }
        if (suspWifi.isNotEmpty())
            sb.appendLine("Suspicious WiFi: ${suspWifi.map { "${it.vendorName}(${it.threatScore})" }}")

        if (result.rogueDevices.isNotEmpty()) {
            val d = result.rogueDevices.first()
            sb.appendLine("Rogue device: IP=${d.ipAddress}, RTSP=${d.hasRTSP}, ONVIF=${d.hasONVIF}, risk=${d.riskScore}")
            sb.appendLine("Evidence: ${d.riskReason.take(3).joinToString("; ")}")
        }

        val suspBt = result.bluetoothDevices.filter { it.isSuspicious }
        if (suspBt.isNotEmpty())
            sb.appendLine("Suspicious BT: ${suspBt.map { "${it.name}(${it.distanceEstimate}m)" }}")

        if (result.lensGlints.isNotEmpty()) {
            val g = result.lensGlints.first()
            sb.appendLine("Lens glint: ${g.classification}, confidence=${g.confidence}")
        }

        result.audioFrequencies?.let {
            sb.appendLine("Audio: score=${it.threatScore}, capacitorWhine=${it.hasCapacitorWhine}")
        }

        result.ultrasonicResult?.let {
            if (it.detected) sb.appendLine("Ultrasonic: score=${it.threatScore}, beacon=${it.hasBeaconSignal}")
        }

        val staticCandidates = result.staticObjects.filter { it.isCameraCandidate }
        if (staticCandidates.isNotEmpty())
            sb.appendLine("Static camera candidates: ${staticCandidates.size}, confidence=${staticCandidates.first().confidence}")

        result.feedbackLoopResult?.let {
            sb.appendLine("Feedback loop: bugDetected=${it.bugDetected}")
        }

        sb.appendLine("Local threat score: ${result.localThreatScore}/100")

        sb.appendLine()
        sb.appendLine("Respond with ONLY this JSON, no other text:")
        sb.appendLine("""{"cameraThreatLevel":"NONE","cameraThreatScore":0,"microphoneThreatLevel":"NONE","microphoneThreatScore":0,"overallThreatLevel":"NONE","overallSafetyScore":100,"locationHint":"","hidingSpotSuggestions":[],"summary":"","recommendation":"","falsePositiveRisk":"LOW","confidenceStatement":""}""")
        sb.appendLine("Threat levels must be one of: NONE, LOW, MEDIUM, HIGH")

        return sb.toString()
    }

    private fun parseResponse(text: String): AIEvaluation {
        return try {
            val clean = text.replace("```json", "").replace("```", "").trim()
            val json  = JSONObject(clean)
            AIEvaluation(
                cameraThreatLevel     = json.optString("cameraThreatLevel", "NONE"),
                cameraThreatScore     = json.optInt("cameraThreatScore", 0),
                microphoneThreatLevel = json.optString("microphoneThreatLevel", "NONE"),
                microphoneThreatScore = json.optInt("microphoneThreatScore", 0),
                overallThreatLevel    = json.optString("overallThreatLevel", "NONE"),
                overallSafetyScore    = json.optInt("overallSafetyScore", 100),
                locationHint          = json.optString("locationHint", ""),
                hidingSpotSuggestions = json.optJSONArray("hidingSpotSuggestions")?.let { arr ->
                    (0 until arr.length()).map { arr.getString(it) }
                } ?: emptyList(),
                summary               = json.optString("summary", ""),
                recommendation        = json.optString("recommendation", ""),
                falsePositiveRisk     = json.optString("falsePositiveRisk", "MEDIUM"),
                confidenceStatement   = json.optString("confidenceStatement", "Gemini AI")
            )
        } catch (e: Exception) {
            ThreatScoreEngine().generateFallbackEvaluation(50)
        }
    }
}
