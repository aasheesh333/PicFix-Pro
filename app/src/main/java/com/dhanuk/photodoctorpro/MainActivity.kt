package com.dhanuk.photodoctorpro

import android.Manifest
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.os.Bundle
import android.os.SystemClock
import android.widget.Toast
import com.dhanuk.photodoctorpro.R
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import com.dhanuk.photodoctorpro.ui.navigation.AppScaffold
import com.dhanuk.photodoctorpro.ui.theme.PicFixProTheme
import com.dhanuk.photodoctorpro.utils.AdManager
import com.dhanuk.photodoctorpro.utils.ConsentManager
import com.dhanuk.photodoctorpro.utils.CrashReporter
import com.dhanuk.photodoctorpro.utils.ImageEnhancer
import com.dhanuk.photodoctorpro.utils.ThemeController

class MainActivity : ComponentActivity() {

    private val deniedStatuses = mutableSetOf<String>()

    private val requestPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { permissions ->
            val denied = permissions.filterValues { !it }.keys
            if (denied.isNotEmpty()) {
                if (BuildConfig.DEBUG) {
                    Log.w("MainActivity", "Permissions denied: $denied")
                }
                deniedStatuses.addAll(denied)
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        val splashScreen = installSplashScreen()
        super.onCreate(savedInstanceState)
        splashScreen.setKeepOnScreenCondition { false }
        ConsentManager.init(this)
        if (ConsentManager.canRequestAds()) {
            AdManager.initialize(this)
        }
        CrashReporter.registerActivity(this)

        requestRequiredPermissions()

        notifySystemDarkMode()

        setContent {
            val isDarkTheme by ThemeController.isDarkTheme.collectAsState()
            PicFixProTheme(darkTheme = isDarkTheme, animateThemeChange = true) {
                AppScaffold()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        ThemeController.init(this)
        AdManager.setCurrentActivity(this)
        notifySystemDarkMode()
        if (ConsentManager.canRequestAds()) {
            AdManager.onAppForeground(this)
        }
        maybeRepromptForDeniedPermissions()
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

    private var lastSettingsIntentMs: Long = 0L
    private fun maybeRepromptForDeniedPermissions() {
        if (deniedStatuses.isEmpty()) return
        val now = SystemClock.elapsedRealtime()
        if (now - lastRationalePromptMs < 5 * 60 * 1000L || now - lastSettingsIntentMs < 5 * 60 * 1000L) return
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return

        val stillDenied = deniedStatuses.firstOrNull { perm ->
            checkSelfPermission(perm) != PackageManager.PERMISSION_GRANTED
        } ?: return

        val shouldShowRationale = shouldShowRequestPermissionRationale(stillDenied)
        if (stillDenied == Manifest.permission.READ_MEDIA_IMAGES && 
            !shouldShowRationale && 
            android.provider.Settings.System.canWrite(this)
        ) {
            lastSettingsIntentMs = now
            deniedStatuses.remove(stillDenied)
                    val intent = Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                        data = Uri.parse("package:$packageName")
                    }
                    startActivity(intent)
                    Toast.makeText(this, R.string.this_app_requires_storage_access_to_function, Toast.LENGTH_LONG).show()
                    return
        }

        lastRationalePromptMs = now
        if ((stillDenied == Manifest.permission.READ_MEDIA_IMAGES && shouldShowRationale) ||
            stillDenied == Manifest.permission.POST_NOTIFICATIONS
        ) {
            deniedStatuses.remove(stillDenied)
            requestPermissionLauncher.launch(arrayOf(stillDenied))
        }
    }

    private fun notifySystemDarkMode() {
        val nightMode = resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
        ThemeController.onSystemDarkModeChanged(nightMode == Configuration.UI_MODE_NIGHT_YES)
    }
}
