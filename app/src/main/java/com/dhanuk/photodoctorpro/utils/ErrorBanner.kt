package com.dhanuk.photodoctorpro.utils

import android.app.Application
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

object ErrorBanner {
    private val _openCvErrorShown = MutableStateFlow(false)
    fun dismissOpenCvError() {
        _openCvErrorShown.value = true
    }

    fun openCvFailed(context: Application) {
        _openCvErrorShown.value = true
    }

    @Composable
    fun GlobalErrorBanner() {
        val picFixApp = com.dhanuk.photodoctorpro.PicFixApplication
        val openCvFailed by picFixApp.openCVInitFailed.collectAsState(false)
        val openCvErrorDismissed by openCvErrorShown.collectAsState(false)

        if (openCvFailed && !openCvErrorDismissed) {
            LuminaFlatErrorBanner(
                error = "Image processing engine failed to initialize. Some features may not work."
            )
        }
    }
}
