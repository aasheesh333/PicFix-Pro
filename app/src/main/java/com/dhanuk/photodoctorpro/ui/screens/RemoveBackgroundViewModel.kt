package com.dhanuk.photodoctorpro.ui.screens

import android.app.Activity
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.net.Uri
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.dhanuk.photodoctorpro.data.local.History
import com.dhanuk.photodoctorpro.data.repository.HistoryRepository
import com.dhanuk.photodoctorpro.utils.AdManager
import com.dhanuk.photodoctorpro.utils.BitmapUtils
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.segmentation.subject.SubjectSegmentation
import com.google.mlkit.vision.segmentation.subject.SubjectSegmenterOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import org.opencv.android.Utils
import org.opencv.core.Mat
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import java.nio.ByteBuffer
import java.util.ArrayDeque

class RemoveBackgroundViewModel(private val repository: HistoryRepository) : ViewModel() {

    private val _uiState = MutableStateFlow(RemoveBackgroundUiState())
    val uiState = _uiState.asStateFlow()

    val maskVersion = mutableStateOf(0)

    private val undoStack = ArrayDeque<Bitmap>()
    private val redoStack = ArrayDeque<Bitmap>()
    private val MAX_STACK_SIZE = 10

    fun onImageSelected(uri: Uri, context: Context) {
        viewModelScope.launch {
            _uiState.value = RemoveBackgroundUiState(selectedImageUri = uri, isLoading = true)
            val bitmap = BitmapUtils.loadBitmapFromUri(uri, context, 2048)
            if (bitmap != null) {
                // Ensure strictly ARGB_8888 and Mutable
                val argbBitmap = bitmap.copy(Bitmap.Config.ARGB_8888, true)
                _uiState.value = _uiState.value.copy(
                    originalBitmap = argbBitmap,
                    isLoading = false
                )
            } else {
                _uiState.value = _uiState.value.copy(isLoading = false, error = "Failed to load image")
            }
        }
    }

