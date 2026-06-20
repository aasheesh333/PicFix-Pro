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

object AdManager {

    private const val TAG = "AdManager"
    private var interstitialAd: InterstitialAd? = null
    private var lastAdShowTime = 0L
    private var majorActionCount = 0

    // Debug properties
    var lastLoadError: String = "None"
        private set
    var isAdLoaded: Boolean = false
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
        // Retry loading if ad is missing
        if (interstitialAd == null) {
            if (BuildConfig.DEBUG) Log.d(TAG, "Ad is null, attempting to reload")
            loadInterstitialAd(activity)
        }

        majorActionCount++
        val currentTime = System.currentTimeMillis()
        if (interstitialAd != null &&
            currentTime - lastAdShowTime >= AD_FREQUENCY_CAP_MS &&
            majorActionCount >= MAJOR_ACTION_COUNT_CAP
        ) {
            if (BuildConfig.DEBUG) Log.d(TAG, "Showing Interstitial Ad")
            interstitialAd?.show(activity)
            lastAdShowTime = currentTime
            majorActionCount = 0
            loadInterstitialAd(activity) // Preload the next ad
        } else {
             if (BuildConfig.DEBUG) Log.d(TAG, "Ad not shown. Time diff: ${currentTime - lastAdShowTime}, Major Actions: $majorActionCount, Ad Ready: ${interstitialAd != null}")
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
        majorActionCount = 0
    }
}
