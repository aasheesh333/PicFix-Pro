package com.dhanuk.photodoctorpro.ui.screens

import android.app.Activity
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.Crossfade
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
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
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import com.dhanuk.photodoctorpro.R
import com.dhanuk.photodoctorpro.data.local.AppDatabase
import com.dhanuk.photodoctorpro.data.repository.HistoryRepository

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ObjectEraserScreen(navController: NavController) {
    val context = LocalContext.current
    val activity = context as Activity
    val db = AppDatabase.getDatabase(context)
    val repository = HistoryRepository(db.historyDao())
    val viewModel: ObjectEraserViewModel = viewModel(factory = ViewModelFactory(repository))
    val uiState by viewModel.uiState.collectAsState()

    val imagePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let { viewModel.onImageSelected(it, context) }
    }

    var currentPath by remember { mutableStateOf<Path?>(null) }
    var currentPaths by remember { mutableStateOf<List<Pair<Path, Float>>>(emptyList()) }

    LaunchedEffect(uiState.paths) {
        currentPaths = uiState.paths
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.object_eraser)) },
                actions = {
                    if (uiState.processedBitmap != null) {
                        IconButton(onClick = { viewModel.saveImage(activity) }) {
                            Icon(Icons.Default.Check, contentDescription = stringResource(R.string.save_image))
                        }
                    }
                }
            )
        }
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
                    .weight(1f),
                contentAlignment = Alignment.Center
            ) {
                if (uiState.originalBitmap == null) {
                    Button(onClick = { imagePickerLauncher.launch("image/*") }) {
                        Text(stringResource(R.string.select_image))
                    }
                }

                Crossfade(targetState = uiState.processedBitmap) { processed ->
                    if (processed != null) {
                        Image(bitmap = processed.asImageBitmap(), contentDescription = "Processed Image")
                    } else if (uiState.originalBitmap != null) {
                        Image(
                            bitmap = uiState.originalBitmap!!.asImageBitmap(),
                            contentDescription = "Original Image",
                            modifier = Modifier
                                .fillMaxSize()
                                .pointerInput(uiState.brushSize) {
                                    detectDragGestures(
                                        onDragStart = { offset ->
                                            currentPath = Path().apply { moveTo(offset.x, offset.y) }
                                        },
                                        onDrag = { change, _ ->
                                            currentPath?.lineTo(change.position.x, change.position.y)
                                            // Force recomposition
                                            currentPaths = currentPaths
                                        },
                                        onDragEnd = {
                                            currentPath?.let {
                                                viewModel.onPathsChanged(currentPaths + (it to uiState.brushSize))
                                            }
                                            currentPath = null
                                        }
                                    )
                                }
                        )

                        Canvas(modifier = Modifier.fillMaxSize()) {
                            (currentPaths + (currentPath?.let { listOf(it to uiState.brushSize) } ?: emptyList())).forEach { (path, strokeWidth) ->
                                drawPath(
                                    path = path,
                                    color = Color.Red.copy(alpha = 0.5f),
                                    style = Stroke(
                                        width = strokeWidth,
                                        cap = StrokeCap.Round,
                                        join = StrokeJoin.Round
                                    )
                                )
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
                    Text(stringResource(R.string.brush_size))
                    Slider(
                        value = uiState.brushSize,
                        onValueChange = viewModel::onBrushSizeChanged,
                        valueRange = 10f..100f
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceEvenly
                    ) {
                        IconButton(onClick = viewModel::onUndo, enabled = uiState.paths.isNotEmpty()) {
                            Icon(Icons.Default.ArrowBack, contentDescription = stringResource(R.string.undo))
                        }
                        Button(onClick = viewModel::eraseObjects, enabled = uiState.paths.isNotEmpty()) {
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
             uiState.error?.let {
                Text(it, color = MaterialTheme.colorScheme.error)
            }
        }
    }
}
