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

    // We use a separate trigger for recomposition of the mask because simple Bitmap mutation
    // doesn't trigger Compose state updates unless the object reference changes or we force it.
    // We will use a version counter.
    val maskVersion = mutableStateOf(0)

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
                // FORCE ARGB_8888 and Mutable
                val inputBitmap = if (rawBitmap.config != Bitmap.Config.ARGB_8888 || !rawBitmap.isMutable) {
                     rawBitmap.copy(Bitmap.Config.ARGB_8888, true)
                } else {
                    rawBitmap
                }

                // Keep the ARGB_8888 original for processing
                val finalOriginal = if (inputBitmap != rawBitmap) inputBitmap else rawBitmap.copy(Bitmap.Config.ARGB_8888, true)

                _uiState.value = _uiState.value.copy(originalBitmap = finalOriginal)

                // Process to get Mask
                val mask = processToGetMask(finalOriginal)

                // Create initial processed bitmap
                val result = applyMaskToOriginal(finalOriginal, mask, 0f)

                undoStack.clear()
                redoStack.clear()
                // Store initial mask state for Undo
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

        // Ensure InputImage is created from ARGB_8888 bitmap
        val inputImage = InputImage.fromBitmap(bitmap, 0)

        val result = segmenter.process(inputImage).await()
        val maskBuffer = result.foregroundConfidenceMask

        if (maskBuffer != null) {
            val width = result.foregroundBitmap?.width ?: bitmap.width
            val height = result.foregroundBitmap?.height ?: bitmap.height

            maskBuffer.rewind()
            val pixels = ByteArray(width * height)
            // maskBuffer contains floats 0.0-1.0
            // We map this to 0-255 for ALPHA_8 bitmap
            // To ensure compatibility, we iterate and convert
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

    private suspend fun applyMaskToOriginal(original: Bitmap, mask: Bitmap, feather: Float): Bitmap = withContext(Dispatchers.Default) {
        val smoothMask: Bitmap
        if (feather > 0) {
            val src = Mat()
            Utils.bitmapToMat(mask, src)

            val blurred = Mat()
            // Ensure kSize is odd
            var kVal = (feather * 2).toInt()
            if (kVal % 2 == 0) kVal++
            val kSize = Size(kVal.toDouble(), kVal.toDouble())

            Imgproc.GaussianBlur(src, blurred, kSize, 0.0)

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

        // Don't recycle smoothMask if it's the same object as mask
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
             val result = applyMaskToOriginal(original, mask, feather)
             _uiState.value = _uiState.value.copy(
                 processedBitmap = result,
                 isRefining = false,
                 isLoading = false
             )
        }
    }

    // Called on every drag to update the mask bitmap live
    fun updateMask(path: Path, isAdd: Boolean, strokeWidth: Float) {
        val currentMask = _uiState.value.maskBitmap ?: return

        // We draw directly onto the mutable bitmap
        val canvas = Canvas(currentMask)
        val paint = Paint().apply {
            style = Paint.Style.STROKE
            this.strokeWidth = strokeWidth
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
            isAntiAlias = true
            // In ALPHA_8:
            // 255 (Opaque) keeps the image -> "Add" (Keep)
            // 0 (Transparent) removes the image -> "Erase" (Remove)
            color = if (isAdd) Color.WHITE else Color.BLACK
            // NOTE: For ALPHA_8, Color.BLACK usually maps to alpha 0 (Transparent) in some contexts or opaque black in others.
            // But PorterDuff Mode matters.
            // To ERASE (make transparent): Mode.CLEAR or Mode.SRC with color 0?
            // To ADD (make opaque): Mode.SRC with alpha 255.

            if (isAdd) {
                // Paint white (255) to keep
                xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC)
                color = Color.WHITE
            } else {
                // Paint transparent (0) to remove
                xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR)
            }
        }
        canvas.drawPath(path, paint)

        // Trigger recomposition
        maskVersion.value += 1
    }

    fun saveMaskStateForUndo() {
        val currentMask = _uiState.value.maskBitmap ?: return
        pushToUndo(currentMask.copy(currentMask.config, true))
        redoStack.clear()
    }

    private fun pushToUndo(bitmap: Bitmap) {
        if (undoStack.size >= MAX_STACK_SIZE) {
            val old = undoStack.removeFirst()
            // old.recycle() // Be careful recycling if used elsewhere
        }
        undoStack.addLast(bitmap)
    }

    fun undo() {
        if (undoStack.isNotEmpty()) {
            val current = _uiState.value.maskBitmap
            if (current != null) redoStack.addLast(current) // Don't copy, just move ref? Or copy?
            // Ideally we copy current state to redo stack before restoring

            // To be safe against mutation:
            // If we restore 'prev', and then draw on it, we mutate the object in undoStack?
            // Yes, so we must copy when pushing to undo, and copy when restoring?

            val prev = undoStack.removeLast() // This should be a snapshot

            // Restore by replacing the maskBitmap in uiState?
            // But we need to keep the object mutable and same config
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
