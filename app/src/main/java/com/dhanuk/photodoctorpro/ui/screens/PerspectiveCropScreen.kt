package com.dhanuk.photodoctorpro.ui.screens

import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.AutoFixHigh
import androidx.compose.material.icons.filled.Compare
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
import androidx.core.content.FileProvider
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import com.dhanuk.photodoctorpro.data.local.AppDatabase
import com.dhanuk.photodoctorpro.R
import com.dhanuk.photodoctorpro.data.repository.HistoryRepository
import com.dhanuk.photodoctorpro.ui.components.BeforeAfterSlider
import com.dhanuk.photodoctorpro.ui.components.SaveSuccessDialog
import com.dhanuk.photodoctorpro.ui.components.rememberBitmap
import com.dhanuk.photodoctorpro.utils.findActivity
import com.dhanuk.photodoctorpro.utils.viewModelExceptionHandler
import com.dhanuk.photodoctorpro.utils.createShareIntent
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
import java.io.File

/**
 * Aspect ratio lock for the cropped output. FREE = use the actual selected
 * region (no lock). All other values force the output to that ratio.
 *
 * A4 is the standard ISO 216 paper size (210×297mm) → ratio ≈ 0.707.
 */
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
                _uiState.update {
                    it.copy(
                        originalBitmap = bitmap,
                        isLoading = false
                    )
                }
                if (old != null && old != bitmap && !old.isRecycled) old.recycle()
                autoDetectEdges(bitmap)
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
        // When the aspect ratio is locked, treat the user's drag as a
        // resize of the rectangle: anchor the opposite corner and rebuild
        // the four points to satisfy the locked ratio. This prevents the
        // "stretched" output the user reported.
        val lock = _uiState.value.aspectRatio
        if (lock.ratio != null && updated.size == 4) {
            val newRect = rectangleForDrag(updated, index, bitmapX, bitmapY, lock.ratio, bitmapW, bitmapH)
            _uiState.update { it.copy(corners = newRect) }
        } else {
            updated[index] = PointF(bitmapX, bitmapY)
            _uiState.update { it.copy(corners = updated) }
        }
    }

    /**
     * Snap the dragged corner to the user's click position. The opposite
     * corner stays put. The other 2 corners are computed so the 4 corners
     * form an axis-aligned rectangle (TL/BR diagonal) with horizontal and
     * vertical edges only.
     *
     * The resulting rectangle's aspect ratio may not match the locked ratio;
     * warpPerspective() normalizes the output to the locked ratio, so the
     * final saved image has the correct aspect. The visual green overlay
     * shows a clean rectangle so the user sees what they're going to get.
     *
     * 0=TL, 1=TR, 2=BR, 3=BL. Opposite corner is at [(index+2) % 4].
     */
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
        // The dragged corner lands at the click position, clamped to bitmap.
        val draggedX = newX.coerceIn(0f, bitmapW)
        val draggedY = newY.coerceIn(0f, bitmapH)
        // Build the axis-aligned rectangle from the dragged corner and the
        // opposite anchor corner. The other 2 corners are the projections
        // of the dragged corner onto the anchor's vertical and horizontal
        // lines. If the dragged corner is on the "wrong" side of the anchor
        // (e.g. user dragged TL below BR), the opposite is automatically
        // picked so the rectangle is well-formed.
        val leftX: Float
        val rightX: Float
        val topY: Float
        val bottomY: Float
        when (index) {
            0 -> { // TL dragged, anchor is BR
                var lxL = draggedX
                var rxL = anchor.x
                var tyL = draggedY
                var byL = anchor.y
                // Normalize so left <= right and top <= bottom
                if (lxL > rxL) { val t = lxL; lxL = rxL; rxL = t }
                if (tyL > byL) { val t = tyL; tyL = byL; byL = t }
                leftX = lxL; rightX = rxL; topY = tyL; bottomY = byL
            }
            1 -> { // TR dragged, anchor is BL
                var rxL = draggedX
                var lxL = anchor.x
                var tyL = draggedY
                var byL = anchor.y
                if (rxL < lxL) { val t = rxL; rxL = lxL; lxL = t }
                if (tyL > byL) { val t = tyL; tyL = byL; byL = t }
                rightX = rxL; leftX = lxL; topY = tyL; bottomY = byL
            }
            2 -> { // BR dragged, anchor is TL
                var rxL = draggedX
                var lxL = anchor.x
                var byL = draggedY
                var tyL = anchor.y
                if (rxL < lxL) { val t = rxL; rxL = lxL; lxL = t }
                if (byL < tyL) { val t = byL; byL = tyL; tyL = t }
                rightX = rxL; leftX = lxL; bottomY = byL; topY = tyL
            }
            3 -> { // BL dragged, anchor is TR
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
        // Clamp to bitmap. The rectangle is always non-degenerate after this.
        val lx = leftX.coerceIn(0f, bitmapW)
        val rx = rightX.coerceIn(lx + 1f, bitmapW)
        val ty = topY.coerceIn(0f, bitmapH)
        val by = bottomY.coerceIn(ty + 1f, bitmapH)
        return listOf(
            PointF(lx, ty),  // TL
            PointF(rx, ty),  // TR
            PointF(rx, by),  // BR
            PointF(lx, by)   // BL
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
            // When locked, we want to preserve the content's natural aspect ratio
            // as much as possible. First compute the content's aspect ratio.
            val contentRatio = if (naturalH > 0) naturalW / naturalH else lock.ratio
            val targetRatio = lock.ratio!!
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

    /**
     * Set (or clear) the aspect-ratio lock. When set, the four corners are
     * forced to form an axis-aligned rectangle whose width/height matches the
     * locked ratio. Switching ratios re-snaps the existing corners using the
     * same center and area.
     */
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

    /**
     * Given 4 corner points (in any order, possibly a non-rectangle), produce
     * 4 corner points that form an axis-aligned rectangle with the requested
     * width/height ratio [ratio]. The rectangle is the *minimum-area* rectangle
     * covering the original four points, scaled so the ratio matches.
     */
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
        _uiState.value.originalBitmap?.takeIf { !it.isRecycled }?.recycle()
        _uiState.value.processedBitmap?.takeIf { !it.isRecycled }?.recycle()
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
                catch (e: Exception) { Toast.makeText(context, context.getString(R.string.whatsapp_not_installed), Toast.LENGTH_SHORT).show() }
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
                if (originalImage != null && processedImage != null && compareMode) {
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
                                if (originalBitmap != null) {
                                Canvas(modifier = Modifier.fillMaxSize()) {
                                    val w = size.width
                                    val h = size.height
                                    val bitmapW = originalBitmap.width.toFloat()
                                    val bitmapH = originalBitmap.height.toFloat()
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
                                    val obmp = uiState.originalBitmap
                                    if (obmp != null) {
                                    val bitmapW = obmp.width.toFloat()
                                    val bitmapH = obmp.height.toFloat()
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
                // Aspect-ratio lock chips. Tapping a chip constrains the
                // four corners to form an axis-aligned rectangle with that
                // ratio. Tap "Free" to remove the lock.
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
                    OutlinedButton(onClick = { viewModel.autoDetect() }, modifier = Modifier.weight(1f)) {
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
        }
    }
}
