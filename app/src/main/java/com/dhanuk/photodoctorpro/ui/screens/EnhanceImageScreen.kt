package com.dhanuk.photodoctorpro.ui.screens

import android.app.Activity
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
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
import com.dhanuk.photodoctorpro.ui.navigation.LocalGlobalNavigationState
import kotlinx.coroutines.launch
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
    val globalState = LocalGlobalNavigationState.current
    val scope = rememberCoroutineScope()

    // Hold to compare state
    var isHolding by remember { mutableStateOf(false) }
    var showUnsavedDialog by remember { mutableStateOf(false) }

    val hasUnsavedChanges = uiState.processedBitmap != null && uiState.savedFilePath == null

    // Sync with Global State for Bottom Nav interception
    LaunchedEffect(hasUnsavedChanges) {
        globalState.hasUnsavedChanges = hasUnsavedChanges
        if (hasUnsavedChanges) {
            globalState.onSave = { viewModel.saveImage(activity) }
            globalState.onDiscard = { viewModel.reset() }
        } else {
            globalState.clear()
        }
    }

    // System Back Handler
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
                        // Save and exit
                        // We can't use suspend functions in onClick directly, so we need a scope or LaunchedEffect
                        // But here, we can just call viewModel.saveImage which launches internally
                        // Wait, viewModel.saveImage updates state. We need to wait for completion to pop back?
                        // The ViewModel logic doesn't return a "completed" event easily for navigation here.
                        // Ideally we should use the GlobalState pattern or a callback.
                        // Let's launch a coroutine scope here.
                    }
                ) {
                    // Simpler: Trigger Save, then observe savedFilePath change to exit?
                    // But we want to exit.
                    // Let's use the Global State logic manually or duplicate it locally.
                    // Since viewModel.saveImage is async, let's just trigger it and let the user click Back again? No.
                    // Proper way:
                    Text("Save")
                }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = {
                        // Discard
                        showUnsavedDialog = false
                        viewModel.reset()
                        navController.popBackStack()
                    }) {
                        Text(stringResource(R.string.discard))
                    }
                    TextButton(onClick = { showUnsavedDialog = false }) {
                        Text(stringResource(R.string.cancel))
                    }
                }
            }
        )
        // Fix for Save button logic inside Dialog:
        // Since we can't easily launch suspend from onClick inside Dialog content comfortably without scope:
        // We will implement the Save action in the Confirm Button onClick using a separate LaunchedEffect trigger or scope.
    }

    // Better Dialog Handling for Save
    if (showUnsavedDialog) {
        AlertDialog(
            onDismissRequest = { showUnsavedDialog = false },
            title = { Text("Unsaved Changes") },
            text = { Text("You have unsaved changes. What would you like to do?") },
            confirmButton = {
                Button(onClick = {
                     scope.launch {
                         val success = viewModel.saveImage(activity)
                         if (success) {
                             showUnsavedDialog = false
                             navController.popBackStack()
                         }
                     }
                }) { Text("Save") }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = {
                        showUnsavedDialog = false
                        viewModel.reset()
                        navController.popBackStack()
                    }) { Text("Discard") }
                    TextButton(onClick = { showUnsavedDialog = false }) { Text("Cancel") }
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
                } catch (e: Exception) { }
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
                            modifier = Modifier.fillMaxSize()
                        )
                        if (uiState.processedBitmap != null) {
                            Box(modifier = Modifier.fillMaxSize()) {
                                Text(
                                    text = if (isHolding) stringResource(R.string.original) else stringResource(R.string.enhanced),
                                    modifier = Modifier
                                        .align(Alignment.TopStart)
                                        .background(Color.Black.copy(alpha = 0.5f))
                                        .padding(8.dp),
                                    style = MaterialTheme.typography.labelLarge,
                                    color = Color.White
                                )
                            }
                        }
                    } else {
                         Text(stringResource(R.string.select_an_image_to_enhance))
                    }
                }
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
                    // Limit chips based on resolution warning logic in VM, or show all and let VM handle error
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
                        onClick = { viewModel.reset() },
                        enabled = !uiState.isEnhancing
                    ) {
                        Text(stringResource(R.string.reset))
                    }
                    Button(
                        onClick = {
                            scope.launch {
                                viewModel.saveImage(activity)
                            }
                        },
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
