package com.dhanuk.photodoctorpro.ui.screens

import android.app.Activity
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.net.Uri
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
import org.opencv.imgproc.Imgproc
import org.opencv.photo.Photo
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asAndroidPath
import android.util.Log
import java.util.Stack

class ObjectEraserViewModel(private val repository: HistoryRepository) : ViewModel() {

    private val _uiState = MutableStateFlow(ObjectEraserUiState())
    val uiState = _uiState.asStateFlow()

    // Stack to store state history.
    // We store the Bitmap at each step.
    private val undoStack = Stack<Bitmap>()
    private val redoStack = Stack<Bitmap>()

    init {
        if (!OpenCVLoader.initDebug()) {
            _uiState.value = _uiState.value.copy(error = "OpenCV initialization failed.")
        }
    }

    fun onImageSelected(uri: Uri, context: Context) {
        viewModelScope.launch {
            val bitmap = BitmapUtils.loadBitmapFromUri(uri, context)
            undoStack.clear()
            redoStack.clear()
            // Downscale if too huge for eraser performance
            val displayBitmap = if (bitmap != null && (bitmap.width > 2048 || bitmap.height > 2048)) {
                val scale = 2048f / kotlin.math.max(bitmap.width, bitmap.height)
                Bitmap.createScaledBitmap(bitmap, (bitmap.width * scale).toInt(), (bitmap.height * scale).toInt(), true)
            } else {
                bitmap
            }

            _uiState.value = ObjectEraserUiState(selectedImageUri = uri, originalBitmap = displayBitmap, canUndo = false, canRedo = false)
        }
    }

    fun onBrushSizeChanged(newSize: Float) {
        _uiState.value = _uiState.value.copy(brushSize = newSize)
    }

    fun onPathsChanged(newPaths: List<Pair<Path, Float>>) {
        _uiState.value = _uiState.value.copy(paths = newPaths)
    }

    fun onUndo() {
        // If there are drawing paths (not erased yet), undo the last stroke
        val currentPaths = _uiState.value.paths
        if (currentPaths.isNotEmpty()) {
            _uiState.value = _uiState.value.copy(paths = currentPaths.dropLast(1))
            return
        }

        // Undo Erase Operation
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
                undoStack.push(current)
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
            resetPerformed = true,
            canUndo = false,
            canRedo = false
        )
    }

    fun onResetMessageShown() {
        _uiState.value = _uiState.value.copy(resetPerformed = false)
    }

    fun eraseObjects() {
        val sourceBitmap = _uiState.value.processedBitmap ?: _uiState.value.originalBitmap ?: return
        val paths = _uiState.value.paths
        if (paths.isEmpty()) return

        _uiState.value = _uiState.value.copy(isErasing = true, error = null)

        // Push current state BEFORE change to undo stack
        undoStack.push(sourceBitmap)
        redoStack.clear()

        viewModelScope.launch {
            try {
                // Ensure the bitmap is mutable or copy
                val workingBitmap = if (sourceBitmap.isMutable && sourceBitmap.config == Bitmap.Config.ARGB_8888) {
                    sourceBitmap.copy(Bitmap.Config.ARGB_8888, true)
                } else {
                    sourceBitmap.copy(Bitmap.Config.ARGB_8888, true)
                }

                val maskBitmap = createMask(workingBitmap.width, workingBitmap.height, paths)

                val resultBitmap = applyInpainting(workingBitmap, maskBitmap)

                _uiState.value = _uiState.value.copy(
                    isErasing = false,
                    processedBitmap = resultBitmap,
                    paths = emptyList(), // Clear mask
                    canUndo = true,
                    canRedo = false
                )
            } catch (e: Exception) {
                // Revert
                if (undoStack.isNotEmpty() && undoStack.peek() == sourceBitmap) {
                    undoStack.pop()
                }
                _uiState.value = _uiState.value.copy(isErasing = false, error = "Error during inpainting: ${e.message}")
            }
        }
    }

    private suspend fun createMask(width: Int, height: Int, paths: List<Pair<Path, Float>>): Bitmap = withContext(Dispatchers.Default) {
        val maskBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(maskBitmap)
        // Background black, draw white paths
        canvas.drawColor(android.graphics.Color.BLACK)

        val paint = Paint().apply {
            color = android.graphics.Color.WHITE
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }

        paths.forEach { (path, strokeWidth) ->
            paint.strokeWidth = strokeWidth
            canvas.drawPath(path.asAndroidPath(), paint)
        }
        maskBitmap
    }


    private suspend fun applyInpainting(original: Bitmap, mask: Bitmap): Bitmap = withContext(Dispatchers.Default) {
        val src = Mat()
        Utils.bitmapToMat(original, src)
        Imgproc.cvtColor(src, src, Imgproc.COLOR_RGBA2RGB)

        val maskMat = Mat()
        Utils.bitmapToMat(mask, maskMat)
        // Ensure single channel mask
        Imgproc.cvtColor(maskMat, maskMat, Imgproc.COLOR_BGRA2GRAY)

        val resultMat = Mat()
        // Use Telea with radius 5.0
        Photo.inpaint(src, maskMat, resultMat, 5.0, Photo.INPAINT_TELEA)

        val resultBitmap = Bitmap.createBitmap(resultMat.cols(), resultMat.rows(), Bitmap.Config.ARGB_8888)
        Utils.matToBitmap(resultMat, resultBitmap)

        src.release()
        maskMat.release()
        resultMat.release()

        resultBitmap
    }

    fun saveImage(activity: Activity) {
        val bitmap = _uiState.value.processedBitmap ?: return
        val uri = _uiState.value.selectedImageUri ?: return
        _uiState.value = _uiState.value.copy(isErasing = true)
        viewModelScope.launch {
            try {
                // Update: Use new BitmapUtils saving logic
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
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(error = "Failed to save image: ${e.message}")
            } finally {
                _uiState.value = _uiState.value.copy(isErasing = false)
            }
        }
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
    val brushSize: Float = 20f,
    val isErasing: Boolean = false,
    val error: String? = null,
    val savedFilePath: String? = null,
    val resetPerformed: Boolean = false,
    val canUndo: Boolean = false,
    val canRedo: Boolean = false
)
