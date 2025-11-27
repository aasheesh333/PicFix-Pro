package com.dhanuk.photodoctorpro

import android.app.Application
import com.onesignal.OneSignal
import com.onesignal.debug.LogLevel

class PhotoDoctorApplication : Application() {
    override fun onCreate() {
        super.onCreate()

        // Verbose Logging set to help debug issues, remove before releasing your app.
        OneSignal.Debug.logLevel = LogLevel.VERBOSE

        // OneSignal Initialization
        OneSignal.initWithContext(this, BuildConfig.ONESIGNAL_APP_ID)

        // requestPermission will show the native Android notification permission prompt.
        // NOTE: It's recommended to call this from your UI layer instead.
        // OneSignal.Notifications.requestPermission(true)
    }
}
