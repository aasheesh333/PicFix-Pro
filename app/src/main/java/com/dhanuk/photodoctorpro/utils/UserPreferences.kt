package com.dhanuk.photodoctorpro.utils

import android.content.Context

object UserPreferences {
    private const val PREFS_NAME = "photo_doctor_prefs"
    private const val KEY_SAVE_DIRECTORY = "save_directory"
    private const val KEY_DARK_MODE = "dark_mode"
    private const val KEY_FOLLOW_SYSTEM = "follow_system"
    private const val KEY_HAS_REQUESTED_REVIEW = "has_requested_review"
    private const val KEY_FIRST_SAVE_COMPLETED = "first_save_completed"
    private const val KEY_REMINDERS_ENABLED = "reminders_enabled"
    private const val KEY_SAVE_FORMAT = "save_format"
    private const val KEY_SAVE_QUALITY = "save_quality"
    private const val KEY_SAVE_BG_COLOR = "save_bg_color"

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

    fun isRemindersEnabled(context: Context): Boolean {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getBoolean(KEY_REMINDERS_ENABLED, false)
    }

    fun setRemindersEnabled(context: Context, enabled: Boolean) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putBoolean(KEY_REMINDERS_ENABLED, enabled).apply()
    }

    fun getSaveOptions(context: Context): SaveOptions {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val formatName = prefs.getString(KEY_SAVE_FORMAT, SaveFormat.JPEG.name) ?: SaveFormat.JPEG.name
        val format = runCatching { SaveFormat.valueOf(formatName) }.getOrDefault(SaveFormat.JPEG)
        val quality = prefs.getInt(KEY_SAVE_QUALITY, 95).coerceIn(1, 100)
        val bgColor = if (prefs.contains(KEY_SAVE_BG_COLOR)) prefs.getInt(KEY_SAVE_BG_COLOR, 0xFFFFFFFF.toInt()) else null
        return SaveOptions(format = format, quality = quality, bgColor = bgColor)
    }

    fun setSaveOptions(context: Context, options: SaveOptions) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit()
            .putString(KEY_SAVE_FORMAT, options.format.name)
            .putInt(KEY_SAVE_QUALITY, options.quality)
            .apply()
        val editor = prefs.edit()
        if (options.bgColor != null) {
            editor.putInt(KEY_SAVE_BG_COLOR, options.bgColor)
        } else {
            editor.remove(KEY_SAVE_BG_COLOR)
        }
        editor.apply()
    }
}