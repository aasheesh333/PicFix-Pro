package com.dhanuk.photodoctorpro.utils

import android.app.Activity
import android.content.Context
import android.util.Log
import com.dhanuk.photodoctorpro.BuildConfig
import com.google.android.ump.ConsentDebugSettings
import com.google.android.ump.ConsentForm
import com.google.android.ump.ConsentInformation
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
        val activity = context as? Activity ?: run {
            isConsentObtained = true
            AdManager.initialize(context)
            return
        }

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
            activity,
            params,
            object : ConsentInformation.OnConsentInfoUpdateSuccessListener {
                override fun onConsentInfoUpdateSuccess() {
                    isConsentRequired = consentInfo.isConsentRequired
                    if (isConsentRequired) {
                        loadAndShowConsentForm(activity, context)
                    } else {
                        isConsentObtained = true
                        AdManager.initialize(context)
                    }
                }
            },
            object : ConsentInformation.OnConsentInfoUpdateFailureListener {
                override fun onConsentInfoUpdateFailure(formError: FormError) {
                    if (BuildConfig.DEBUG) Log.e(TAG, "Consent info update failed: ${formError.message}")
                    isConsentRequired = false
                    isConsentObtained = true
                    AdManager.initialize(context)
                }
            }
        )
    }

    private fun loadAndShowConsentForm(activity: Activity, context: Context) {
        UserMessagingPlatform.loadConsentForm(
            context,
            object : UserMessagingPlatform.OnConsentFormLoadSuccessListener {
                override fun onConsentFormLoadSuccess(consentForm: ConsentForm) {
                    consentForm.show(
                        activity,
                        object : ConsentForm.OnConsentFormDismissedListener {
                            override fun onConsentFormDismissed(formError: FormError?) {
                                if (formError != null && BuildConfig.DEBUG) {
                                    Log.e(TAG, "Consent form show failed: ${formError.message}")
                                }
                                val info = UserMessagingPlatform.getConsentInformation(context)
                                isConsentObtained = info.canRequestAds()
                                AdManager.initialize(context)
                            }
                        }
                    )
                }
            },
            object : UserMessagingPlatform.OnConsentFormLoadFailureListener {
                override fun onConsentFormLoadFailure(formError: FormError) {
                    if (BuildConfig.DEBUG) Log.e(TAG, "Consent form load failed: ${formError.message}")
                    isConsentObtained = true
                    AdManager.initialize(context)
                }
            }
        )
    }

    fun resetConsent(context: Context) {
        UserMessagingPlatform.getConsentInformation(context).reset()
        init(context)
    }
}