package com.dhanuk.photodoctorpro.utils

import android.util.Log
import com.dhanuk.photodoctorpro.BuildConfig
import kotlinx.coroutines.CoroutineExceptionHandler

fun viewModelExceptionHandler(tag: String): CoroutineExceptionHandler =
    CoroutineExceptionHandler { _, throwable ->
        if (throwable is kotlinx.coroutines.CancellationException) return@CoroutineExceptionHandler
        if (BuildConfig.DEBUG) {
            Log.e(tag, "Unhandled coroutine exception", throwable)
        }
        if (throwable is OutOfMemoryError || throwable is StackOverflowError) {
            Thread.getDefaultUncaughtExceptionHandler()
                ?.uncaughtException(Thread.currentThread(), throwable)
        }
    }

fun getOpenCvNotReadyMessage(): String {
    return "Image processing engine is not ready yet. Please try again in a moment."
}

fun getBitmapAllocFailedMessage(): String {
    return "Could not allocate bitmap"
}

fun getHigherScalesDisabledMessage(): String {
    return "Higher scales disabled for very large images."
}
