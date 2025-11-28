package com.dhanuk.photodoctorpro.utils

import android.content.Context

object UserPreferences {
    private const val PREFS_NAME = "photo_doctor_prefs"
    private const val KEY_SAVE_DIRECTORY = "save_directory"

    fun getSaveDirectory(context: Context): String? {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getString(KEY_SAVE_DIRECTORY, null)
    }

    fun setSaveDirectory(context: Context, uriString: String) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putString(KEY_SAVE_DIRECTORY, uriString).apply()
    }
}
