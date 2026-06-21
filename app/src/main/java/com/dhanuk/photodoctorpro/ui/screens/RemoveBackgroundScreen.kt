package com.dhanuk.photodoctorpro.ui.screens

import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import com.dhanuk.photodoctorpro.ui.components.BeforeAfterSlider
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowForward
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
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
import com.dhanuk.photodoctorpro.ui.components.rememberBitmap
import com.dhanuk.photodoctorpro.ui.components.rememberZoomableBoxState
import com.dhanuk.photodoctorpro.ui.navigation.LocalGlobalNavigationState
import com.dhanuk.photodoctorpro.utils.findActivity
import com.dhanuk.photodoctorpro.utils.createOpenIntent
import com.dhanuk.photodoctorpro.utils.createShareIntent
import com.dhanuk.photodoctorpro.utils.mapToBitmap
import com.dhanuk.photodoctorpro.utils.calculateScaleFactor
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RemoveBackgroundScreen(navController: NavController) {
    val context = LocalContext.current
    val activity = context.findActivity() ?: return
    val db = AppDatabase.getDatabase(context)
    val repository = HistoryRepository(db.historyDao())
    val viewModel: RemoveBackgroundViewModel = viewModel(factory = ViewModelFactory(repository))
    val uiState by viewModel.uiState.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    val globalState = LocalGlobalNavigationState.current
    val scope = rememberCoroutineScope()

    var showUnsavedDialog by remember { mutableStateOf(false) }
    var showSaveSuccessDialog by remember { mutableStateOf<String?>(null) }
    val hasUnsavedChanges = uiState.processedBitmap != null && uiState.savedFilePath == null

    val originalImage = rememberBitmap(uiState.originalBitmap)
    val processedImage = rememberBitmap(uiState.processedBitmap)

    LaunchedEffect(hasUnsavedChanges) {
        globalState.hasUnsavedChanges = hasUnsavedChanges
        if (hasUnsavedChanges) {
            globalState.onSave = { viewModel.saveImage(activity) }
            globalState.onDiscard = { viewModel.reset() }
        } else {
            globalState.clear()
        }
    }

    BackHandler(enabled = hasUnsavedChanges || uiState.isRefining) {
        if (uiState.isRefining) {
             viewModel.applyRefinement(context)
        } else {
            showUnsavedDialog = true
        }
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

    showSaveSuccessDialog?.let { path ->
        SaveSuccessDialog(
            filePath = path,
            onDismiss = { showSaveSuccessDialog = null },
            onShareWhatsApp = {
                 try {
                     context.startActivity(createShareIntent(path, context, "com.whatsapp"))
                 } catch (e: Exception) {
                     Toast.makeText(context, context.getString(R.string.whatsapp_not_installed), Toast.LENGTH_SHORT).show()
                 }
            },
            onShareOther = {
                 try {
                     context.startActivity(Intent.createChooser(createShareIntent(path, context), context.getString(R.string.share_image)))
                 } catch (e: Exception) {
                     if (com.dhanuk.photodoctorpro.BuildConfig.DEBUG) android.util.Log.e("RemoveBackgroundVM", "operation failed", e)
                     Toast.makeText(context, "context.getString(R.string.error_sharing, e.message)", Toast.LENGTH_SHORT).show()
                 }
            },
            onOpen = {
                try {
                    context.startActivity(createOpenIntent(path, context))
                } catch (e: Exception) {
                    if (com.dhanuk.photodoctorpro.BuildConfig.DEBUG) android.util.Log.e("RemoveBackgroundVM", "operation failed", e)
                    Toast.makeText(context, "context.getString(R.string.error_opening, e.message)", Toast.LENGTH_SHORT).show()
                }
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
            snackbarHostState.showSnackbar(context.getString(R.string.error_prefix, it))
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
                title = { Text(if (uiState.isRefining) stringResource(R.string.refine_edges) else stringResource(R.string.remove_background)) },
                navigationIcon = {
                    if (uiState.isRefining) {
                        IconButton(onClick = {
             viewModel.applyRefinement(context)
                        }) {
                            Icon(Icons.Default.ArrowBack, contentDescription = stringResource(R.string.cd_back_button))
                        }
                    } else {
                         IconButton(onClick = {
                            if (hasUnsavedChanges) showUnsavedDialog = true else navController.popBackStack()
                        }) {
                            Icon(Icons.Default.ArrowBack, contentDescription = stringResource(R.string.cd_back_button))
                        }
                    }
                },
                actions = {
                    if (uiState.isRefining) {
                        IconButton(onClick = { viewModel.applyRefinement(context) }) {
                            Icon(Icons.Default.Check, contentDescription = stringResource(R.string.action_done))
                        }
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
                } else if (uiState.isRefining) {
                    RefineEditor(
                        viewModel = viewModel,
                        original = uiState.originalBitmap!!,
                        mask = uiState.maskBitmap!!,
                        maskVersion = viewModel.maskVersion.value
                    )
                } else {
                    if (originalImage != null && processedImage != null) {
                        BeforeAfterSlider(
                            beforeImage = originalImage,
                            afterImage = processedImage,
                            modifier = Modifier.fillMaxSize()
                        )
                    } else if (uiState.selectedImageUri != null) {
                         ZoomableBox {
                            Image(
                                painter = rememberAsyncImagePainter(uiState.selectedImageUri),
                                contentDescription = stringResource(R.string.cd_selected_image),
                                modifier = Modifier.fillMaxSize(),
                                contentScale = ContentScale.Fit
                            )
                        }
                    } else {
                        Text(stringResource(R.string.select_an_image_to_start))
                    }
                }
            }

            if (!uiState.isRefining) {
                if (uiState.originalBitmap == null) {
                    Button(onClick = { imagePickerLauncher.launch("image/*") }, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.select_image))
                    }
                } else if (uiState.processedBitmap == null) {
                    Button(
                        onClick = { viewModel.removeBackground(context) },
                        enabled = !uiState.isLoading,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(stringResource(R.string.remove_background))
                    }
                } else {
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                        OutlinedButton(onClick = { viewModel.startRefining() }, enabled = !uiState.isLoading) {
                            Text(stringResource(R.string.refine_edges))
                        }
                        Button(
                            onClick = { scope.launch { viewModel.saveImage(activity) } },
                            enabled = !uiState.isLoading
                        ) {
                            Text(stringResource(R.string.save_png))
                        }
                    }
                    OutlinedButton(
                        onClick = { viewModel.reset() },
                        enabled = !uiState.isLoading,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(stringResource(R.string.reset))
                    }
                }
            }
        }
    }
}

