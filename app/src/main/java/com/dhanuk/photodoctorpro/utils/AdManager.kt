package com.dhanuk.photodoctorpro.utils

import android.app.Activity
import android.content.Context
import android.util.Log
import com.dhanuk.photodoctorpro.BuildConfig
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.LoadAdError
import com.google.android.gms.ads.MobileAds
import com.google.android.gms.ads.interstitial.InterstitialAd
import com.google.android.gms.ads.interstitial.InterstitialAdLoadCallback
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
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

    private const val AD_FREQUENCY_CAP_MS = 2 * 60 * 1000L
    private const val AUTO_AD_INTERVAL_MS = 5 * 60 * 1000L
    private const val MAJOR_ACTION_COUNT_CAP = 2

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    @Volatile private var autoAdJob: kotlinx.coroutines.Job? = null

    fun initialize(context: Context) {
        MobileAds.initialize(context) {}
        loadInterstitialAd(context)
        startAutoAdTimer(context)
    }

    private fun startAutoAdTimer(context: Context) {
        autoAdJob?.cancel()
        autoAdJob = scope.launch {
            while (true) {
                delay(AUTO_AD_INTERVAL_MS)
                val activity = currentActivity ?: continue
                if (!ConsentManager.canRequestAds()) continue
                if (InAppReviewManager.isReviewInProgress) continue
                val ad = interstitialAd ?: continue
                val currentTime = System.currentTimeMillis()
                if (currentTime - lastAdShowTime >= AD_FREQUENCY_CAP_MS) {
                    if (BuildConfig.DEBUG) Log.d(TAG, "Showing auto periodic Interstitial Ad")
                    safeShowAd(ad, activity)
                }
            }
        }
    }

    @Volatile private var currentActivity: Activity? = null

    fun setCurrentActivity(activity: Activity?) {
        currentActivity = activity
    }

    private fun safeShowAd(ad: InterstitialAd, activity: Activity) {
        try {
            ad.show(activity)
        } catch (_: Exception) {}
        lastAdShowTime = System.currentTimeMillis()
        majorActionCount.set(0)
        loadInterstitialAd(activity)
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

    fun showInterstitialOnSave(activity: Activity) {
        if (!ConsentManager.canRequestAds()) {
            if (BuildConfig.DEBUG) Log.d(TAG, "Skipping interstitial on save — no ad consent")
            return
        }
        if (InAppReviewManager.isReviewInProgress) {
            if (BuildConfig.DEBUG) Log.d(TAG, "Skipping interstitial on save — review in progress")
            return
        }
        val ad: InterstitialAd? = interstitialAd
        if (ad == null) {
            if (BuildConfig.DEBUG) Log.d(TAG, "Ad is null on save, attempting to reload")
            loadInterstitialAd(activity)
            return
        }
        val currentTime = System.currentTimeMillis()
        val lastShow = lastAdShowTime
        if (currentTime - lastShow >= AD_FREQUENCY_CAP_MS) {
            if (BuildConfig.DEBUG) Log.d(TAG, "Showing Interstitial Ad on Save")
            safeShowAd(ad, activity)
        } else {
            if (BuildConfig.DEBUG) Log.d(TAG, "Save ad skipped — 2min cap not reached. Time diff: ${currentTime - lastShow}")
        }
    }

    fun showInterstitialAd(activity: Activity) {
        if (!ConsentManager.canRequestAds()) {
            if (BuildConfig.DEBUG) Log.d(TAG, "Skipping interstitial — no ad consent")
            return
        }
        if (InAppReviewManager.isReviewInProgress) {
            if (BuildConfig.DEBUG) Log.d(TAG, "Skipping interstitial — review in progress")
            return
        }
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
            safeShowAd(ad, activity)
        } else {
            if (BuildConfig.DEBUG) Log.d(TAG, "Ad not shown. Time diff: ${currentTime - lastShow}, Major Actions: $actions, Ad Ready: ${interstitialAd != null}")
        }
    }

    fun showInterstitialOnShare(activity: Activity) {
        if (!ConsentManager.canRequestAds()) {
            if (BuildConfig.DEBUG) Log.d(TAG, "Skipping interstitial on share — no ad consent")
            return
        }
        if (InAppReviewManager.isReviewInProgress) {
            if (BuildConfig.DEBUG) Log.d(TAG, "Skipping interstitial on share — review in progress")
            return
        }
        val ad: InterstitialAd? = interstitialAd
        if (ad == null) {
            if (BuildConfig.DEBUG) Log.d(TAG, "Ad is null, attempting to reload")
            loadInterstitialAd(activity)
        }

        val currentTime = System.currentTimeMillis()
        val lastShow = lastAdShowTime
        if (ad != null && currentTime - lastShow >= AD_FREQUENCY_CAP_MS) {
            if (BuildConfig.DEBUG) Log.d(TAG, "Showing Interstitial Ad on Share")
            safeShowAd(ad, activity)
        } else {
            majorActionCount.incrementAndGet()
            if (BuildConfig.DEBUG) Log.d(TAG, "Share action recorded, ad not shown yet. Time diff: ${currentTime - lastShow}")
        }
    }

    fun onAppForeground(activity: Activity) {
        setCurrentActivity(activity)
        if (!ConsentManager.canRequestAds()) {
            if (BuildConfig.DEBUG) Log.d(TAG, "Skipping interstitial on foreground — no ad consent")
            return
        }
        if (InAppReviewManager.isReviewInProgress) {
            if (BuildConfig.DEBUG) Log.d(TAG, "Skipping interstitial on foreground — review in progress")
            return
        }
        val ad: InterstitialAd? = interstitialAd
        val currentTime = System.currentTimeMillis()
        val lastShow = lastAdShowTime
        if (ad != null && currentTime - lastShow >= AD_FREQUENCY_CAP_MS) {
            if (BuildConfig.DEBUG) Log.d(TAG, "Showing periodic Interstitial Ad")
            safeShowAd(ad, activity)
        }
    }

    fun cleanup() {
        autoAdJob?.cancel()
        autoAdJob = null
        interstitialAd = null
        isAdLoaded = false
        lastLoadError = "Cleaned up"
        majorActionCount.set(0)
    }
}