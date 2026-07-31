package com.dhanuk.photodoctorpro.ui.components

import android.app.Activity
import android.os.Handler
import android.os.Looper
import android.view.View
import com.dhanuk.photodoctorpro.BuildConfig
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.AdSize
import com.google.android.gms.ads.AdView
import com.google.android.gms.ads.AdListener
import com.google.android.gms.ads.LoadAdError
import java.lang.ref.WeakReference

class BannerAdManager private constructor() {

    private var adView: AdView? = null
    private var activityRef: WeakReference<Activity>? = null
    private var isVisible = false
    private val handler = Handler(Looper.getMainLooper())
    private val refreshRunnable = Runnable { refreshAd() }

    companion object {
        @Volatile private var INSTANCE: BannerAdManager? = null
        fun getInstance(): BannerAdManager = INSTANCE ?: synchronized(this) {
            INSTANCE ?: BannerAdManager().also { INSTANCE = it }
        }
    }

    fun initialize(activity: Activity) {
        val current = activityRef?.get()
        if (current != null && current === activity && adView != null) return
        if (adView != null && current !== activity) {
            destroy()
        }
        activityRef = WeakReference(activity)
        if (adView == null) {
            createAdView(activity)
        }
    }

    private fun createAdView(activity: Activity) {
        adView = AdView(activity).apply {
            setAdSize(AdSize.BANNER)
            adUnitId = BuildConfig.ADMOB_BANNER_ID
            adListener = object : AdListener() {
                override fun onAdLoaded() {
                    if (isVisible) scheduleRefresh()
                }

                override fun onAdFailedToLoad(adError: LoadAdError) {
                    if (com.dhanuk.photodoctorpro.BuildConfig.DEBUG) {
                        android.util.Log.w("BannerAdManager", "Banner failed: ${adError.message}")
                    }
                }
            }
        }
        loadAd()
    }

    private fun loadAd() {
        adView?.loadAd(AdRequest.Builder().build())
    }

    private fun refreshAd() {
        if (isVisible && adView != null && activityRef?.get() != null) {
            loadAd()
        }
    }

    private fun scheduleRefresh() {
        handler.removeCallbacks(refreshRunnable)
        if (!isVisible) return
        handler.postDelayed(refreshRunnable, 2 * 60 * 1000)
    }

    fun show() {
        isVisible = true
        adView?.visibility = View.VISIBLE
        if (adView?.responseInfo == null) {
            loadAd()
        }
        scheduleRefresh()
    }

    fun hide() {
        isVisible = false
        handler.removeCallbacks(refreshRunnable)
        adView?.visibility = View.GONE
    }

    fun getAdView(): AdView? = adView

    fun destroy() {
        handler.removeCallbacks(refreshRunnable)
        adView?.destroy()
        adView = null
        activityRef = null
        isVisible = false
    }
}
