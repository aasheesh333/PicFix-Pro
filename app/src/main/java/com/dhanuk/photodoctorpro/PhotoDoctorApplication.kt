package com.dhanuk.photodoctorpro

import android.app.Application
import android.util.Log
import com.onesignal.OneSignal
import com.onesignal.debug.LogLevel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.opencv.android.OpenCVLoader

class PhotoDoctorApplication : Application() {
    override fun onCreate() {
        super.onCreate()

        if (OpenCVLoader.initDebug()) {
            Log.d("PhotoDoctor", "OpenCV loaded successfully")
        } else {
            Log.e("PhotoDoctor", "OpenCV initialization failed!")
        }

        // Verbose Logging set to help debug issues, remove before releasing your app.
        OneSignal.Debug.logLevel = LogLevel.VERBOSE

        // OneSignal Initialization
        OneSignal.initWithContext(this, BuildConfig.ONESIGNAL_APP_ID)

        // requestPermission will show the native Android notification permission prompt.
        // NOTE: It's recommended to call this from your UI layer instead.
        CoroutineScope(Dispatchers.Main).launch {
            OneSignal.Notifications.requestPermission(true)
        }
    }
}
