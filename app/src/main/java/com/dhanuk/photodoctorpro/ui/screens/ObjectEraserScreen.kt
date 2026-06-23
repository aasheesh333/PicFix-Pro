package com.dhanuk.photodoctorpro.ui.screens

import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BlurMaskFilter
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Compare
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asAndroidPath
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
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
import com.dhanuk.photodoctorpro.ui.components.BeforeAfterSlider
import com.dhanuk.photodoctorpro.ui.components.SaveSuccessDialog
import com.dhanuk.photodoctorpro.ui.components.ZoomableBox
import com.dhanuk.photodoctorpro.ui.components.rememberBitmap
import com.dhanuk.photodoctorpro.ui.components.rememberZoomableBoxState
import com.dhanuk.photodoctorpro.ui.components.AnimatedLoadingIndicator
import com.dhanuk.photodoctorpro.ui.navigation.LocalGlobalNavigationState
import com.dhanuk.photodoctorpro.utils.findActivity
import com.dhanuk.photodoctorpro.utils.createOpenIntent
import com.dhanuk.photodoctorpro.utils.createShareIntent
import com.dhanuk.photodoctorpro.utils.mapToBitmap
import com.dhanuk.photodoctorpro.utils.calculateScaleFactor
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ObjectEraserScreen(navController: NavController) {
    val context = LocalContext.current
    val activity = context.findActivity() ?: return
    val db = AppDatabase.getDatabase(context)
    val repository = HistoryRepository.getInstance(db.historyDao())
    val viewModel: ObjectEraserViewModel = viewModel(factory = ViewModelFactory.getInstance(repository))
    val uiState by viewModel.uiState.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val globalState = LocalGlobalNavigationState.current
    val openCvReady by com.dhanuk.photodoctorpro.PhotoDoctorApplication.openCVInitialized.collectAsState(false)
    var compareMode by remember { mutableStateOf(false) }

    val originalImage = rememberBitmap(uiState.originalBitmap)
    val processedImage = rememberBitmap(uiState.processedBitmap)

    var showUnsavedDialog by remember { mutableStateOf(false) }
    var showSaveSuccessDialog by remember { mutableStateOf<String?>(null) }
    val hasUnsavedChanges = (uiState.processedBitmap != null || uiState.paths.isNotEmpty()) && uiState.savedFilePath == null

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
                         if (uiState.paths.isNotEmpty()) {
                             viewModel.eraseObjectsSuspend()
                         }
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
                 } catch (e: Exception) { Toast.makeText(context, context.getString(R.string.whatsapp_not_installed), Toast.LENGTH_SHORT).show() }
            },
            onShareOther = {
                 try {
                     context.startActivity(Intent.createChooser(createShareIntent(path, context), context.getString(R.string.share_image)))
                 } catch (e: Exception) { if (com.dhanuk.photodoctorpro.BuildConfig.DEBUG) android.util.Log.e("ObjectEraserVM", "operation failed", e) }
            },
            onOpen = {
                try {
                    context.startActivity(createOpenIntent(path, context))
                } catch (e: Exception) { if (com.dhanuk.photodoctorpro.BuildConfig.DEBUG) android.util.Log.e("ObjectEraserVM", "operation failed", e) }
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
                title = { Text(stringResource(R.string.object_eraser)) },
                navigationIcon = {
                    IconButton(onClick = {
                        if (hasUnsavedChanges) showUnsavedDialog = true else navController.popBackStack()
                    }) {
                        Icon(Icons.Default.ArrowBack, contentDescription = stringResource(R.string.cd_back_button))
                    }
                },
                actions = {
                    if (uiState.paths.isNotEmpty()) {
                        IconButton(onClick = { viewModel.eraseObjects() }, enabled = openCvReady && !uiState.isErasing) {
                            Icon(Icons.Default.Check, contentDescription = stringResource(R.string.action_done))
                        }
                    }
                    if (uiState.processedBitmap != null) {
                        IconButton(onClick = { compareMode = !compareMode }) {
                            Icon(
                                Icons.Default.Compare,
                                contentDescription = stringResource(R.string.compare_with_original),
                                tint = if (compareMode) MaterialTheme.colorScheme.primary else Color.Gray
                            )
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
                .padding(padding),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                contentAlignment = Alignment.Center
            ) {
                if (uiState.isLoading || uiState.isErasing) {
                    AnimatedLoadingIndicator(
                        message = if (uiState.isErasing) stringResource(R.string.erasing_progress, (uiState.progress * 100).toInt()) else stringResource(R.string.loading),
                        progress = if (uiState.isErasing && uiState.progress > 0f) uiState.progress else null
                    )
                } else if (originalImage != null && compareMode && processedImage != null) {
                    BeforeAfterSlider(
                        beforeImage = originalImage,
                        afterImage = processedImage,
                        modifier = Modifier.fillMaxSize()
                    )
                } else if (uiState.originalBitmap != null) {
                    EraserEditor(
                        viewModel = viewModel,
                        uiState = uiState
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

            if (uiState.originalBitmap == null) {
                Button(onClick = { imagePickerLauncher.launch("image/*") }, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.select_image))
                }
            } else {
                Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 16.dp)) {
                    Text(stringResource(R.string.brush_size_value, uiState.brushSize.toInt()))
                    Slider(
                        value = uiState.brushSize,
                        onValueChange = { viewModel.onBrushSizeChanged(it) },
                        valueRange = 10f..100f
                    )

                    Text(stringResource(R.string.brush_softness_value, uiState.brushSoftness.toInt()))
                    Slider(
                        value = uiState.brushSoftness,
                        onValueChange = { viewModel.onBrushSoftnessChanged(it) },
                        valueRange = 0f..50f
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceEvenly
                    ) {
                        OutlinedButton(onClick = { viewModel.undo() }, enabled = uiState.canUndo) {
                            Text(stringResource(R.string.undo))
                        }
                        Button(
                            onClick = { viewModel.eraseObjects() },
                            enabled = uiState.paths.isNotEmpty() && openCvReady && !uiState.isErasing
                        ) {
                            Text(stringResource(R.string.erase))
                        }
                        OutlinedButton(onClick = { viewModel.redo() }, enabled = uiState.canRedo) {
                            Text(stringResource(R.string.action_redo))
                        }
                    }

                    Spacer(Modifier.height(8.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceEvenly
                    ) {
                        Button(onClick = { scope.launch { viewModel.saveImage(activity) } }, modifier = Modifier.weight(1f)) {
                            Text(stringResource(R.string.action_save))
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
}

@Composable
fun EraserEditor(
    viewModel: ObjectEraserViewModel,
    uiState: ObjectEraserUiState
) {
    val zoomState = rememberZoomableBoxState()

    var livePath by remember { mutableStateOf(Path()) }
    var liveBitmapPath by remember { mutableStateOf(Path()) }

    var pathVersion by remember { mutableStateOf(0) }
    var layoutSize by remember { mutableStateOf(androidx.compose.ui.unit.IntSize.Zero) }

    val bitmapToShow = uiState.processedBitmap ?: uiState.originalBitmap ?: return

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        if (constraints.hasBoundedWidth && constraints.hasBoundedHeight) {
            val width = constraints.maxWidth
            val height = constraints.maxHeight
            if (width > 0 && height > 0 && layoutSize.width == 0) {
                layoutSize = androidx.compose.ui.unit.IntSize(width, height)
            }
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(uiState.brushSize, uiState.brushSoftness, zoomState, layoutSize) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        var isZooming = false

                        livePath = Path()
                        livePath.moveTo(down.position.x, down.position.y)
                        livePath.lineTo(down.position.x, down.position.y)

                        liveBitmapPath = Path()
                        val startX = (down.position.x - zoomState.offset.x) / zoomState.scale
                        val startY = (down.position.y - zoomState.offset.y) / zoomState.scale
                        val startPt = mapToBitmap(startX, startY, layoutSize.width.toFloat(), layoutSize.height.toFloat(), bitmapToShow)
                        if (startPt != null) {
                            liveBitmapPath.moveTo(startPt.first, startPt.second)
                            liveBitmapPath.lineTo(startPt.first, startPt.second)
                        } else {
                            liveBitmapPath.moveTo(0f, 0f)
                        }

                        pathVersion++

                        do {
                            val event = awaitPointerEvent()
                            val pointerCount = event.changes.size
                            if (pointerCount >= 2) isZooming = true

                            if (isZooming) {
                                if (pointerCount >= 2) {
                                     val zoomChange = event.calculateZoom()
                                     val panChange = event.calculatePan()
                                     val centroid = event.calculateCentroid(useCurrent = false)

                                     val oldScale = zoomState.scale
                                     val newScale = (oldScale * zoomChange).coerceIn(1f, 10f)

                                     val zoomOffset = centroid - (centroid - zoomState.offset) * (newScale / oldScale)

                                     zoomState.scale = newScale
                                     zoomState.offset = zoomOffset + panChange

                                     event.changes.forEach { it.consume() }
                                }
                            } else {
                                 event.changes.forEach { change ->
                                    if (change.positionChanged()) {
                                         livePath.lineTo(change.position.x, change.position.y)

                                         val touchX = (change.position.x - zoomState.offset.x) / zoomState.scale
                                         val touchY = (change.position.y - zoomState.offset.y) / zoomState.scale
                                         val pt = mapToBitmap(touchX, touchY, layoutSize.width.toFloat(), layoutSize.height.toFloat(), bitmapToShow)
                                         if (pt != null) {
                                             liveBitmapPath.lineTo(pt.first, pt.second)
                                         }

                                         pathVersion++
                                         change.consume()
                                    }
                                }
                            }
                        } while (event.changes.any { it.pressed })

                        if (!isZooming) {
                            val savedPath = Path()
                            savedPath.addPath(liveBitmapPath)

                            val scaleFactor = calculateScaleFactor(layoutSize.width.toFloat(), layoutSize.height.toFloat(), bitmapToShow)
                            val bitmapBrushSize = uiState.brushSize / scaleFactor
                            val bitmapSoftness = uiState.brushSoftness / scaleFactor

                            val newPath = EraserPath(savedPath, bitmapBrushSize, bitmapSoftness)
                            viewModel.addPath(newPath)
                        }

                        livePath = Path()
                        liveBitmapPath = Path()
                        pathVersion++
                    }
                }
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer(
                        scaleX = zoomState.scale,
                        scaleY = zoomState.scale,
                        translationX = zoomState.offset.x,
                        translationY = zoomState.offset.y,
                        transformOrigin = TransformOrigin(0f, 0f)
                    )
            ) {
                if (bitmapToShow != null) {
                    val bitmapToShowImage = remember(bitmapToShow) {
                        bitmapToShow.takeIf { !it.isRecycled }?.asImageBitmap()
                    }
                    bitmapToShowImage?.let { img ->
                    Image(
                        bitmap = img,
                        contentDescription = null,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Fit
                    )

                Canvas(modifier = Modifier.fillMaxSize()) {
                    val viewAspectRatio = size.width / size.height
                    val imageAspectRatio = bitmapToShow.width.toFloat() / bitmapToShow.height.toFloat()

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

                    val scaleX = drawWidth / bitmapToShow.width
                    val scaleY = drawHeight / bitmapToShow.height

                    drawContext.canvas.save()
                    drawContext.canvas.translate(drawX, drawY)
                    drawContext.canvas.scale(scaleX, scaleY)

                    drawIntoCanvas { canvas ->
                        val paint = android.graphics.Paint()
                        paint.style = android.graphics.Paint.Style.STROKE
                        paint.strokeCap = android.graphics.Paint.Cap.ROUND
                        paint.strokeJoin = android.graphics.Paint.Join.ROUND
                        paint.color = android.graphics.Color.RED
                        paint.alpha = 180

                        uiState.paths.forEach { eraserPath ->
                            paint.strokeWidth = eraserPath.strokeWidth
                            if (eraserPath.softness > 0) {
                                paint.maskFilter = BlurMaskFilter(eraserPath.softness + 0.1f, BlurMaskFilter.Blur.NORMAL)
                            } else {
                                paint.maskFilter = null
                            }
                            canvas.nativeCanvas.drawPath(eraserPath.path.asAndroidPath(), paint)
                        }
                    }

                    drawContext.canvas.restore()
                }
                }
            }

            if (pathVersion > 0 && !livePath.isEmpty) {
                Canvas(modifier = Modifier.fillMaxSize()) {
                    drawIntoCanvas { canvas ->
                        val paint = android.graphics.Paint()
                        paint.style = android.graphics.Paint.Style.STROKE
                        paint.strokeCap = android.graphics.Paint.Cap.ROUND
                        paint.strokeJoin = android.graphics.Paint.Join.ROUND
                        paint.color = android.graphics.Color.RED
                        paint.strokeWidth = uiState.brushSize
                        if (uiState.brushSoftness > 0) {
                            paint.maskFilter = BlurMaskFilter(uiState.brushSoftness + 0.1f, BlurMaskFilter.Blur.NORMAL)
                        }
                        canvas.nativeCanvas.drawPath(livePath.asAndroidPath(), paint)
                    }
                }
            }
        }
    }
}
}
