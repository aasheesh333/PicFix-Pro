package com.dhanuk.photodoctorpro.ui.screens

import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
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
import org.opencv.core.Core
import org.opencv.core.Mat
import org.opencv.core.Point
import org.opencv.core.Scalar
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc

enum class AspectRatioLock(val displayName: String, val ratio: Float?) {
    FREE("Free", null),
    SQUARE("1:1", 1f),
    RATIO_4_3("4:3", 4f / 3f),
    RATIO_3_2("3:2", 3f / 2f),
    RATIO_16_9("16:9", 16f / 9f),
    RATIO_9_16("9:16", 9f / 16f),
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
        val scale = displayedW / bitmapW
        val bitmapX = ((x - offsetX) / scale).coerceIn(0f, bitmapW)
        val bitmapY = ((y - offsetY) / scale).coerceIn(0f, bitmapH)

        val lock = _uiState.value.aspectRatio
        if (lock.ratio == null) {
            val updated = _uiState.value.corners.toMutableList()
            if (index !in updated.indices) return
            updated[index] = PointF(bitmapX, bitmapY)
            _uiState.update { it.copy(corners = updated) }
        } else {
            rectangularResizeCorner(index, bitmapX, bitmapY, lock.ratio)
        }
    }

    private fun rectangularResizeCorner(dragIndex: Int, dragX: Float, dragY: Float, ratio: Float) {
        val bitmap = _uiState.value.originalBitmap ?: return
        val bitmapW = bitmap.width.toFloat()
        val bitmapH = bitmap.height.toFloat()
        val corners = _uiState.value.corners.toMutableList()
        if (corners.size != 4) return

        val oppositeIndex = (dragIndex + 2) % 4
        val fixed = corners[oppositeIndex]

        val rawW = kotlin.math.abs(dragX - fixed.x)
        val rawH = kotlin.math.abs(dragY - fixed.y)
        if (rawW < 10f || rawH < 10f) return

        val newW: Float
        val newH: Float
        if (rawW / rawH > ratio) {
            newW = rawW
            newH = newW / ratio
        } else {
            newH = rawH
            newW = newH * ratio
        }
        if (newW < 10f || newH < 10f) return

        val tlX: Float
        val tlY: Float
        when (dragIndex) {
            0 -> { tlX = fixed.x - newW; tlY = fixed.y - newH }
            1 -> { tlX = fixed.x; tlY = fixed.y - newH }
            2 -> { tlX = fixed.x; tlY = fixed.y }
            3 -> { tlX = fixed.x - newW; tlY = fixed.y }
            else -> return
        }

        val cTlX = tlX.coerceIn(0f, bitmapW)
        val cTlY = tlY.coerceIn(0f, bitmapH)
        val cBrX = (tlX + newW).coerceIn(0f, bitmapW)
        val cBrY = (tlY + newH).coerceIn(0f, bitmapH)
        if (cBrX - cTlX < 10f || cBrY - cTlY < 10f) return

        corners[0] = PointF(cTlX, cTlY)
        corners[1] = PointF(cBrX, cTlY)
        corners[2] = PointF(cBrX, cBrY)
        corners[3] = PointF(cTlX, cBrY)

        _uiState.update { it.copy(corners = corners) }
    }

    fun rectangularResizeEdge(edgeIndex: Int, dragX: Float, dragY: Float, ratio: Float) {
        val bitmap = _uiState.value.originalBitmap ?: return
        val bitmapW = bitmap.width.toFloat()
        val bitmapH = bitmap.height.toFloat()
        val corners = _uiState.value.corners.toMutableList()
        if (corners.size != 4) return

        val tl = corners[0]; val tr = corners[1]; val br = corners[2]; val bl = corners[3]
        val centerX = (tl.x + tr.x + br.x + bl.x) / 4f
        val centerY = (tl.y + tr.y + bl.y + br.y) / 4f

        var newW = br.x - tl.x
        var newH = br.y - tl.y
        when (edgeIndex) {
            0 -> {
                val topY = dragY.coerceAtMost(br.y - 10f)
                newH = 2f * (centerY - topY)
                newW = newH * ratio
            }
            1 -> {
                val rightX = dragX.coerceAtLeast(tl.x + 10f)
                newW = 2f * (rightX - centerX)
                newH = newW / ratio
            }
            2 -> {
                val bottomY = dragY.coerceAtLeast(tl.y + 10f)
                newH = 2f * (bottomY - centerY)
                newW = newH * ratio
            }
            3 -> {
                val leftX = dragX.coerceAtMost(br.x - 10f)
                newW = 2f * (centerX - leftX)
                newH = newW / ratio
            }
        }
        if (newW < 10f || newH < 10f) return

        val halfW = newW / 2f
        val halfH = newH / 2f
        val nTlX = (centerX - halfW).coerceIn(0f, bitmapW)
        val nTlY = (centerY - halfH).coerceIn(0f, bitmapH)
        val nBrX = (centerX + halfW).coerceIn(0f, bitmapW)
        val nBrY = (centerY + halfH).coerceIn(0f, bitmapH)
        if (nBrX - nTlX < 10f || nBrY - nTlY < 10f) return

        corners[0] = PointF(nTlX, nTlY)
        corners[1] = PointF(nBrX, nTlY)
        corners[2] = PointF(nBrX, nBrY)
        corners[3] = PointF(nTlX, nBrY)

        _uiState.update { it.copy(corners = corners) }
    }

    fun moveAllCorners(dx: Float, dy: Float) {
        val bitmap = _uiState.value.originalBitmap ?: return
        val bitmapW = bitmap.width.toFloat()
        val bitmapH = bitmap.height.toFloat()
        val current = _uiState.value.corners
        if (current.size != 4) return
        val moved = current.map { c ->
            PointF(
                (c.x + dx).coerceIn(0f, bitmapW),
                (c.y + dy).coerceIn(0f, bitmapH)
            )
        }
        _uiState.update { it.copy(corners = moved) }
    }

    fun resetCorners() {
        val bitmap = _uiState.value.originalBitmap ?: return
        val w = bitmap.width.toFloat()
        val h = bitmap.height.toFloat()
        val padding = 0.03f
        val oldProcessed = _uiState.value.processedBitmap
        _uiState.update {
            it.copy(
                corners = listOf(
                    PointF(w * padding, h * padding),
                    PointF(w * (1 - padding), h * padding),
                    PointF(w * (1 - padding), h * (1 - padding)),
                    PointF(w * padding, h * (1 - padding))
                ),
                processedBitmap = null,
                aspectRatio = AspectRatioLock.FREE
            )
        }
        if (oldProcessed != null && !oldProcessed.isRecycled) oldProcessed.recycle()
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
        if (corners.size != 4) return source
        if (!com.dhanuk.photodoctorpro.PicFixApplication.OpenCVInitialized) return source

        val (tl, tr, br, bl) = sortCornersForWarp(corners)

        val outW = dist(tl, tr).toInt().coerceAtLeast(1)
        val outH = dist(tr, br).toInt().coerceAtLeast(1)

        val srcPts = org.opencv.core.MatOfPoint2f(
            org.opencv.core.Point(tl.x.toDouble(), tl.y.toDouble()),
            org.opencv.core.Point(tr.x.toDouble(), tr.y.toDouble()),
            org.opencv.core.Point(br.x.toDouble(), br.y.toDouble()),
            org.opencv.core.Point(bl.x.toDouble(), bl.y.toDouble())
        )
        val dstPts = org.opencv.core.MatOfPoint2f(
            org.opencv.core.Point(0.0, 0.0),
            org.opencv.core.Point(outW.toDouble(), 0.0),
            org.opencv.core.Point(outW.toDouble(), outH.toDouble()),
            org.opencv.core.Point(0.0, outH.toDouble())
        )

        val transform = Imgproc.getPerspectiveTransform(srcPts, dstPts)

        val srcBitmap = if (source.config != Bitmap.Config.ARGB_8888) {
            val converted = Bitmap.createBitmap(source.width, source.height, Bitmap.Config.ARGB_8888)
            val c = android.graphics.Canvas(converted)
            c.drawBitmap(source, 0f, 0f, null)
            converted
        } else {
            source
        }

        val srcBmpMat = Mat()
        Utils.bitmapToMat(srcBitmap, srcBmpMat)
        if (srcBitmap !== source && !srcBitmap.isRecycled) srcBitmap.recycle()

        val dstMatImg = Mat()
        Imgproc.warpPerspective(
            srcBmpMat,
            dstMatImg,
            transform,
            Size(outW.toDouble(), outH.toDouble()),
            Imgproc.INTER_LINEAR,
            org.opencv.core.Core.BORDER_CONSTANT,
            org.opencv.core.Scalar(0.0, 0.0, 0.0, 0.0)
        )

        val output = Bitmap.createBitmap(outW, outH, Bitmap.Config.ARGB_8888)
        Utils.matToBitmap(dstMatImg, output)

        srcPts.release()
        dstPts.release()
        transform.release()
        srcBmpMat.release()
        dstMatImg.release()

        return output
    }

    private fun dist(a: PointF, b: PointF): Float {
        val dx = b.x - a.x
        val dy = b.y - a.y
        return kotlin.math.sqrt(dx * dx + dy * dy)
    }

    private fun sortCornersForWarp(corners: List<PointF>): List<PointF> {
        if (corners.size != 4) return corners
        var tl = corners[0]; var tr = corners[0]; var br = corners[0]; var bl = corners[0]
        var minSum = Float.MAX_VALUE
        var maxSum = Float.MIN_VALUE
        var minDiff = Float.MAX_VALUE
        var maxDiff = Float.MIN_VALUE
        for (c in corners) {
            val sum = c.x + c.y
            val diff = c.x - c.y
            if (sum < minSum) { minSum = sum; tl = c }
            if (sum > maxSum) { maxSum = sum; br = c }
            if (diff > maxDiff) { maxDiff = diff; tr = c }
            if (diff < minDiff) { minDiff = diff; bl = c }
        }
        return listOf(tl, tr, br, bl)
    }

    fun saveImage(context: android.content.Context, options: com.dhanuk.photodoctorpro.utils.SaveOptions = com.dhanuk.photodoctorpro.utils.SaveOptions()) {
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
                    options = options
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
        val bitmap = _uiState.value.originalBitmap ?: return corners
        val bitmapW = bitmap.width.toFloat()
        val bitmapH = bitmap.height.toFloat()
        val minX = corners.minOf { it.x }
        val maxX = corners.maxOf { it.x }
        val minY = corners.minOf { it.y }
        val maxY = corners.maxOf { it.y }
        val cx = (minX + maxX) / 2f
        val cy = (minY + maxY) / 2f
        val currentW = maxX - minX
        val currentH = maxY - minY
        val currentRatio = if (currentH > 0) currentW / currentH else ratio
        val newW: Float
        val newH: Float
        if (currentRatio > ratio) {
            newH = currentH
            newW = newH * ratio
        } else {
            newW = currentW
            newH = newW / ratio
        }
        val halfW = newW / 2f
        val halfH = newH / 2f
        return listOf(
            PointF((cx - halfW).coerceIn(0f, bitmapW), (cy - halfH).coerceIn(0f, bitmapH)),
            PointF((cx + halfW).coerceIn(0f, bitmapW), (cy - halfH).coerceIn(0f, bitmapH)),
            PointF((cx + halfW).coerceIn(0f, bitmapW), (cy + halfH).coerceIn(0f, bitmapH)),
            PointF((cx - halfW).coerceIn(0f, bitmapW), (cy + halfH).coerceIn(0f, bitmapH))
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
        contract = androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia()
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
                        IconButton(onClick = { viewModel.resetCorners() }) {
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

                                    val dimPath = remember { androidx.compose.ui.graphics.Path() }
                                    Canvas(modifier = Modifier.fillMaxSize()) {
                                        if (mappedCorners.size == 4) {
                                            dimPath.reset()
                                            dimPath.addRect(androidx.compose.ui.geometry.Rect(offX, offY, offX + drawW, offY + drawH))
                                            val cropPath = androidx.compose.ui.graphics.Path()
                                            cropPath.moveTo(mappedCorners[0].x, mappedCorners[0].y)
                                            cropPath.lineTo(mappedCorners[1].x, mappedCorners[1].y)
                                            cropPath.lineTo(mappedCorners[2].x, mappedCorners[2].y)
                                            cropPath.lineTo(mappedCorners[3].x, mappedCorners[3].y)
                                            cropPath.close()
                                            dimPath.op(dimPath, cropPath, androidx.compose.ui.graphics.PathOperation.Difference)
                                            drawPath(
                                                path = dimPath,
                                                color = ComposeColor.Black.copy(alpha = 0.55f),
                                                style = androidx.compose.ui.graphics.drawscope.Fill
                                            )
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
                                            if (uiState.aspectRatio.ratio != null) {
                                                for (i in 0..3) {
                                                    val mid = Offset(
                                                        (mappedCorners[i].x + mappedCorners[(i + 1) % 4].x) / 2f,
                                                        (mappedCorners[i].y + mappedCorners[(i + 1) % 4].y) / 2f
                                                    )
                                                    drawCircle(color = ComposeColor(0xFF7C5CF7), radius = 10f, center = mid)
                                                    drawCircle(color = ComposeColor.White, radius = 5f, center = mid)
                                                }
                                            }
                                        }
                                    }

                                    val currentCorners by rememberUpdatedState(uiState.corners)
                                    val currentScale by rememberUpdatedState(iScale)
                                    val currentOffX by rememberUpdatedState(offX)
                                    val currentOffY by rememberUpdatedState(offY)
                                    val currentAspectRatio by rememberUpdatedState(uiState.aspectRatio)

                                    Box(
                                        modifier = Modifier
                                            .fillMaxSize()
                                            .pointerInput(canvasSize) {
                                                var activeCorner = -1
                                                var activeEdge = -1
                                                var isBodyDrag = false
                                                var lastDragX = 0f
                                                var lastDragY = 0f
                                                detectDragGestures(
                                                    onDragStart = { offset ->
                                                        val corners = currentCorners
                                                        val s = currentScale
                                                        val ox = currentOffX
                                                        val oy = currentOffY
                                                        val isRatioLocked = currentAspectRatio.ratio != null
                                                        if (corners.size == 4) {
                                                            var minDist = Float.MAX_VALUE
                                                            var closestIdx = -1
                                                            corners.forEachIndexed { idx, corner ->
                                                                val sx = ox + corner.x * s
                                                                val sy = oy + corner.y * s
                                                                val dx = offset.x - sx
                                                                val dy = offset.y - sy
                                                                val dist = kotlin.math.sqrt(dx * dx + dy * dy)
                                                                if (dist < minDist) {
                                                                    minDist = dist
                                                                    closestIdx = idx
                                                                }
                                                            }
                                                            if (minDist < 80f) {
                                                                activeCorner = closestIdx
                                                                activeEdge = -1
                                                                isBodyDrag = false
                                                            } else if (isRatioLocked) {
                                                                val edgeMidpoints = listOf(
                                                                    Offset(ox + (corners[0].x + corners[1].x) / 2f * s, oy + (corners[0].y + corners[1].y) / 2f * s),
                                                                    Offset(ox + (corners[1].x + corners[2].x) / 2f * s, oy + (corners[1].y + corners[2].y) / 2f * s),
                                                                    Offset(ox + (corners[2].x + corners[3].x) / 2f * s, oy + (corners[2].y + corners[3].y) / 2f * s),
                                                                    Offset(ox + (corners[3].x + corners[0].x) / 2f * s, oy + (corners[3].y + corners[0].y) / 2f * s)
                                                                )
                                                                var minEdgeDist = Float.MAX_VALUE
                                                                var closestEdge = -1
                                                                edgeMidpoints.forEachIndexed { idx, mp ->
                                                                    val dx = offset.x - mp.x
                                                                    val dy = offset.y - mp.y
                                                                    val dist = kotlin.math.sqrt(dx * dx + dy * dy)
                                                                    if (dist < minEdgeDist) {
                                                                        minEdgeDist = dist
                                                                        closestEdge = idx
                                                                    }
                                                                }
                                                                if (minEdgeDist < 80f) {
                                                                    activeCorner = -1
                                                                    activeEdge = closestEdge
                                                                    isBodyDrag = false
                                                                } else {
                                                                    val pts = corners.map { c -> Offset(ox + c.x * s, oy + c.y * s) }
                                                                    if (pointInQuad(offset, pts)) {
                                                                        activeCorner = -1
                                                                        activeEdge = -1
                                                                        isBodyDrag = true
                                                                        lastDragX = offset.x
                                                                        lastDragY = offset.y
                                                                    } else {
                                                                        activeCorner = -1
                                                                        activeEdge = -1
                                                                        isBodyDrag = false
                                                                    }
                                                                }
                                                            } else {
                                                                val pts = corners.map { c -> Offset(ox + c.x * s, oy + c.y * s) }
                                                                if (pointInQuad(offset, pts)) {
                                                                    activeCorner = -1
                                                                    activeEdge = -1
                                                                    isBodyDrag = true
                                                                    lastDragX = offset.x
                                                                    lastDragY = offset.y
                                                                } else {
                                                                    activeCorner = -1
                                                                    activeEdge = -1
                                                                    isBodyDrag = false
                                                                }
                                                            }
                                                        }
                                                    },
                                                    onDrag = { change, _ ->
                                                        change.consume()
                                                        if (activeCorner >= 0) {
                                                            viewModel.updateCorner(
                                                                activeCorner,
                                                                change.position.x,
                                                                change.position.y,
                                                                canvasSize
                                                            )
                                                        } else if (activeEdge >= 0) {
                                                            val lock = currentAspectRatio
                                                            val s = currentScale
                                                            val ox = currentOffX
                                                            val oy = currentOffY
                                                            if (lock.ratio != null && s > 0f) {
                                                                val bitmapX = ((change.position.x - ox) / s)
                                                                val bitmapY = ((change.position.y - oy) / s)
                                                                viewModel.rectangularResizeEdge(activeEdge, bitmapX, bitmapY, lock.ratio)
                                                            }
                                                        } else if (isBodyDrag) {
                                                            val dx = change.position.x - lastDragX
                                                            val dy = change.position.y - lastDragY
                                                            lastDragX = change.position.x
                                                            lastDragY = change.position.y
                                                            val s = currentScale
                                                            if (s > 0f) {
                                                                viewModel.moveAllCorners(dx / s, dy / s)
                                                            }
                                                        }
                                                    },
                                                    onDragEnd = {
                                                        activeCorner = -1
                                                        activeEdge = -1
                                                        isBodyDrag = false
                                                    },
                                                    onDragCancel = {
                                                        activeCorner = -1
                                                        activeEdge = -1
                                                        isBodyDrag = false
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
                        Button(onClick = { imagePickerLauncher.launch(androidx.activity.result.PickVisualMediaRequest(androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia.ImageOnly)) }) {
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
            AspectRatioLock.RATIO_9_16 -> stringResource(R.string.aspect_ratio_9_16)
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
                    OutlinedButton(onClick = { imagePickerLauncher.launch(androidx.activity.result.PickVisualMediaRequest(androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia.ImageOnly)) }, modifier = Modifier.weight(1f)) {
                        Text(stringResource(R.string.action_new))
                    }
                    if (uiState.processedBitmap == null) {
                        Button(
                            onClick = {
                                if (!openCvReady) {
                                    snackbarMessage = com.dhanuk.photodoctorpro.utils.getOpenCvNotReadyMessage()
                                    snackbarType = SnackbarType.ERROR
                                } else {
                                    viewModel.applyCrop()
                                }
                            },
                            enabled = openCvReady,
                            modifier = Modifier.weight(1f)
                        ) {
                            Text(stringResource(R.string.action_crop))
                        }
                    } else {
                        Button(
                            onClick = { viewModel.saveImage(context, com.dhanuk.photodoctorpro.utils.UserPreferences.getSaveOptions(context)) },
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

private fun pointInQuad(p: Offset, quad: List<Offset>): Boolean {
    if (quad.size != 4) return false
    fun cross(o: Offset, a: Offset, b: Offset): Float =
        (a.x - o.x) * (b.y - o.y) - (a.y - o.y) * (b.x - o.x)
    val s0 = cross(quad[0], quad[1], p)
    val s1 = cross(quad[1], quad[2], p)
    val s2 = cross(quad[2], quad[3], p)
    val s3 = cross(quad[3], quad[0], p)
    val allPos = s0 >= 0 && s1 >= 0 && s2 >= 0 && s3 >= 0
    val allNeg = s0 <= 0 && s1 <= 0 && s2 <= 0 && s3 <= 0
    return allPos || allNeg
}
