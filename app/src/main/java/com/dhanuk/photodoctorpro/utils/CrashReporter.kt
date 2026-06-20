package com.dhanuk.photodoctorpro.utils

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.os.Looper
import android.util.Log
import android.widget.Toast
import com.dhanuk.photodoctorpro.BuildConfig

object CrashReporter {

    private const val TAG = "PhotoDoctorCrash"
    private var installed = false

    fun install() {
        if (installed) return
        installed = true
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            if (BuildConfig.DEBUG) {
                Log.e(TAG, "Uncaught exception on thread ${thread.name}", throwable)
            }
            if (previous != null) {
                previous.uncaughtException(thread, throwable)
            }
        }
    }

    fun showFatalDialog(activity: Activity, throwable: Throwable) {
        if (BuildConfig.DEBUG) {
            Log.e(TAG, "Fatal error shown to user", throwable)
        }
        activity.runOnUiThread {
            try {
                AlertDialog.Builder(activity)
                    .setTitle("Something went wrong")
                    .setMessage("An unexpected error occurred. The app will close. Please try again.")
                    .setPositiveButton("OK") { _, _ ->
                        activity.finishAffinity()
                    }
                    .setCancelable(false)
                    .show()
            } catch (e: Exception) {
                Toast.makeText(activity, "Unexpected error. Restart the app.", Toast.LENGTH_LONG).show()
            }
        }
    }
}
