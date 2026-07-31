package com.dhanuk.photodoctorpro

import android.app.Application
import android.app.ActivityManager
import android.os.Build
import android.util.Log
import com.onesignal.OneSignal
import com.onesignal.debug.LogLevel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.opencv.android.OpenCVLoader
import com.dhanuk.photodoctorpro.utils.CrashReporter
import com.dhanuk.photodoctorpro.utils.ErrorBanner
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

        if (BuildConfig.DEBUG) {
            OneSignal.Debug.logLevel = LogLevel.VERBOSE
        } else {
            OneSignal.Debug.logLevel = LogLevel.NONE
        }

        if (isMainProcess()) {
            initializeOneSignal()
        }
    }

    private fun initOpenCvAsync() {
        applicationScope.launch(Dispatchers.IO) {
            val ok = try {
                OpenCVLoader.initDebug()
            } catch (e: Exception) {
                if (BuildConfig.DEBUG) Log.e("PicFix", "OpenCV init threw", e)
                false
            }
            OpenCVInitialized = ok
            OpenCVInitializedFlow.value = ok
        if (ok) {
            if (BuildConfig.DEBUG) Log.d("PicFix", "OpenCV loaded successfully")
        } else {
            OpenCVInitFailureFlow.value = true
            ErrorBanner.openCvFailed(this@PicFixApplication)
            Log.e("PicFix", "OpenCV initialization failed!")
        }
        }
    }

    private fun requestNotificationPermission() {
        applicationScope.launch {
            OneSignal.Notifications.requestPermission(true)
        }
    }

    private fun isMainProcess(): Boolean {
        val currentProcessName = getCurrentProcessName()
        return currentProcessName == packageName
    }

    private fun getCurrentProcessName(): String {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            return getProcessName()
        }
        val pid = android.os.Process.myPid()
        val am = getSystemService(ACTIVITY_SERVICE) as ActivityManager
        val processes = am.runningAppProcesses ?: return packageName
        for (info in processes) {
            if (info.pid == pid) return info.processName
        }
        return packageName
    }

    private fun initializeOneSignal() {
        if (BuildConfig.ONESIGNAL_APP_ID.isEmpty()) {
            Log.w("PicFix", "OneSignal APP_ID is empty - push notifications disabled")
            return
        }
        OneSignal.initWithContext(this, BuildConfig.ONESIGNAL_APP_ID)
        requestNotificationPermission()
    }

    companion object {
        @Volatile
        var OpenCVInitialized: Boolean = false
            private set

        val OpenCVInitializedFlow = MutableStateFlow(false)
        val OpenCVInitFailureFlow = MutableStateFlow(false)
        val openCVInitialized: StateFlow<Boolean> get() = OpenCVInitializedFlow
        val openCVInitFailed: StateFlow<Boolean> get() = OpenCVInitFailureFlow

        private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    }
}
