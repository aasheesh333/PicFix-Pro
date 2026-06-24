package com.dhanuk.photodoctorpro.utils

import android.app.Activity
import android.content.Context
import android.util.Log
import com.dhanuk.photodoctorpro.BuildConfig
import com.google.android.ump.ConsentDebugSettings
import com.google.android.ump.ConsentRequestParameters
import com.google.android.ump.UserMessagingPlatform

object ConsentManager {

    private const val TAG = "ConsentManager"
    @Volatile var isConsentRequired: Boolean = false
        private set
    @Volatile var isConsentObtained: Boolean = false
        private set

    fun init(context: Context) {
        val builder = ConsentRequestParameters.Builder()
            .setTagForUnderAgeOfConsent(false)

        if (BuildConfig.DEBUG) {
            val debugSettings = ConsentDebugSettings.Builder()
                .setDebugGeography(ConsentDebugSettings.DebugGeography.DEBUG_GEOGRAPHY_EEA)
                .addTestDeviceHashedId("EMULATOR")
                .build()
            builder.setConsentDebugSettings(debugSettings)
        }

        val params = builder.build()

        UserMessagingPlatform.getConsentInformation(context).let { consentInfo ->
            consentInfo.requestConsentInfoUpdate(params) {
                if (it != null) {
                    if (BuildConfig.DEBUG) Log.e(TAG, "Consent info update failed: ${it.message}")
                    isConsentRequired = false
                    isConsentObtained = true
                    AdManager.initialize(context)
                    return@requestConsentInfoUpdate
                }

                isConsentRequired = consentInfo.isConsentRequired
                if (isConsentRequired) {
                    loadAndShowConsentForm(context)
                } else {
                    isConsentObtained = true
                    AdManager.initialize(context)
                }
            }
        }
    }

    private fun loadAndShowConsentForm(context: Context) {
        UserMessagingPlatform.loadConsentForm(context) { form, error ->
            if (error != null) {
                if (BuildConfig.DEBUG) Log.e(TAG, "Consent form load failed: ${error.message}")
                isConsentObtained = true
                AdManager.initialize(context)
                return@loadConsentForm
            }

            val activity = context as? Activity
            if (form != null && activity != null) {
                form.show(activity) { showError ->
                    if (showError != null) {
                        if (BuildConfig.DEBUG) Log.e(TAG, "Consent form show failed: ${showError.message}")
                    }
                    val consentInfo = UserMessagingPlatform.getConsentInformation(context)
                    isConsentObtained = consentInfo.canRequestAds()
                    AdManager.initialize(context)
                }
            } else {
                isConsentObtained = true
                AdManager.initialize(context)
            }
        }
    }

    fun resetConsent(context: Context) {
        UserMessagingPlatform.getConsentInformation(context).reset()
        init(context)
    }
}