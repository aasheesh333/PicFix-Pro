package com.dhanuk.photodoctorpro.ui.components

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import com.dhanuk.photodoctorpro.BuildConfig
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.AdSize
import com.google.android.gms.ads.AdView

@Composable
fun BannerAd() {
    AndroidView(
        modifier = Modifier.fillMaxWidth(),
        factory = { context ->
            AdView(context).apply {
                setAdSize(AdSize.BANNER)
                adUnitId = BuildConfig.ADMOB_BANNER_ID
                loadAd(AdRequest.Builder().build())
            }
        }
    )
}
