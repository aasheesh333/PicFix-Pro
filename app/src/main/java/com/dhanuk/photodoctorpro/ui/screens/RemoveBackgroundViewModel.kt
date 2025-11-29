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

    private val undoStack = ArrayDeque<Bitmap>()
    private val redoStack = ArrayDeque<Bitmap>()
    private val MAX_STACK_SIZE = 10

    fun onImageSelected(uri: Uri, context: Context) {
        viewModelScope.launch {
            _uiState.value = RemoveBackgroundUiState(selectedImageUri = uri, isLoading = true)
            // Load safely with subsampling
            val bitmap = BitmapUtils.loadBitmapFromUri(uri, context, 2048)
            if (bitmap != null) {
                _uiState.value = _uiState.value.copy(
                    originalBitmap = bitmap,
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
                // Ensure strictly ARGB_8888 and Mutable (Software) to avoid Buffer errors in ML Kit
                val inputBitmap = if (rawBitmap.config != Bitmap.Config.ARGB_8888 || rawBitmap.isRecycled) {
                    rawBitmap.copy(Bitmap.Config.ARGB_8888, true)
                } else {
                    // Even if config is ARGB_8888, ensure it's mutable and not hardware if possible (copy helps)
                    rawBitmap.copy(Bitmap.Config.ARGB_8888, true)
                }

                // Update state with the clean bitmap
                _uiState.value = _uiState.value.copy(originalBitmap = inputBitmap)

                // Process to get Mask
                val mask = processToGetMask(inputBitmap)

                // Create initial processed bitmap
                val result = applyMaskToOriginal(inputBitmap, mask, 0f)

                undoStack.clear()
                redoStack.clear()
                pushToUndo(mask)

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

            // Manual conversion from FloatBuffer (0.0-1.0) to Byte Array (0-255)
            // This avoids using unsupported PixelBuffer formats
            maskBuffer.rewind()
            val pixels = ByteArray(width * height)
            for (i in 0 until width * height) {
                val confidence = maskBuffer.get()
                // Thresholding or direct mapping? Confidence 0.0-1.0.
                // Alpha8 expects 0-255.
                pixels[i] = (confidence * 255).toInt().toByte()
            }

            val safeMask = Bitmap.createBitmap(width, height, Bitmap.Config.ALPHA_8)
            val tempBuffer = ByteBuffer.wrap(pixels)
            safeMask.copyPixelsFromBuffer(tempBuffer)

            return@withContext safeMask
        } else {
             throw Exception("Could not generate mask.")
        }
    }

    private suspend fun applyMaskToOriginal(original: Bitmap, mask: Bitmap, feather: Float): Bitmap = withContext(Dispatchers.Default) {
        val smoothMask: Bitmap
        if (feather > 0) {
            val src = Mat()
            Utils.bitmapToMat(mask, src)

            val blurred = Mat()
            val kSize = (feather * 2 + 1).toDouble()
            Imgproc.GaussianBlur(src, blurred, Size(kSize, kSize), 0.0)

            smoothMask = Bitmap.createBitmap(mask.width, mask.height, Bitmap.Config.ALPHA_8)
            Utils.matToBitmap(blurred, smoothMask)

            src.release()
            blurred.release()
        } else {
            smoothMask = mask
        }

        val result = Bitmap.createBitmap(original.width, original.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(result)
        val paint = Paint()

        canvas.drawBitmap(original, 0f, 0f, paint)

        paint.xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN)
        canvas.drawBitmap(smoothMask, 0f, 0f, paint)

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
             val result = applyMaskToOriginal(original, mask, feather)
             _uiState.value = _uiState.value.copy(
                 processedBitmap = result,
                 isRefining = false,
                 isLoading = false
             )
        }
    }

    fun updateMask(path: Path, isAdd: Boolean, strokeWidth: Float) {
        val currentMask = _uiState.value.maskBitmap ?: return
        pushToUndo(currentMask.copy(currentMask.config, true))
        redoStack.clear()

        val canvas = Canvas(currentMask)
        val paint = Paint().apply {
            style = Paint.Style.STROKE
            this.strokeWidth = strokeWidth
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
            isAntiAlias = true
            color = if (isAdd) Color.WHITE else Color.TRANSPARENT // Alpha8: White=Opaque, Trans=Transparent
             if (!isAdd) {
                 xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC)
             }
        }
        canvas.drawPath(path, paint)
        _uiState.value = _uiState.value.copy(maskBitmap = currentMask)
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
            _uiState.value = _uiState.value.copy(maskBitmap = prev)
        }
    }

    fun redo() {
        if (redoStack.isNotEmpty()) {
            val current = _uiState.value.maskBitmap
            if (current != null) {
                 if (undoStack.size >= MAX_STACK_SIZE) undoStack.removeFirst()
                 undoStack.addLast(current)
            }
            val next = redoStack.removeLast()
            _uiState.value = _uiState.value.copy(maskBitmap = next)
        }
    }

    suspend fun saveImage(activity: Activity): Boolean {
        val bitmap = _uiState.value.processedBitmap ?: return false
        val uri = _uiState.value.selectedImageUri ?: return false
        _uiState.value = _uiState.value.copy(isLoading = true)

        return try {
            val filePath = BitmapUtils.saveBitmap(activity, bitmap, "PhotoDoctorPro_BG_${System.currentTimeMillis()}", Bitmap.CompressFormat.PNG)
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
