package com.dhanuk.photodoctorpro.ui.screens

import android.app.Activity
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.Crossfade
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import com.dhanuk.photodoctorpro.R
import com.dhanuk.photodoctorpro.data.local.AppDatabase
import com.dhanuk.photodoctorpro.data.repository.HistoryRepository
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EnhanceImageScreen(navController: NavController) {
    val context = LocalContext.current
    val activity = context as Activity
    val db = AppDatabase.getDatabase(context)
    val repository = HistoryRepository(db.historyDao())
    val viewModel: EnhanceImageViewModel = viewModel(factory = ViewModelFactory(repository))
    val uiState by viewModel.uiState.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }

    // Hold to compare state
    var isHolding by remember { mutableStateOf(false) }
    var showUnsavedDialog by remember { mutableStateOf(false) }

    val hasUnsavedChanges = uiState.processedBitmap != null && uiState.savedFilePath == null

    BackHandler(enabled = hasUnsavedChanges) {
        showUnsavedDialog = true
    }

    if (showUnsavedDialog) {
        AlertDialog(
            onDismissRequest = { showUnsavedDialog = false },
            title = { Text(stringResource(R.string.unsaved_changes)) },
            text = { Text(stringResource(R.string.you_have_unsaved_changes_discard)) },
            confirmButton = {
                Button(
                    onClick = {
                        showUnsavedDialog = false
                        navController.popBackStack()
                    }
                ) {
                    Text(stringResource(R.string.discard))
                }
            },
            dismissButton = {
                TextButton(onClick = { showUnsavedDialog = false }) {
                    Text(stringResource(R.string.cancel))
                }
            }
        )
    }

    val imagePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let { viewModel.onImageSelected(it, context) }
    }

    // Effect for handling errors
    LaunchedEffect(uiState.error) {
        uiState.error?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.onErrorShown()
        }
    }

    // Effect for handling save success
    LaunchedEffect(uiState.savedFilePath) {
        uiState.savedFilePath?.let { path ->
            // Use path directly if it's a content URI string, or File name if it's a file path
            val displayName = if (path.startsWith("content://")) "Gallery/Selected Folder" else File(path).name

            val result = snackbarHostState.showSnackbar(
                message = context.getString(R.string.saved_to, displayName),
                actionLabel = context.getString(R.string.open),
                duration = SnackbarDuration.Long
            )
            if (result == SnackbarResult.ActionPerformed) {
                val uri = if (path.startsWith("content://")) {
                    Uri.parse(path)
                } else {
                    val file = File(path)
                    FileProvider.getUriForFile(context, "${context.packageName}.provider", file)
                }

                val intent = Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(uri, "image/*")
                    flags = Intent.FLAG_GRANT_READ_URI_PERMISSION
                }
                try {
                    context.startActivity(intent)
                } catch (e: Exception) {
                    // Handle case where no app can open the file
                }
            }
            viewModel.onSavedMessageShown()
        }
    }

    Scaffold(
        topBar = { TopAppBar(title = { Text(stringResource(R.string.enhance_image)) }) },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .pointerInput(Unit) {
                        detectTapGestures(
                            onPress = {
                                isHolding = true
                                tryAwaitRelease()
                                isHolding = false
                            }
                        )
                    },
                contentAlignment = Alignment.Center
            ) {
                 if (uiState.isEnhancing) {
                    CircularProgressIndicator()
                } else {
                    val bitmapToShow = if (isHolding || uiState.processedBitmap == null) {
                        uiState.originalBitmap
                    } else {
                        uiState.processedBitmap
                    }

                    if (bitmapToShow != null) {
                        Image(
                            bitmap = bitmapToShow.asImageBitmap(),
                            contentDescription = if (isHolding) "Original" else "Enhanced",
                            modifier = Modifier.fillMaxWidth()
                        )
                        if (uiState.processedBitmap != null) {
                            Text(
                                text = if (isHolding) stringResource(R.string.original) else stringResource(R.string.enhanced),
                                modifier = Modifier
                                    .align(Alignment.TopStart)
                                    .padding(8.dp),
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }
                    } else {
                         Text(stringResource(R.string.select_an_image_to_enhance))
                    }
                }
            }

            if (uiState.showEnhanceSuggestion && uiState.processedBitmap == null) {
                Text(stringResource(R.string.suggested_enhance_this_image), style = MaterialTheme.typography.bodyMedium)
            }

            if (uiState.originalBitmap == null) {
                Button(onClick = { imagePickerLauncher.launch("image/*") }, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.select_image))
                }
            } else if (uiState.processedBitmap == null) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly
                ) {
                    listOf(2, 4, 6, 8).forEach { scale ->
                        FilterChip(
                            selected = uiState.scaleFactor == scale,
                            onClick = { viewModel.onScaleChanged(scale) },
                            label = { Text("${scale}x") }
                        )
                    }
                }

                Button(
                    onClick = { viewModel.enhanceImage(activity) },
                    enabled = !uiState.isEnhancing,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(stringResource(R.string.one_tap_enhance))
                }
            } else {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                     OutlinedButton(
                        onClick = { viewModel.onImageSelected(uiState.selectedImageUri!!, context) }, // Re-process/Reset
                        enabled = !uiState.isEnhancing
                    ) {
                        Text(stringResource(R.string.reset))
                    }
                    Button(
                        onClick = { viewModel.saveImage(activity) },
                        enabled = !uiState.isEnhancing
                    ) {
                        Text(stringResource(R.string.save_enhanced_image))
                    }
                }
                Text("Long press image to see Original", style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}
