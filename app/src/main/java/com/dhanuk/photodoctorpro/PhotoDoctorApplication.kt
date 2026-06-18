package com.dhanuk.photodoctorpro

import android.app.Application
import android.util.Log
import com.onesignal.OneSignal
import com.onesignal.debug.LogLevel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.opencv.android.OpenCVLoader
import com.dhanuk.photodoctorpro.utils.ThemeController

class PhotoDoctorApplication : Application() {
    override fun onCreate() {
        super.onCreate()

        ThemeController.init(this)

        if (OpenCVLoader.initDebug()) {
            Log.d("PhotoDoctor", "OpenCV loaded successfully")
        } else {
            Log.e("PhotoDoctor", "OpenCV initialization failed!")
        }

        if (BuildConfig.DEBUG) {
            OneSignal.Debug.logLevel = LogLevel.VERBOSE
        } else {
            OneSignal.Debug.logLevel = LogLevel.NONE
        }

        if (BuildConfig.ONESIGNAL_APP_ID.isNotEmpty()) {
            OneSignal.initWithContext(this, BuildConfig.ONESIGNAL_APP_ID)
        } else {
            Log.w("PhotoDoctor", "OneSignal APP_ID is empty - push notifications disabled")
        }

        // requestPermission will show the native Android notification permission prompt.
        // NOTE: It's recommended to call this from your UI layer instead.
        CoroutineScope(Dispatchers.Main).launch {
            OneSignal.Notifications.requestPermission(true)
        }
    }
}
