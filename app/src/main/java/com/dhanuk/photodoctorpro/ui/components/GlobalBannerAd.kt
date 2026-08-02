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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.navigation.NavHostController
import androidx.navigation.compose.currentBackStackEntryAsState

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

    LaunchedEffect(activity) {
        manager.initialize(activity)
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
    // Only occupy layout space when the banner should actually show.
    // Previously the AndroidView was always composed with a fixed 50.dp height,
    // leaving a blank white strip on screens where the ad was hidden (GONE still
    // consumes space inside a Column). This was the "double gap" below the ad on
    // non-home screens.
    if (adView != null && shouldShowBanner) {
        AndroidView(
            modifier = Modifier
                .fillMaxWidth()
                .height(50.dp),
            factory = { adView },
            update = { view ->
                view.visibility = View.VISIBLE
            }
        )
    }
}