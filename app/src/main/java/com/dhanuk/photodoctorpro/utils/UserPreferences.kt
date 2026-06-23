package com.dhanuk.photodoctorpro.utils

import android.content.Context

object UserPreferences {
    private const val PREFS_NAME = "photo_doctor_prefs"
    private const val KEY_SAVE_DIRECTORY = "save_directory"
    private const val KEY_DARK_MODE = "dark_mode"
    private const val KEY_FOLLOW_SYSTEM = "follow_system"

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
        if (prefs.contains(KEY_FOLLOW_SYSTEM) && prefs.getBoolean(KEY_FOLLOW_SYSTEM, true)) {
            return isSystemDarkMode(context)
        }
        return prefs.getBoolean(KEY_DARK_MODE, isSystemDarkMode(context))
    }

    fun isFollowSystem(context: Context): Boolean {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getBoolean(KEY_FOLLOW_SYSTEM, true)
    }

    fun setFollowSystem(context: Context, follow: Boolean) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putBoolean(KEY_FOLLOW_SYSTEM, follow).apply()
    }

    private fun isSystemDarkMode(context: Context): Boolean {
        return context.resources.configuration.uiMode and
                android.content.res.Configuration.UI_MODE_NIGHT_MASK ==
                android.content.res.Configuration.UI_MODE_NIGHT_YES
    }

    fun setDarkMode(context: Context, enabled: Boolean) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putBoolean(KEY_DARK_MODE, enabled)
            .putBoolean(KEY_FOLLOW_SYSTEM, false).apply()
    }
}
