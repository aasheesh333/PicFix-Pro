package com.dhanuk.photodoctorpro.ui.screens

import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
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
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowForward
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
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

                    Text("Feather (Blur): ${uiState.feather.toInt()}")
                    Slider(
                        value = uiState.feather,
                        onValueChange = { viewModel.onFeatherChanged(it) },
                        valueRange = 0f..20f
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

    var currentPath by remember { mutableStateOf(Path()) }
    var layoutSize by remember { mutableStateOf(androidx.compose.ui.unit.IntSize.Zero) }

    val bitmapToShow = uiState.processedBitmap ?: uiState.originalBitmap!!

    Box(
        modifier = Modifier
            .fillMaxSize()
            .onSizeChanged { layoutSize = it }
            .pointerInput(uiState.brushSize, zoomState, layoutSize) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)

                    var isZooming = false

                    // Reset Path
                    currentPath = Path()
                    currentPath.moveTo(Float.NaN, Float.NaN)

                    fun mapToBitmap(x: Float, y: Float): Pair<Float, Float>? {
                         if (layoutSize.width <= 0 || layoutSize.height <= 0) return null
                         val viewAspectRatio = layoutSize.width.toFloat() / layoutSize.height.toFloat()
                         val imageAspectRatio = bitmapToShow.width.toFloat() / bitmapToShow.height.toFloat()
                         var drawWidth = layoutSize.width.toFloat()
                         var drawHeight = layoutSize.height.toFloat()
                         var drawX = 0f
                         var drawY = 0f
                         if (imageAspectRatio > viewAspectRatio) {
                             drawHeight = drawWidth / imageAspectRatio
                             drawY = (layoutSize.height - drawHeight) / 2f
                         } else {
                             drawWidth = drawHeight * imageAspectRatio
                             drawX = (layoutSize.width - drawWidth) / 2f
                         }
                         val localX = x - drawX
                         val localY = y - drawY
                         val bitmapX = (localX / drawWidth) * bitmapToShow.width
                         val bitmapY = (localY / drawHeight) * bitmapToShow.height
                         return Pair(bitmapX, bitmapY)
                    }

                    val downX = (down.position.x - zoomState.offset.x) / zoomState.scale
                    val downY = (down.position.y - zoomState.offset.y) / zoomState.scale

                    val startPt = mapToBitmap(downX, downY)
                    if (startPt != null) {
                        currentPath.moveTo(startPt.first, startPt.second)
                    }

                    do {
                        val event = awaitPointerEvent()
                        val pointerCount = event.changes.size

                        if (pointerCount >= 2) {
                            isZooming = true
                        }

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
                            // Drawing
                             event.changes.forEach { change ->
                                if (change.positionChanged()) {
                                     val currX = (change.position.x - zoomState.offset.x) / zoomState.scale
                                     val currY = (change.position.y - zoomState.offset.y) / zoomState.scale

                                     val pt = mapToBitmap(currX, currY)
                                     if (pt != null) {
                                         currentPath.lineTo(pt.first, pt.second)
                                     }
                                     change.consume()
                                }
                            }
                        }

                    } while (event.changes.any { it.pressed })

                    if (!isZooming) {
                        val newPaths = uiState.paths + (currentPath to uiState.brushSize)
                        viewModel.onPathsChanged(newPaths)
                    }
                    currentPath = Path() // Reset
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

                // Scale factor
                val scaleX = drawWidth / bitmapToShow.width
                val scaleY = drawHeight / bitmapToShow.height

                drawContext.canvas.save()
                drawContext.canvas.translate(drawX, drawY)
                drawContext.canvas.scale(scaleX, scaleY)

                // Draw committed paths
                uiState.paths.forEach { (path, strokeWidth) ->
                    drawPath(
                        path = path,
                        color = Color.Red.copy(alpha = 0.5f),
                        style = Stroke(width = strokeWidth, cap = StrokeCap.Round, join = StrokeJoin.Round)
                    )
                }

                // Draw current dragging path
                if (!currentPath.isEmpty) {
                    drawPath(
                        path = currentPath,
                        color = Color.Red.copy(alpha = 0.5f),
                        style = Stroke(width = uiState.brushSize, cap = StrokeCap.Round, join = StrokeJoin.Round)
                    )
                }

                drawContext.canvas.restore()
            }
        }
    }
}
