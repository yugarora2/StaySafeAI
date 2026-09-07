package com.staysafeai.app.managers

import android.app.Activity
import android.content.Context
import com.google.android.gms.ads.*
import com.google.android.gms.ads.rewarded.RewardedAd
import com.google.android.gms.ads.rewarded.RewardedAdLoadCallback

class AdMobManager(private val context: Context) {

    companion object {
        // Replace with your real AdMob IDs before publishing
        const val BANNER_AD_UNIT_ID   = "ca-app-pub-3940256099942544/6300978111" // test ID
        const val REWARDED_AD_UNIT_ID = "ca-app-pub-3940256099942544/5224354917" // test ID
        // Real IDs go here when you publish:
        // const val BANNER_AD_UNIT_ID   = "ca-app-pub-YOURCODE/BANNERCODE"
        // const val REWARDED_AD_UNIT_ID = "ca-app-pub-YOURCODE/REWARDEDCODE"
    }

    private var rewardedAd: RewardedAd? = null

    init {
        MobileAds.initialize(context)
    }

    fun loadRewardedAd() {
        val request = AdRequest.Builder().build()
        RewardedAd.load(context, REWARDED_AD_UNIT_ID, request,
            object : RewardedAdLoadCallback() {
                override fun onAdLoaded(ad: RewardedAd) { rewardedAd = ad }
                override fun onAdFailedToLoad(error: LoadAdError) { rewardedAd = null }
            }
        )
    }

    fun isAdReady() = rewardedAd != null

    fun showRewardedAd(
        activity: Activity,
        onRewarded: () -> Unit,
        onFailed: () -> Unit
    ) {
        val ad = rewardedAd
        if (ad == null) { onFailed(); return }

        ad.fullScreenContentCallback = object : FullScreenContentCallback() {
            override fun onAdDismissedFullScreenContent() { rewardedAd = null; loadRewardedAd() }
            override fun onAdFailedToShowFullScreenContent(e: AdError) { onFailed() }
        }
        ad.show(activity) { onRewarded() }
    }
}
