package com.dhanuk.photodoctorpro.ui.navigation

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.dhanuk.photodoctorpro.ui.components.BottomNavigationBar
import com.dhanuk.photodoctorpro.ui.components.GlobalBannerAd

@Composable
fun AppScaffold() {
    val navController = rememberNavController()
    val globalNavigationState = remember { GlobalNavigationState() }

    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = navBackStackEntry?.destination?.route
    val showBottomBar = currentRoute in listOf("home", "history", "settings")

    CompositionLocalProvider(LocalGlobalNavigationState provides globalNavigationState) {
        Scaffold(
            bottomBar = {
                AnimatedVisibility(
                    visible = showBottomBar,
                    enter = slideInVertically(
                        animationSpec = tween(300),
                        initialOffsetY = { it }
                    ) + fadeIn(animationSpec = tween(300)),
                    exit = slideOutVertically(
                        animationSpec = tween(200),
                        targetOffsetY = { it }
                    ) + fadeOut(animationSpec = tween(200))
                ) {
                    Column {
                        GlobalBannerAd(navController)
                        BottomNavigationBar(navController)
                    }
                }
            }
        ) { padding ->
            AppNavigation(navController = navController, modifier = Modifier.padding(padding))
        }
    }
}