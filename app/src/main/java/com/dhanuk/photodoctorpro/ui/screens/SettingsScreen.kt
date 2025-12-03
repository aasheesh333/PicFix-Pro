package com.dhanuk.photodoctorpro.ui.screens

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import com.dhanuk.photodoctorpro.R
import com.dhanuk.photodoctorpro.utils.ThemeController
import com.dhanuk.photodoctorpro.utils.UserPreferences

class SettingsViewModel : ViewModel() {
    var saveDirectory by mutableStateOf<String?>(null)
        private set

    fun loadSettings(context: Context) {
        saveDirectory = UserPreferences.getSaveDirectory(context)
    }

    fun updateSaveDirectory(context: Context, uri: Uri?) {
        if (uri != null) {
            // Persist permission
            try {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )
            } catch (e: Exception) {
                e.printStackTrace()
            }
            UserPreferences.setSaveDirectory(context, uri.toString())
            saveDirectory = uri.toString()
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(navController: NavController) {
    val context = LocalContext.current
    val viewModel: SettingsViewModel = viewModel()

    LaunchedEffect(Unit) {
        viewModel.loadSettings(context)
    }

    val directoryPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        viewModel.updateSaveDirectory(context, uri)
    }

    Scaffold(
        topBar = { TopAppBar(title = { Text(stringResource(R.string.settings)) }) }
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .padding(16.dp)
        ) {
            Text(
                text = "General",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary
            )
            Spacer(modifier = Modifier.height(8.dp))

            val isDarkTheme by ThemeController.isDarkTheme.collectAsState()
            ListItem(
                headlineContent = { Text("Dark Mode") },
                trailingContent = {
                    Switch(
                        checked = isDarkTheme,
                        onCheckedChange = { ThemeController.setDarkTheme(context, it) }
                    )
                }
            )

            ListItem(
                headlineContent = { Text(stringResource(R.string.default_save_location)) },
                supportingContent = {
                    Text(
                        text = viewModel.saveDirectory?.let {
                            try {
                                Uri.parse(it).path ?: it
                            } catch(e: Exception) { it }
                        } ?: "Default (DCIM/PhotoDoctorPro)",
                        style = MaterialTheme.typography.bodySmall
                    )
                },
                leadingContent = { Icon(Icons.Default.Folder, contentDescription = null) },
                trailingContent = { Icon(Icons.Default.KeyboardArrowRight, contentDescription = null) },
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable {
                        directoryPickerLauncher.launch(null)
                    }
            )

            Divider(modifier = Modifier.padding(vertical = 8.dp))

             Text(
                text = "About",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary
            )
            Spacer(modifier = Modifier.height(8.dp))

            ListItem(
                headlineContent = { Text(stringResource(R.string.privacy_policy)) },
                modifier = Modifier.clickable {
                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://dhanuk.page.gd/PhotoDoctor-Pro/Privacy-Policy.html"))
                    try { context.startActivity(intent) } catch (e: Exception) {}
                }
            )
            ListItem(
                headlineContent = { Text(stringResource(R.string.terms_conditions)) },
                modifier = Modifier.clickable {
                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://dhanuk.page.gd/PhotoDoctor-Pro/Terms-and-Conditions.html"))
                    try { context.startActivity(intent) } catch (e: Exception) {}
                }
            )
            ListItem(
                headlineContent = { Text("App Version") },
                supportingContent = { Text("1.0.0") }
            )
        }
    }
}
