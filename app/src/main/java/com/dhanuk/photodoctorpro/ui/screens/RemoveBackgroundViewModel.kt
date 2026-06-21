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
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.dhanuk.photodoctorpro.data.local.History
import com.dhanuk.photodoctorpro.data.repository.HistoryRepository
import com.dhanuk.photodoctorpro.utils.AdManager
import com.dhanuk.photodoctorpro.utils.BitmapUtils
import com.dhanuk.photodoctorpro.utils.viewModelExceptionHandler
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.segmentation.subject.SubjectSegmentation
import com.google.mlkit.vision.segmentation.subject.SubjectSegmenterOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext

import java.nio.ByteBuffer
import java.util.ArrayDeque

class RemoveBackgroundViewModel(
    private val repository: HistoryRepository,
    private val savedStateHandle: SavedStateHandle
) : ViewModel() {

    private val _uiState = MutableStateFlow(
        RemoveBackgroundUiState(
            selectedImageUri = savedStateHandle.get<String>(KEY_URI)?.let {
                runCatching { Uri.parse(it) }.getOrNull()
            }
        )
    )
    val uiState = _uiState.asStateFlow()

    val maskVersion = mutableStateOf(0)

    private val undoStack = ArrayDeque<Bitmap>()
    private val redoStack = ArrayDeque<Bitmap>()
    private val MAX_STACK_SIZE = 10

    private var removeBgJob: kotlinx.coroutines.Job? = null
    private var refinementJob: kotlinx.coroutines.Job? = null

    fun onImageSelected(uri: Uri, context: Context) {
        savedStateHandle[KEY_URI] = uri.toString()
        viewModelScope.launch(viewModelExceptionHandler("RemoveBGVM") + Dispatchers.IO) {
            _uiState.value = RemoveBackgroundUiState(selectedImageUri = uri, isLoading = true)
            try {
                val bitmap = BitmapUtils.loadBitmapFromUri(uri, context, 2048)
                if (bitmap != null) {
                    val argbBitmap = bitmap.copy(Bitmap.Config.ARGB_8888, true)
                    val old = _uiState.value.originalBitmap
                    _uiState.value = _uiState.value.copy(
                        originalBitmap = argbBitmap,
                        isLoading = false
                    )
                    if (old != null && old != argbBitmap && !old.isRecycled) old.recycle()
                    if (bitmap != argbBitmap && !bitmap.isRecycled) bitmap.recycle()
                } else {
                    _uiState.value = _uiState.value.copy(isLoading = false, error = "Failed to load image")
                }
            } catch (e: Exception) {
                if (com.dhanuk.photodoctorpro.BuildConfig.DEBUG) {
                    android.util.Log.e("RemoveBGVM", "onImageSelected failed", e)
                }
                _uiState.value = _uiState.value.copy(isLoading = false, error = "Load failed: ${e.message}")
            }
        }
    }

    fun removeBackground(context: Context) {
        val rawBitmap = _uiState.value.originalBitmap ?: return
        _uiState.value = _uiState.value.copy(isLoading = true, error = null)

        removeBgJob?.cancel()
        removeBgJob = viewModelScope.launch(viewModelExceptionHandler("RemoveBGVM")) {
            try {
                val inputBitmap = rawBitmap.copy(Bitmap.Config.ARGB_8888, true)
                checkActive()
                val mask = processToGetMask(inputBitmap)
                checkActive()
                val result = applyMaskToOriginal(inputBitmap, mask)
                checkActive()
                if (inputBitmap != rawBitmap && !inputBitmap.isRecycled) inputBitmap.recycle()

                val oldProcessed = _uiState.value.processedBitmap
                val oldMask = _uiState.value.maskBitmap

                undoStack.forEach { if (!it.isRecycled) it.recycle() }
                redoStack.forEach { if (!it.isRecycled) it.recycle() }
                undoStack.clear()
                redoStack.clear()
                pushToUndo(mask.copy(mask.config, true))

                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    processedBitmap = result,
                    maskBitmap = mask,
                    isRefining = false
                )
                if (oldProcessed != null && oldProcessed != result && !oldProcessed.isRecycled) oldProcessed.recycle()
                if (oldMask != null && oldMask != mask && !oldMask.isRecycled) oldMask.recycle()
            } catch (ce: kotlinx.coroutines.CancellationException) {
                throw ce
            } catch (e: Exception) {
                if (com.dhanuk.photodoctorpro.BuildConfig.DEBUG) {
                    android.util.Log.e("RemoveBGVM", "removeBackground failed", e)
                }
                _uiState.value = _uiState.value.copy(isLoading = false, error = e.message ?: "Unknown error")
            }
        }
    }

    private suspend fun checkActive() = kotlinx.coroutines.currentCoroutineContext().ensureActive()

    private suspend fun processToGetMask(bitmap: Bitmap): Bitmap = withContext(Dispatchers.Default) {
        val options = SubjectSegmenterOptions.Builder()
            .enableForegroundConfidenceMask()
            .build()
        val segmenter = SubjectSegmentation.getClient(options)

        val inputImage = InputImage.fromBitmap(bitmap, 0)
        val result = segmenter.process(inputImage).await()

        val maskBuffer = result.foregroundConfidenceMask
            ?: throw Exception("Could not generate mask.")

        val width = bitmap.width
        val height = bitmap.height
        val totalPixels = width * height

        val threshold = 0.4f
        val pixels = ByteArray(totalPixels)

        if (maskBuffer.hasArray()) {
             val floatArray = maskBuffer.array()
             for (i in 0 until totalPixels) {
                pixels[i] = if (floatArray[i] > threshold) 255.toByte() else 0
             }
        } else {
             val floatArray = FloatArray(totalPixels)
             maskBuffer.rewind()
             maskBuffer.get(floatArray)
             for (i in 0 until totalPixels) {
                 pixels[i] = if (floatArray[i] > threshold) 255.toByte() else 0
             }
        }

        val safeMask = Bitmap.createBitmap(width, height, Bitmap.Config.ALPHA_8)
        safeMask.copyPixelsFromBuffer(ByteBuffer.wrap(pixels))

        return@withContext safeMask
    }

    private suspend fun applyMaskToOriginal(original: Bitmap, mask: Bitmap): Bitmap = withContext(Dispatchers.Default) {
        val scaledMask = if (mask.width != original.width || mask.height != original.height) {
            Bitmap.createScaledBitmap(mask, original.width, original.height, true)
        } else {
            mask
        }

        // --- NEW LOGIC: Draw Mask then SRC_IN Image ---
        val result = Bitmap.createBitmap(original.width, original.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(result)

        val paint = Paint()
        paint.isAntiAlias = true

        // 1. Draw the Mask (Alpha determines opacity)
        // We draw the mask bitmap directly. ALPHA_8 -> ARGB_8888 canvas.
        // Pixels with Alpha 255 become Opaque Black (or whatever color paint has, default black).
        // Pixels with Alpha 0 become Transparent.
        canvas.drawBitmap(scaledMask, 0f, 0f, paint)

        // 2. Draw the Image with SRC_IN
        // SRC_IN: Keeps Source (Image) where Dest (Mask/Canvas) is Opaque.
        paint.xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC_IN)
        canvas.drawBitmap(original, 0f, 0f, paint)

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

        refinementJob?.cancel()
        refinementJob = viewModelScope.launch(viewModelExceptionHandler("RemoveBGVM")) {
             try {
                 val result = applyMaskToOriginal(original, mask)
                 checkActive()
                 val old = _uiState.value.processedBitmap
                 _uiState.value = _uiState.value.copy(
                     processedBitmap = result,
                     isRefining = false,
                     isLoading = false
                 )
                 if (old != null && old != result && !old.isRecycled) old.recycle()
             } catch (ce: kotlinx.coroutines.CancellationException) {
                 throw ce
             } catch (e: Exception) {
                 if (com.dhanuk.photodoctorpro.BuildConfig.DEBUG) {
                     android.util.Log.e("RemoveBGVM", "applyRefinement failed", e)
                 }
                 _uiState.value = _uiState.value.copy(isLoading = false, error = "Refinement failed: ${e.message}")
             }
        }
    }

    fun updateMask(path: Path, isAdd: Boolean, strokeWidth: Float, brushSoftness: Float) {
        val currentMask = _uiState.value.maskBitmap ?: return

        val canvas = Canvas(currentMask)
        val paint = Paint().apply {
            style = Paint.Style.STROKE
            this.strokeWidth = strokeWidth
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
            isAntiAlias = true

            if (brushSoftness > 0) {
                val radius = brushSoftness + 0.1f
                maskFilter = BlurMaskFilter(radius, BlurMaskFilter.Blur.NORMAL)
            }

            if (isAdd) {
                // To ADD to the mask (Keep area), we paint 255 (Opaque)
                // SRC mode replaces correctly.
                xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC)
                color = Color.WHITE // Alpha 255
            } else {
                // To REMOVE from mask (Erase area), we paint 0 (Transparent)
                // CLEAR mode sets alpha to 0.
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
            _uiState.value = _uiState.value.copy(maskBitmap = prev)
            maskVersion.value += 1
        }
    }

    fun redo() {
        if (redoStack.isNotEmpty()) {
            val current = _uiState.value.maskBitmap
            if (current != null) pushToUndo(current)
            val next = redoStack.removeLast()
            _uiState.value = _uiState.value.copy(maskBitmap = next)
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
            if (com.dhanuk.photodoctorpro.BuildConfig.DEBUG) {
                android.util.Log.e("RemoveBGVM", "saveImage failed", e)
            }
            _uiState.value = _uiState.value.copy(error = "Failed to save image: ${e.message}")
            false
        } finally {
            _uiState.value = _uiState.value.copy(isLoading = false)
        }
    }

    fun reset() {
        _uiState.value.originalBitmap?.takeIf { !it.isRecycled }?.recycle()
        _uiState.value.processedBitmap?.takeIf { !it.isRecycled }?.recycle()
        _uiState.value.maskBitmap?.takeIf { !it.isRecycled }?.recycle()
        undoStack.forEach { if (!it.isRecycled) it.recycle() }
        redoStack.forEach { if (!it.isRecycled) it.recycle() }
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

    override fun onCleared() {
        super.onCleared()
        removeBgJob?.cancel()
        refinementJob?.cancel()
        removeBgJob = null
        refinementJob = null
        _uiState.value.originalBitmap?.takeIf { !it.isRecycled }?.recycle()
        _uiState.value.processedBitmap?.takeIf { !it.isRecycled }?.recycle()
        _uiState.value.maskBitmap?.takeIf { !it.isRecycled }?.recycle()
        undoStack.forEach { if (!it.isRecycled) it.recycle() }
        redoStack.forEach { if (!it.isRecycled) it.recycle() }
        undoStack.clear()
        redoStack.clear()
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

private const val KEY_URI = "selectedImageUri"
