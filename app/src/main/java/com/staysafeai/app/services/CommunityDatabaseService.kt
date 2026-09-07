package com.staysafeai.app.services

import com.staysafeai.app.models.AIEvaluation
import com.staysafeai.app.models.CommunityScanRecord
import com.staysafeai.app.models.CommunityLocationSummary
import com.staysafeai.app.models.ScanResult
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query
import kotlinx.coroutines.tasks.await
import java.text.SimpleDateFormat
import java.util.*

class CommunityDatabaseService {

    private val db   = FirebaseFirestore.getInstance()
    private val auth = FirebaseAuth.getInstance()

    // ── Save scan result (user's own + community) ─────────────────────────────

    suspend fun saveScanResult(
        scan: ScanResult,
        ai: AIEvaluation,
        hotelName: String,
        roomNumber: String,
        city: String
    ) {
        val uid = auth.currentUser?.uid ?: return
        val locationKey = buildLocationKey(hotelName, city)
        val timestamp   = System.currentTimeMillis()

        // 1. Save to user's personal history
        val userRecord = hashMapOf(
            "timestamp"       to timestamp,
            "hotelName"       to hotelName,
            "roomNumber"      to roomNumber,
            "city"            to city,
            "locationKey"     to locationKey,
            "threatLevel"     to (ai.overallThreatLevel),
            "safetyScore"     to ai.overallSafetyScore,
            "cameraThreat"    to ai.cameraThreatLevel,
            "micThreat"       to ai.microphoneThreatLevel,
            "summary"         to ai.summary,
            "recommendation"  to ai.recommendation,
            "locationHint"    to ai.locationHint,
            "hidingSpots"     to ai.hidingSpotSuggestions,
            // Sensor highlights (no PII)
            "emfPeak"         to (scan.emfData?.peakMagnitude ?: 0f),
            "irSources"       to scan.irSources.size,
            "rogueDevices"    to scan.rogueDevices.size,
            "lensGlints"      to scan.lensGlints.size,
            "audioScore"      to (scan.audioFrequencies?.threatScore ?: 0),
            "ultrasonicHit"   to (scan.ultrasonicResult?.detected ?: false),
            "staticCandidates" to scan.staticObjects.count { it.isCameraCandidate }
        )

        db.collection("users")
            .document(uid)
            .collection("scans")
            .add(userRecord)
            .await()

        // 2. Save anonymised record to community database
        // Never store user ID in community record — fully anonymous
        val communityRecord = hashMapOf(
            "timestamp"      to timestamp,
            "locationKey"    to locationKey,
            "hotelName"      to hotelName,
            "city"           to city,
            // Room number stored only as hash — privacy
            "roomHash"       to roomNumber.hashCode().toString(),
            "threatLevel"    to ai.overallThreatLevel,
            "safetyScore"    to ai.overallSafetyScore,
            "summary"        to ai.summary,
            "hidingSpots"    to ai.hidingSpotSuggestions,
            "emfPeak"        to (scan.emfData?.peakMagnitude ?: 0f),
            "rogueDevices"   to scan.rogueDevices.size,
            "hasRTSP"        to scan.rogueDevices.any { it.hasRTSP },
            "ultrasonicHit"  to (scan.ultrasonicResult?.detected ?: false),
            "month"          to SimpleDateFormat("yyyy-MM", Locale.getDefault()).format(Date(timestamp))
        )

        db.collection("community_scans")
            .add(communityRecord)
            .await()

        // 3. Update location summary (aggregated stats for the hotel)
        updateLocationSummary(locationKey, hotelName, city, ai)
    }

    // ── Update aggregated location summary ────────────────────────────────────

    private suspend fun updateLocationSummary(
        locationKey: String,
        hotelName: String,
        city: String,
        ai: AIEvaluation
    ) {
        val ref = db.collection("location_summaries").document(locationKey)
        val doc = ref.get().await()

        if (!doc.exists()) {
            // First scan ever at this location
            ref.set(
                hashMapOf(
                    "hotelName"       to hotelName,
                    "city"            to city,
                    "locationKey"     to locationKey,
                    "totalScans"      to 1,
                    "highThreatCount" to if (ai.overallThreatLevel == "HIGH") 1 else 0,
                    "mediumThreatCount" to if (ai.overallThreatLevel == "MEDIUM") 1 else 0,
                    "avgSafetyScore"  to ai.overallSafetyScore.toDouble(),
                    "lastScanned"     to System.currentTimeMillis(),
                    "flaggedByUsers"  to if (ai.overallThreatLevel in listOf("HIGH","MEDIUM")) 1 else 0
                )
            ).await()
        } else {
            // Update running averages
            val currentTotal = doc.getLong("totalScans") ?: 0
            val currentAvg   = doc.getDouble("avgSafetyScore") ?: 100.0
            val newAvg = ((currentAvg * currentTotal) + ai.overallSafetyScore) / (currentTotal + 1)

            val updates = hashMapOf<String, Any>(
                "totalScans"    to FieldValue.increment(1),
                "avgSafetyScore" to newAvg,
                "lastScanned"   to System.currentTimeMillis()
            )
            if (ai.overallThreatLevel == "HIGH") {
                updates["highThreatCount"] = FieldValue.increment(1)
                updates["flaggedByUsers"]  = FieldValue.increment(1)
            }
            if (ai.overallThreatLevel == "MEDIUM") {
                updates["mediumThreatCount"] = FieldValue.increment(1)
                updates["flaggedByUsers"]    = FieldValue.increment(1)
            }
            ref.update(updates).await()
        }
    }

    // ── Get community summary for a hotel ────────────────────────────────────

