package com.dhanuk.photodoctorpro.utils

import android.content.Context
import android.util.Log
import com.dhanuk.photodoctorpro.BuildConfig

object ConsentManager {

    private const val TAG = "ConsentManager"
    @Volatile var isConsentRequired: Boolean = false
        private set
    @Volatile var isConsentObtained: Boolean = true
        private set

    fun init(context: Context) {
        isConsentObtained = true
        AdManager.initialize(context)
        if (BuildConfig.DEBUG) Log.d(TAG, "AdManager initialized (UMP simplified)")
    }

    fun resetConsent(context: Context) {
        isConsentObtained = true
        init(context)
    }
}