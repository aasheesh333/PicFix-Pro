package com.dhanuk.photodoctorpro.ui.screens

import android.app.Activity
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Paint
import android.net.Uri
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asAndroidPath
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.dhanuk.photodoctorpro.data.local.History
import com.dhanuk.photodoctorpro.data.repository.HistoryRepository
import com.dhanuk.photodoctorpro.utils.AdManager
import com.dhanuk.photodoctorpro.utils.BitmapUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.opencv.android.OpenCVLoader
import org.opencv.android.Utils
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Scalar
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import org.opencv.photo.Photo
import java.util.Stack
import kotlin.math.max

// Data class for a stroke
data class EraserPath(
    val path: Path,
    val strokeWidth: Float,
    val softness: Float
)

class ObjectEraserViewModel(private val repository: HistoryRepository) : ViewModel() {

    private val _uiState = MutableStateFlow(ObjectEraserUiState())
    val uiState = _uiState.asStateFlow()

    private val undoStack = Stack<Bitmap>()
    private val redoStack = Stack<Bitmap>()
    private val MAX_STACK_SIZE = 10

    fun onImageSelected(uri: Uri, context: Context) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true)
            val bitmap = withContext(Dispatchers.IO) { BitmapUtils.loadBitmapFromUri(uri, context, 2048) }
            undoStack.forEach { if (!it.isRecycled) it.recycle() }
            redoStack.forEach { if (!it.isRecycled) it.recycle() }
            undoStack.clear()
            redoStack.clear()
            _uiState.value = ObjectEraserUiState(selectedImageUri = uri, originalBitmap = bitmap, isLoading = false)
        }
    }

    fun onBrushSizeChanged(newSize: Float) {
        _uiState.value = _uiState.value.copy(brushSize = newSize)
    }

    fun onBrushSoftnessChanged(newSoftness: Float) {
        _uiState.value = _uiState.value.copy(brushSoftness = newSoftness)
    }

    fun onPathsChanged(newPaths: List<EraserPath>) {
        _uiState.value = _uiState.value.copy(paths = newPaths)
    }

    fun addPath(path: EraserPath) {
        val currentPaths = _uiState.value.paths
        _uiState.value = _uiState.value.copy(paths = currentPaths + path)
    }

    fun undo() {
        if (undoStack.isNotEmpty()) {
            val current = _uiState.value.processedBitmap ?: _uiState.value.originalBitmap
            if (current != null) {
                redoStack.push(current)
            }
            val prev = undoStack.pop()
            val old = _uiState.value.processedBitmap
            _uiState.value = _uiState.value.copy(
                processedBitmap = prev,
                canUndo = undoStack.isNotEmpty(),
                canRedo = true
            )
            if (old != null && old != prev && !old.isRecycled) old.recycle()
        }
    }

    fun redo() {
        if (redoStack.isNotEmpty()) {
            val current = _uiState.value.processedBitmap ?: _uiState.value.originalBitmap
            if (current != null) {
                pushToStack(undoStack, current)
            }
            val next = redoStack.pop()
            val old = _uiState.value.processedBitmap
            _uiState.value = _uiState.value.copy(
                processedBitmap = next,
                canUndo = true,
                canRedo = redoStack.isNotEmpty()
            )
            if (old != null && old != next && !old.isRecycled) old.recycle()
        }
    }

    fun reset() {
        val uri = _uiState.value.selectedImageUri
        val bitmap = _uiState.value.originalBitmap
        undoStack.forEach { if (!it.isRecycled) it.recycle() }
        redoStack.forEach { if (!it.isRecycled) it.recycle() }
        undoStack.clear()
        redoStack.clear()
        _uiState.value = ObjectEraserUiState(
            selectedImageUri = uri,
            originalBitmap = bitmap,
            resetPerformed = true
        )
    }

    fun onResetMessageShown() {
        _uiState.value = _uiState.value.copy(resetPerformed = false)
    }

    fun eraseObjects() {
        val sourceBitmap = _uiState.value.processedBitmap ?: _uiState.value.originalBitmap ?: return
        val paths = _uiState.value.paths
        if (paths.isEmpty()) return

        eraseJob?.cancel()
        eraseJob = viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isErasing = true, error = null)

            pushToStack(undoStack, sourceBitmap)
            redoStack.clear()

            var workingBitmap: Bitmap? = null
            var softMask: Bitmap? = null
            try {
                workingBitmap = if (sourceBitmap.config != Bitmap.Config.ARGB_8888 || !sourceBitmap.isMutable) {
                    sourceBitmap.copy(Bitmap.Config.ARGB_8888, true)
                } else {
                    sourceBitmap
                }
                if (workingBitmap == null) {
                    _uiState.value = _uiState.value.copy(isErasing = false, error = "Could not allocate bitmap")
                    return@launch
                }
                checkActive()

                val safeWorking = workingBitmap!!
                softMask = createMask(safeWorking.width, safeWorking.height, paths)
                checkActive()

                val maxStroke = paths.maxOfOrNull { it.strokeWidth } ?: 20f
                val maxSoftness = paths.maxOfOrNull { it.softness } ?: 0f
                val dynamicRadius = max(15.0, maxStroke / 3.0) + (maxSoftness / 2.0)

                val resultBitmap = applyInpainting(safeWorking, softMask!!, dynamicRadius)
                checkActive()

                softMask = null
                if (safeWorking != sourceBitmap) safeWorking.recycle()
                workingBitmap = null

                val oldProcessed = _uiState.value.processedBitmap
                _uiState.value = _uiState.value.copy(
                    isErasing = false,
                    processedBitmap = resultBitmap,
                    paths = emptyList(),
                    canUndo = true,
                    canRedo = false
                )
                if (oldProcessed != null && oldProcessed != resultBitmap && !oldProcessed.isRecycled) {
                    oldProcessed.recycle()
                }
            } catch (ce: kotlinx.coroutines.CancellationException) {
                softMask?.recycle()
                if (workingBitmap != null && workingBitmap != sourceBitmap) workingBitmap.recycle()
                throw ce
            } catch (e: Exception) {
                if (com.dhanuk.photodoctorpro.BuildConfig.DEBUG) {
                    android.util.Log.e("ObjectEraserVM", "eraseObjects failed", e)
                }
                if (undoStack.isNotEmpty()) undoStack.pop()
                _uiState.value = _uiState.value.copy(isErasing = false, error = "Error: ${e.message}")
            }
        }
    }

    private var eraseJob: kotlinx.coroutines.Job? = null

    private suspend fun checkActive() = kotlinx.coroutines.currentCoroutineContext().ensureActive()

    private fun pushToStack(stack: Stack<Bitmap>, bitmap: Bitmap) {
        if (stack.size >= MAX_STACK_SIZE) {
            stack.removeAt(0)
        }
        stack.push(bitmap)
    }

    private suspend fun createMask(width: Int, height: Int, paths: List<EraserPath>): Bitmap = withContext(Dispatchers.Default) {
        val maskBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(maskBitmap)
        // Background black (no mask)
        canvas.drawColor(android.graphics.Color.BLACK)

        val paint = Paint().apply {
            color = android.graphics.Color.WHITE
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
            isAntiAlias = true
        }

        paths.forEach { eraserPath ->
            paint.strokeWidth = eraserPath.strokeWidth

            // Apply Softness per stroke
            if (eraserPath.softness > 0) {
                val radius = eraserPath.softness + 0.1f
                paint.maskFilter = BlurMaskFilter(radius, BlurMaskFilter.Blur.NORMAL)
            } else {
                paint.maskFilter = null
            }

            canvas.drawPath(eraserPath.path.asAndroidPath(), paint)
        }

        return@withContext maskBitmap
    }

    private suspend fun applyInpainting(original: Bitmap, softMask: Bitmap, radius: Double): Bitmap = withContext(Dispatchers.Default) {
        val src = Mat()
        val softMaskMat = Mat()
        val hardMaskMat = Mat()
        val inpaintedMat = Mat()
        val softMaskFloat = Mat()
        val mask3 = Mat()
        val invMask3 = Mat()
        val srcFloat = Mat()
        val inpaintedFloat = Mat()
        val part1 = Mat()
        val part2 = Mat()
        val resultFloat = Mat()
        val finalMat = Mat()
        try {
            Utils.bitmapToMat(original, src)
            Imgproc.cvtColor(src, src, Imgproc.COLOR_BGRA2BGR)

            Utils.bitmapToMat(softMask, softMaskMat)
            Imgproc.cvtColor(softMaskMat, softMaskMat, Imgproc.COLOR_BGRA2GRAY)

            // Threshold: any non-zero pixel in the soft mask (even 1/255) becomes opaque for the INPAINT step.
            Imgproc.threshold(softMaskMat, hardMaskMat, 1.0, 255.0, Imgproc.THRESH_BINARY)

            Photo.inpaint(src, hardMaskMat, inpaintedMat, radius, Photo.INPAINT_TELEA)

            // BLENDING
            softMaskMat.convertTo(softMaskFloat, CvType.CV_32F, 1.0/255.0)
            Imgproc.cvtColor(softMaskFloat, mask3, Imgproc.COLOR_GRAY2RGB)
            Core.subtract(Mat(mask3.size(), mask3.type(), Scalar(1.0, 1.0, 1.0)), mask3, invMask3)

            src.convertTo(srcFloat, CvType.CV_32F)
            inpaintedMat.convertTo(inpaintedFloat, CvType.CV_32F)

            Core.multiply(srcFloat, invMask3, part1)
            Core.multiply(inpaintedFloat, mask3, part2)

            Core.add(part1, part2, resultFloat)
            resultFloat.convertTo(finalMat, CvType.CV_8U)

            val resultBitmap = Bitmap.createBitmap(finalMat.cols(), finalMat.rows(), Bitmap.Config.ARGB_8888)
            Utils.matToBitmap(finalMat, resultBitmap)
            return@withContext resultBitmap
        } finally {
            src.release()
            softMaskMat.release()
            hardMaskMat.release()
            inpaintedMat.release()
            softMaskFloat.release()
            mask3.release()
            invMask3.release()
            srcFloat.release()
            inpaintedFloat.release()
            part1.release()
            part2.release()
            resultFloat.release()
            finalMat.release()
        }
    }

    suspend fun saveImage(activity: Activity): Boolean {
        val bitmap = _uiState.value.processedBitmap ?: return false
        val uri = _uiState.value.selectedImageUri ?: return false
        _uiState.value = _uiState.value.copy(isLoading = true)

        return try {
            val fileName = "PhotoDoctorPro_Erased_${System.currentTimeMillis()}"
            val filePath = BitmapUtils.saveBitmap(activity, bitmap, fileName, Bitmap.CompressFormat.JPEG)
            repository.addHistory(
                History(
                    operationType = "Object Erased",
                    inputFilePath = uri.toString(),
                    filePath = filePath,
                    timestamp = System.currentTimeMillis()
                )
            )
            AdManager.showInterstitialAd(activity)
            _uiState.value = _uiState.value.copy(savedFilePath = filePath)
            true
        } catch (e: Exception) {
            if (com.dhanuk.photodoctorpro.BuildConfig.DEBUG) {
                android.util.Log.e("ObjectEraserVM", "saveImage failed", e)
            }
            _uiState.value = _uiState.value.copy(error = "Failed to save image: ${e.message}")
            false
        } finally {
            _uiState.value = _uiState.value.copy(isLoading = false)
        }
    }

    fun onErrorShown() {
        _uiState.value = _uiState.value.copy(error = null)
    }

    fun onSavedMessageShown() {
        _uiState.value = _uiState.value.copy(savedFilePath = null)
    }

    override fun onCleared() {
        super.onCleared()
        eraseJob?.cancel()
        eraseJob = null
        _uiState.value.originalBitmap?.takeIf { !it.isRecycled }?.recycle()
        _uiState.value.processedBitmap?.takeIf { !it.isRecycled }?.recycle()
        undoStack.forEach { if (!it.isRecycled) it.recycle() }
        redoStack.forEach { if (!it.isRecycled) it.recycle() }
        undoStack.clear()
        redoStack.clear()
    }
}

data class ObjectEraserUiState(
    val selectedImageUri: Uri? = null,
    val originalBitmap: Bitmap? = null,
    val processedBitmap: Bitmap? = null,
    val paths: List<EraserPath> = emptyList(), // Updated to EraserPath
    val brushSize: Float = 40f,
    val brushSoftness: Float = 0f, // Added Softness
    val isErasing: Boolean = false,
    val isLoading: Boolean = false,
    val error: String? = null,
    val savedFilePath: String? = null,
    val resetPerformed: Boolean = false,
    val canUndo: Boolean = false,
    val canRedo: Boolean = false
)
