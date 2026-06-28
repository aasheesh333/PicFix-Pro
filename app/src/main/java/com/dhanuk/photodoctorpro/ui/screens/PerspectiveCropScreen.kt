package com.dhanuk.photodoctorpro.ui.screens

import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PointF
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.AutoFixHigh
import androidx.compose.material.icons.filled.Compare
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Save
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
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
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import com.dhanuk.photodoctorpro.data.local.AppDatabase
import com.dhanuk.photodoctorpro.R
import com.dhanuk.photodoctorpro.data.repository.HistoryRepository
import com.dhanuk.photodoctorpro.ui.components.BeforeAfterSlider
import com.dhanuk.photodoctorpro.ui.components.AnimatedLoadingIndicator
import com.dhanuk.photodoctorpro.ui.components.AnimatedSnackbar
import com.dhanuk.photodoctorpro.ui.components.SnackbarType
import com.dhanuk.photodoctorpro.ui.components.SaveSuccessDialog
import com.dhanuk.photodoctorpro.ui.components.rememberBitmap
import com.dhanuk.photodoctorpro.utils.findActivity
import com.dhanuk.photodoctorpro.utils.viewModelExceptionHandler
import com.dhanuk.photodoctorpro.utils.createShareIntent
import com.dhanuk.photodoctorpro.utils.AdManager
import com.dhanuk.photodoctorpro.utils.createOpenIntent
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

enum class AspectRatioLock(val displayName: String, val ratio: Float?) {
    FREE("Free", null),
    SQUARE("1:1", 1f),
    RATIO_4_3("4:3", 4f / 3f),
    RATIO_3_2("3:2", 3f / 2f),
    RATIO_16_9("16:9", 16f / 9f),
    A4("A4", 210f / 297f)
}

data class PerspectiveCropUiState(
    val selectedImageUri: Uri? = null,
    val originalBitmap: Bitmap? = null,
    val processedBitmap: Bitmap? = null,
    val corners: List<PointF> = emptyList(),
    val isLoading: Boolean = false,
    val error: String? = null,
    val savedFilePath: String? = null,
    val aspectRatio: AspectRatioLock = AspectRatioLock.FREE
)

