package com.dhanuk.photodoctorpro.ui.navigation

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.navigation.compose.rememberNavController
import com.dhanuk.photodoctorpro.ui.components.BannerAd

@Composable
fun AppScaffold() {
    val navController = rememberNavController()
    val globalNavigationState = remember { GlobalNavigationState() }

    CompositionLocalProvider(LocalGlobalNavigationState provides globalNavigationState) {
        Scaffold(
            bottomBar = {
                Column {
                    BannerAd()
                    BottomNavigationBar(navController)
                }
            }
        ) { padding ->
            AppNavigation(navController = navController, modifier = Modifier.padding(padding))
        }
    }
}
