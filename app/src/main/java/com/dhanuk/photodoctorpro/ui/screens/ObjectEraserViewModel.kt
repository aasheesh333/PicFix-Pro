package com.dhanuk.photodoctorpro.ui.screens

import android.app.Activity
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.net.Uri
import android.util.Log
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asAndroidPath
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.dhanuk.photodoctorpro.data.local.History
import com.dhanuk.photodoctorpro.data.repository.HistoryRepository
import com.dhanuk.photodoctorpro.utils.AdManager
import com.dhanuk.photodoctorpro.utils.BitmapUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.opencv.android.OpenCVLoader
import org.opencv.android.Utils
import org.opencv.core.Mat
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import org.opencv.photo.Photo
import java.util.Stack

class ObjectEraserViewModel(private val repository: HistoryRepository) : ViewModel() {

    private val _uiState = MutableStateFlow(ObjectEraserUiState())
    val uiState = _uiState.asStateFlow()

    private val undoStack = Stack<Bitmap>()
    private val redoStack = Stack<Bitmap>()
    private val MAX_STACK_SIZE = 10

    init {
        if (!OpenCVLoader.initDebug()) {
            _uiState.value = _uiState.value.copy(error = "OpenCV initialization failed.")
        }
    }

    fun onImageSelected(uri: Uri, context: Context) {
        viewModelScope.launch {
            val bitmap = BitmapUtils.loadBitmapFromUri(uri, context, 2048)
            undoStack.clear()
            redoStack.clear()
            _uiState.value = ObjectEraserUiState(selectedImageUri = uri, originalBitmap = bitmap)
        }
    }

    fun onBrushSizeChanged(newSize: Float) {
        _uiState.value = _uiState.value.copy(brushSize = newSize)
    }

    fun onFeatherChanged(newFeather: Float) {
        _uiState.value = _uiState.value.copy(feather = newFeather)
    }

    fun onPathsChanged(newPaths: List<Pair<Path, Float>>) {
        _uiState.value = _uiState.value.copy(paths = newPaths)
    }

    fun onUndo() {
        val currentPaths = _uiState.value.paths
        if (currentPaths.isNotEmpty()) {
            _uiState.value = _uiState.value.copy(paths = currentPaths.dropLast(1))
            return
        }

        if (undoStack.isNotEmpty()) {
            val current = _uiState.value.processedBitmap ?: _uiState.value.originalBitmap
            if (current != null) {
                redoStack.push(current)
            }
            val prev = undoStack.pop()
            _uiState.value = _uiState.value.copy(
                processedBitmap = prev,
                canUndo = undoStack.isNotEmpty(),
                canRedo = true
            )
        }
    }

    fun onRedo() {
        if (redoStack.isNotEmpty()) {
            val current = _uiState.value.processedBitmap ?: _uiState.value.originalBitmap
            if (current != null) {
                pushToStack(undoStack, current)
            }
            val next = redoStack.pop()
            _uiState.value = _uiState.value.copy(
                processedBitmap = next,
                canUndo = true,
                canRedo = redoStack.isNotEmpty()
            )
        }
    }

    fun onReset() {
        val uri = _uiState.value.selectedImageUri
        val bitmap = _uiState.value.originalBitmap
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
        val feather = _uiState.value.feather

        _uiState.value = _uiState.value.copy(isErasing = true, error = null)

        pushToStack(undoStack, sourceBitmap)
        redoStack.clear()

        viewModelScope.launch {
            try {
                // Ensure mutable
                val workingBitmap = sourceBitmap.copy(Bitmap.Config.ARGB_8888, true)

                // Create Mask
                val maskBitmap = createMask(workingBitmap.width, workingBitmap.height, paths, feather)

                // Inpaint
                val resultBitmap = applyInpainting(workingBitmap, maskBitmap)

                _uiState.value = _uiState.value.copy(
                    isErasing = false,
                    processedBitmap = resultBitmap,
                    paths = emptyList(),
                    canUndo = true,
                    canRedo = false
                )
            } catch (e: Exception) {
                if (undoStack.isNotEmpty()) undoStack.pop() // revert stack push
                _uiState.value = _uiState.value.copy(isErasing = false, error = "Error: ${e.message}")
            }
        }
    }

