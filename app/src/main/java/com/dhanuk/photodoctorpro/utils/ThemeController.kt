package com.dhanuk.photodoctorpro.utils

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

object ThemeController {
    private val _isDarkTheme = MutableStateFlow(false)
    val isDarkTheme = _isDarkTheme.asStateFlow()

    fun init(context: Context) {
        _isDarkTheme.value = UserPreferences.isDarkMode(context)
    }

    fun setDarkTheme(context: Context, isDark: Boolean) {
        _isDarkTheme.value = isDark
        UserPreferences.setDarkMode(context, isDark)
    }
}