    fun removeBackground(context: Context) {
        val rawBitmap = _uiState.value.originalBitmap ?: return
        _uiState.value = _uiState.value.copy(isLoading = true, error = null)

        viewModelScope.launch {
            try {
                // 1. Convert to ARGB_8888
                val inputBitmap = rawBitmap.copy(Bitmap.Config.ARGB_8888, true)

                // 2. Process to get Mask
                val mask = processToGetMask(inputBitmap)

                // 3. Apply Mask (Scale -> Compose)
                val result = applyMaskToOriginal(inputBitmap, mask)

                undoStack.clear()
                redoStack.clear()
                pushToUndo(mask.copy(mask.config, true))

                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    processedBitmap = result,
                    maskBitmap = mask,
                    isRefining = false
                )
            } catch (e: Exception) {
                e.printStackTrace()
                _uiState.value = _uiState.value.copy(isLoading = false, error = e.message ?: "Unknown error")
            }
        }
    }

    private suspend fun processToGetMask(bitmap: Bitmap): Bitmap = withContext(Dispatchers.Default) {
        val options = SubjectSegmenterOptions.Builder()
            .enableForegroundConfidenceMask()
            .build()
        val segmenter = SubjectSegmentation.getClient(options)

        val inputImage = InputImage.fromBitmap(bitmap, 0)
        val result = segmenter.process(inputImage).await()

        val maskBuffer = result.foregroundConfidenceMask
            ?: throw Exception("Could not generate mask (buffer is null).")

        val width = bitmap.width
        val height = bitmap.height

        maskBuffer.rewind()
        val totalPixels = width * height
        val pixels = ByteArray(totalPixels)

        if (maskBuffer.hasArray()) {
             val floatArray = maskBuffer.array()
             for (i in 0 until totalPixels) {
                pixels[i] = (floatArray[i] * 255).toInt().toByte()
             }
        } else {
             val floatArray = FloatArray(totalPixels)
             maskBuffer.get(floatArray)
             for (i in 0 until totalPixels) {
                 pixels[i] = (floatArray[i] * 255).toInt().toByte()
             }
        }

        val safeMask = Bitmap.createBitmap(width, height, Bitmap.Config.ALPHA_8)
        safeMask.copyPixelsFromBuffer(ByteBuffer.wrap(pixels))

        return@withContext safeMask
    }

    private suspend fun applyMaskToOriginal(original: Bitmap, mask: Bitmap): Bitmap = withContext(Dispatchers.Default) {
        // 1. Scale mask if needed (Crucial)
        val scaledMask = if (mask.width != original.width || mask.height != original.height) {
            Bitmap.createScaledBitmap(mask, original.width, original.height, true)
        } else {
            mask
        }

        // 2. Merge (Alpha Blending)
        val result = Bitmap.createBitmap(original.width, original.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(result)
        val paint = Paint()
        paint.isAntiAlias = true

        // Draw Original
        canvas.drawBitmap(original, 0f, 0f, paint)

        // Draw Mask with DST_IN
        val alphaPaint = Paint().apply {
            isAntiAlias = true
            xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN)
        }

        canvas.drawBitmap(scaledMask, 0f, 0f, alphaPaint)

        if (scaledMask != mask) {
            scaledMask.recycle()
        }

        return@withContext result
    }

    fun startRefining() {
        _uiState.value = _uiState.value.copy(isRefining = true)
    }

    fun applyRefinement() {
        val original = _uiState.value.originalBitmap ?: return
        val mask = _uiState.value.maskBitmap ?: return
        _uiState.value = _uiState.value.copy(isLoading = true)

        viewModelScope.launch {
             val result = applyMaskToOriginal(original, mask)
             _uiState.value = _uiState.value.copy(
                 processedBitmap = result,
                 isRefining = false,
                 isLoading = false
             )
        }
    }

    // Live update of the mask during Refine
    fun updateMask(path: Path, isAdd: Boolean, strokeWidth: Float, brushSoftness: Float) {
        val currentMask = _uiState.value.maskBitmap ?: return

        val canvas = Canvas(currentMask)
        val paint = Paint().apply {
            style = Paint.Style.STROKE
            this.strokeWidth = strokeWidth
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
            isAntiAlias = true

            // Brush Softness (Feather)
            if (brushSoftness > 0) {
                // radius must be > 0
                // Map 0..20 slider to sane radius, e.g. 1..50?
                // Or just use value directly.
                val radius = brushSoftness + 0.1f
                maskFilter = BlurMaskFilter(radius, BlurMaskFilter.Blur.NORMAL)
            }

            if (isAdd) {
                xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC)
                color = Color.WHITE
            } else {
                xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR)
                color = Color.TRANSPARENT
            }
        }
        canvas.drawPath(path, paint)
        maskVersion.value += 1
    }

    fun saveMaskStateForUndo() {
        val currentMask = _uiState.value.maskBitmap ?: return
        pushToUndo(currentMask.copy(currentMask.config, true))
        redoStack.clear()
    }

    private fun pushToUndo(bitmap: Bitmap) {
        if (undoStack.size >= MAX_STACK_SIZE) {
            undoStack.removeFirst()
        }
        undoStack.addLast(bitmap)
    }

    fun undo() {
        if (undoStack.isNotEmpty()) {
            val current = _uiState.value.maskBitmap
            if (current != null) redoStack.addLast(current)
            val prev = undoStack.removeLast()
             _uiState.value = _uiState.value.copy(maskBitmap = prev.copy(prev.config, true))
             maskVersion.value += 1
        }
    }

    fun redo() {
        if (redoStack.isNotEmpty()) {
            val current = _uiState.value.maskBitmap
            if (current != null) {
                 pushToUndo(current.copy(current.config, true))
            }
            val next = redoStack.removeLast()
             _uiState.value = _uiState.value.copy(maskBitmap = next.copy(next.config, true))
             maskVersion.value += 1
        }
    }

    suspend fun saveImage(activity: Activity): Boolean {
        val bitmap = _uiState.value.processedBitmap ?: return false
        val uri = _uiState.value.selectedImageUri ?: return false
        _uiState.value = _uiState.value.copy(isLoading = true)

        return try {
            val fileName = "PhotoDoctorPro_BG_${System.currentTimeMillis()}"
            val filePath = BitmapUtils.saveBitmap(activity, bitmap, fileName, Bitmap.CompressFormat.PNG)
            repository.addHistory(
                History(
                    operationType = "Background Removed",
                    inputFilePath = uri.toString(),
                    filePath = filePath,
                    timestamp = System.currentTimeMillis()
                )
            )
            AdManager.showInterstitialAd(activity)
            _uiState.value = _uiState.value.copy(savedFilePath = filePath)
            true
        } catch (e: Exception) {
            _uiState.value = _uiState.value.copy(error = "Failed to save image: ${e.message}")
            false
        } finally {
            _uiState.value = _uiState.value.copy(isLoading = false)
        }
    }

    fun reset() {
        _uiState.value = RemoveBackgroundUiState()
        undoStack.clear()
        redoStack.clear()
    }

    fun onErrorShown() {
        _uiState.value = _uiState.value.copy(error = null)
    }

    fun onSavedMessageShown() {
         _uiState.value = _uiState.value.copy(savedFilePath = null)
    }
}

data class RemoveBackgroundUiState(
    val selectedImageUri: Uri? = null,
    val originalBitmap: Bitmap? = null,
    val processedBitmap: Bitmap? = null,
    val maskBitmap: Bitmap? = null,
    val isRefining: Boolean = false,
    val isLoading: Boolean = false,
    val error: String? = null,
    val savedFilePath: String? = null
)
