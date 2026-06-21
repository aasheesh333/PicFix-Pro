package com.dhanuk.photodoctorpro

import android.Manifest
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import com.dhanuk.photodoctorpro.ui.navigation.AppScaffold
import com.dhanuk.photodoctorpro.ui.theme.PhotoDoctorProTheme
import com.dhanuk.photodoctorpro.utils.AdManager
import com.dhanuk.photodoctorpro.utils.CrashReporter
import com.dhanuk.photodoctorpro.utils.ThemeController

class MainActivity : ComponentActivity() {

    private val requestPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { permissions ->
            // Results handled individually; no-op here.
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        val splashScreen = installSplashScreen()
        super.onCreate(savedInstanceState)
        splashScreen.setKeepOnScreenCondition { false }
        AdManager.initialize(this)
        CrashReporter.registerActivity(this)

        requestRequiredPermissions()

        setContent {
            val isDarkTheme by ThemeController.isDarkTheme.collectAsState()
            PhotoDoctorProTheme(darkTheme = isDarkTheme) {
                AppScaffold()
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        CrashReporter.unregisterActivity(this)
        AdManager.cleanup()
    }

    private fun requestRequiredPermissions() {
        val permissions = mutableListOf<String>()
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.READ_MEDIA_IMAGES)
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        if (permissions.isNotEmpty()) {
            requestPermissionLauncher.launch(permissions.toTypedArray())
        }
    }
}
