package com.dhanuk.photodoctorpro.utils

import android.app.Activity
import android.content.Context
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.LoadAdError
import com.google.android.gms.ads.MobileAds
import com.google.android.gms.ads.interstitial.InterstitialAd
import com.google.android.gms.ads.interstitial.InterstitialAdLoadCallback
import android.util.Log
import com.dhanuk.photodoctorpro.BuildConfig
import java.util.concurrent.atomic.AtomicInteger

object AdManager {

    private const val TAG = "AdManager"
    @Volatile private var interstitialAd: InterstitialAd? = null
    @Volatile private var lastAdShowTime = 0L
    private val majorActionCount = AtomicInteger(0)

    @Volatile var lastLoadError: String = "None"
        private set
    @Volatile var isAdLoaded: Boolean = false
        private set

    private const val AD_FREQUENCY_CAP_MS = 3 * 60 * 1000 // 3 minutes
    private const val MAJOR_ACTION_COUNT_CAP = 2

    fun initialize(context: Context) {
        MobileAds.initialize(context) {}
        loadInterstitialAd(context)
    }

    private fun loadInterstitialAd(context: Context) {
        val adRequest = AdRequest.Builder().build()
        if (BuildConfig.DEBUG) Log.d(TAG, "Loading Interstitial Ad")
        InterstitialAd.load(
            context,
            BuildConfig.ADMOB_INTERSTITIAL_ID,
            adRequest,
            object : InterstitialAdLoadCallback() {
                override fun onAdLoaded(ad: InterstitialAd) {
                    if (BuildConfig.DEBUG) Log.d(TAG, "Interstitial Ad Loaded")
                    interstitialAd = ad
                    isAdLoaded = true
                    lastLoadError = "Success"
                }

                override fun onAdFailedToLoad(adError: LoadAdError) {
                    if (BuildConfig.DEBUG) Log.e(TAG, "Interstitial Ad Failed to Load: ${adError.message}")
                    interstitialAd = null
                    isAdLoaded = false
                    lastLoadError = "Code: ${adError.code}, Message: ${adError.message}"
                }
            }
        )
    }

    fun showInterstitialAd(activity: Activity) {
        val ad: InterstitialAd? = interstitialAd
        if (ad == null) {
            if (BuildConfig.DEBUG) Log.d(TAG, "Ad is null, attempting to reload")
            loadInterstitialAd(activity)
        }

        val actions = majorActionCount.incrementAndGet()
        val currentTime = System.currentTimeMillis()
        val lastShow = lastAdShowTime
        if (ad != null &&
            currentTime - lastShow >= AD_FREQUENCY_CAP_MS &&
            actions >= MAJOR_ACTION_COUNT_CAP
        ) {
            if (BuildConfig.DEBUG) Log.d(TAG, "Showing Interstitial Ad")
            try {
                ad.show(activity)
            } catch (_: Exception) {}
            lastAdShowTime = currentTime
            majorActionCount.set(0)
            loadInterstitialAd(activity)
        } else {
             if (BuildConfig.DEBUG) Log.d(TAG, "Ad not shown. Time diff: ${currentTime - lastShow}, Major Actions: $actions, Ad Ready: ${interstitialAd != null}")
        }
    }

    /**
     * Release ad resources. Safe to call multiple times. Call from Activity.onDestroy()
     * to ensure the loaded InterstitialAd is not pinned to a destroyed Activity.
     */
    fun cleanup() {
        interstitialAd = null
        isAdLoaded = false
        lastLoadError = "Cleaned up"
        majorActionCount.set(0)
    }
}
