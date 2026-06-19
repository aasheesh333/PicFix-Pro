package com.dhanuk.photodoctorpro.ui.screens

import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PointF
import android.net.Uri
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
import androidx.compose.material.icons.filled.Compare
import androidx.compose.material.icons.filled.Save
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color as ComposeColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import com.dhanuk.photodoctorpro.data.local.AppDatabase
import com.dhanuk.photodoctorpro.data.repository.HistoryRepository
import com.dhanuk.photodoctorpro.ui.components.BeforeAfterSlider
import com.dhanuk.photodoctorpro.ui.components.SaveSuccessDialog
import com.dhanuk.photodoctorpro.utils.findActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.opencv.android.Utils
import org.opencv.core.Mat
import org.opencv.core.Point
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import java.io.File

data class PerspectiveCropUiState(
    val originalBitmap: Bitmap? = null,
    val processedBitmap: Bitmap? = null,
    val corners: List<PointF> = emptyList(),
    val isLoading: Boolean = false,
    val error: String? = null,
    val savedFilePath: String? = null
)

class PerspectiveCropViewModel(private val repository: com.dhanuk.photodoctorpro.data.repository.HistoryRepository) : ViewModel() {
    private val _uiState = MutableStateFlow(PerspectiveCropUiState())
    val uiState: StateFlow<PerspectiveCropUiState> = _uiState.asStateFlow()

