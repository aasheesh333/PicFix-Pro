package com.dhanuk.photodoctorpro.utils

import android.app.Activity
import android.content.Context
import android.util.Log
import com.google.android.play.core.review.ReviewManagerFactory

object InAppReviewManager {

    private const val TAG = "InAppReviewManager"
    @Volatile var isReviewInProgress = false
        private set

    fun requestReviewIfNeeded(context: Context) {
        if (UserPreferences.hasRequestedReview(context)) return
        val activity = context as? Activity ?: return

        isReviewInProgress = true
        try {
            val reviewManager = ReviewManagerFactory.create(context)
            val request = reviewManager.requestReviewFlow()
            request.addOnCompleteListener { task ->
                if (task.isSuccessful) {
                    val reviewInfo = task.result
                    reviewManager.launchReviewFlow(activity, reviewInfo).addOnCompleteListener {
                        isReviewInProgress = false
                        if (com.dhanuk.photodoctorpro.BuildConfig.DEBUG) {
                            Log.d(TAG, "In-App Review flow completed")
                        }
                    }
                } else {
                    isReviewInProgress = false
                    if (com.dhanuk.photodoctorpro.BuildConfig.DEBUG) {
                        Log.w(TAG, "Review flow failed: ${task.exception?.message}")
                    }
                }
            }
        } catch (e: Exception) {
            isReviewInProgress = false
            if (com.dhanuk.photodoctorpro.BuildConfig.DEBUG) {
                Log.e(TAG, "Error launching review", e)
            }
        }
    }
}