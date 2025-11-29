package com.dhanuk.photodoctorpro.ui.screens

import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Matrix
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowForward
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.asAndroidPath
import androidx.compose.ui.graphics.asComposePath
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
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
fun ObjectEraserScreen(navController: NavController) {
    val context = LocalContext.current
    val activity = context as Activity
    val db = AppDatabase.getDatabase(context)
    val repository = HistoryRepository(db.historyDao())
    val viewModel: ObjectEraserViewModel = viewModel(factory = ViewModelFactory(repository))
    val uiState by viewModel.uiState.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    val globalState = LocalGlobalNavigationState.current

    var showUnsavedDialog by remember { mutableStateOf(false) }

    // Logic: Unsaved changes if paths exist OR if we have undo history (meaning we edited) AND not saved yet.
    val hasUnsavedChanges = (uiState.paths.isNotEmpty() || uiState.canUndo) && uiState.savedFilePath == null

    // Sync Global State
    LaunchedEffect(hasUnsavedChanges) {
        globalState.hasUnsavedChanges = hasUnsavedChanges
        if (hasUnsavedChanges) {
            globalState.onSave = {
                // We assume save returns boolean now (updated logic in similar VMs, let's verify VM signature)
                // VM saveImage returns boolean in my previous thought but maybe not implemented that way in ObjectEraserVM yet?
                // Checked ObjectEraserViewModel: saveImage returns Boolean.
                viewModel.saveImage(activity)
            }
            globalState.onDiscard = { viewModel.onReset() }
        } else {
            globalState.clear()
        }
    }

    BackHandler(enabled = hasUnsavedChanges) {
        showUnsavedDialog = true
    }

    if (showUnsavedDialog) {
        val scope = rememberCoroutineScope()
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
                        viewModel.onReset()
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

    // Temporary path state for drawing in View Coords
    var currentPathViewCoords by remember { mutableStateOf<Path?>(null) }

    LaunchedEffect(uiState.error) {
        uiState.error?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.onErrorShown()
        }
    }

    LaunchedEffect(uiState.resetPerformed) {
        if (uiState.resetPerformed) {
            snackbarHostState.showSnackbar(context.getString(R.string.changes_reset))
            viewModel.onResetMessageShown()
        }
    }

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
                    if (uiState.processedBitmap != null || uiState.canUndo) {
                        IconButton(onClick = { viewModel.saveImage(activity) }) {
                            Icon(Icons.Default.Check, contentDescription = stringResource(R.string.save_image))
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
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .padding(16.dp),
                contentAlignment = Alignment.Center
            ) {
                if (uiState.originalBitmap == null && uiState.processedBitmap == null) {
                    Button(onClick = { imagePickerLauncher.launch("image/*") }) {
                        Text(stringResource(R.string.select_image))
                    }
                } else {
                    val displayBitmap = uiState.processedBitmap ?: uiState.originalBitmap
                    if (displayBitmap != null) {

                        // Use BoxWithConstraints to get view size
                        BoxWithConstraints(
                             modifier = Modifier.fillMaxSize(),
                             contentAlignment = Alignment.Center
                        ) {
                            val bitmapWidth = displayBitmap.width.toFloat()
                            val bitmapHeight = displayBitmap.height.toFloat()
                            val aspectRatio = bitmapWidth / bitmapHeight

                            // Aspect Ratio Box
                            Box(
                                modifier = Modifier
                                    .aspectRatio(aspectRatio)
                                    .pointerInput(uiState.brushSize) {
                                        detectDragGestures(
                                            onDragStart = { offset ->
                                                currentPathViewCoords = Path().apply { moveTo(offset.x, offset.y) }
                                            },
                                            onDrag = { change, _ ->
                                                currentPathViewCoords?.lineTo(change.position.x, change.position.y)
                                            },
                                            onDragEnd = {
                                                currentPathViewCoords?.let { pathView ->
                                                    // Convert View Coords -> Bitmap Coords
                                                    val scaleX = bitmapWidth / size.width
                                                    val scaleY = bitmapHeight / size.height
                                                    val matrix = Matrix()
                                                    matrix.setScale(scaleX, scaleY)

                                                    val androidPath = pathView.asAndroidPath()
                                                    androidPath.transform(matrix)
                                                    val pathBitmapCoords = androidPath.asComposePath()

                                                    // Add to VM (Bitmap Coords)
                                                    // Stroke Width also needs scaling?
                                                    // VM uses stroke width to draw on Bitmap Canvas.
                                                    // uiState.brushSize is from slider. Let's say slider 10..100.
                                                    // This usually means Screen Pixels? Or Bitmap Pixels?
                                                    // Ideally user selects visual size.
                                                    // So we should scale brush size too.
                                                    val scaledBrushSize = uiState.brushSize * scaleX

                                                    viewModel.onPathsChanged(uiState.paths + (pathBitmapCoords to scaledBrushSize))
                                                }
                                                currentPathViewCoords = null
                                            }
                                        )
                                    }
                            ) {
                                Image(
                                    bitmap = displayBitmap.asImageBitmap(),
                                    contentDescription = "Editing Image",
                                    modifier = Modifier.fillMaxSize(),
                                    contentScale = ContentScale.FillBounds
                                )

                                Canvas(modifier = Modifier.fillMaxSize()) {
                                    // 1. Draw Saved Paths (Bitmap Coords -> View Coords)
                                    val scaleX = size.width / bitmapWidth
                                    val scaleY = size.height / bitmapHeight

                                    scale(scaleX, scaleY, pivot = androidx.compose.ui.geometry.Offset.Zero) {
                                        uiState.paths.forEach { (path, strokeWidth) ->
                                            drawPath(
                                                path = path,
                                                color = Color.Red.copy(alpha = 0.5f),
                                                style = Stroke(
                                                    width = strokeWidth, // This will be scaled by `scale(...)`
                                                    cap = StrokeCap.Round,
                                                    join = StrokeJoin.Round
                                                )
                                            )
                                        }
                                    }

                                    // 2. Draw Current Path (Already in View Coords)
                                    currentPathViewCoords?.let { path ->
                                        drawPath(
                                            path = path,
                                            color = Color.Red.copy(alpha = 0.5f),
                                            style = Stroke(
                                                width = uiState.brushSize,
                                                cap = StrokeCap.Round,
                                                join = StrokeJoin.Round
                                            )
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                if (uiState.isErasing) {
                    CircularProgressIndicator()
                }
            }

            if (uiState.originalBitmap != null) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("Brush Size")
                    Slider(
                        value = uiState.brushSize,
                        onValueChange = viewModel::onBrushSizeChanged,
                        valueRange = 10f..100f
                    )

                    Text("Feather: ${uiState.feather.toInt()}")
                    Slider(
                        value = uiState.feather,
                        onValueChange = viewModel::onFeatherChanged,
                        valueRange = 0f..20f
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceEvenly
                    ) {
                        IconButton(onClick = viewModel::onUndo, enabled = uiState.paths.isNotEmpty() || uiState.canUndo) {
                            Icon(Icons.Default.ArrowBack, contentDescription = stringResource(R.string.undo))
                        }
                        IconButton(onClick = viewModel::onRedo, enabled = uiState.canRedo) {
                            Icon(Icons.Default.ArrowForward, contentDescription = "Redo")
                        }
                        Button(
                            onClick = { viewModel.eraseObjects() },
                            enabled = uiState.paths.isNotEmpty() && !uiState.isErasing
                        ) {
                             Icon(Icons.Default.Edit, contentDescription = null)
                             Spacer(Modifier.size(ButtonDefaults.IconSpacing))
                            Text(stringResource(R.string.erase))
                        }
                        IconButton(onClick = { viewModel.onReset() }) {
                            Icon(Icons.Default.Delete, contentDescription = stringResource(R.string.reset))
                        }
                    }
                }
            }
        }
    }
}