    fun onImageSelected(uri: Uri, context: android.content.Context) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null) }
            try {
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
            try {
                val src = Mat()
                val gray = Mat()
                val edges = Mat()
                val hierarchy = Mat()
                Utils.bitmapToMat(bitmap, src)

                val scale = 600f / maxOf(bitmap.width, bitmap.height)
                val resized = Mat()
                Imgproc.resize(src, resized, Size(), scale.toDouble(), scale.toDouble())

                Imgproc.cvtColor(resized, gray, Imgproc.COLOR_RGBA2GRAY)
                Imgproc.GaussianBlur(gray, gray, Size(5.0, 5.0), 0.0)
                Imgproc.Canny(gray, edges, 50.0, 150.0)

                val contours = mutableListOf<org.opencv.core.MatOfPoint>()
                Imgproc.findContours(edges, contours, hierarchy, Imgproc.RETR_EXTERNAL, Imgproc.CHAIN_APPROX_SIMPLE)

                val maxContour = contours.maxByOrNull { Imgproc.contourArea(it) }
                val w = bitmap.width.toFloat()
                val h = bitmap.height.toFloat()
                val padding = 0.03f

                if (maxContour != null && contours.size > 0) {
                    val peri = Imgproc.arcLength(org.opencv.core.MatOfPoint2f(*maxContour.toArray()), true)
                    val approx = org.opencv.core.MatOfPoint2f()
                    Imgproc.approxPolyDP(org.opencv.core.MatOfPoint2f(*maxContour.toArray()), approx, 0.02 * peri, true)

                    if (approx.toList().size == 4) {
                        val pts = approx.toList()
                        val sorted = sortCorners(pts, scale)
                        _uiState.update { it.copy(corners = sorted) }
                        src.release()
                        gray.release()
                        edges.release()
                        hierarchy.release()
                        resized.release()
                        return@launch
                    }
                }

                src.release()
                gray.release()
                edges.release()
                hierarchy.release()
                resized.release()

                val corners = listOf(
                    PointF(w * padding, h * padding),
                    PointF(w * (1 - padding), h * padding),
                    PointF(w * (1 - padding), h * (1 - padding)),
                    PointF(w * padding, h * (1 - padding))
                )
                _uiState.update { it.copy(corners = corners) }
            } catch (_: Exception) {
                val w = bitmap.width.toFloat()
                val h = bitmap.height.toFloat()
                val padding = 0.03f
                val corners = listOf(
                    PointF(w * padding, h * padding),
                    PointF(w * (1 - padding), h * padding),
                    PointF(w * (1 - padding), h * (1 - padding)),
                    PointF(w * padding, h * (1 - padding))
                )
                _uiState.update { it.copy(corners = corners) }
            }
        }
    }

    private fun sortCorners(pts: List<Point>, scale: Float): List<PointF> {
        val w = _uiState.value.originalBitmap?.width?.toFloat() ?: return emptyList()
        val h = _uiState.value.originalBitmap?.height?.toFloat() ?: return emptyList()
        val mapped = pts.map {
            val nx = (it.x / scale.toDouble()).coerceIn(0.0, w.toDouble()).toFloat()
            val ny = (it.y / scale.toDouble()).coerceIn(0.0, h.toDouble()).toFloat()
            PointF(nx, ny)
        }
        val sortedByY = mapped.sortedBy { it.y }
        val top = sortedByY.take(2).sortedBy { it.x }
        val bottom = sortedByY.drop(2).sortedByDescending { it.x }
        return listOf(top[0], top[1], bottom[0], bottom[1])
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
                val savedPath = com.dhanuk.photodoctorpro.utils.UnifiedSaveHelper.saveAndRecordNoAd(
                    context = context,
                    bitmap = bitmap,
                    fileNamePrefix = "PDPro_Scan",
                    operationType = "Document Scan",
                    inputUriString = "",
                    repository = repository,
                    format = android.graphics.Bitmap.CompressFormat.JPEG,
                )
                _uiState.update { it.copy(savedFilePath = savedPath) }
            } catch (e: Exception) {
                _uiState.update { it.copy(error = e.message) }
            }
        }
    }

    fun onErrorShown() { _uiState.update { it.copy(error = null) } }
    fun onSavedMessageShown() { _uiState.update { it.copy(savedFilePath = null) } }

    override fun onCleared() {
        super.onCleared()
        _uiState.value.originalBitmap?.recycle()
        _uiState.value.processedBitmap?.recycle()
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PerspectiveCropScreen(navController: NavController) {
    val context = LocalContext.current
    val activity = context.findActivity() ?: return
    val db = AppDatabase.getDatabase(context)
    val repository = HistoryRepository(db.historyDao())
    val viewModel: PerspectiveCropViewModel = viewModel(factory = ViewModelFactory(repository))
    val uiState by viewModel.uiState.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    var showSaveSuccessDialog by remember { mutableStateOf<String?>(null) }
    var canvasSize by remember { mutableStateOf(IntSize.Zero) }
    var compareMode by remember { mutableStateOf(false) }

    val imagePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? -> uri?.let { viewModel.onImageSelected(it, context) } }

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
                },
                actions = {
                    if (uiState.originalBitmap != null && uiState.processedBitmap != null) {
                        IconButton(onClick = { compareMode = !compareMode }) {
                            Icon(
                                Icons.Default.Compare,
                                contentDescription = "Compare",
                                tint = if (compareMode) MaterialTheme.colorScheme.primary else ComposeColor.Gray
                            )
                        }
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
                if (uiState.originalBitmap != null && uiState.processedBitmap != null && compareMode) {
                    BeforeAfterSlider(
                        beforeImage = uiState.originalBitmap!!.asImageBitmap(),
                        afterImage = uiState.processedBitmap!!.asImageBitmap(),
                        modifier = Modifier.fillMaxSize().padding(8.dp)
                    )
                } else if (uiState.originalBitmap != null) {
                    if (uiState.processedBitmap != null && !compareMode) {
                        Image(
                            bitmap = uiState.processedBitmap!!.asImageBitmap(),
                            contentDescription = "Cropped",
                            modifier = Modifier.fillMaxSize().padding(8.dp),
                            contentScale = ContentScale.Fit
                        )
                    } else {
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
                                                color = ComposeColor(0xFF00E676),
                                                start = start,
                                                end = end,
                                                strokeWidth = 4f
                                            )
                                        }
                                        mappedCorners.forEach { p ->
                                            drawCircle(
                                                color = ComposeColor(0xFF00E676),
                                                radius = 16f,
                                                center = p
                                            )
                                            drawCircle(
                                                color = ComposeColor.White,
                                                radius = 8f,
                                                center = p
                                            )
                                        }
                                    }
                                }

                                if (canvasSize.width > 0) {
                                    val bitmapW = uiState.originalBitmap!!.width.toFloat()
                                    val bitmapH = uiState.originalBitmap!!.height.toFloat()
                                    val scale = minOf(canvasSize.width.toFloat() / bitmapW, canvasSize.height.toFloat() / bitmapH)
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
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedButton(onClick = { viewModel.autoDetect() }, modifier = Modifier.weight(1f)) {
                        Icon(Icons.Default.AutoFixHigh, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Auto")
                    }
                    OutlinedButton(onClick = { imagePickerLauncher.launch("image/*") }, modifier = Modifier.weight(1f)) {
                        Text("New")
                    }
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
