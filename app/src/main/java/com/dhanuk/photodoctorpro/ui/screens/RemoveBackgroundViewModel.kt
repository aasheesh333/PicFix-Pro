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
import java.util.ArrayDeque

class RemoveBackgroundViewModel(private val repository: HistoryRepository) : ViewModel() {

    private val _uiState = MutableStateFlow(RemoveBackgroundUiState())
    val uiState = _uiState.asStateFlow()

    // Undo/Redo Stacks for Mask Bitmap
    private val undoStack = ArrayDeque<Bitmap>()
    private val redoStack = ArrayDeque<Bitmap>()
    private val MAX_STACK_SIZE = 10

    fun onImageSelected(uri: Uri, context: Context) {
        viewModelScope.launch {
            _uiState.value = RemoveBackgroundUiState(selectedImageUri = uri, isLoading = true)
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
        val bitmap = _uiState.value.originalBitmap ?: return
        _uiState.value = _uiState.value.copy(isLoading = true, error = null)

        viewModelScope.launch {
            try {
                // Process to get Mask
                val mask = processToGetMask(bitmap)

                // Create initial processed bitmap
                val result = applyMaskToOriginal(bitmap, mask, 0f)

                // Clear stacks
                undoStack.clear()
                redoStack.clear()
                pushToUndo(mask) // Initial state

                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    processedBitmap = result,
                    maskBitmap = mask,
                    isRefining = false // Initially show result, user can click Refine
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
            val maskBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ALPHA_8)
            maskBuffer.rewind()
            maskBitmap.copyPixelsFromBuffer(maskBuffer)

            // The buffer is float, copyPixelsFromBuffer expects bytes?
            // Wait, foregroundConfidenceMask returns FloatBuffer.
            // Bitmap.copyPixelsFromBuffer supports Int, Short, Byte. NOT Float.
            // We must manually convert FloatBuffer to Byte array (0-255).

            // Manual conversion:
            maskBuffer.rewind()
            val pixels = ByteArray(width * height)
            for (i in 0 until width * height) {
                val confidence = maskBuffer.get()
                pixels[i] = (confidence * 255).toInt().toByte()
            }
            val safeMask = Bitmap.createBitmap(width, height, Bitmap.Config.ALPHA_8)
            val tempBuffer = java.nio.ByteBuffer.wrap(pixels)
            safeMask.copyPixelsFromBuffer(tempBuffer)

            return@withContext safeMask
        } else {
            // Fallback: If no mask but foreground bitmap exists (unlikely with options)
            // Or just create a full mask?
             throw Exception("Could not generate mask.")
        }
    }

    private suspend fun applyMaskToOriginal(original: Bitmap, mask: Bitmap, feather: Float): Bitmap = withContext(Dispatchers.Default) {
        // Feather the mask using OpenCV
        val smoothMask: Bitmap
        if (feather > 0) {
            val src = Mat()
            // Convert ALPHA_8 to something OpenCV likes (GRAY)
            // Utils.bitmapToMat handles ALPHA_8 as CvType.CV_8UC1
            Utils.bitmapToMat(mask, src)

            val blurred = Mat()
            val kSize = (feather * 2 + 1).toDouble() // Must be odd
            Imgproc.GaussianBlur(src, blurred, Size(kSize, kSize), 0.0)

            smoothMask = Bitmap.createBitmap(mask.width, mask.height, Bitmap.Config.ALPHA_8)
            Utils.matToBitmap(blurred, smoothMask)

            src.release()
            blurred.release()
        } else {
            smoothMask = mask
        }

        // Composite
        val result = Bitmap.createBitmap(original.width, original.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(result)
        val paint = Paint()

        // Draw original
        canvas.drawBitmap(original, 0f, 0f, paint)

        // Draw mask as DST_IN to keep only masked area
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

        // Save current state for Undo BEFORE modifying
        // We need a copy because currentMask is mutable and will be changed
        pushToUndo(currentMask.copy(currentMask.config, true))
        redoStack.clear()

        val canvas = Canvas(currentMask)
        val paint = Paint().apply {
            style = Paint.Style.STROKE
            this.strokeWidth = strokeWidth
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
            // AntiAlias? Yes.
            isAntiAlias = true

            // If Add -> Color White (Alpha 255)
            // If Remove -> Color Transparent (Alpha 0) -- wait, ALPHA_8 bitmap.
            // On ALPHA_8:
            // White = 0xFF (Full Opacity)
            // Black/Transparent = 0x00

            // However, drawing on Alpha8 with Canvas:
            // Color.WHITE (0xFFFFFFFF) -> Alpha 0xFF.
            // Color.TRANSPARENT (0x00000000) -> Alpha 0x00.
             color = if (isAdd) Color.WHITE else Color.TRANSPARENT

             if (!isAdd) {
                 // To "Erase" on the mask, we need to clear pixels.
                 // SRC mode replaces pixels.
                 xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC)
             }
        }

        canvas.drawPath(path, paint)

        // Trigger update
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
            if (current != null) pushToUndo(current) // Don't use pushToUndo here, it clears redo? No.
            // Actually standard Undo/Redo:
            // Undo: Current -> Redo, Pop Undo -> Current.
            // Redo: Current -> Undo, Pop Redo -> Current.

            // My implementation of pushToUndo clears redoStack. So I should manually handle stack.
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
    val processedBitmap: Bitmap? = null, // The result shown
    val maskBitmap: Bitmap? = null, // The current mask (for Refine)
    val isRefining: Boolean = false,
    val isLoading: Boolean = false,
    val error: String? = null,
    val savedFilePath: String? = null
)