@Composable
fun RefineEditor(
    viewModel: RemoveBackgroundViewModel,
    original: Bitmap,
    mask: Bitmap,
    maskVersion: Int
) {
    var brushSize by remember { mutableStateOf(40f) }
    var feather by remember { mutableStateOf(0f) }
    var isAddMode by remember { mutableStateOf(false) }

    val originalImage = rememberBitmap(original)
    val maskImage = remember(maskVersion) { mask.asImageBitmap() }

    var currentPath by remember { mutableStateOf(android.graphics.Path()) }
    val zoomState = rememberZoomableBoxState()

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val layoutWidth = constraints.maxWidth.toFloat()
        val layoutHeight = constraints.maxHeight.toFloat()

        Box(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(isAddMode, brushSize, feather, zoomState, layoutWidth, layoutHeight) {
                    detectDragGestures(
                        onDragStart = { offset ->
                            viewModel.saveMaskStateForUndo()
                            currentPath.reset()

                            val touchX = (offset.x - zoomState.offset.x) / zoomState.scale
                            val touchY = (offset.y - zoomState.offset.y) / zoomState.scale

                            val bitmapPt = mapToBitmap(touchX, touchY, layoutWidth, layoutHeight, original)
                            if (bitmapPt != null) {
                                currentPath.moveTo(bitmapPt.first, bitmapPt.second)
                            }
                        },
                        onDrag = { change, dragAmount ->
                            val touchX = (change.position.x - zoomState.offset.x) / zoomState.scale
                            val touchY = (change.position.y - zoomState.offset.y) / zoomState.scale

                            val bitmapPt = mapToBitmap(touchX, touchY, layoutWidth, layoutHeight, original)
                            if (bitmapPt != null) {
                                currentPath.lineTo(bitmapPt.first, bitmapPt.second)
                                val scaleFactor = calculateScaleFactor(layoutWidth, layoutHeight, original)
                                viewModel.updateMask(currentPath, isAddMode, brushSize * scaleFactor, feather)
                            }
                        },
                        onDragEnd = {
                            currentPath.reset()
                        }
                    )
                }
        ) {
            ZoomableBox(
                state = zoomState,
                enableZoom = true
            ) {
                Box(modifier = Modifier.fillMaxSize()) {
                    Image(
                        bitmap = originalImage ?: return@Box,
                        contentDescription = null,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Fit
                    )

                    Canvas(modifier = Modifier.fillMaxSize()) {
                        val viewAspectRatio = size.width / size.height
                        val imageAspectRatio = original.width.toFloat() / original.height.toFloat()

                        var drawWidth = size.width
                        var drawHeight = size.height
                        var drawX = 0f
                        var drawY = 0f

                        if (imageAspectRatio > viewAspectRatio) {
                             drawHeight = drawWidth / imageAspectRatio
                             drawY = (size.height - drawHeight) / 2f
                        } else {
                             drawWidth = drawHeight * imageAspectRatio
                             drawX = (size.width - drawWidth) / 2f
                        }

                        drawImage(
                            image = maskImage,
                            dstOffset = androidx.compose.ui.unit.IntOffset(drawX.toInt(), drawY.toInt()),
                            dstSize = androidx.compose.ui.unit.IntSize(drawWidth.toInt(), drawHeight.toInt()),
                            colorFilter = androidx.compose.ui.graphics.ColorFilter.tint(Color.Red.copy(alpha = 0.5f), BlendMode.SrcIn)
                        )
                    }
                }
            }
        }

        Column(modifier = Modifier.align(Alignment.BottomCenter).padding(8.dp).background(MaterialTheme.colorScheme.surface)) {
            Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                IconButton(onClick = { viewModel.undo() }) { Icon(Icons.Default.ArrowBack, contentDescription = stringResource(R.string.undo)) }
                Row {
                    FilledTonalIconToggleButton(checked = isAddMode, onCheckedChange = { isAddMode = true }) {
                        Icon(Icons.Default.Add, contentDescription = stringResource(R.string.keep))
                    }
                    Spacer(Modifier.width(8.dp))
                    FilledTonalIconToggleButton(checked = !isAddMode, onCheckedChange = { isAddMode = false }) {
                        Icon(Icons.Default.Remove, contentDescription = stringResource(R.string.erase))
                    }
                }
                IconButton(onClick = { viewModel.redo() }) { Icon(Icons.Default.ArrowForward, stringResource(R.string.action_redo)) }
            }

            Text(stringResource(R.string.brush_size))
            Slider(value = brushSize, onValueChange = { brushSize = it }, valueRange = 10f..100f)

            Text(stringResource(R.string.brush_softness_value, feather.toInt()))
            Slider(
                value = feather,
                onValueChange = { feather = it },
                valueRange = 0f..50f
            )
        }
    }
}
