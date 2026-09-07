package com.staysafeai.app.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.staysafeai.app.databinding.ActivityScanHistoryBinding
import com.staysafeai.app.databinding.ItemScanHistoryBinding
import com.staysafeai.app.models.ScanHistoryItem
import com.staysafeai.app.services.CommunityDatabaseService
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*

class ScanHistoryActivity : AppCompatActivity() {

    private lateinit var binding: ActivityScanHistoryBinding
    private val communityDb = CommunityDatabaseService()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityScanHistoryBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.rvHistory.layoutManager = LinearLayoutManager(this)
        binding.btnBack.setOnClickListener { finish() }

        loadHistory()
    }

    private fun loadHistory() {
        binding.progressBar.visibility = android.view.View.VISIBLE
        lifecycleScope.launch {
            val items = communityDb.getUserScanHistory()
            runOnUiThread {
                binding.progressBar.visibility = android.view.View.GONE
                if (items.isEmpty()) {
                    binding.tvEmpty.text = "No scans yet.\nScan a room to get started!"
                    binding.tvEmpty.visibility = android.view.View.VISIBLE
                } else {
                    binding.rvHistory.adapter = HistoryAdapter(items)
                }
            }
        }
    }

    inner class HistoryAdapter(private val items: List<ScanHistoryItem>) :
        RecyclerView.Adapter<HistoryAdapter.VH>() {

        inner class VH(val b: ItemScanHistoryBinding) : RecyclerView.ViewHolder(b.root)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            VH(ItemScanHistoryBinding.inflate(LayoutInflater.from(parent.context), parent, false))

        override fun getItemCount() = items.size

        override fun onBindViewHolder(holder: VH, position: Int) {
            val item = items[position]
            val sdf = SimpleDateFormat("MMM dd, yyyy  HH:mm", Locale.getDefault())
            holder.b.tvHistoryDate.text = sdf.format(Date(item.timestamp))
            holder.b.tvHistoryLocation.text = item.location
            holder.b.tvHistoryThreat.text = item.threatLevel
            holder.b.tvHistorySummary.text = item.summary
            holder.b.tvHistoryScore.text = "Safety: ${item.safetyScore}/100"

            val color = when (item.threatLevel) {
                "HIGH"   -> getColor(com.staysafeai.app.R.color.danger_red)
                "MEDIUM" -> getColor(com.staysafeai.app.R.color.warning_orange)
                "LOW"    -> getColor(com.staysafeai.app.R.color.caution_yellow)
                else     -> getColor(com.staysafeai.app.R.color.safe_green)
            }
            holder.b.tvHistoryThreat.setTextColor(color)
            holder.b.viewThreatDot.backgroundTintList =
                android.content.res.ColorStateList.valueOf(color)
        }
    }
}
