package com.dhanuk.photodoctorpro

import android.Manifest
import android.content.res.Configuration
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
import com.dhanuk.photodoctorpro.utils.FaceEnhancer
import com.dhanuk.photodoctorpro.utils.ImageEnhancer
import com.dhanuk.photodoctorpro.utils.ThemeController

class MainActivity : ComponentActivity() {

    private val requestPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { permissions ->
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        val splashScreen = installSplashScreen()
        super.onCreate(savedInstanceState)
        splashScreen.setKeepOnScreenCondition { false }
        AdManager.initialize(this)
        CrashReporter.registerActivity(this)

        requestRequiredPermissions()

        notifySystemDarkMode()

        setContent {
            val isDarkTheme by ThemeController.isDarkTheme.collectAsState()
            PhotoDoctorProTheme(darkTheme = isDarkTheme, animateThemeChange = true) {
                AppScaffold()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        ThemeController.init(this)
        notifySystemDarkMode()
        AdManager.onAppForeground(this)
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        val nightMode = newConfig.uiMode and Configuration.UI_MODE_NIGHT_MASK
        ThemeController.onSystemDarkModeChanged(nightMode == Configuration.UI_MODE_NIGHT_YES)
    }

    override fun onDestroy() {
        super.onDestroy()
        CrashReporter.unregisterActivity(this)
        if (isFinishing) {
            AdManager.cleanup()
            ImageEnhancer.shutdown()
            FaceEnhancer.shutdown()
        }
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

    private fun notifySystemDarkMode() {
        val nightMode = resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
        ThemeController.onSystemDarkModeChanged(nightMode == Configuration.UI_MODE_NIGHT_YES)
    }
}