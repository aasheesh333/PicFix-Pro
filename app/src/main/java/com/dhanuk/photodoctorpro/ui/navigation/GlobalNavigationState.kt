package com.dhanuk.photodoctorpro.ui.navigation

import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue

class GlobalNavigationState {
    var hasUnsavedChanges by mutableStateOf(false)
    var onSave: (suspend () -> Boolean)? = null
    var onDiscard: (() -> Unit)? = null

    // We can also store the "Cancel" action if needed, but usually Cancel just means "don't navigate"

    fun clear() {
        hasUnsavedChanges = false
        onSave = null
        onDiscard = null
    }
}

val LocalGlobalNavigationState = compositionLocalOf { GlobalNavigationState() }