    private fun pushToStack(stack: Stack<Bitmap>, bitmap: Bitmap) {
        if (stack.size >= MAX_STACK_SIZE) {
            stack.removeAt(0)
        }
        stack.push(bitmap)
    }

    private suspend fun createMask(width: Int, height: Int, paths: List<Pair<Path, Float>>, feather: Float): Bitmap = withContext(Dispatchers.Default) {
        val maskBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(maskBitmap)
        canvas.drawColor(android.graphics.Color.BLACK)

        val paint = Paint().apply {
            color = android.graphics.Color.WHITE
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
            isAntiAlias = true
        }

        paths.forEach { (path, strokeWidth) ->
            paint.strokeWidth = strokeWidth
            canvas.drawPath(path.asAndroidPath(), paint)
        }

        // Apply Feather (Blur) if needed
        if (feather > 0) {
            val mat = Mat()
            Utils.bitmapToMat(maskBitmap, mat)
            // Blur
            val kSize = (feather * 2 + 1).toDouble()
            Imgproc.GaussianBlur(mat, mat, Size(kSize, kSize), 0.0)

            // Threshold to ensure mask isn't too faint?
            // Telea expects mask. Non-zero pixels are inpainted.
            // Blur spreads non-zero pixels outwards (dilation effectively) but reduces their intensity.
            // This creates a smoother transition region? No, Telea uses mask to define region to fill.
            // If we want "Feathered" edges, we essentially want to inpaint a slightly larger area to blend better?
            // Actually, `Photo.inpaint` doesn't support alpha blending in the mask (it treats >0 as "unknown").
            // So feathering the mask just expands the inpaint area if pixels > 0.
            // To truly feather, we might need manual blending.
            // But let's stick to expanding the mask (Dilation) which helps with "halo" effects.
            // GaussianBlur does this by spreading values.

            Utils.matToBitmap(mat, maskBitmap)
            mat.release()
        }

        return@withContext maskBitmap
    }

    private suspend fun applyInpainting(original: Bitmap, mask: Bitmap): Bitmap = withContext(Dispatchers.Default) {
        val src = Mat()
        Utils.bitmapToMat(original, src)
        Imgproc.cvtColor(src, src, Imgproc.COLOR_RGBA2RGB)

        val maskMat = Mat()
        Utils.bitmapToMat(mask, maskMat)
        Imgproc.cvtColor(maskMat, maskMat, Imgproc.COLOR_BGRA2GRAY)

        val resultMat = Mat()
        Photo.inpaint(src, maskMat, resultMat, 5.0, Photo.INPAINT_TELEA)

        val resultBitmap = Bitmap.createBitmap(resultMat.cols(), resultMat.rows(), Bitmap.Config.ARGB_8888)
        Utils.matToBitmap(resultMat, resultBitmap)

        src.release()
        maskMat.release()
        resultMat.release()

        resultBitmap
    }

    fun saveImage(activity: Activity): Boolean {
        val bitmap = _uiState.value.processedBitmap ?: return false
        val uri = _uiState.value.selectedImageUri ?: return false
        _uiState.value = _uiState.value.copy(isErasing = true)

        // Saving handled by VM, return boolean via state or...
        // Here we just launch.
        var success = false
        viewModelScope.launch {
            try {
                val filePath = BitmapUtils.saveBitmap(activity, bitmap, "PhotoDoctorPro_Erased_${System.currentTimeMillis()}", Bitmap.CompressFormat.JPEG)

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
                success = true
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(error = "Failed to save image: ${e.message}")
            } finally {
                _uiState.value = _uiState.value.copy(isErasing = false)
            }
        }
        return true // Optimistic/Async
    }

    fun onErrorShown() {
        _uiState.value = _uiState.value.copy(error = null)
    }

    fun onSavedMessageShown() {
        _uiState.value = _uiState.value.copy(savedFilePath = null)
    }
}

data class ObjectEraserUiState(
    val selectedImageUri: Uri? = null,
    val originalBitmap: Bitmap? = null,
    val processedBitmap: Bitmap? = null,
    val paths: List<Pair<Path, Float>> = emptyList(),
    val brushSize: Float = 40f,
    val feather: Float = 0f,
    val isErasing: Boolean = false,
    val error: String? = null,
    val savedFilePath: String? = null,
    val resetPerformed: Boolean = false,
    val canUndo: Boolean = false,
    val canRedo: Boolean = false
)
