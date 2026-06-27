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
import com.dhanuk.photodoctorpro.utils.ConsentManager
import com.dhanuk.photodoctorpro.utils.CrashReporter
import com.dhanuk.photodoctorpro.utils.NotificationHelper
import com.dhanuk.photodoctorpro.utils.ThemeController
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

class PicFixApplication : Application() {
    override fun onCreate() {
        super.onCreate()

        CrashReporter.install()
        ThemeController.init(this)

        initOpenCvAsync()

        NotificationHelper.createNotificationChannel(this)

        ConsentManager.init(this)

        if (BuildConfig.DEBUG) {
            OneSignal.Debug.logLevel = LogLevel.VERBOSE
        } else {
            OneSignal.Debug.logLevel = LogLevel.NONE
        }

        if (BuildConfig.ONESIGNAL_APP_ID.isNotEmpty()) {
            OneSignal.initWithContext(this, BuildConfig.ONESIGNAL_APP_ID)
            requestNotificationPermission()
        } else {
            Log.w("PicFix", "OneSignal APP_ID is empty - push notifications disabled")
        }
    }

    private fun initOpenCvAsync() {
        applicationScope.launch(Dispatchers.IO) {
            val ok = OpenCVLoader.initDebug()
            OpenCVInitialized = ok
            OpenCVInitializedFlow.value = ok
            if (ok) {
                if (BuildConfig.DEBUG) Log.d("PicFix", "OpenCV loaded successfully")
            } else {
                Log.e("PicFix", "OpenCV initialization failed!")
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

        val OpenCVInitializedFlow = MutableStateFlow(false)
        val openCVInitialized: StateFlow<Boolean> get() = OpenCVInitializedFlow

        private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    }
}