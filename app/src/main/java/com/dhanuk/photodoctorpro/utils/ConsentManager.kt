package com.dhanuk.photodoctorpro.utils

import android.app.Activity
import android.content.Context
import android.util.Log
import com.dhanuk.photodoctorpro.BuildConfig
import com.google.android.ump.ConsentDebugSettings
import com.google.android.ump.ConsentForm
import com.google.android.ump.ConsentRequestParameters
import com.google.android.ump.FormError
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
        val consentInfo = UserMessagingPlatform.getConsentInformation(context)

        consentInfo.requestConsentInfoUpdate(
            params,
            {
                isConsentRequired = consentInfo.isConsentRequired
                if (isConsentRequired) {
                    loadAndShowConsentForm(context)
                } else {
                    isConsentObtained = true
                    AdManager.initialize(context)
                }
            },
            { requestConsentError: FormError ->
                if (BuildConfig.DEBUG) Log.e(TAG, "Consent info update failed: ${requestConsentError.message}")
                isConsentRequired = false
                isConsentObtained = true
                AdManager.initialize(context)
            }
        )
    }

    private fun loadAndShowConsentForm(context: Context) {
        UserMessagingPlatform.loadConsentForm(
            context,
            { consentForm: ConsentForm ->
                val activity = context as? Activity
                if (activity != null) {
                    consentForm.show(
                        activity,
                        { formError: FormError? ->
                            if (formError != null && BuildConfig.DEBUG) {
                                Log.e(TAG, "Consent form show failed: ${formError.message}")
                            }
                            val info = UserMessagingPlatform.getConsentInformation(context)
                            isConsentObtained = info.canRequestAds()
                            AdManager.initialize(context)
                        }
                    )
                } else {
                    isConsentObtained = true
                    AdManager.initialize(context)
                }
            },
            { formLoadError: FormError ->
                if (BuildConfig.DEBUG) Log.e(TAG, "Consent form load failed: ${formLoadError.message}")
                isConsentObtained = true
                AdManager.initialize(context)
            }
        )
    }

    fun resetConsent(context: Context) {
        UserMessagingPlatform.getConsentInformation(context).reset()
        init(context)
    }
}