    suspend fun getCommunityDataForLocation(
        hotelName: String,
        city: String
    ): CommunityLocationSummary? {
        return try {
            val locationKey = buildLocationKey(hotelName, city)
            val doc = db.collection("location_summaries")
                .document(locationKey)
                .get().await()

            if (!doc.exists()) return null

            CommunityLocationSummary(
                hotelName        = doc.getString("hotelName") ?: hotelName,
                city             = doc.getString("city") ?: city,
                totalScans       = doc.getLong("totalScans")?.toInt() ?: 0,
                highThreatCount  = doc.getLong("highThreatCount")?.toInt() ?: 0,
                mediumThreatCount = doc.getLong("mediumThreatCount")?.toInt() ?: 0,
                avgSafetyScore   = doc.getDouble("avgSafetyScore")?.toInt() ?: 100,
                flaggedByUsers   = doc.getLong("flaggedByUsers")?.toInt() ?: 0,
                lastScanned      = doc.getLong("lastScanned") ?: 0L,
                riskLevel        = computeLocationRisk(
                    doc.getLong("highThreatCount")?.toInt() ?: 0,
                    doc.getLong("totalScans")?.toInt() ?: 1
                )
            )
        } catch (e: Exception) { null }
    }

    // ── Get recent community scans for a hotel ────────────────────────────────

    suspend fun getRecentScansForLocation(
        hotelName: String,
        city: String,
        limit: Int = 10
    ): List<CommunityScanRecord> {
        return try {
            val locationKey = buildLocationKey(hotelName, city)
            val docs = db.collection("community_scans")
                .whereEqualTo("locationKey", locationKey)
                .orderBy("timestamp", Query.Direction.DESCENDING)
                .limit(limit.toLong())
                .get().await()

            docs.documents.mapNotNull { doc ->
                CommunityScanRecord(
                    timestamp    = doc.getLong("timestamp") ?: 0L,
                    threatLevel  = doc.getString("threatLevel") ?: "NONE",
                    safetyScore  = doc.getLong("safetyScore")?.toInt() ?: 100,
                    summary      = doc.getString("summary") ?: "",
                    hidingSpots  = (doc.get("hidingSpots") as? List<*>)
                        ?.filterIsInstance<String>() ?: emptyList(),
                    hasRTSP      = doc.getBoolean("hasRTSP") ?: false,
                    ultrasonicHit = doc.getBoolean("ultrasonicHit") ?: false,
                    month        = doc.getString("month") ?: ""
                )
            }
        } catch (e: Exception) { emptyList() }
    }

    // ── Get user's own scan history ───────────────────────────────────────────

    suspend fun getUserScanHistory(limit: Int = 50): List<com.staysafeai.app.models.ScanHistoryItem> {
        val uid = auth.currentUser?.uid ?: return emptyList()
        return try {
            val docs = db.collection("users")
                .document(uid)
                .collection("scans")
                .orderBy("timestamp", Query.Direction.DESCENDING)
                .limit(limit.toLong())
                .get().await()

            docs.documents.mapNotNull { doc ->
                com.staysafeai.app.models.ScanHistoryItem(
                    id          = doc.id,
                    timestamp   = doc.getLong("timestamp") ?: 0L,
                    location    = "${doc.getString("hotelName") ?: "Unknown"}, ${doc.getString("city") ?: ""}",
                    threatLevel = doc.getString("threatLevel") ?: "NONE",
                    safetyScore = doc.getLong("safetyScore")?.toInt() ?: 100,
                    summary     = doc.getString("summary") ?: ""
                )
            }
        } catch (e: Exception) { emptyList() }
    }

    // ── Search hotels by name ─────────────────────────────────────────────────

    suspend fun searchLocations(query: String): List<CommunityLocationSummary> {
        return try {
            val docs = db.collection("location_summaries")
                .orderBy("hotelName")
                .startAt(query)
                .endAt(query + "\uf8ff")
                .limit(20)
                .get().await()

            docs.documents.mapNotNull { doc ->
                CommunityLocationSummary(
                    hotelName         = doc.getString("hotelName") ?: "",
                    city              = doc.getString("city") ?: "",
                    totalScans        = doc.getLong("totalScans")?.toInt() ?: 0,
                    highThreatCount   = doc.getLong("highThreatCount")?.toInt() ?: 0,
                    mediumThreatCount = doc.getLong("mediumThreatCount")?.toInt() ?: 0,
                    avgSafetyScore    = doc.getDouble("avgSafetyScore")?.toInt() ?: 100,
                    flaggedByUsers    = doc.getLong("flaggedByUsers")?.toInt() ?: 0,
                    lastScanned       = doc.getLong("lastScanned") ?: 0L,
                    riskLevel         = computeLocationRisk(
                        doc.getLong("highThreatCount")?.toInt() ?: 0,
                        doc.getLong("totalScans")?.toInt() ?: 1
                    )
                )
            }
        } catch (e: Exception) { emptyList() }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun buildLocationKey(hotelName: String, city: String): String {
        // Normalise: lowercase, remove spaces/special chars → consistent key
        val cleanHotel = hotelName.lowercase().replace(Regex("[^a-z0-9]"), "")
        val cleanCity  = city.lowercase().replace(Regex("[^a-z0-9]"), "")
        return "${cleanCity}_${cleanHotel}"
    }

    private fun computeLocationRisk(highCount: Int, totalScans: Int): String {
        if (totalScans == 0) return "UNKNOWN"
        val highRatio = highCount.toFloat() / totalScans
        return when {
            highRatio >= 0.3f -> "HIGH"     // 30%+ high threat scans
            highRatio >= 0.1f -> "MEDIUM"
            else              -> "LOW"
        }
    }
}
