package com.dhanuk.photodoctorpro.utils

import android.util.Log
import com.dhanuk.photodoctorpro.BuildConfig
import kotlinx.coroutines.CoroutineExceptionHandler

/**
 * Default CoroutineExceptionHandler for ViewModel coroutines. Logs the error in debug
 * and rethrows so that the process-level CrashReporter can capture it. Cancellation
 * exceptions are not handled here.
 */
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
