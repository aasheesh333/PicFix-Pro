package com.dhanuk.photodoctorpro.ui.screens

import android.app.Activity
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import com.dhanuk.photodoctorpro.R
import com.dhanuk.photodoctorpro.data.local.AppDatabase
import com.dhanuk.photodoctorpro.data.repository.HistoryRepository
import com.dhanuk.photodoctorpro.ui.components.BannerAd
import java.net.URLDecoder

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(navController: NavController) {
    val context = LocalContext.current
    val db = AppDatabase.getDatabase(context)
    val repository = HistoryRepository(db.historyDao())
    val historyViewModel: HistoryViewModel = viewModel(factory = ViewModelFactory(repository))
    val settingsViewModel: SettingsViewModel = viewModel()

    val saveDirectory by settingsViewModel.saveDirectory.collectAsState()
    var showHistoryDialog by remember { mutableStateOf(false) }

    val directoryPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { uri: Uri? ->
        if (uri != null) {
            settingsViewModel.setSaveDirectory(uri)
        }
    }

    if (showHistoryDialog) {
        AlertDialog(
            onDismissRequest = { showHistoryDialog = false },
            title = { Text(stringResource(R.string.clear_history)) },
            text = { Text(stringResource(R.string.are_you_sure_you_want_to_clear_all_history)) },
            confirmButton = {
                Button(
                    onClick = {
                        historyViewModel.clearHistory()
                        showHistoryDialog = false
                    }
                ) {
                    Text(stringResource(R.string.clear))
                }
            },
            dismissButton = {
                TextButton(onClick = { showHistoryDialog = false }) {
                    Text(stringResource(R.string.cancel))
                }
            }
        )
    }

    Scaffold(
        topBar = { TopAppBar(title = { Text(stringResource(R.string.settings)) }) }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp)
        ) {
            Text("Storage", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.primary)
            Spacer(modifier = Modifier.height(8.dp))

            Text("Save Location:", style = MaterialTheme.typography.bodyMedium)

            val displayPath = if (saveDirectory != null) {
                try {
                    // Try to decode readable path, though URI structure varies
                    URLDecoder.decode(saveDirectory, "UTF-8")
                } catch (e: Exception) {
                    saveDirectory
                }
            } else {
                "Default (App Folder)"
            }

            Text(
                text = displayPath ?: "Default",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(vertical = 4.dp)
            )

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { directoryPicker.launch(null) }) {
                    Text("Change Folder")
                }
                if (saveDirectory != null) {
                    OutlinedButton(onClick = { settingsViewModel.clearSaveDirectory() }) {
                        Text("Reset to Default")
                    }
                }
            }

            Divider(modifier = Modifier.padding(vertical = 16.dp))

            Text(stringResource(R.string.app_info), style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.primary)
            Spacer(modifier = Modifier.height(8.dp))
            Text(stringResource(R.string.app_name))
            Text(stringResource(R.string.version_1_0))
            Text(stringResource(R.string.developer_dhanuk_software))

            Divider(modifier = Modifier.padding(vertical = 16.dp))

            Text("Data Management", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.primary)
            Spacer(modifier = Modifier.height(8.dp))
            Button(
                onClick = { showHistoryDialog = true },
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
            ) {
                Text(stringResource(R.string.clear_all_history))
            }

            Divider(modifier = Modifier.padding(vertical = 16.dp))

            Text(stringResource(R.string.legal), style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.primary)
            Spacer(modifier = Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { navController.navigate("privacy_policy") }) {
                    Text(stringResource(R.string.privacy_policy))
                }
                OutlinedButton(onClick = { navController.navigate("terms_and_conditions") }) {
                    Text(stringResource(R.string.terms_conditions))
                }
            }

            Spacer(modifier = Modifier.weight(1f))
            BannerAd()
        }
    }
}
