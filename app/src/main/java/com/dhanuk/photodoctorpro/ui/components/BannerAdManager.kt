package com.dhanuk.photodoctorpro.ui.components

import android.app.Activity
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.FrameLayout
import com.dhanuk.photodoctorpro.BuildConfig
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.AdSize
import com.google.android.gms.ads.AdView
import com.google.android.gms.ads.AdListener
import com.google.android.gms.ads.LoadAdError

class BannerAdManager private constructor() {

    private var adView: AdView? = null
    private var activity: Activity? = null
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
        if (this.activity != null && this.activity == activity && adView != null) return
        this.activity = activity
        if (adView == null) {
            createAdView(activity)
        }
    }

    private fun createAdView(activity: Activity) {
        adView = AdView(activity).apply {
            adSize = AdSize.BANNER
            adUnitId = BuildConfig.ADMOB_BANNER_ID
            adListener = object : AdListener() {
                override fun onAdLoaded() {
                    if (isVisible) scheduleRefresh()
                }
            }
        }
        loadAd()
    }

    private fun loadAd() {
        adView?.loadAd(AdRequest.Builder().build())
    }

    private fun refreshAd() {
        if (isVisible && adView != null) {
            loadAd()
        }
    }

    private fun scheduleRefresh() {
        handler.removeCallbacks(refreshRunnable)
        handler.postDelayed(refreshRunnable, 2 * 60 * 1000)
    }

    fun show() {
        isVisible = true
        handler.removeCallbacks(refreshRunnable)
        adView?.visibility = View.VISIBLE
        loadAd()
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
        activity = null
    }
}