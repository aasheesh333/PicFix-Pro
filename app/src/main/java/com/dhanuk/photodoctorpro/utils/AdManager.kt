package com.dhanuk.photodoctorpro.utils

import android.app.Activity
import android.content.Context
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.LoadAdError
import com.google.android.gms.ads.MobileAds
import com.google.android.gms.ads.interstitial.InterstitialAd
import com.google.android.gms.ads.interstitial.InterstitialAdLoadCallback
import android.util.Log

object AdManager {

    private const val TAG = "AdManager"
    private var interstitialAd: InterstitialAd? = null
    private var lastAdShowTime = 0L
    private var majorActionCount = 0
    private var isFirstAction = true

    private const val AD_FREQUENCY_CAP_MS = 3 * 60 * 1000 // 3 minutes
    private const val MAJOR_ACTION_COUNT_CAP = 2

    fun initialize(context: Context) {
        MobileAds.initialize(context) {}
        loadInterstitialAd(context)
    }

    private fun loadInterstitialAd(context: Context) {
        val adRequest = AdRequest.Builder().build()
        Log.d(TAG, "Loading Interstitial Ad")
        InterstitialAd.load(
            context,
            "ca-app-pub-3940256099942544/1033173712", // Test ad unit ID
            adRequest,
            object : InterstitialAdLoadCallback() {
                override fun onAdLoaded(ad: InterstitialAd) {
                    Log.d(TAG, "Interstitial Ad Loaded")
                    interstitialAd = ad
                }

                override fun onAdFailedToLoad(adError: LoadAdError) {
                    Log.e(TAG, "Interstitial Ad Failed to Load: ${adError.message}")
                    interstitialAd = null
                }
            }
        )
    }

    fun showInterstitialAd(activity: Activity) {
        if (isFirstAction) {
            Log.d(TAG, "Skipping ad for first action")
            isFirstAction = false
            return
        }
        majorActionCount++
        val currentTime = System.currentTimeMillis()
        if (interstitialAd != null &&
            currentTime - lastAdShowTime >= AD_FREQUENCY_CAP_MS &&
            majorActionCount >= MAJOR_ACTION_COUNT_CAP
        ) {
            Log.d(TAG, "Showing Interstitial Ad")
            interstitialAd?.show(activity)
            lastAdShowTime = currentTime
            majorActionCount = 0
            loadInterstitialAd(activity) // Preload the next ad
        } else {
             Log.d(TAG, "Ad not shown. Time diff: ${currentTime - lastAdShowTime}, Major Actions: $majorActionCount, Ad Ready: ${interstitialAd != null}")
        }
    }
}
