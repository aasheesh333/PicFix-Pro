package com.dhanuk.photodoctorpro.utils

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.os.Looper
import android.util.Log
import android.widget.Toast
import com.dhanuk.photodoctorpro.BuildConfig
import com.dhanuk.photodoctorpro.R
import java.lang.ref.WeakReference

object CrashReporter {

    private const val TAG = "PhotoDoctorCrash"
    private var installed = false
    private var currentActivity: WeakReference<Activity>? = null

    fun install() {
        if (installed) return
        installed = true
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            if (BuildConfig.DEBUG) {
                Log.e(TAG, "Uncaught exception on thread ${thread.name}", throwable)
            }
            val activity = currentActivity?.get()
            if (activity != null && !activity.isFinishing && !activity.isDestroyed) {
                try {
                    activity.runOnUiThread { showFatalDialog(activity, throwable) }
                } catch (e: Exception) {
                    if (BuildConfig.DEBUG) {
                        Log.e(TAG, "Failed to show fatal dialog", e)
                    }
                }
            }
            if (previous != null) {
                previous.uncaughtException(thread, throwable)
            }
        }
    }

    fun registerActivity(activity: Activity) {
        currentActivity = WeakReference(activity)
    }

    fun unregisterActivity(activity: Activity) {
        val ref = currentActivity
        if (ref != null && ref.get() === activity) {
            currentActivity = null
        }
    }

    fun showFatalDialog(activity: Activity, throwable: Throwable) {
        if (BuildConfig.DEBUG) {
            Log.e(TAG, "Fatal error shown to user", throwable)
        }
        try {
            AlertDialog.Builder(activity)
                .setTitle(activity.getString(R.string.something_went_wrong))
                .setMessage(activity.getString(R.string.fatal_error_message))
                .setPositiveButton(activity.getString(R.string.ok)) { _, _ ->
                    activity.finishAffinity()
                }
                .setCancelable(false)
                .show()
        } catch (e: Exception) {
            Toast.makeText(activity, R.string.fatal_error_restart, Toast.LENGTH_LONG).show()
        }
    }
}
