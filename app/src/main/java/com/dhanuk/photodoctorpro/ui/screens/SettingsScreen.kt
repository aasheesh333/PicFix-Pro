package com.dhanuk.photodoctorpro.ui.screens

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.Policy
import androidx.compose.material.icons.outlined.DarkMode
import androidx.compose.material3.Divider
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import com.dhanuk.photodoctorpro.BuildConfig
import com.dhanuk.photodoctorpro.R
import com.dhanuk.photodoctorpro.ui.components.LuminaListItem
import com.dhanuk.photodoctorpro.ui.components.LuminaSectionLabel
import com.dhanuk.photodoctorpro.utils.NotificationHelper
import com.dhanuk.photodoctorpro.utils.ThemeController
import com.dhanuk.photodoctorpro.utils.UserPreferences

class SettingsViewModel : ViewModel() {
    var saveDirectory by mutableStateOf<String?>(null)
        private set
    var remindersEnabled by mutableStateOf(false)
        private set

    fun loadSettings(context: Context) {
        saveDirectory = UserPreferences.getSaveDirectory(context)
        remindersEnabled = UserPreferences.isRemindersEnabled(context)
    }

    fun updateSaveDirectory(context: Context, uri: Uri?) {
        if (uri != null) {
            try {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )
            } catch (e: Exception) {
                if (com.dhanuk.photodoctorpro.BuildConfig.DEBUG) android.util.Log.e("SettingsVM", "operation failed", e)
            }
            UserPreferences.setSaveDirectory(context, uri.toString())
            saveDirectory = uri.toString()
        }
    }

    fun updateReminders(context: Context, enabled: Boolean) {
        UserPreferences.setRemindersEnabled(context, enabled)
        remindersEnabled = enabled
        if (enabled) {
            NotificationHelper.scheduleReEngagement(context)
        } else {
            NotificationHelper.cancelReEngagement(context)
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
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = stringResource(R.string.settings),
                        style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.SemiBold),
                        color = MaterialTheme.colorScheme.onBackground
                    )
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background
                )
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
        ) {
            Spacer(modifier = Modifier.height(8.dp))
            LuminaSectionLabel(stringResource(R.string.general))
            Spacer(modifier = Modifier.height(4.dp))

            val isDarkTheme by ThemeController.isDarkTheme.collectAsState()
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Outlined.DarkMode,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(22.dp)
                )
                Spacer(modifier = Modifier.size(16.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.dark_mode),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = if (isDarkTheme) stringResource(R.string.dark_mode_on) else stringResource(R.string.dark_mode_off),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(
                    checked = isDarkTheme,
                    onCheckedChange = { ThemeController.setDarkTheme(context, it) }
                )
            }

            Spacer(modifier = Modifier.height(8.dp))
            Divider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
            Spacer(modifier = Modifier.height(8.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Outlined.Notifications,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(22.dp)
                )
                Spacer(modifier = Modifier.size(16.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.reminders),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = stringResource(R.string.reminders_subtitle),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(
                    checked = viewModel.remindersEnabled,
                    onCheckedChange = { viewModel.updateReminders(context, it) }
                )
            }

            Spacer(modifier = Modifier.height(8.dp))
            Divider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
            Spacer(modifier = Modifier.height(8.dp))

            LuminaListItem(
                title = stringResource(R.string.default_save_location),
                subtitle = viewModel.saveDirectory?.let {
                    try {
                        Uri.parse(it).path ?: it
                    } catch (e: Exception) { it }
                } ?: stringResource(R.string.default_save_location_label),
                leading = Icons.Outlined.Folder,
                trailing = {
                    Icon(
                        imageVector = Icons.Outlined.KeyboardArrowRight,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                },
                onClick = { directoryPickerLauncher.launch(null) }
            )

            Spacer(modifier = Modifier.height(24.dp))
            LuminaSectionLabel(stringResource(R.string.about))
            Spacer(modifier = Modifier.height(4.dp))

            LuminaListItem(
                title = stringResource(R.string.privacy_policy),
                leading = Icons.Outlined.Policy,
                trailing = {
                    Icon(
                        imageVector = Icons.Outlined.KeyboardArrowRight,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                },
                onClick = {
                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://dhanuk.page.gd/PhotoDoctor-Pro/Privacy-Policy.html?i=1"))
                    context.startActivity(intent)
                }
            )

            LuminaListItem(
                title = stringResource(R.string.terms_conditions),
                leading = Icons.Outlined.Policy,
                trailing = {
                    Icon(
                        imageVector = Icons.Outlined.KeyboardArrowRight,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                },
                onClick = {
                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://dhanuk.page.gd/PhotoDoctor-Pro/Terms-and-Conditions.html"))
                    context.startActivity(intent)
                }
            )

            LuminaListItem(
                title = stringResource(R.string.app_version),
                subtitle = "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})",
                leading = Icons.Outlined.Info
            )

            Spacer(modifier = Modifier.height(40.dp))
        }
    }
}
