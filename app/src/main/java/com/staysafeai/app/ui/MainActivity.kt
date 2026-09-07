package com.staysafeai.app.ui

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.staysafeai.app.databinding.ActivityMainBinding
import com.staysafeai.app.managers.AdMobManager
import com.staysafeai.app.managers.GoogleAuthManager
import com.google.android.gms.ads.*
import com.google.android.material.dialog.MaterialAlertDialogBuilder

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var adMobManager: AdMobManager
    private lateinit var authManager: GoogleAuthManager
    private var bannerAd: AdView? = null

    private val requiredPermissions = arrayOf(
        android.Manifest.permission.CAMERA,
        android.Manifest.permission.RECORD_AUDIO,
        android.Manifest.permission.ACCESS_FINE_LOCATION,
        android.Manifest.permission.ACCESS_WIFI_STATE,
        android.Manifest.permission.CHANGE_WIFI_STATE,
        android.Manifest.permission.BLUETOOTH_SCAN,
        android.Manifest.permission.BLUETOOTH_CONNECT
    )

    private val permissionLauncher =
        registerForActivityResult(
            androidx.activity.result.contract.ActivityResultContracts.RequestMultiplePermissions()
        ) { permissions ->
            if (permissions.values.all { it }) showWatchAdDialog()
            else Toast.makeText(this, "Permissions required to scan", Toast.LENGTH_LONG).show()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        authManager  = GoogleAuthManager(this)
        adMobManager = AdMobManager(this)

        setupUI()
        loadBannerAd()
        adMobManager.loadRewardedAd()
    }

    private fun setupUI() {
        // Show user name
        val user = authManager.getCurrentUser()
        binding.tvUserName.text = "Hi, ${user?.displayName?.split(" ")?.first() ?: "there"} 👋"

        // ONE button — watch ad then scan. That's it.
        binding.btnStartScan.setOnClickListener {
            checkPermissionsThenAd()
        }

        binding.btnHistory.setOnClickListener {
            startActivity(Intent(this, ScanHistoryActivity::class.java))
        }

        binding.ivUserAvatar.setOnClickListener {
            MaterialAlertDialogBuilder(this)
                .setTitle(authManager.getUserEmail() ?: "Account")
                .setItems(arrayOf("Scan History", "Sign Out")) { _, which ->
                    when (which) {
                        0 -> startActivity(Intent(this, ScanHistoryActivity::class.java))
                        1 -> { authManager.signOut(); startActivity(Intent(this, LoginActivity::class.java)); finish() }
                    }
                }.show()
        }
    }

    private fun checkPermissionsThenAd() {
        val missing = requiredPermissions.filter {
            androidx.core.content.ContextCompat.checkSelfPermission(this, it) !=
                android.content.pm.PackageManager.PERMISSION_GRANTED
        }
        if (missing.isEmpty()) showWatchAdDialog()
        else permissionLauncher.launch(missing.toTypedArray())
    }

    private fun showWatchAdDialog() {
        MaterialAlertDialogBuilder(this)
            .setTitle("Watch a short ad")
            .setMessage("Watch a 30-second ad to unlock your room scan. This keeps StaySafe AI free!")
            .setPositiveButton("Watch Ad") { _, _ -> showRewardedAd() }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showRewardedAd() {
        if (!adMobManager.isAdReady()) {
            Toast.makeText(this, "Ad loading, please wait a moment…", Toast.LENGTH_SHORT).show()
            adMobManager.loadRewardedAd()
            return
        }
        adMobManager.showRewardedAd(
            activity   = this,
            onRewarded = { startScan() },
            onFailed   = {
                // If ad fails, still let them scan — don't block the user
                Toast.makeText(this, "Ad unavailable — scanning anyway!", Toast.LENGTH_SHORT).show()
                startScan()
            }
        )
    }

    private fun startScan() {
        startActivity(Intent(this, LocationInputActivity::class.java))
    }

    private fun loadBannerAd() {
        bannerAd = AdView(this).apply {
            adUnitId = AdMobManager.BANNER_AD_UNIT_ID
            setAdSize(AdSize.BANNER)
        }
        binding.bannerAdContainer.removeAllViews()
        binding.bannerAdContainer.addView(bannerAd)
        bannerAd?.loadAd(AdRequest.Builder().build())
    }

    override fun onResume()  { super.onResume();  bannerAd?.resume();  adMobManager.loadRewardedAd() }
    override fun onPause()   { super.onPause();   bannerAd?.pause() }
    override fun onDestroy() { super.onDestroy(); bannerAd?.destroy() }
}
