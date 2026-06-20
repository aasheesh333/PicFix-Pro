package com.dhanuk.photodoctorpro

import android.app.Application
import android.util.Log
import com.onesignal.OneSignal
import com.onesignal.debug.LogLevel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.opencv.android.OpenCVLoader
import com.dhanuk.photodoctorpro.utils.CrashReporter
import com.dhanuk.photodoctorpro.utils.ThemeController

class PhotoDoctorApplication : Application() {
    override fun onCreate() {
        super.onCreate()

        CrashReporter.install()
        ThemeController.init(this)

        initOpenCvAsync()

        if (BuildConfig.DEBUG) {
            OneSignal.Debug.logLevel = LogLevel.VERBOSE
        } else {
            OneSignal.Debug.logLevel = LogLevel.NONE
        }

        if (BuildConfig.ONESIGNAL_APP_ID.isNotEmpty()) {
            OneSignal.initWithContext(this, BuildConfig.ONESIGNAL_APP_ID)
            requestNotificationPermission()
        } else {
            Log.w("PhotoDoctor", "OneSignal APP_ID is empty - push notifications disabled")
        }
    }

    private fun initOpenCvAsync() {
        applicationScope.launch {
            val ok = OpenCVLoader.initDebug()
            OpenCVInitialized = ok
            if (ok) {
                if (BuildConfig.DEBUG) Log.d("PhotoDoctor", "OpenCV loaded successfully")
            } else {
                Log.e("PhotoDoctor", "OpenCV initialization failed!")
            }
        }
    }

    private fun requestNotificationPermission() {
        applicationScope.launch {
            OneSignal.Notifications.requestPermission(true)
        }
    }

    companion object {
        @Volatile
        var OpenCVInitialized: Boolean = false
            private set

        private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    }
}
