package com.dhanuk.photodoctorpro.utils

import android.content.Context
import android.net.Uri
import androidx.core.content.edit

object UserPreferences {
    private const val PREFS_NAME = "user_prefs"
    private const val KEY_SAVE_DIR = "save_dir"

    fun getSaveDirectory(context: Context): String? {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getString(KEY_SAVE_DIR, null)
    }

    fun setSaveDirectory(context: Context, uriString: String?) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit {
            putString(KEY_SAVE_DIR, uriString)
        }
    }
}
