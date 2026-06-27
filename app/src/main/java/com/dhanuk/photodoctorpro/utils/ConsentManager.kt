package com.dhanuk.photodoctorpro.utils

import android.app.Activity
import android.content.Context
import android.util.Log
import com.dhanuk.photodoctorpro.BuildConfig
import com.google.android.ump.ConsentInformation
import com.google.android.ump.ConsentRequestParameters
import com.google.android.ump.FormError
import com.google.android.ump.UserMessagingPlatform

object ConsentManager {

    private const val TAG = "ConsentManager"

    @Volatile var isConsentObtained: Boolean = false
        private set

    private var consentInformation: ConsentInformation? = null

    fun init(context: Context) {
        val params = ConsentRequestParameters.Builder()
            .setTagForUnderAgeOfConsent(false)
            .build()

        consentInformation = UserMessagingPlatform.getConsentInformation(context)
        consentInformation?.requestConsentInfoUpdate(
            context,
            params,
            {
                val info = consentInformation ?: return@requestConsentInfoUpdate
                if (info.isConsentFormAvailable && context is Activity) {
                    loadAndShowConsentForm(context)
                } else {
                    checkConsentAndInitAds(context)
                }
            },
            { formError: FormError ->
                Log.e(TAG, "Consent info request failed: ${formError.message}")
                checkConsentAndInitAds(context)
            }
        )
    }

    private fun loadAndShowConsentForm(activity: Activity) {
        UserMessagingPlatform.loadConsentForm(
            activity,
            { consentForm ->
                consentForm.show(activity) { formError ->
                    if (formError != null) {
                        Log.e(TAG, "Consent form error: ${formError.message}")
                    }
                    checkConsentAndInitAds(activity)
                }
            },
            { formError: FormError ->
                Log.e(TAG, "Consent form load failed: ${formError.message}")
                checkConsentAndInitAds(activity)
            }
        )
    }

    private fun checkConsentAndInitAds(context: Context) {
        val info = consentInformation
        if (info != null) {
            isConsentObtained = info.canRequestAds()
        }
        if (canRequestAds()) {
            AdManager.initialize(context)
        }
    }

    fun canRequestAds(): Boolean {
        val info = consentInformation
        return if (info != null) {
            info.canRequestAds()
        } else {
            isConsentObtained
        }
    }

    fun resetConsent(context: Context) {
        consentInformation?.reset()
        isConsentObtained = false
        init(context)
    }
}
