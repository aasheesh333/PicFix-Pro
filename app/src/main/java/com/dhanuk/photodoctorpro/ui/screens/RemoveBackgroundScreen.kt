package com.dhanuk.photodoctorpro.ui.screens

import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.Crossfade
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidPath
import androidx.compose.ui.graphics.asComposePath
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
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
import com.dhanuk.photodoctorpro.ui.navigation.LocalGlobalNavigationState
import kotlinx.coroutines.launch
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RemoveBackgroundScreen(navController: NavController) {
    val context = LocalContext.current
    val activity = context as Activity
    val db = AppDatabase.getDatabase(context)
    val repository = HistoryRepository(db.historyDao())
    val viewModel: RemoveBackgroundViewModel = viewModel(factory = ViewModelFactory(repository))
    val uiState by viewModel.uiState.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    val globalState = LocalGlobalNavigationState.current
    val scope = rememberCoroutineScope()

    var showUnsavedDialog by remember { mutableStateOf(false) }
    val hasUnsavedChanges = uiState.processedBitmap != null && uiState.savedFilePath == null

    // Sync Global State
    LaunchedEffect(hasUnsavedChanges) {
        globalState.hasUnsavedChanges = hasUnsavedChanges
        if (hasUnsavedChanges) {
            globalState.onSave = { viewModel.saveImage(activity) }
            globalState.onDiscard = { viewModel.reset() }
        } else {
            globalState.clear()
        }
    }

    // Local Back Handler
    BackHandler(enabled = hasUnsavedChanges || uiState.isRefining) {
        if (uiState.isRefining) {
            showUnsavedDialog = true
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
                         if (uiState.isRefining) {
                             viewModel.applyRefinement(0f)
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
                title = { Text(if (uiState.isRefining) "Refine Edges" else stringResource(R.string.remove_background)) },
                navigationIcon = {
                    if (uiState.isRefining) {
                        IconButton(onClick = {
                            showUnsavedDialog = true
                        }) {
                            Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                        }
                    }
                },
                actions = {
                    if (uiState.isRefining) {
                        IconButton(onClick = { viewModel.applyRefinement(0f) }) {
                            Icon(Icons.Default.Check, contentDescription = "Apply")
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
                    // Refine Mode
                    RefineEditor(
                        viewModel = viewModel,
                        original = uiState.originalBitmap!!,
                        mask = uiState.maskBitmap!!
                    )
                } else {
                    // Result Mode or Start
                    if (uiState.processedBitmap != null) {
                        // Show Checkerboard background for transparency
                        Box(modifier = Modifier.fillMaxSize().background(Color.LightGray)) {
                            Image(
                                bitmap = uiState.processedBitmap!!.asImageBitmap(),
                                contentDescription = "Processed",
                                modifier = Modifier.fillMaxSize(),
                                contentScale = ContentScale.Fit
                            )
                        }
                    } else if (uiState.selectedImageUri != null) {
                        Image(
                            painter = rememberAsyncImagePainter(uiState.selectedImageUri),
                            contentDescription = "Selected",
                            modifier = Modifier.fillMaxSize(),
                            contentScale = ContentScale.Fit
                        )
                    } else {
                        Text(stringResource(R.string.select_an_image_to_start))
                    }
                }
            }

            // Bottom Controls
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
                    // Result Actions
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                        OutlinedButton(onClick = { viewModel.startRefining() }) {
                            Text("Refine Edges")
                        }
                        Button(onClick = { scope.launch { viewModel.saveImage(activity) } }) {
                            Text(stringResource(R.string.save_png))
                        }
                    }
                    OutlinedButton(onClick = { viewModel.reset() }, modifier = Modifier.fillMaxWidth()) {
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
    mask: Bitmap
) {
    var brushSize by remember { mutableStateOf(40f) }
    var feather by remember { mutableStateOf(0f) }
    var isAddMode by remember { mutableStateOf(false) }

    var currentPath by remember { mutableStateOf(android.graphics.Path()) }

    val aspectRatio = original.width.toFloat() / original.height.toFloat()

    Column(modifier = Modifier.fillMaxSize()) {
        Box(
             modifier = Modifier
                 .weight(1f)
                 .fillMaxWidth()
                 .aspectRatio(aspectRatio)
                 .pointerInput(isAddMode, brushSize) {
                     detectDragGestures(
                         onDragStart = { offset ->
                             currentPath.reset()
                             currentPath.moveTo(offset.x, offset.y)
                         },
                         onDrag = { change, _ ->
                             currentPath.lineTo(change.position.x, change.position.y)
                         },
                         onDragEnd = {
                             // Map coords
                             val scaleX = original.width.toFloat() / size.width
                             val scaleY = original.height.toFloat() / size.height

                             val matrix = android.graphics.Matrix()
                             matrix.setScale(scaleX, scaleY)
                             val mappedPath = android.graphics.Path(currentPath)
                             mappedPath.transform(matrix)

                             viewModel.updateMask(mappedPath, isAddMode, brushSize * scaleX)
                             currentPath.reset()
                         }
                     )
                 }
        ) {
            Image(
                bitmap = original.asImageBitmap(),
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.FillBounds
            )

            val maskImage = mask.asImageBitmap()
            Canvas(modifier = Modifier.fillMaxSize()) {
                // Draw mask tinted Red
                drawImage(
                    image = maskImage,
                    dstSize = androidx.compose.ui.unit.IntSize(size.width.toInt(), size.height.toInt()),
                    colorFilter = androidx.compose.ui.graphics.ColorFilter.tint(Color.Red.copy(alpha = 0.5f), BlendMode.SrcIn)
                )

                if (!currentPath.isEmpty) {
                    drawPath(
                        path = currentPath.asComposePath(),
                        color = if (isAddMode) Color.Red.copy(alpha = 0.8f) else Color.Blue.copy(alpha=0.5f),
                        style = Stroke(width = brushSize, cap = androidx.compose.ui.graphics.StrokeCap.Round)
                    )
                }
            }
        }

        Column(modifier = Modifier.padding(8.dp)) {
            Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                IconButton(onClick = { viewModel.undo() }) { Icon(Icons.Default.ArrowBack, "Undo") }
                Row {
                    FilledTonalIconToggleButton(checked = isAddMode, onCheckedChange = { isAddMode = true }) {
                        Icon(Icons.Default.Add, "Keep")
                    }
                    Spacer(Modifier.width(8.dp))
                    FilledTonalIconToggleButton(checked = !isAddMode, onCheckedChange = { isAddMode = false }) {
                        Icon(Icons.Default.Remove, "Erase")
                    }
                }
                IconButton(onClick = { viewModel.redo() }) { Icon(Icons.Default.ArrowForward, "Redo") }
            }

            Text("Brush Size")
            Slider(value = brushSize, onValueChange = { brushSize = it }, valueRange = 10f..100f)

            Text("Feather Edges: ${feather.toInt()}")
            Slider(
                value = feather,
                onValueChange = { feather = it },
                valueRange = 0f..20f
            )

            Button(onClick = { viewModel.applyRefinement(feather) }, modifier = Modifier.fillMaxWidth()) {
                Text("Done")
            }
        }
    }
}
