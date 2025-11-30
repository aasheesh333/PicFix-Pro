package com.dhanuk.photodoctorpro.ui.screens

import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BlurMaskFilter
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
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
import androidx.core.content.FileProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import coil.compose.rememberAsyncImagePainter
import com.dhanuk.photodoctorpro.R
import com.dhanuk.photodoctorpro.data.local.AppDatabase
import com.dhanuk.photodoctorpro.data.repository.HistoryRepository
import com.dhanuk.photodoctorpro.ui.components.SaveSuccessDialog
import com.dhanuk.photodoctorpro.ui.components.ZoomableBox
import com.dhanuk.photodoctorpro.ui.components.rememberZoomableBoxState
import com.dhanuk.photodoctorpro.ui.navigation.LocalGlobalNavigationState
import kotlinx.coroutines.launch
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ObjectEraserScreen(navController: NavController) {
    val context = LocalContext.current
    val activity = context as Activity
    val db = AppDatabase.getDatabase(context)
    val repository = HistoryRepository(db.historyDao())
    val viewModel: ObjectEraserViewModel = viewModel(factory = ViewModelFactory(repository))
    val uiState by viewModel.uiState.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val globalState = LocalGlobalNavigationState.current

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
                             viewModel.eraseObjects()
                         }
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
                 try {
                     val uriString = path
                     val uriToShare = if (uriString.startsWith("content://")) {
                         Uri.parse(uriString)
                     } else {
                         val cleanPath = if (uriString.startsWith("file://")) Uri.parse(uriString).path else uriString
                         val file = File(cleanPath!!)
                         FileProvider.getUriForFile(context, "${context.packageName}.provider", file)
                     }
                     val intent = Intent(Intent.ACTION_SEND).apply {
                        type = "image/png"
                        putExtra(Intent.EXTRA_STREAM, uriToShare)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    context.startActivity(Intent.createChooser(intent, "Share Image"))
                 } catch (e: Exception) { e.printStackTrace() }
            },
            onOpen = {
                try {
                    val uriString = path
                    val uriToOpen = if (uriString.startsWith("content://")) {
                        Uri.parse(uriString)
                    } else {
                        val cleanPath = if (uriString.startsWith("file://")) Uri.parse(uriString).path else uriString
                        val file = File(cleanPath!!)
                        FileProvider.getUriForFile(context, "${context.packageName}.provider", file)
                    }
                    val intent = Intent(Intent.ACTION_VIEW).apply {
                        setDataAndType(uriToOpen, "image/*")
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    context.startActivity(intent)
                } catch (e: Exception) { e.printStackTrace() }
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
                title = { Text(stringResource(R.string.object_eraser)) },
                navigationIcon = {
                    IconButton(onClick = {
                        if (hasUnsavedChanges) showUnsavedDialog = true else navController.popBackStack()
                    }) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    if (uiState.paths.isNotEmpty()) {
                        IconButton(onClick = { viewModel.eraseObjects() }) {
                            Icon(Icons.Default.Check, contentDescription = "Apply Erase")
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
                if (uiState.isLoading || uiState.isErasing) {
                    CircularProgressIndicator()
                } else if (uiState.originalBitmap != null) {
                    EraserEditor(
                        viewModel = viewModel,
                        uiState = uiState
                    )
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

            // Controls
            if (uiState.originalBitmap == null) {
                Button(onClick = { imagePickerLauncher.launch("image/*") }, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.select_image))
                }
            } else {
                Column(modifier = Modifier.fillMaxWidth()) {
                    Text("Brush Size: ${uiState.brushSize.toInt()}")
                    Slider(
                        value = uiState.brushSize,
                        onValueChange = { viewModel.onBrushSizeChanged(it) },
                        valueRange = 10f..100f
                    )

                    Text("Brush Softness: ${uiState.brushSoftness.toInt()}")
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
                            Text("Undo")
                        }
                        Button(onClick = { viewModel.eraseObjects() }, enabled = uiState.paths.isNotEmpty()) {
                            Text("Erase")
                        }
                        OutlinedButton(onClick = { viewModel.redo() }, enabled = uiState.canRedo) {
                            Text("Redo")
                        }
                    }

                    Spacer(Modifier.height(8.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceEvenly
                    ) {
                        Button(onClick = { scope.launch { viewModel.saveImage(activity) } }, modifier = Modifier.weight(1f)) {
                            Text("Save")
                        }
                        Spacer(Modifier.width(16.dp))
                        OutlinedButton(onClick = { viewModel.reset() }, modifier = Modifier.weight(1f)) {
                            Text("Reset")
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

    // Live Paths
    var livePath by remember { mutableStateOf(Path()) }
    var liveBitmapPath by remember { mutableStateOf(Path()) }

    var pathVersion by remember { mutableStateOf(0) }
    var layoutSize by remember { mutableStateOf(androidx.compose.ui.unit.IntSize.Zero) }

    val bitmapToShow = uiState.processedBitmap ?: uiState.originalBitmap!!

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
                        // Ensure a dot is drawn if it's just a tap
                        livePath.lineTo(down.position.x, down.position.y)

                        liveBitmapPath = Path()
                        val startX = (down.position.x - zoomState.offset.x) / zoomState.scale
                        val startY = (down.position.y - zoomState.offset.y) / zoomState.scale
                        // NOTE: mapToBitmap helper is robust.
                        val startPt = mapToBitmap(startX, startY, layoutSize.width.toFloat(), layoutSize.height.toFloat(), bitmapToShow)
                        if (startPt != null) {
                            liveBitmapPath.moveTo(startPt.first, startPt.second)
                            liveBitmapPath.lineTo(startPt.first, startPt.second)
                        } else {
                            liveBitmapPath.moveTo(0f, 0f) // Safety
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
                                     val newScale = (zoomState.scale * zoomChange).coerceIn(1f, 10f)
                                     zoomState.scale = newScale
                                     zoomState.offset += panChange
                                     event.changes.forEach { it.consume() }
                                }
                            } else {
                                 event.changes.forEach { change ->
                                    if (change.positionChanged()) {
                                         livePath.lineTo(change.position.x, change.position.y)

                                         // Update Bitmap Path
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
                            // Commit the parallel tracked Bitmap Path
                            val newPath = EraserPath(liveBitmapPath, uiState.brushSize, uiState.brushSoftness)
                            val newPaths = uiState.paths + newPath
                            viewModel.onPathsChanged(newPaths)
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
                        translationY = zoomState.offset.y
                    )
            ) {
                Image(
                    bitmap = bitmapToShow.asImageBitmap(),
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

                    // Draw committed paths using Native Canvas for Blur (Softness)
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

            // Draw Live Path (Screen Coords) - Overlay
            if (pathVersion > 0 && !livePath.isEmpty) { // Read pathVersion to trigger redraw
                Canvas(modifier = Modifier.fillMaxSize()) {
                    drawIntoCanvas { canvas ->
                        val paint = android.graphics.Paint()
                        paint.style = android.graphics.Paint.Style.STROKE
                        paint.strokeCap = android.graphics.Paint.Cap.ROUND
                        paint.strokeJoin = android.graphics.Paint.Join.ROUND
                        paint.color = android.graphics.Color.RED
                        paint.strokeWidth = uiState.brushSize
                        // Apply softness to live preview so user sees exactly what will happen
                        if (uiState.brushSoftness > 0) {
                            paint.maskFilter = BlurMaskFilter(uiState.brushSoftness + 0.1f, BlurMaskFilter.Blur.NORMAL)
                        }
                        canvas.nativeCanvas.drawPath(livePath.asAndroidPath(), paint)
                    }
                }
            }
        }

        // Controls
        Column(modifier = Modifier.align(Alignment.BottomCenter).padding(8.dp).background(MaterialTheme.colorScheme.surface)) {
            Text("Brush Size: ${uiState.brushSize.toInt()}")
            Slider(value = uiState.brushSize, onValueChange = { viewModel.onBrushSizeChanged(it) }, valueRange = 10f..100f)

            Text("Brush Softness: ${uiState.brushSoftness.toInt()}")
            Slider(
                value = uiState.brushSoftness,
                onValueChange = { viewModel.onBrushSoftnessChanged(it) },
                valueRange = 0f..50f
            )
        }
    }
}

private fun mapToBitmap(x: Float, y: Float, layoutW: Float, layoutH: Float, original: Bitmap): Pair<Float, Float>? {
     if (layoutW <= 0 || layoutH <= 0) return null
     val viewAspectRatio = layoutW / layoutH
     val imageAspectRatio = original.width.toFloat() / original.height.toFloat()
     var drawWidth = layoutW
     var drawHeight = layoutH
     var drawX = 0f
     var drawY = 0f
     if (imageAspectRatio > viewAspectRatio) {
         drawHeight = drawWidth / imageAspectRatio
         drawY = (layoutH - drawHeight) / 2f
     } else {
         drawWidth = drawHeight * imageAspectRatio
         drawX = (layoutW - drawWidth) / 2f
     }
     val localX = x - drawX
     val localY = y - drawY
     val bitmapX = (localX / drawWidth) * original.width
     val bitmapY = (localY / drawHeight) * original.height
     return Pair(bitmapX, bitmapY)
}
