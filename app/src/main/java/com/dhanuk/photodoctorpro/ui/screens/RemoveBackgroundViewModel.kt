package com.dhanuk.photodoctorpro.ui.screens

import android.app.Activity
import android.content.Context
import android.graphics.Bitmap
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
                // Ensure strictly ARGB_8888
                val argbBitmap = if (bitmap.config != Bitmap.Config.ARGB_8888 || !bitmap.isMutable) {
                    bitmap.copy(Bitmap.Config.ARGB_8888, true)
                } else {
                    bitmap
                }
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
                // Double check ARGB_8888
                val inputBitmap = if (rawBitmap.config != Bitmap.Config.ARGB_8888) {
                     rawBitmap.copy(Bitmap.Config.ARGB_8888, true)
                } else {
                    rawBitmap
                }

                // Process to get Mask
                val mask = processToGetMask(inputBitmap)

                // Create initial processed bitmap (Feather 0)
                val result = applyMaskToOriginal(inputBitmap, mask, 0f)

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

        if (maskBuffer != null) {
            val width = result.foregroundBitmap?.width ?: bitmap.width
            val height = result.foregroundBitmap?.height ?: bitmap.height

            maskBuffer.rewind()
            val pixels = ByteArray(width * height)

            // Convert Float (0.0 - 1.0) to Byte (0 - 255)
            // High confidence (1.0) -> 255 (Keep)
            // Low confidence (0.0) -> 0 (Remove)
            if (maskBuffer.hasArray()) {
                 val floatArray = maskBuffer.array()
                 for (i in 0 until width * height) {
                    pixels[i] = (floatArray[i] * 255).toInt().toByte()
                 }
            } else {
                 for (i in 0 until width * height) {
                     pixels[i] = (maskBuffer.get() * 255).toInt().toByte()
                 }
            }

            val safeMask = Bitmap.createBitmap(width, height, Bitmap.Config.ALPHA_8)
            val tempBuffer = ByteBuffer.wrap(pixels)
            safeMask.copyPixelsFromBuffer(tempBuffer)

            return@withContext safeMask
        } else {
             throw Exception("Could not generate mask.")
        }
    }

    // Apply Mask + Feather
    private suspend fun applyMaskToOriginal(original: Bitmap, mask: Bitmap, feather: Float): Bitmap = withContext(Dispatchers.Default) {
        val smoothMask: Bitmap
        // Apply Gaussian Blur if feather > 0
        if (feather > 0) {
            val src = Mat()
            Utils.bitmapToMat(mask, src)

            val blurred = Mat()
            var kVal = (feather * 2).toInt()
            if (kVal % 2 == 0) kVal++
            if (kVal < 1) kVal = 1

            val kSize = Size(kVal.toDouble(), kVal.toDouble())
            Imgproc.GaussianBlur(src, blurred, kSize, 0.0)

            smoothMask = Bitmap.createBitmap(mask.width, mask.height, Bitmap.Config.ALPHA_8)
            Utils.matToBitmap(blurred, smoothMask)

            src.release()
            blurred.release()
        } else {
            smoothMask = mask
        }

        // Result Bitmap
        val result = Bitmap.createBitmap(original.width, original.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(result)
        val paint = Paint()
        paint.isAntiAlias = true

        // 1. Draw Original
        canvas.drawBitmap(original, 0f, 0f, paint)

        // 2. Apply Mask using DST_IN
        // DST_IN: Keeps Source where Dest (Mask) is Opaque.
        // Wait. DST_IN: "The source pixels are combined with the destination pixels. The alpha of the source determines the alpha of the result."
        // Source = Mask (The one we are drawing second). Dest = Original (Already on canvas).
        // Mask Alpha=255 -> Result Alpha=255 (Keep).
        // Mask Alpha=0 -> Result Alpha=0 (Transparent).
        paint.xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN)
        canvas.drawBitmap(smoothMask, 0f, 0f, paint)

        if (smoothMask != mask) smoothMask.recycle()

        return@withContext result
    }

    fun startRefining() {
        _uiState.value = _uiState.value.copy(isRefining = true)
    }

    fun applyRefinement(feather: Float) {
        val original = _uiState.value.originalBitmap ?: return
        val mask = _uiState.value.maskBitmap ?: return
        _uiState.value = _uiState.value.copy(isLoading = true)

        viewModelScope.launch {
             // Create final result with current mask and feather
             val result = applyMaskToOriginal(original, mask, feather)
             _uiState.value = _uiState.value.copy(
                 processedBitmap = result,
                 isRefining = false, // Exit refine mode
                 isLoading = false
             )
        }
    }

    fun updateMask(path: Path, isAdd: Boolean, strokeWidth: Float) {
        val currentMask = _uiState.value.maskBitmap ?: return

        val canvas = Canvas(currentMask)
        val paint = Paint().apply {
            style = Paint.Style.STROKE
            this.strokeWidth = strokeWidth
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
            isAntiAlias = true

            if (isAdd) {
                // Add = Make Opaque = 255
                xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC)
                color = Color.WHITE // Alpha 255
            } else {
                // Remove = Make Transparent = 0
                // For ALPHA_8, Color doesn't matter much if we use CLEAR, but safety first.
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
