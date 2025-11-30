package com.dhanuk.photodoctorpro.ui.screens

import android.app.Activity
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import coil.compose.rememberAsyncImagePainter
import com.dhanuk.photodoctorpro.R
import com.dhanuk.photodoctorpro.data.local.AppDatabase
import com.dhanuk.photodoctorpro.data.repository.HistoryRepository
import com.dhanuk.photodoctorpro.ui.components.SaveSuccessDialog
import com.dhanuk.photodoctorpro.ui.components.ZoomableBox
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
    val scope = rememberCoroutineScope()
    val globalState = LocalGlobalNavigationState.current

    var showUnsavedDialog by remember { mutableStateOf(false) }
    var showSaveSuccessDialog by remember { mutableStateOf<String?>(null) }
    val hasUnsavedChanges = uiState.enhancedBitmap != null && uiState.savedFilePath == null

    // Hold to compare
    var isHoldingOriginal by remember { mutableStateOf(false) }

    LaunchedEffect(hasUnsavedChanges) {
        globalState.hasUnsavedChanges = hasUnsavedChanges
        if (hasUnsavedChanges) {
            globalState.onSave = { viewModel.saveImage(activity) }
            globalState.onDiscard = { viewModel.reset() }
        } else {
            globalState.clear()
        }
    }

    BackHandler(enabled = hasUnsavedChanges) {
        showUnsavedDialog = true
    }

    if (showUnsavedDialog) {
        AlertDialog(
            onDismissRequest = { showUnsavedDialog = false },
            title = { Text(stringResource(R.string.unsaved_changes)) },
            text = { Text(stringResource(R.string.you_have_unsaved_changes_discard)) },
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

    showSaveSuccessDialog?.let { path ->
        SaveSuccessDialog(
            filePath = path,
            onDismiss = { showSaveSuccessDialog = null },
            onShare = {
                 val file = File(path)
                 val uri = FileProvider.getUriForFile(context, "${context.packageName}.provider", file)
                 val intent = Intent(Intent.ACTION_SEND).apply {
                    type = "image/png"
                    putExtra(Intent.EXTRA_STREAM, uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                context.startActivity(Intent.createChooser(intent, "Share Image"))
            },
            onOpen = {
                val file = File(path)
                val uri = FileProvider.getUriForFile(context, "${context.packageName}.provider", file)
                val intent = Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(uri, "image/*")
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                try { context.startActivity(intent) } catch (e: Exception) {}
            }
        )
    }

    val imagePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let { viewModel.onImageSelected(it, context) }
    }

    LaunchedEffect(uiState.error) {
        uiState.error?.let {
            snackbarHostState.showSnackbar("Error: $it")
            viewModel.onErrorShown()
        }
    }

    LaunchedEffect(uiState.savedFilePath) {
        uiState.savedFilePath?.let { path ->
            showSaveSuccessDialog = path
            viewModel.onSavedMessageShown()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.enhance_image)) },
                navigationIcon = {
                    IconButton(onClick = {
                        if (hasUnsavedChanges) showUnsavedDialog = true else navController.popBackStack()
                    }) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        },
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
                    .weight(1f),
                contentAlignment = Alignment.Center
            ) {
                if (uiState.isLoading) {
                    CircularProgressIndicator()
                } else {
                    if (uiState.enhancedBitmap != null) {
                        // Show Enhanced (or Original if holding)
                        val bitmapToShow = if (isHoldingOriginal) uiState.originalBitmap else uiState.enhancedBitmap

                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .pointerInput(Unit) {
                                    detectTapGestures(
                                        onPress = {
                                            isHoldingOriginal = true
                                            tryAwaitRelease()
                                            isHoldingOriginal = false
                                        }
                                    )
                                }
                        ) {
                            ZoomableBox(enableZoom = !isHoldingOriginal) {
                                Image(
                                    bitmap = bitmapToShow!!.asImageBitmap(),
                                    contentDescription = "Enhanced",
                                    modifier = Modifier.fillMaxSize(),
                                    contentScale = ContentScale.Fit
                                )
                            }
                            // Overlay hint
                            if (!isHoldingOriginal) {
                                Surface(
                                    modifier = Modifier.align(Alignment.TopCenter).padding(8.dp),
                                    color = MaterialTheme.colorScheme.surface.copy(alpha=0.6f),
                                    shape = MaterialTheme.shapes.small
                                ) {
                                    Text("Hold to compare", modifier = Modifier.padding(8.dp), style = MaterialTheme.typography.labelSmall)
                                }
                            }
                        }
                    } else if (uiState.selectedImageUri != null) {
                         ZoomableBox {
                             Image(
                                painter = rememberAsyncImagePainter(uiState.selectedImageUri),
                                contentDescription = "Selected",
                                modifier = Modifier.fillMaxSize(),
                                contentScale = ContentScale.Fit
                            )
                        }
                    } else {
                        Text(stringResource(R.string.select_an_image_to_start))
                    }
                }
            }

            // Controls
            if (uiState.originalBitmap == null) {
                Button(onClick = { imagePickerLauncher.launch("image/*") }, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.select_image))
                }
            } else if (uiState.enhancedBitmap == null) {
                Text("Select Upscale Factor:")
                Row(horizontalArrangement = Arrangement.SpaceEvenly, modifier = Modifier.fillMaxWidth()) {
                    listOf(2, 4, 6, 8).forEach { scale ->
                        OutlinedButton(
                            onClick = { viewModel.enhanceImage(context, scale) },
                            enabled = !uiState.isLoading
                        ) {
                            Text("${scale}x")
                        }
                    }
                }
            } else {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                    Button(onClick = { scope.launch { viewModel.saveImage(activity) } }, modifier = Modifier.weight(1f)) {
                        Text("Save")
                    }
                    Spacer(Modifier.width(16.dp))
                    OutlinedButton(onClick = { viewModel.reset() }, modifier = Modifier.weight(1f)) {
                        Text(stringResource(R.string.reset))
                    }
                }
            }
        }
    }
}
