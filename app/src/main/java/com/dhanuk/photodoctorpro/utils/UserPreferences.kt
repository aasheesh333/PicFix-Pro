package com.dhanuk.photodoctorpro.utils

import android.content.Context

object UserPreferences {
    private const val PREFS_NAME = "photo_doctor_prefs"
    private const val KEY_SAVE_DIRECTORY = "save_directory"
    private const val KEY_DARK_MODE = "dark_mode"

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
        // Default to false (Light) if not set.
        // Ideally we should check if set, but simple toggle implies boolean.
        return prefs.getBoolean(KEY_DARK_MODE, false)
    }

    fun setDarkMode(context: Context, enabled: Boolean) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putBoolean(KEY_DARK_MODE, enabled).apply()
    }
}
