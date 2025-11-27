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

class ObjectEraserViewModel(private val repository: HistoryRepository) : ViewModel() {

    private val _uiState = MutableStateFlow(ObjectEraserUiState())
    val uiState = _uiState.asStateFlow()

    init {
        if (!OpenCVLoader.initDebug()) {
            Log.e("ObjectEraserViewModel", "OpenCV initialization failed.")
            _uiState.value = _uiState.value.copy(error = "OpenCV initialization failed.")
        } else {
            Log.d("ObjectEraserViewModel", "OpenCV initialized successfully.")
        }
    }

    fun onImageSelected(uri: Uri, context: Context) {
        viewModelScope.launch {
            val bitmap = BitmapUtils.loadBitmapFromUri(uri, context)
            _uiState.value = ObjectEraserUiState(selectedImageUri = uri, originalBitmap = bitmap)
        }
    }

    fun onBrushSizeChanged(newSize: Float) {
        _uiState.value = _uiState.value.copy(brushSize = newSize)
    }

    fun onPathsChanged(newPaths: List<Pair<Path, Float>>) {
        _uiState.value = _uiState.value.copy(paths = newPaths)
    }

    fun onUndo() {
        val currentPaths = _uiState.value.paths
        if (currentPaths.isNotEmpty()) {
            _uiState.value = _uiState.value.copy(paths = currentPaths.dropLast(1))
        }
    }

    fun onReset() {
        val uri = _uiState.value.selectedImageUri
        val bitmap = _uiState.value.originalBitmap
        _uiState.value = ObjectEraserUiState(
            selectedImageUri = uri,
            originalBitmap = bitmap,
            resetPerformed = true // Trigger snackbar
        )
    }

    fun onResetMessageShown() {
        _uiState.value = _uiState.value.copy(resetPerformed = false)
    }

    fun eraseObjects() {
        val originalBitmap = _uiState.value.originalBitmap ?: return
        val paths = _uiState.value.paths
        if (paths.isEmpty()) return

        _uiState.value = _uiState.value.copy(isErasing = true, error = null)
        Log.d("ObjectEraserViewModel", "Starting eraseObjects")

        viewModelScope.launch {
            try {
                val maskBitmap = createMask(originalBitmap.width, originalBitmap.height, paths)
                val resultBitmap = applyInpainting(originalBitmap, maskBitmap)
                _uiState.value = _uiState.value.copy(
                    isErasing = false,
                    processedBitmap = resultBitmap,
                    paths = emptyList() // Clear paths after processing
                )
                Log.d("ObjectEraserViewModel", "eraseObjects finished successfully")
            } catch (e: Exception) {
                Log.e("ObjectEraserViewModel", "Error during inpainting", e)
                _uiState.value = _uiState.value.copy(isErasing = false, error = "Error during inpainting: ${e.message}")
            }
        }
    }

    private suspend fun createMask(width: Int, height: Int, paths: List<Pair<Path, Float>>): Bitmap = withContext(Dispatchers.Default) {
        val maskBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(maskBitmap)
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
        Imgproc.cvtColor(maskMat, maskMat, Imgproc.COLOR_BGRA2GRAY)

        val resultMat = Mat()
        Photo.inpaint(src, maskMat, resultMat, 3.0, Photo.INPAINT_TELEA)

        val resultBitmap = Bitmap.createBitmap(resultMat.cols(), resultMat.rows(), Bitmap.Config.ARGB_8888)
        Utils.matToBitmap(resultMat, resultBitmap)
        resultBitmap
    }

    fun saveImage(activity: Activity) {
        val bitmap = _uiState.value.processedBitmap ?: return
        val uri = _uiState.value.selectedImageUri ?: return
        _uiState.value = _uiState.value.copy(isErasing = true) // Reuse loading state
        viewModelScope.launch {
            try {
                val file = BitmapUtils.saveBitmap(activity, bitmap, "PhotoDoctorPro_Erased_${System.currentTimeMillis()}.jpg", Bitmap.CompressFormat.JPEG)
                repository.addHistory(
                    History(
                        operationType = "Object Erased",
                        inputFilePath = uri.toString(),
                        filePath = file.absolutePath,
                        timestamp = System.currentTimeMillis()
                    )
                )
                AdManager.showInterstitialAd(activity)
                Log.d("ObjectEraserViewModel", "Image saved to: ${file.absolutePath}")
                _uiState.value = _uiState.value.copy(savedFilePath = file.absolutePath)
            } catch (e: Exception) {
                Log.e("ObjectEraserViewModel", "Failed to save image", e)
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
        _uiState.value = ObjectEraserUiState() // Reset state completely after save notification is handled
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
    val resetPerformed: Boolean = false
)
