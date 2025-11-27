package com.dhanuk.photodoctorpro

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import com.dhanuk.photodoctorpro.ui.navigation.AppScaffold
import com.dhanuk.photodoctorpro.ui.theme.PhotoDoctorProTheme
import com.dhanuk.photodoctorpro.utils.AdManager

class MainActivity : ComponentActivity() {

    private var showPermissionDeniedDialog by mutableStateOf(false)

    private val requestPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted: Boolean ->
            if (!isGranted) {
                showPermissionDeniedDialog = true
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AdManager.initialize(this)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            requestPermissionLauncher.launch(Manifest.permission.READ_MEDIA_IMAGES)
        } else {
            requestPermissionLauncher.launch(Manifest.permission.READ_EXTERNAL_STORAGE)
        }

        setContent {
            PhotoDoctorProTheme {
                if (showPermissionDeniedDialog) {
                    PermissionDeniedDialog {
                        showPermissionDeniedDialog = false
                    }
                } else {
                    AppScaffold()
                }
            }
        }
    }
}

@Composable
fun PermissionDeniedDialog(onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Permission Denied") },
        text = { Text("This app requires storage access to function. Please grant the permission in your device settings.") },
        confirmButton = {
            Button(onClick = onDismiss) {
                Text("OK")
            }
        }
    )
}
