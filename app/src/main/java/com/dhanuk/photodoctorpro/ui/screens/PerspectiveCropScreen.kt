package com.dhanuk.photodoctorpro.ui.screens

import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Point
import android.graphics.PointF
import android.net.Uri
import android.os.Environment
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.AutoFixHigh
import androidx.compose.material.icons.filled.Save
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color as ComposeColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import com.dhanuk.photodoctorpro.data.local.AppDatabase
import com.dhanuk.photodoctorpro.data.local.History
import com.dhanuk.photodoctorpro.ui.components.SaveSuccessDialog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

data class PerspectiveCropUiState(
    val originalBitmap: Bitmap? = null,
    val processedBitmap: Bitmap? = null,
    val corners: List<PointF> = emptyList(),
    val isLoading: Boolean = false,
    val error: String? = null,
    val savedFilePath: String? = null
)

class PerspectiveCropViewModel : ViewModel() {
    private val _uiState = MutableStateFlow(PerspectiveCropUiState())
    val uiState: StateFlow<PerspectiveCropUiState> = _uiState.asStateFlow()

    private var appContext: android.content.Context? = null
    fun setContext(context: android.content.Context) { appContext = context }

    fun onImageSelected(uri: Uri) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null) }
            try {
                val context = appContext ?: return@launch
                val bitmap = withContext(Dispatchers.IO) {
                    val inputStream = context.contentResolver.openInputStream(uri)
                    BitmapFactory.decodeStream(inputStream)
                }
                if (bitmap != null) {
                    _uiState.update {
                        it.copy(
                            originalBitmap = bitmap,
                            isLoading = false
                        )
                    }
                    autoDetectEdges(bitmap)
                }
            } catch (e: Exception) {
                _uiState.update { it.copy(error = e.message, isLoading = false) }
            }
        }
    }

    private fun autoDetectEdges(bitmap: Bitmap) {
        viewModelScope.launch(Dispatchers.Default) {
            val w = bitmap.width
            val h = bitmap.height
            val padding = 0.05f
            val corners = listOf(
                PointF(w * padding, h * padding),
                PointF(w * (1 - padding), h * padding),
                PointF(w * (1 - padding), h * (1 - padding)),
                PointF(w * padding, h * (1 - padding))
            )
            _uiState.update { it.copy(corners = corners) }
        }
    }

    fun updateCorner(index: Int, x: Float, y: Float, canvasSize: IntSize) {
        val bitmap = _uiState.value.originalBitmap ?: return
        val scaleX = bitmap.width.toFloat() / canvasSize.width
        val scaleY = bitmap.height.toFloat() / canvasSize.height
        val updated = _uiState.value.corners.toMutableList()
        if (index !in updated.indices) return
        updated[index] = PointF(
            x.coerceIn(0f, canvasSize.width.toFloat()) * scaleX,
            y.coerceIn(0f, canvasSize.height.toFloat()) * scaleY
        )
        _uiState.update { it.copy(corners = updated) }
    }

    fun autoDetect() {
        val bitmap = _uiState.value.originalBitmap ?: return
        autoDetectEdges(bitmap)
    }

    fun applyCrop() {
        val state = _uiState.value
        val bitmap = state.originalBitmap ?: return
        val corners = state.corners
        if (corners.size != 4) return
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true) }
            val result = withContext(Dispatchers.Default) {
                warpPerspective(bitmap, corners)
            }
            _uiState.update { it.copy(processedBitmap = result, isLoading = false) }
        }
    }

    private fun warpPerspective(source: Bitmap, corners: List<PointF>): Bitmap {
        val w0 = source.width.toFloat()
        val h0 = source.height.toFloat()
        val src = floatArrayOf(
            corners[0].x, corners[0].y,
            corners[1].x, corners[1].y,
            corners[2].x, corners[2].y,
            corners[3].x, corners[3].y
        )
        val widthA = distance(src[0], src[1], src[4], src[5])
        val widthB = distance(src[2], src[3], src[6], src[7])
        val heightA = distance(src[0], src[1], src[2], src[3])
        val heightB = distance(src[4], src[5], src[6], src[7])
        val maxW = maxOf(widthA, widthB).toInt()
        val maxH = maxOf(heightA, heightB).toInt()

        val dst = floatArrayOf(
            0f, 0f,
            maxW.toFloat(), 0f,
            maxW.toFloat(), maxH.toFloat(),
            0f, maxH.toFloat()
        )

        val matrix = Matrix()
        matrix.setPolyToPoly(src, 0, dst, 0, 4)
        val output = Bitmap.createBitmap(maxW.coerceAtLeast(1), maxH.coerceAtLeast(1), Bitmap.Config.ARGB_8888)
        val canvas = Canvas(output)
        val paint = Paint().apply { isAntiAlias = true; isFilterBitmap = true }
        canvas.drawBitmap(source, matrix, paint)
        return output
    }

    private fun distance(x1: Float, y1: Float, x2: Float, y2: Float): Float {
        val dx = x2 - x1
        val dy = y2 - y1
        return kotlin.math.sqrt(dx * dx + dy * dy)
    }

    fun saveImage(context: android.content.Context) {
        val bitmap = _uiState.value.processedBitmap ?: return
        viewModelScope.launch {
            try {
                val savedPath = withContext(Dispatchers.IO) {
                    val file = File(
                        context.getExternalFilesDir(Environment.DIRECTORY_PICTURES),
                        "PDPro_Scan_${System.currentTimeMillis()}.jpg"
                    )
                    FileOutputStream(file).use { out ->
                        bitmap.compress(Bitmap.CompressFormat.JPEG, 95, out)
                    }
                    file.absolutePath
                }
                _uiState.update { it.copy(savedFilePath = savedPath) }
                try {
                    val db = AppDatabase.getDatabase(context)
                    db.historyDao().insert(
                        History(
                            operationType = "Document Scan",
                            inputFilePath = "",
                            filePath = savedPath,
                            timestamp = System.currentTimeMillis()
                        )
                    )
                } catch (_: Exception) {}
            } catch (e: Exception) {
                _uiState.update { it.copy(error = e.message) }
            }
        }
    }

    fun onErrorShown() { _uiState.update { it.copy(error = null) } }
    fun onSavedMessageShown() { _uiState.update { it.copy(savedFilePath = null) } }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PerspectiveCropScreen(navController: NavController) {
    val context = LocalContext.current
    val activity = context as Activity
    val viewModel: PerspectiveCropViewModel = viewModel()
    LaunchedEffect(Unit) { viewModel.setContext(context) }
    val uiState by viewModel.uiState.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    var showSaveSuccessDialog by remember { mutableStateOf<String?>(null) }
    var canvasSize by remember { mutableStateOf(IntSize.Zero) }

    val imagePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? -> uri?.let { viewModel.onImageSelected(it) } }

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

    showSaveSuccessDialog?.let { path ->
        SaveSuccessDialog(
            filePath = path,
            onDismiss = { showSaveSuccessDialog = null },
            onShareWhatsApp = {
                try {
                    val file = File(path)
                    val uri = FileProvider.getUriForFile(context, "${context.packageName}.provider", file)
                    val intent = Intent(Intent.ACTION_SEND).apply {
                        type = "image/jpeg"
                        putExtra(Intent.EXTRA_STREAM, uri)
                        setPackage("com.whatsapp")
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    context.startActivity(intent)
                } catch (e: Exception) {
                    Toast.makeText(context, "WhatsApp not installed", Toast.LENGTH_SHORT).show()
                }
            },
            onShareOther = {
                try {
                    val file = File(path)
                    val uri = FileProvider.getUriForFile(context, "${context.packageName}.provider", file)
                    val intent = Intent(Intent.ACTION_SEND).apply {
                        type = "image/jpeg"
                        putExtra(Intent.EXTRA_STREAM, uri)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    context.startActivity(Intent.createChooser(intent, "Share Image"))
                } catch (e: Exception) { e.printStackTrace() }
            },
            onOpen = {
                try {
                    val file = File(path)
                    val uri = FileProvider.getUriForFile(context, "${context.packageName}.provider", file)
                    val intent = Intent(Intent.ACTION_VIEW).apply {
                        setDataAndType(uri, "image/*")
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    context.startActivity(intent)
                } catch (e: Exception) { e.printStackTrace() }
            }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Document Scanner") },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).background(MaterialTheme.colorScheme.background)
        ) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .onSizeChanged { canvasSize = it },
                contentAlignment = Alignment.Center
            ) {
                if (uiState.originalBitmap != null) {
                    Box(modifier = Modifier.fillMaxSize()) {
                        Image(
                            bitmap = uiState.originalBitmap!!.asImageBitmap(),
                            contentDescription = "Document",
                            modifier = Modifier.fillMaxSize(),
                            contentScale = ContentScale.Fit
                        )
                        if (uiState.processedBitmap == null) {
                            Canvas(modifier = Modifier.fillMaxSize()) {
                                val w = size.width
                                val h = size.height
                                val bitmapW = uiState.originalBitmap!!.width.toFloat()
                                val bitmapH = uiState.originalBitmap!!.height.toFloat()
                                val scale = minOf(w / bitmapW, h / bitmapH)
                                val drawW = bitmapW * scale
                                val drawH = bitmapH * scale
                                val offsetX = (w - drawW) / 2
                                val offsetY = (h - drawH) / 2

                                val mappedCorners = uiState.corners.map { corner ->
                                    Offset(
                                        offsetX + corner.x * scale,
                                        offsetY + corner.y * scale
                                    )
                                }

                                if (mappedCorners.size == 4) {
                                    for (i in mappedCorners.indices) {
                                        val start = mappedCorners[i]
                                        val end = mappedCorners[(i + 1) % mappedCorners.size]
                                        drawLine(
                                            color = androidx.compose.ui.graphics.Color(0xFF00E676),
                                            start = start,
                                            end = end,
                                            strokeWidth = 4f
                                        )
                                    }
                                    mappedCorners.forEach { p ->
                                        drawCircle(
                                            color = androidx.compose.ui.graphics.Color(0xFF00E676),
                                            radius = 16f,
                                            center = p
                                        )
                                        drawCircle(
                                            color = androidx.compose.ui.graphics.Color.White,
                                            radius = 8f,
                                            center = p
                                        )
                                    }
                                }
                            }

                            // Touch handlers for each corner
                            if (canvasSize.width > 0) {
                                val bitmapW = uiState.originalBitmap!!.width.toFloat()
                                val bitmapH = uiState.originalBitmap!!.height.toFloat()
                                val scale = minOf(
                                    canvasSize.width.toFloat() / bitmapW,
                                    canvasSize.height.toFloat() / bitmapH
                                )
                                val drawW = bitmapW * scale
                                val drawH = bitmapH * scale
                                val offsetX = (canvasSize.width - drawW) / 2
                                val offsetY = (canvasSize.height - drawH) / 2

                                uiState.corners.forEachIndexed { idx, corner ->
                                    val screenX = offsetX + corner.x * scale
                                    val screenY = offsetY + corner.y * scale
                                    Box(
                                        modifier = Modifier
                                            .offset {
                                                androidx.compose.ui.unit.IntOffset(
                                                    (screenX - 50).toInt().coerceAtLeast(0),
                                                    (screenY - 50).toInt().coerceAtLeast(0)
                                                )
                                            }
                                            .size(100.dp)
                                            .pointerInput(idx) {
                                                detectDragGestures { change, _ ->
                                                    change.consume()
                                                    viewModel.updateCorner(
                                                        idx,
                                                        change.position.x + (screenX - 50),
                                                        change.position.y + (screenY - 50),
                                                        canvasSize
                                                    )
                                                }
                                            }
                                    )
                                }
                            }
                        }
                    }
                } else {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(Icons.Default.AutoFixHigh, contentDescription = null, modifier = Modifier.size(64.dp), tint = MaterialTheme.colorScheme.primary)
                        Spacer(modifier = Modifier.height(16.dp))
                        Text("Auto-flatten documents & photos", style = MaterialTheme.typography.bodyLarge)
                        Spacer(modifier = Modifier.height(8.dp))
                        Text("Drag corners to adjust", color = MaterialTheme.colorScheme.secondary)
                        Spacer(modifier = Modifier.height(16.dp))
                        Button(onClick = { imagePickerLauncher.launch("image/*") }) {
                            Text("Pick Image")
                        }
                    }
                }
            }

            if (uiState.originalBitmap != null) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                    horizontalArrangement = Arrangement.SpaceEvenly
                ) {
                    OutlinedButton(onClick = { viewModel.autoDetect() }, modifier = Modifier.weight(1f)) {
                        Text("Auto")
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    OutlinedButton(onClick = { imagePickerLauncher.launch("image/*") }, modifier = Modifier.weight(1f)) {
                        Text("New")
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    if (uiState.processedBitmap == null) {
                        Button(onClick = { viewModel.applyCrop() }, modifier = Modifier.weight(1f)) {
                            Text("Crop")
                        }
                    } else {
                        Button(
                            onClick = { viewModel.saveImage(context) },
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(Icons.Default.Save, contentDescription = "Save")
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Save")
                        }
                    }
                }
            }
        }
    }
}