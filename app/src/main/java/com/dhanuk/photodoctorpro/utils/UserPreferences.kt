package com.dhanuk.photodoctorpro.utils

import android.content.Context

object UserPreferences {
    private const val PREFS_NAME = "photo_doctor_prefs"
    private const val KEY_SAVE_DIRECTORY = "save_directory"
    private const val KEY_DARK_MODE = "dark_mode"
    private const val KEY_FOLLOW_SYSTEM = "follow_system"
    private const val KEY_HAS_REQUESTED_REVIEW = "has_requested_review"
    private const val KEY_FIRST_SAVE_COMPLETED = "first_save_completed"

    fun getSaveDirectory(context: Context): String? {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getString(KEY_SAVE_DIRECTORY, null)
    }

    fun setSaveDirectory(context: Context, uriString: String) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putString(KEY_SAVE_DIRECTORY, uriString).apply()
    }

    fun isDarkMode(context: Context): Boolean {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        if (prefs.contains(KEY_FOLLOW_SYSTEM) && prefs.getBoolean(KEY_FOLLOW_SYSTEM, false)) {
            return isSystemDarkMode(context)
        }
        return prefs.getBoolean(KEY_DARK_MODE, false)
    }

    fun isFollowSystem(context: Context): Boolean {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getBoolean(KEY_FOLLOW_SYSTEM, false)
    }

    fun setFollowSystem(context: Context, follow: Boolean) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putBoolean(KEY_FOLLOW_SYSTEM, follow).apply()
    }

    fun setDarkMode(context: Context, enabled: Boolean) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putBoolean(KEY_DARK_MODE, enabled)
            .putBoolean(KEY_FOLLOW_SYSTEM, false).apply()
    }

    private fun isSystemDarkMode(context: Context): Boolean {
        return context.resources.configuration.uiMode and
                android.content.res.Configuration.UI_MODE_NIGHT_MASK ==
                android.content.res.Configuration.UI_MODE_NIGHT_YES
    }

    fun hasRequestedReview(context: Context): Boolean {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getBoolean(KEY_HAS_REQUESTED_REVIEW, false)
    }

    fun setHasRequestedReview(context: Context) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putBoolean(KEY_HAS_REQUESTED_REVIEW, true).apply()
    }

    fun isFirstSaveCompleted(context: Context): Boolean {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getBoolean(KEY_FIRST_SAVE_COMPLETED, false)
    }

    fun setFirstSaveCompleted(context: Context) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putBoolean(KEY_FIRST_SAVE_COMPLETED, true).apply()
    }
}