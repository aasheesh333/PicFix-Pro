package com.dhanuk.photodoctorpro.ui.components

import android.app.Activity
import android.view.View
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.navigation.NavHostController
import androidx.navigation.compose.currentBackStackEntryAsState
import com.google.android.gms.ads.AdSize

@Composable
fun GlobalBannerAd(navController: NavHostController) {
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = navBackStackEntry?.destination?.route

    val excludedRoutes = remember {
        setOf(
            "object_eraser",
            "color_adjustments",
            "perspective_crop",
            "resize_compress"
        )
    }

    val shouldShowBanner = currentRoute != null && currentRoute !in excludedRoutes

    val context = LocalContext.current
    val activity = context as? Activity ?: return
    val manager = remember { BannerAdManager.getInstance() }

    // Anchored adaptive banner: exact height for this device width, so the
    // Compose container never leaves a white strip above/below the ad.
    val configuration = LocalConfiguration.current
    val adSize = remember(configuration.screenWidthDp) {
        AdSize.getCurrentOrientationAnchoredAdaptiveBannerAdSize(
            context, configuration.screenWidthDp
        )
    }

    LaunchedEffect(activity, adSize) {
        manager.initialize(activity, adSize)
    }

    LaunchedEffect(shouldShowBanner) {
        if (shouldShowBanner) {
            manager.show()
        } else {
            manager.hide()
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            manager.hide()
        }
    }

    val adView = manager.getAdView()
    // Only occupy layout space when the banner should actually show, and use the
    // adaptive size's exact height — a fixed 50.dp container mismatched the real
    // ad height and left a white strip (the "double gap").
    if (adView != null && shouldShowBanner) {
        AndroidView(
            modifier = Modifier
                .fillMaxWidth()
                .height(adSize.height.dp),
            factory = { adView },
            update = { view ->
                view.visibility = View.VISIBLE
            }
        )
    }
}