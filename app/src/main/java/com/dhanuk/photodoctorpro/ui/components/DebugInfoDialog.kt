package com.dhanuk.photodoctorpro.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import com.dhanuk.photodoctorpro.BuildConfig
import com.dhanuk.photodoctorpro.utils.AdManager

@Composable
fun DebugInfoDialog(onDismiss: () -> Unit) {
    fun maskId(id: String): String {
        return if (id.length > 10) "${id.take(10)}...${id.takeLast(4)}" else id
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Debug Info") },
        text = {
            Column {
                Text("App ID: ${maskId(BuildConfig.ADMOB_APP_ID)}")
                Text("Interstitial: ${maskId(BuildConfig.ADMOB_INTERSTITIAL_ID)}")
                Text("Banner: ${maskId(BuildConfig.ADMOB_BANNER_ID)}")
                Text("-----")
                Text("Ad Loaded: ${AdManager.isAdLoaded}")
                Text("Last Error: ${AdManager.lastLoadError}")
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Close")
            }
        }
    )
}
