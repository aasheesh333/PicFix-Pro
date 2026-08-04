package com.dhanuk.photodoctorpro.ui.screens

import android.app.Activity
import com.dhanuk.photodoctorpro.ui.components.BeforeAfterSlider
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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import coil.compose.rememberAsyncImagePainter
import com.dhanuk.photodoctorpro.R
import com.dhanuk.photodoctorpro.data.local.AppDatabase
import com.dhanuk.photodoctorpro.data.repository.HistoryRepository
import com.dhanuk.photodoctorpro.ui.components.SaveSuccessDialog
import com.dhanuk.photodoctorpro.ui.components.ZoomableBox
import com.dhanuk.photodoctorpro.ui.components.rememberBitmap
import com.dhanuk.photodoctorpro.ui.components.rememberZoomableBoxState
import com.dhanuk.photodoctorpro.ui.components.AnimatedSnackbar
import com.dhanuk.photodoctorpro.ui.components.SnackbarType
import com.dhanuk.photodoctorpro.ui.components.AnimatedLoadingIndicator
import com.dhanuk.photodoctorpro.utils.AdManager
import com.dhanuk.photodoctorpro.utils.findActivity
import com.dhanuk.photodoctorpro.ui.navigation.LocalGlobalNavigationState
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EnhanceImageScreen(navController: NavController) {
    val context = LocalContext.current
    val activity = context.findActivity() ?: return
    val db = AppDatabase.getDatabase(context)
    val repository = HistoryRepository.getInstance(db.historyDao())
    val viewModel: EnhanceImageViewModel = viewModel(factory = ViewModelFactory.getInstance(repository))
    val uiState by viewModel.uiState.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val globalState = LocalGlobalNavigationState.current

    var showUnsavedDialog by remember { mutableStateOf(false) }
    var showSaveSuccessDialog by remember { mutableStateOf<String?>(null) }
    var showSaveOptionsSheet by remember { mutableStateOf(false) }
    val hasUnsavedChanges = uiState.enhancedBitmap != null && uiState.savedFilePath == null
    var snackbarMessage by remember { mutableStateOf<String?>(null) }
    var snackbarType by remember { mutableStateOf(SnackbarType.INFO) }

    val originalImage = rememberBitmap(uiState.originalBitmap)
    val enhancedImage = rememberBitmap(uiState.enhancedBitmap)

    var isHoldingOriginal by remember { mutableStateOf(false) }

    LaunchedEffect(hasUnsavedChanges) {
        globalState.hasUnsavedChanges = hasUnsavedChanges
        if (hasUnsavedChanges) {
            globalState.onSave = { viewModel.saveImage(activity, com.dhanuk.photodoctorpro.utils.UserPreferences.getSaveOptions(context)) }
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
                        val success = viewModel.saveImage(activity, com.dhanuk.photodoctorpro.utils.UserPreferences.getSaveOptions(context))
                        if (success) {
                            showUnsavedDialog = false
                            navController.popBackStack()
                        }
                    }
                }) { Text(stringResource(R.string.action_save)) }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = {
                        showUnsavedDialog = false
                        viewModel.reset()
                        navController.popBackStack()
                    }) { Text(stringResource(R.string.action_discard)) }
                    TextButton(onClick = { showUnsavedDialog = false }) { Text(stringResource(R.string.cancel)) }
                }
            }
        )
    }

    if (showSaveOptionsSheet) {
        val initialOptions = remember {
            com.dhanuk.photodoctorpro.utils.UserPreferences.getSaveOptions(context)
        }
        com.dhanuk.photodoctorpro.ui.components.SaveOptionsSheet(
            initial = initialOptions,
            hasTransparency = false,
            onConfirm = { options ->
                com.dhanuk.photodoctorpro.utils.UserPreferences.setSaveOptions(context, options)
                showSaveOptionsSheet = false
                scope.launch {
                    val success = viewModel.saveImage(activity, options)
                    if (!success) showUnsavedDialog = false
                }
            },
            onDismiss = { showSaveOptionsSheet = false }
        )
    }

    showSaveSuccessDialog?.let { path ->
        SaveSuccessDialog(
            filePath = path,
            onDismiss = { showSaveSuccessDialog = null },
             onShareWhatsApp = {
                  try { context.startActivity(com.dhanuk.photodoctorpro.utils.createShareIntent(path, context, "com.whatsapp")) }
                  catch (e: Exception) { snackbarMessage = context.getString(R.string.whatsapp_not_installed); snackbarType = SnackbarType.ERROR }
             },
            onShareOther = {
                try {
                    context.startActivity(Intent.createChooser(
                        com.dhanuk.photodoctorpro.utils.createShareIntent(path, context), context.getString(R.string.share_image)))
                } catch (e: Exception) { if (com.dhanuk.photodoctorpro.BuildConfig.DEBUG) android.util.Log.e("EnhanceImageVM", "operation failed", e) }
            },
            onOpen = {
                try { context.startActivity(com.dhanuk.photodoctorpro.utils.createOpenIntent(path, context)) }
                catch (e: Exception) { if (com.dhanuk.photodoctorpro.BuildConfig.DEBUG) android.util.Log.e("EnhanceImageVM", "operation failed", e) }
            }
        )
    }

    val imagePickerLauncher = rememberLauncherForActivityResult(
        contract = androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia()
    ) { uri: Uri? ->
        uri?.let { viewModel.onImageSelected(it, context) }
    }

    LaunchedEffect(uiState.error) {
        uiState.error?.let {
            snackbarHostState.showSnackbar(context.getString(R.string.error_prefix, it))
            viewModel.onErrorShown()
        }
    }

    LaunchedEffect(uiState.savedFilePath) {
        uiState.savedFilePath?.let { path ->
            (context as? android.app.Activity)?.let { AdManager.showInterstitialOnSave(it) }
            showSaveSuccessDialog = path
            viewModel.onSavedMessageShown()
        }
    }

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.enhance_image)) },
                navigationIcon = {
                    IconButton(onClick = {
                        if (hasUnsavedChanges) showUnsavedDialog = true else navController.popBackStack()
                    }) {
                        Icon(Icons.Default.ArrowBack, contentDescription = stringResource(R.string.cd_back_button))
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
                    AnimatedLoadingIndicator(
                        message = stringResource(R.string.processing),
                        progress = if (uiState.progress > 0f) uiState.progress else null
                    )
                } else {
                    if (originalImage != null && enhancedImage != null) {
                        BeforeAfterSlider(
                            beforeImage = originalImage,
                            afterImage = enhancedImage,
                            modifier = Modifier.fillMaxSize()
                        )
                    } else if (originalImage != null) {
                        ZoomableBox {
                            Image(
                                bitmap = originalImage,
                                contentDescription = stringResource(R.string.cd_image_preview),
                                modifier = Modifier.fillMaxSize(),
                                contentScale = ContentScale.Fit
                            )
                        }
                    } else if (uiState.selectedImageUri != null) {
                        ZoomableBox {
                            Image(
                                painter = rememberAsyncImagePainter(uiState.selectedImageUri),
                                contentDescription = stringResource(R.string.cd_image_preview),
                                modifier = Modifier.fillMaxSize(),
                                contentScale = ContentScale.Fit
                            )
                        }
                    } else {
                        Text(stringResource(R.string.select_an_image_to_start))
                    }
                }
            }

            if (uiState.engineInfo.isNotBlank() && uiState.enhancedBitmap != null) {
                Text(
                    text = stringResource(R.string.enhance_engine_fmt, uiState.engineInfo),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 4.dp)
                )
            }

            if (uiState.originalBitmap == null) {
                Button(
                    onClick = {
                        imagePickerLauncher.launch(
                            androidx.activity.result.PickVisualMediaRequest(
                                androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia.ImageOnly
                            )
                        )
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(stringResource(R.string.select_image))
                }
            } else if (uiState.enhancedBitmap == null) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center
                ) {
                    val isStandard = uiState.qualityMode == "standard"
                    TextButton(
                        onClick = { viewModel.setQualityMode("standard") },
                        colors = ButtonDefaults.textButtonColors(
                            contentColor = if (isStandard) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    ) { Text(stringResource(R.string.quality_fast)) }
                    Spacer(Modifier.width(8.dp))
                    TextButton(
                        onClick = { viewModel.setQualityMode("hd") },
                        colors = ButtonDefaults.textButtonColors(
                            contentColor = if (!isStandard) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    ) { Text(stringResource(R.string.quality_high)) }
                }

                Spacer(Modifier.height(8.dp))
                Text(stringResource(R.string.select_upscale_factor))
                Row(horizontalArrangement = Arrangement.SpaceEvenly, modifier = Modifier.fillMaxWidth()) {
                    listOf(2, 4, 6, 8).forEach { scale ->
                        OutlinedButton(
                            onClick = { viewModel.enhanceImage(context, scale) },
                            enabled = !uiState.isLoading
                        ) {
                            Text(stringResource(R.string.scale_factor_x, scale))
                        }
                    }
                }
            } else {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                    Button(onClick = { showSaveOptionsSheet = true }, modifier = Modifier.weight(1f)) {
                        Text(stringResource(R.string.action_save))
                    }
                    Spacer(Modifier.width(16.dp))
                    OutlinedButton(onClick = { viewModel.reset() }, modifier = Modifier.weight(1f)) {
                        Text(stringResource(R.string.reset))
                    }
                }
            }
        }

        AnimatedSnackbar(
            message = snackbarMessage ?: "",
            type = snackbarType,
            visible = snackbarMessage != null,
            modifier = Modifier.padding(horizontal = 16.dp)
        )
        if (snackbarMessage != null) {
            LaunchedEffect(snackbarMessage) {
                kotlinx.coroutines.delay(3000)
                snackbarMessage = null
            }
        }
    }
}