class PerspectiveCropViewModel(
    private val repository: com.dhanuk.photodoctorpro.data.repository.HistoryRepository,
    private val savedStateHandle: androidx.lifecycle.SavedStateHandle
) : ViewModel() {
    private val _uiState = MutableStateFlow(
        PerspectiveCropUiState(
            selectedImageUri = savedStateHandle.get<String>("pcrop_uri")?.let {
                runCatching { Uri.parse(it) }.getOrNull()
            }
        )
    )
    val uiState: StateFlow<PerspectiveCropUiState> = _uiState.asStateFlow()

    private var autoDetectJob: kotlinx.coroutines.Job? = null
    private var applyCropJob: kotlinx.coroutines.Job? = null

    fun onImageSelected(uri: Uri, context: android.content.Context) {
        savedStateHandle["pcrop_uri"] = uri.toString()
        viewModelScope.launch(viewModelExceptionHandler("PerspectiveCropVM") + Dispatchers.IO) {
            _uiState.update { it.copy(isLoading = true, error = null) }
            try {
                val bitmap = com.dhanuk.photodoctorpro.utils.BitmapUtils.loadBitmapFromUri(uri, context, 3000)
                if (bitmap == null) {
                    _uiState.update { it.copy(isLoading = false, error = context.getString(R.string.error_decoding_image)) }
                    return@launch
                }
                val old = _uiState.value.originalBitmap
                val oldProcessed = _uiState.value.processedBitmap
                _uiState.update {
                    it.copy(
                        originalBitmap = bitmap,
                        processedBitmap = null,
                        isLoading = false
                    )
                }
                if (old != null && old != bitmap && !old.isRecycled) old.recycle()
                if (oldProcessed != null && oldProcessed != bitmap && !oldProcessed.isRecycled) oldProcessed.recycle()
                autoDetectEdges(bitmap)
            } catch (ce: kotlinx.coroutines.CancellationException) {
                throw ce
            } catch (e: Exception) {
                if (com.dhanuk.photodoctorpro.BuildConfig.DEBUG) {
                    android.util.Log.e("PerspectiveCropVM", "onImageSelected failed", e)
                }
                _uiState.update { it.copy(error = context.getString(R.string.error_loading_image, e.message), isLoading = false) }
            }
        }
    }

    private fun autoDetectEdges(bitmap: Bitmap) {
        autoDetectJob?.cancel()
        autoDetectJob = viewModelScope.launch(viewModelExceptionHandler("PerspectiveCropVM") + Dispatchers.Default) {
            if (!com.dhanuk.photodoctorpro.PicFixApplication.OpenCVInitialized) {
                _uiState.update { it.copy(
                    error = com.dhanuk.photodoctorpro.utils.getOpenCvNotReadyMessage(),
                    isLoading = false
                ) }
                return@launch
            }
            val src = Mat()
            val gray = Mat()
            val edges = Mat()
            val hierarchy = Mat()
            val resized = Mat()
            try {
                Utils.bitmapToMat(bitmap, src)

                val scale = 600f / maxOf(bitmap.width, bitmap.height)
                Imgproc.resize(src, resized, Size(), scale.toDouble(), scale.toDouble())

                Imgproc.cvtColor(resized, gray, Imgproc.COLOR_BGRA2GRAY)
                Imgproc.GaussianBlur(gray, gray, Size(5.0, 5.0), 0.0)
                Imgproc.Canny(gray, edges, 50.0, 150.0)

                val contours = mutableListOf<org.opencv.core.MatOfPoint>()
                Imgproc.findContours(edges, contours, hierarchy, Imgproc.RETR_EXTERNAL, Imgproc.CHAIN_APPROX_SIMPLE)

                val maxContour = contours.maxByOrNull { Imgproc.contourArea(it) }
                val w = bitmap.width.toFloat()
                val h = bitmap.height.toFloat()
                val padding = 0.03f

                if (maxContour != null && contours.size > 0) {
                    val maxContourPts = maxContour.toArray()
                    val peri = Imgproc.arcLength(org.opencv.core.MatOfPoint2f(*maxContourPts), true)
                    val approx = org.opencv.core.MatOfPoint2f()
                    try {
                        Imgproc.approxPolyDP(org.opencv.core.MatOfPoint2f(*maxContourPts), approx, 0.02 * peri, true)

                        if (approx.toList().size == 4) {
                            val pts = approx.toList()
                            val sorted = sortCorners(pts, scale)
                            if (sorted.size == 4) {
                                _uiState.update { it.copy(corners = sorted) }
                            }
                            return@launch
                        }
                    } finally {
                        approx.release()
                    }
                }

                val corners = listOf(
                    PointF(w * padding, h * padding),
                    PointF(w * (1 - padding), h * padding),
                    PointF(w * (1 - padding), h * (1 - padding)),
                    PointF(w * padding, h * (1 - padding))
                )
                _uiState.update { it.copy(corners = corners) }
            } catch (ce: kotlinx.coroutines.CancellationException) {
                throw ce
            } catch (e: Exception) {
                if (com.dhanuk.photodoctorpro.BuildConfig.DEBUG) {
                    android.util.Log.e("PerspectiveCropVM", "autoDetectEdges failed", e)
                }
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
            } finally {
                src.release()
                gray.release()
                edges.release()
                hierarchy.release()
                resized.release()
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
        val bitmapW = bitmap.width.toFloat()
        val bitmapH = bitmap.height.toFloat()
        if (bitmapW <= 0f || bitmapH <= 0f) return
        val canvasW = canvasSize.width.toFloat()
        val canvasH = canvasSize.height.toFloat()
        if (canvasW <= 0f || canvasH <= 0f) return

        // Match the screen's ContentScale.Fit: compute the displayed image rect
        // (letterboxed if the image aspect doesn't match the canvas).
        val viewAspect = canvasW / canvasH
        val imageAspect = bitmapW / bitmapH
        val displayedW: Float
        val displayedH: Float
        val offsetX: Float
        val offsetY: Float
        if (imageAspect > viewAspect) {
            displayedW = canvasW
            displayedH = displayedW / imageAspect
            offsetX = 0f
            offsetY = (canvasH - displayedH) / 2f
        } else {
            displayedH = canvasH
            displayedW = displayedH * imageAspect
            offsetX = (canvasW - displayedW) / 2f
            offsetY = 0f
        }
        // Convert the click position (canvas coords) to the displayed image rect,
        // clamp, then scale to bitmap coordinates.
        val localX = (x - offsetX).coerceIn(0f, displayedW)
        val localY = (y - offsetY).coerceIn(0f, displayedH)
        val scale = displayedW / bitmapW
        val bitmapX = (localX / scale).coerceIn(0f, bitmapW)
        val bitmapY = (localY / scale).coerceIn(0f, bitmapH)
        val updated = _uiState.value.corners.toMutableList()
        if (index !in updated.indices) return
        val lock = _uiState.value.aspectRatio
        if (lock.ratio != null && updated.size == 4) {
            val newRect = rectangleForDrag(updated, index, bitmapX, bitmapY, lock.ratio, bitmapW, bitmapH)
            _uiState.update { it.copy(corners = newRect) }
        } else {
            updated[index] = PointF(bitmapX, bitmapY)
            _uiState.update { it.copy(corners = updated) }
        }
    }

    private fun rectangleForDrag(
        current: List<PointF>,
        index: Int,
        newX: Float,
        newY: Float,
        ratio: Float,
        bitmapW: Float,
        bitmapH: Float
    ): List<PointF> {
        if (current.size != 4) return current
        val oppositeIndex = (index + 2) % 4
        val anchor = current[oppositeIndex]
        val draggedX = newX.coerceIn(0f, bitmapW)
        val draggedY = newY.coerceIn(0f, bitmapH)
        val leftX: Float
        val rightX: Float
        val topY: Float
        val bottomY: Float
        when (index) {
            0 -> {
                var lxL = draggedX
                var rxL = anchor.x
                var tyL = draggedY
                var byL = anchor.y
                if (lxL > rxL) { val t = lxL; lxL = rxL; rxL = t }
                if (tyL > byL) { val t = tyL; tyL = byL; byL = t }
                leftX = lxL; rightX = rxL; topY = tyL; bottomY = byL
            }
            1 -> {
                var rxL = draggedX
                var lxL = anchor.x
                var tyL = draggedY
                var byL = anchor.y
                if (rxL < lxL) { val t = rxL; rxL = lxL; lxL = t }
                if (tyL > byL) { val t = tyL; tyL = byL; byL = t }
                rightX = rxL; leftX = lxL; topY = tyL; bottomY = byL
            }
            2 -> {
                var rxL = draggedX
                var lxL = anchor.x
                var byL = draggedY
                var tyL = anchor.y
                if (rxL < lxL) { val t = rxL; rxL = lxL; lxL = t }
                if (byL < tyL) { val t = byL; byL = tyL; tyL = t }
                rightX = rxL; leftX = lxL; bottomY = byL; topY = tyL
            }
            3 -> {
                var lxL = draggedX
                var rxL = anchor.x
                var byL = draggedY
                var tyL = anchor.y
                if (lxL > rxL) { val t = lxL; lxL = rxL; rxL = t }
                if (byL < tyL) { val t = byL; byL = tyL; tyL = t }
                leftX = lxL; rightX = rxL; bottomY = byL; topY = tyL
            }
            else -> return current
        }
        val lx = leftX.coerceIn(0f, bitmapW)
        val rx = rightX.coerceIn(lx + 1f, bitmapW)
        val ty = topY.coerceIn(0f, bitmapH)
        val by = bottomY.coerceIn(ty + 1f, bitmapH)

        val rectW = rx - lx
        val rectH = by - ty
        val adjustedH = if (rectW > 0 && ratio > 0) rectW / ratio else rectH
        val finalBy = (ty + adjustedH).coerceIn(ty + 1f, bitmapH)
        val finalTy = (by - adjustedH).coerceIn(0f, by - 1f)

        val adjBy: Float
        val adjTy: Float
        val adjLx: Float
        val adjRx: Float
        if (ty + adjustedH <= bitmapH) {
            adjTy = ty
            adjBy = ty + adjustedH
            adjLx = lx
            adjRx = rx
        } else if (by - adjustedH >= 0f) {
            adjTy = by - adjustedH
            adjBy = by
            adjLx = lx
            adjRx = rx
        } else {
            val maxH = bitmapH
            val maxW = maxH * ratio
            adjTy = 0f
            adjBy = maxH
            adjLx = if (index == 0 || index == 3) (rx - maxW).coerceIn(0f, bitmapW) else lx
            adjRx = if (index == 1 || index == 2) (lx + maxW).coerceIn(0f, bitmapW) else rx
        }

        return listOf(
            PointF(adjLx, adjTy),
            PointF(adjRx, adjTy),
            PointF(adjRx, adjBy),
            PointF(adjLx, adjBy)
        )
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
        applyCropJob?.cancel()
        applyCropJob = viewModelScope.launch(viewModelExceptionHandler("PerspectiveCropVM")) {
            _uiState.update { it.copy(isLoading = true) }
            try {
                val result = withContext(Dispatchers.Default) {
                    warpPerspective(bitmap, corners)
                }
                val old = _uiState.value.processedBitmap
                _uiState.update { it.copy(processedBitmap = result, isLoading = false) }
                if (old != null && old != result && !old.isRecycled) old.recycle()
            } catch (ce: kotlinx.coroutines.CancellationException) {
                throw ce
            } catch (e: Exception) {
                if (com.dhanuk.photodoctorpro.BuildConfig.DEBUG) {
                    android.util.Log.e("PerspectiveCropVM", "applyCrop failed", e)
                }
                _uiState.update { it.copy(isLoading = false, error = "Crop failed: ${e.message}") }
            }
        }
    }

    private fun warpPerspective(source: Bitmap, corners: List<PointF>): Bitmap {
        val src = floatArrayOf(
            corners[0].x, corners[0].y,
            corners[1].x, corners[1].y,
            corners[2].x, corners[2].y,
            corners[3].x, corners[3].y
        )
        // Compute natural content dimensions from the quadrilateral.
        // Average the top/bottom edges for width, left/right edges for height.
        val widthTop = distance(src[0], src[1], src[2], src[3]) // TL-TR
        val widthBottom = distance(src[6], src[7], src[4], src[5]) // BL-BR
        val heightLeft = distance(src[0], src[1], src[6], src[7]) // TL-BL
        val heightRight = distance(src[2], src[3], src[4], src[5]) // TR-BR
        val naturalW = (widthTop + widthBottom) / 2f
        val naturalH = (heightLeft + heightRight) / 2f

        val lock = _uiState.value.aspectRatio
        val (warpW, warpH) = if (lock.ratio != null) {
            val contentRatio = if (naturalH > 0) naturalW / naturalH else lock.ratio
            val targetRatio = lock.ratio
            // Fit the content into the target ratio with letterboxing.
            // Determine which dimension is the limiting factor.
            if (contentRatio > targetRatio) {
                // Content is wider than target → width is limiting, height will have padding
                naturalW to (naturalW / targetRatio)
            } else {
                // Content is taller than target → height is limiting, width will have padding
                (naturalH * targetRatio) to naturalH
            }
        } else {
            naturalW to naturalH
        }

        val outW = warpW.toInt().coerceAtLeast(1)
        val outH = warpH.toInt().coerceAtLeast(1)

        val dst = floatArrayOf(
            0f, 0f,
            outW.toFloat(), 0f,
            outW.toFloat(), outH.toFloat(),
            0f, outH.toFloat()
        )

        val matrix = Matrix()
        matrix.setPolyToPoly(src, 0, dst, 0, 4)
        val output = Bitmap.createBitmap(outW, outH, Bitmap.Config.ARGB_8888)
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
        viewModelScope.launch(viewModelExceptionHandler("PerspectiveCropVM") + Dispatchers.IO) {
            try {
                val savedPath = com.dhanuk.photodoctorpro.utils.UnifiedSaveHelper.saveAndRecordNoAd(
                    context = context,
                    bitmap = bitmap,
                    fileNamePrefix = "PDPro_Scan",
                    operationType = "Document Scan",
                    inputUriString = "",
                    repository = repository,
                    // Save as PNG so any transparent areas in the warped output
                    // are preserved (JPEG would flatten them to white).
                    format = android.graphics.Bitmap.CompressFormat.PNG,
                )
                _uiState.update { it.copy(savedFilePath = savedPath) }
            } catch (ce: kotlinx.coroutines.CancellationException) {
                throw ce
            } catch (e: Exception) {
                if (com.dhanuk.photodoctorpro.BuildConfig.DEBUG) {
                    android.util.Log.e("PerspectiveCropVM", "saveImage failed", e)
                }
                _uiState.update { it.copy(error = e.message ?: "Save failed") }
            }
        }
    }

    fun onErrorShown() { _uiState.update { it.copy(error = null) } }
    fun onSavedMessageShown() { _uiState.update { it.copy(savedFilePath = null) } }

    fun setAspectRatio(lock: AspectRatioLock) {
        _uiState.update { state ->
            val newCorners = if (lock.ratio == null) {
                state.corners
            } else if (state.corners.size == 4) {
                snapToRectangle(state.corners, lock.ratio)
            } else {
                state.corners
            }
            state.copy(aspectRatio = lock, corners = newCorners)
        }
    }

    private fun snapToRectangle(corners: List<PointF>, ratio: Float): List<PointF> {
        if (corners.size != 4) return corners
        val minX = corners.minOf { it.x }
        val maxX = corners.maxOf { it.x }
        val minY = corners.minOf { it.y }
        val maxY = corners.maxOf { it.y }
        val cx = (minX + maxX) / 2f
        val cy = (minY + maxY) / 2f
        val currentW = maxX - minX
        val currentH = maxY - minY
        // Keep the area, adjust to match the requested ratio.
        val currentRatio = if (currentH > 0) currentW / currentH else ratio
        val newW: Float
        val newH: Float
        if (currentRatio > ratio) {
            // currently wider than ratio → reduce width
            newH = currentH
            newW = newH * ratio
        } else {
            // currently taller than ratio → reduce height
            newW = currentW
            newH = newW / ratio
        }
        val halfW = newW / 2f
        val halfH = newH / 2f
        return listOf(
            PointF(cx - halfW, cy - halfH), // TL
            PointF(cx + halfW, cy - halfH), // TR
            PointF(cx + halfW, cy + halfH), // BR
            PointF(cx - halfW, cy + halfH)  // BL
        )
    }

    override fun onCleared() {
        super.onCleared()
        autoDetectJob?.cancel()
        applyCropJob?.cancel()
        autoDetectJob = null
        applyCropJob = null
        val original = _uiState.value.originalBitmap
        val processed = _uiState.value.processedBitmap
        if (processed != null && processed !== original && !processed.isRecycled) processed.recycle()
        if (original != null && !original.isRecycled) original.recycle()
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PerspectiveCropScreen(navController: NavController) {
    val context = LocalContext.current
    val activity = context.findActivity() ?: return
    val db = AppDatabase.getDatabase(context)
    val repository = HistoryRepository.getInstance(db.historyDao())
    val viewModel: PerspectiveCropViewModel = viewModel(factory = ViewModelFactory.getInstance(repository))
    val uiState by viewModel.uiState.collectAsState()
    val openCvReady by com.dhanuk.photodoctorpro.PicFixApplication.openCVInitialized.collectAsState(false)
    val snackbarHostState = remember { SnackbarHostState() }
    var showSaveSuccessDialog by remember { mutableStateOf<String?>(null) }
    var canvasSize by remember { mutableStateOf(IntSize.Zero) }
    var snackbarMessage by remember { mutableStateOf<String?>(null) }
    var snackbarType by remember { mutableStateOf(SnackbarType.INFO) }

    val originalImage = rememberBitmap(uiState.originalBitmap)
    val processedImage = rememberBitmap(uiState.processedBitmap)
    var compareMode by remember { mutableStateOf(false) }

    val imagePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? -> uri?.let { viewModel.onImageSelected(it, context) } }

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

    showSaveSuccessDialog?.let { path ->
        SaveSuccessDialog(
            filePath = path,
            onDismiss = { showSaveSuccessDialog = null },
            onShareWhatsApp = {
                try { context.startActivity(createShareIntent(path, context, "com.whatsapp")) }
                catch (e: Exception) { snackbarMessage = context.getString(R.string.whatsapp_not_installed); snackbarType = SnackbarType.ERROR }
            },
            onShareOther = {
                try { context.startActivity(Intent.createChooser(createShareIntent(path, context), context.getString(R.string.share_image))) }
                catch (e: Exception) { if (com.dhanuk.photodoctorpro.BuildConfig.DEBUG) android.util.Log.e("PerspectiveCropVM", "operation failed", e) }
            },
            onOpen = {
                try { context.startActivity(createOpenIntent(path, context)) }
                catch (e: Exception) { if (com.dhanuk.photodoctorpro.BuildConfig.DEBUG) android.util.Log.e("PerspectiveCropVM", "operation failed", e) }
            }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.document_scanner)) },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(Icons.Default.ArrowBack, contentDescription = stringResource(R.string.cd_back_button))
                    }
                },
                actions = {
                    if (originalImage != null) {
                        IconButton(onClick = { viewModel.autoDetect() }) {
                            Icon(
                                Icons.Default.Refresh,
                                contentDescription = stringResource(R.string.reset)
                            )
                        }
                    }
                    if (originalImage != null && processedImage != null) {
                        IconButton(onClick = { compareMode = !compareMode }) {
                            Icon(
                                Icons.Default.Compare,
                                contentDescription = stringResource(R.string.compare_with_original),
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
                if (uiState.isLoading) {
                    AnimatedLoadingIndicator(message = stringResource(R.string.processing))
                } else if (originalImage != null && processedImage != null && compareMode) {
                    BeforeAfterSlider(
                        beforeImage = originalImage,
                        afterImage = processedImage,
                        modifier = Modifier.fillMaxSize().padding(8.dp)
                    )
                } else if (originalImage != null) {
                    if (processedImage != null && !compareMode) {
                        Image(
                            bitmap = processedImage,
                            contentDescription = stringResource(R.string.cd_cropped_image),
                            modifier = Modifier.fillMaxSize().padding(8.dp),
                            contentScale = ContentScale.Fit
                        )
                    } else {
                        Box(modifier = Modifier.fillMaxSize()) {
                            Image(
                                bitmap = originalImage,
                                contentDescription = stringResource(R.string.cd_document),
                                modifier = Modifier.fillMaxSize(),
                                contentScale = ContentScale.Fit
                            )
                            if (processedImage == null) {
                                val originalBitmap = uiState.originalBitmap
                                if (originalBitmap != null && canvasSize.width > 0) {
                                    val bitmapW = originalBitmap.width.toFloat()
                                    val bitmapH = originalBitmap.height.toFloat()
                                    val iScale = minOf(canvasSize.width.toFloat() / bitmapW, canvasSize.height.toFloat() / bitmapH)
                                    val drawW = bitmapW * iScale
                                    val drawH = bitmapH * iScale
                                    val offX = (canvasSize.width - drawW) / 2
                                    val offY = (canvasSize.height - drawH) / 2

                                    val mappedCorners = uiState.corners.map { corner ->
                                        Offset(offX + corner.x * iScale, offY + corner.y * iScale)
                                    }

                                    Canvas(modifier = Modifier.fillMaxSize()) {
                                        if (mappedCorners.size == 4) {
                                            for (i in mappedCorners.indices) {
                                                drawLine(
                                                    color = ComposeColor(0xFF7C5CF7),
                                                    start = mappedCorners[i],
                                                    end = mappedCorners[(i + 1) % mappedCorners.size],
                                                    strokeWidth = 4f
                                                )
                                            }
                                            mappedCorners.forEach { p ->
                                                drawCircle(color = ComposeColor(0xFF7C5CF7), radius = 16f, center = p)
                                                drawCircle(color = ComposeColor.White, radius = 8f, center = p)
                                            }
                                        }
                                    }

                                    Box(
                                        modifier = Modifier
                                            .fillMaxSize()
                                            .pointerInput(canvasSize, uiState.corners) {
                                                var activeCorner = -1
                                                detectDragGestures(
                                                    onDragStart = { offset ->
                                                        if (mappedCorners.size == 4) {
                                                            var minDist = Float.MAX_VALUE
                                                            var closestIdx = -1
                                                            mappedCorners.forEachIndexed { idx, corner ->
                                                                val dx = offset.x - corner.x
                                                                val dy = offset.y - corner.y
                                                                val dist = kotlin.math.sqrt(dx * dx + dy * dy)
                                                                if (dist < minDist) {
                                                                    minDist = dist
                                                                    closestIdx = idx
                                                                }
                                                            }
                                                            activeCorner = if (minDist < 80f) closestIdx else -1
                                                        }
                                                    },
                                                    onDrag = { change, _ ->
                                                        if (activeCorner >= 0) {
                                                            change.consume()
                                                            viewModel.updateCorner(
                                                                activeCorner,
                                                                change.position.x,
                                                                change.position.y,
                                                                canvasSize
                                                            )
                                                        }
                                                    },
                                                    onDragEnd = {
                                                        activeCorner = -1
                                                    },
                                                    onDragCancel = {
                                                        activeCorner = -1
                                                    }
                                                )
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
                        Text(stringResource(R.string.auto_flatten_documents_photos), style = MaterialTheme.typography.bodyLarge)
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(stringResource(R.string.drag_corners_to_adjust), color = MaterialTheme.colorScheme.secondary)
                        Spacer(modifier = Modifier.height(16.dp))
                        Button(onClick = { imagePickerLauncher.launch("image/*") }) {
                            Text(stringResource(R.string.pick_image))
                        }
                    }
                }
            }

            if (originalImage != null) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp)
                ) {
                    Text(
                        stringResource(R.string.aspect_ratio),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(4.dp))
                    LazyRow(
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        items(AspectRatioLock.values()) { lock ->
                            FilterChip(
                                selected = uiState.aspectRatio == lock,
                                onClick = { viewModel.setAspectRatio(lock) },
                                label = {
                                    Text(
                                        when (lock) {
                                            AspectRatioLock.FREE -> stringResource(R.string.aspect_ratio_free)
                                            AspectRatioLock.SQUARE -> stringResource(R.string.aspect_ratio_1_1)
                                            AspectRatioLock.RATIO_4_3 -> stringResource(R.string.aspect_ratio_4_3)
                                            AspectRatioLock.RATIO_3_2 -> stringResource(R.string.aspect_ratio_3_2)
                                            AspectRatioLock.RATIO_16_9 -> stringResource(R.string.aspect_ratio_16_9)
                                            AspectRatioLock.A4 -> stringResource(R.string.aspect_ratio_a4)
                                        }
                                    )
                                }
                            )
                        }
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedButton(onClick = { viewModel.autoDetect() }, enabled = openCvReady, modifier = Modifier.weight(1f)) {
                        Icon(Icons.Default.AutoFixHigh, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text(stringResource(R.string.action_auto))
                    }
                    OutlinedButton(onClick = { imagePickerLauncher.launch("image/*") }, modifier = Modifier.weight(1f)) {
                        Text(stringResource(R.string.action_new))
                    }
                    if (uiState.processedBitmap == null) {
                        Button(onClick = { viewModel.applyCrop() }, modifier = Modifier.weight(1f)) {
                            Text(stringResource(R.string.action_crop))
                        }
                    } else {
                        Button(
                            onClick = { viewModel.saveImage(context) },
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(Icons.Default.Save, contentDescription = stringResource(R.string.cd_save_directory))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(stringResource(R.string.action_save))
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
}
