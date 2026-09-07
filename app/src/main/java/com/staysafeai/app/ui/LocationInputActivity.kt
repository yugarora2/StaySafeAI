package com.staysafeai.app.ui

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.staysafeai.app.databinding.ActivityLocationInputBinding
import com.staysafeai.app.services.CommunityDatabaseService
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*

class LocationInputActivity : AppCompatActivity() {

    private lateinit var binding: ActivityLocationInputBinding
    private lateinit var communityDb: CommunityDatabaseService

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityLocationInputBinding.inflate(layoutInflater)
        setContentView(binding.root)
        communityDb = CommunityDatabaseService()
        setupUI()
    }

    private fun setupUI() {
        binding.btnCheckLocation.setOnClickListener {
            val hotel = binding.etHotelName.text.toString().trim()
            val city  = binding.etCity.text.toString().trim()
            if (hotel.isEmpty() || city.isEmpty()) {
                Toast.makeText(this, "Please enter hotel name and city", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            checkCommunityData(hotel, city)
        }

        binding.btnSkipLocation.setOnClickListener {
            // Skip location — go straight to scan without community data
            startScanWithLocation("Unknown Hotel", "Unknown City")
        }

        binding.btnBack.setOnClickListener { finish() }
    }

    private fun checkCommunityData(hotel: String, city: String) {
        binding.progressBar.visibility = View.VISIBLE
        binding.btnCheckLocation.isEnabled = false

        lifecycleScope.launch {
            val summary = communityDb.getCommunityDataForLocation(hotel, city)
            val recentScans = communityDb.getRecentScansForLocation(hotel, city, limit = 5)

            binding.progressBar.visibility = View.GONE
            binding.btnCheckLocation.isEnabled = true

            if (summary == null) {
                // First ever scan at this location
                showCommunityCard(
                    icon = "🔍",
                    title = "First scan at this location",
                    message = "No previous scans found for $hotel, $city.\nYou'll be helping future guests by scanning!",
                    cardColor = "#E8F5E9",
                    riskLevel = "UNKNOWN"
                )
            } else {
                // Show community data
                val sdf = SimpleDateFormat("MMM dd, yyyy", Locale.getDefault())
                val lastDate = sdf.format(Date(summary.lastScanned))
                val flagMsg = when {
                    summary.highThreatCount > 0 ->
                        "⚠️ ${summary.highThreatCount} HIGH threat scan(s) reported here!"
                    summary.mediumThreatCount > 0 ->
                        "⚡ ${summary.mediumThreatCount} medium threat scan(s) reported."
                    else ->
                        "✅ No major threats reported in ${summary.totalScans} scan(s)."
                }

                showCommunityCard(
                    icon = when (summary.riskLevel) {
                        "HIGH"   -> "🚨"
                        "MEDIUM" -> "⚠️"
                        else     -> "✅"
                    },
                    title = "${summary.totalScans} previous scan(s) found",
                    message = "$flagMsg\n\nAvg safety score: ${summary.avgSafetyScore}/100\nLast scanned: $lastDate",
                    cardColor = when (summary.riskLevel) {
                        "HIGH"   -> "#FFEBEE"
                        "MEDIUM" -> "#FFF3E0"
                        else     -> "#E8F5E9"
                    },
                    riskLevel = summary.riskLevel,
                    recentScans = recentScans
                )
            }

            binding.cardCommunity.visibility = View.VISIBLE
            binding.btnStartScanNow.visibility = View.VISIBLE
            binding.btnStartScanNow.setOnClickListener {
                startScanWithLocation(hotel, city)
            }
        }
    }

    private fun showCommunityCard(
        icon: String,
        title: String,
        message: String,
        cardColor: String,
        riskLevel: String,
        recentScans: List<com.staysafeai.app.models.CommunityScanRecord> = emptyList()
    ) {
        binding.tvCommunityIcon.text = icon
        binding.tvCommunityTitle.text = title
        binding.tvCommunityMessage.text = message
        binding.cardCommunity.setCardBackgroundColor(
            android.graphics.Color.parseColor(cardColor)
        )

        // Show recent scan snippets if any HIGH threats
        if (recentScans.any { it.threatLevel == "HIGH" }) {
            binding.tvRecentThreats.visibility = View.VISIBLE
            val highScans = recentScans.filter { it.threatLevel == "HIGH" }
            val snippets = highScans.take(2).joinToString("\n\n") { scan ->
                val sdf = SimpleDateFormat("MMM yyyy", Locale.getDefault())
                val date = sdf.format(Date(scan.timestamp))
                "[$date] ${scan.summary.take(120)}…"
            }
            binding.tvRecentThreats.text = "Recent high-threat reports:\n\n$snippets"
        } else {
            binding.tvRecentThreats.visibility = View.GONE
        }
    }

    private fun startScanWithLocation(hotel: String, city: String) {
        val intent = Intent(this, ScanActivity::class.java).apply {
            putExtra("hotel_name", hotel)
            putExtra("city", city)
        }
        startActivity(intent)
    }
}
