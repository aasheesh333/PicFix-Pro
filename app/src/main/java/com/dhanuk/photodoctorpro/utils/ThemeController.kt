package com.dhanuk.photodoctorpro.utils

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

object ThemeController {
    private val _isDarkTheme = MutableStateFlow(false)
    val isDarkTheme = _isDarkTheme.asStateFlow()

    private var _followSystem = false
    val followSystem: Boolean get() = _followSystem

    fun init(context: Context) {
        _followSystem = UserPreferences.isFollowSystem(context)
        _isDarkTheme.value = UserPreferences.isDarkMode(context)
    }

    fun setDarkTheme(context: Context, isDark: Boolean) {
        _followSystem = false
        _isDarkTheme.value = isDark
        UserPreferences.setDarkMode(context, isDark)
    }

    fun setFollowSystem(context: Context, follow: Boolean) {
        _followSystem = follow
        UserPreferences.setFollowSystem(context, follow)
        if (follow) {
            _isDarkTheme.value = UserPreferences.isDarkMode(context)
        }
    }

    fun onSystemDarkModeChanged(isDark: Boolean) {
        if (_followSystem) {
            _isDarkTheme.value = isDark
        }
    }
}