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
import kotlinx.coroutines.flow.update
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

    private val _maskVersion = MutableStateFlow(0)
    val maskVersion = _maskVersion.asStateFlow()

    private val undoStack = ArrayDeque<Bitmap>()
    private val redoStack = ArrayDeque<Bitmap>()
    private val stackLock = Any()
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
                    _uiState.update { it.copy(
                        originalBitmap = argbBitmap,
                        isLoading = false
                    ) }
                    if (old != null && old != argbBitmap && !old.isRecycled) old.recycle()
                    if (bitmap != argbBitmap && !bitmap.isRecycled) bitmap.recycle()
                } else {
                    _uiState.update { it.copy(isLoading = false, error = context.getString(com.dhanuk.photodoctorpro.R.string.image_load_failed)) }
                }
            } catch (ce: kotlinx.coroutines.CancellationException) {
                throw ce
            } catch (e: Exception) {
                if (com.dhanuk.photodoctorpro.BuildConfig.DEBUG) {
                    android.util.Log.e("RemoveBGVM", "onImageSelected failed", e)
                }
                _uiState.update { it.copy(isLoading = false, error = context.getString(com.dhanuk.photodoctorpro.R.string.image_load_failed_with_reason, e.message)) }
            }
        }
    }

    fun removeBackground(context: Context) {
        val rawBitmap = _uiState.value.originalBitmap ?: return
        _uiState.update { it.copy(isLoading = true, error = null) }

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

                synchronized(stackLock) {
                    undoStack.forEach { if (!it.isRecycled) it.recycle() }
                    redoStack.forEach { if (!it.isRecycled) it.recycle() }
                    undoStack.clear()
                    redoStack.clear()
                    val maskCopy = mask.copy(mask.config ?: Bitmap.Config.ALPHA_8, true)
                    if (maskCopy != null) pushToUndoLocked(maskCopy)
                }

                _uiState.update { it.copy(
                    isLoading = false,
                    processedBitmap = result,
                    maskBitmap = mask,
                    isRefining = false
                ) }
                if (oldProcessed != null && oldProcessed != result && !oldProcessed.isRecycled) oldProcessed.recycle()
                if (oldMask != null && oldMask != mask && !oldMask.isRecycled) oldMask.recycle()
            } catch (ce: kotlinx.coroutines.CancellationException) {
                throw ce
            } catch (e: Exception) {
                if (com.dhanuk.photodoctorpro.BuildConfig.DEBUG) {
                    android.util.Log.e("RemoveBGVM", "removeBackground failed", e)
                }
                _uiState.update { it.copy(isLoading = false, error = e.message ?: context.getString(com.dhanuk.photodoctorpro.R.string.error_unknown)) }
            }
        }
    }

    private suspend fun checkActive() = kotlinx.coroutines.currentCoroutineContext().ensureActive()

    private suspend fun processToGetMask(bitmap: Bitmap): Bitmap = withContext(Dispatchers.Default) {
        val options = SubjectSegmenterOptions.Builder()
            .enableForegroundConfidenceMask()
            .build()
        val segmenter = SubjectSegmentation.getClient(options)

        try {
            val inputImage = InputImage.fromBitmap(bitmap, 0)
            val result = segmenter.process(inputImage).await()

            val maskBuffer = result.foregroundConfidenceMask
                ?: throw Exception("Could not generate mask.")

            val width = bitmap.width
            val height = bitmap.height
            val totalPixels = width * height

            val threshold = 0.4f
            val pixels = ByteArray(totalPixels)

            maskBuffer.rewind()
            val floatArray = FloatArray(minOf(totalPixels, maskBuffer.remaining()))
            maskBuffer.get(floatArray)
            for (i in 0 until totalPixels) {
                val f = if (i < floatArray.size) floatArray[i] else 0f
                pixels[i] = if (f > threshold) 255.toByte() else 0
            }

            val safeMask = Bitmap.createBitmap(width, height, Bitmap.Config.ALPHA_8)
            safeMask.copyPixelsFromBuffer(ByteBuffer.wrap(pixels))

            return@withContext safeMask
        } finally {
            segmenter.close()
        }
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
        _uiState.update { it.copy(isRefining = true) }
    }

    fun applyRefinement(context: Context) {
        val original = _uiState.value.originalBitmap ?: return
        val mask = _uiState.value.maskBitmap ?: return
        _uiState.update { it.copy(isLoading = true) }

        refinementJob?.cancel()
        refinementJob = viewModelScope.launch(viewModelExceptionHandler("RemoveBGVM")) {
             try {
                 val result = applyMaskToOriginal(original, mask)
                 checkActive()
                 val old = _uiState.value.processedBitmap
                 _uiState.update { it.copy(
                     processedBitmap = result,
                     isRefining = false,
                     isLoading = false
                 ) }
                 if (old != null && old != result && !old.isRecycled) old.recycle()
             } catch (ce: kotlinx.coroutines.CancellationException) {
                 throw ce
             } catch (e: Exception) {
                 if (com.dhanuk.photodoctorpro.BuildConfig.DEBUG) {
                     android.util.Log.e("RemoveBGVM", "applyRefinement failed", e)
                 }
                    _uiState.update { it.copy(isLoading = false, error = context.getString(com.dhanuk.photodoctorpro.R.string.refinement_failed_fmt, e.message)) }
             }
        }
    }

    fun updateMask(path: Path, isAdd: Boolean, strokeWidth: Float, brushSoftness: Float) {
        val currentMask = _uiState.value.maskBitmap ?: return
        val config = currentMask.config ?: Bitmap.Config.ALPHA_8
        val needsConversion = currentMask.config == Bitmap.Config.ALPHA_8
        val safeMask = if (needsConversion) {
            val copy = currentMask.copy(Bitmap.Config.ARGB_8888, true) ?: return
            _uiState.update { it.copy(maskBitmap = copy) }
            copy
        } else {
            currentMask
        }

        val canvas = Canvas(safeMask)
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
                xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC)
                color = Color.WHITE
            } else {
                xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR)
                color = Color.TRANSPARENT
            }
        }
        canvas.drawPath(path, paint)
        _maskVersion.value += 1

        if (needsConversion) {
            val oldAlpha8 = currentMask
            synchronized(stackLock) {
                val inStack = undoStack.any { it === oldAlpha8 } || redoStack.any { it === oldAlpha8 }
                if (!inStack && !oldAlpha8.isRecycled) oldAlpha8.recycle()
            }
        }
    }

    fun saveMaskStateForUndo() {
        val currentMask = _uiState.value.maskBitmap ?: return
        synchronized(stackLock) {
            pushToUndoLocked(currentMask.copy(currentMask.config ?: Bitmap.Config.ALPHA_8, true))
            redoStack.clear()
        }
    }

    private fun pushToUndoLocked(bitmap: Bitmap) {
        if (undoStack.size >= MAX_STACK_SIZE) {
            val evicted = undoStack.removeFirst()
            val currentMask = _uiState.value.maskBitmap
            if (evicted !== currentMask && !evicted.isRecycled) evicted.recycle()
        }
        undoStack.addLast(bitmap)
    }

    fun undo() {
        val prev: Bitmap
        val newProcessed: Bitmap?
        synchronized(stackLock) {
            if (undoStack.isEmpty()) return
            val current = _uiState.value.maskBitmap
            if (current != null) {
                val copy = current.copy(current.config ?: Bitmap.Config.ALPHA_8, true)
                if (copy != null) redoStack.addLast(copy)
            }
            prev = undoStack.removeLast()
        }
        val original = _uiState.value.originalBitmap
        newProcessed = if (original != null && !original.isRecycled && !prev.isRecycled) {
            applyMaskToOriginalSync(original, prev)
        } else {
            _uiState.value.processedBitmap
        }
        val oldProcessed = _uiState.value.processedBitmap
        synchronized(stackLock) {
            _uiState.update { it.copy(maskBitmap = prev, processedBitmap = newProcessed) }
        }
        if (oldProcessed != null && oldProcessed != newProcessed && !oldProcessed.isRecycled) oldProcessed.recycle()
        _maskVersion.value += 1
    }

    fun redo() {
        val next: Bitmap
        val newProcessed: Bitmap?
        synchronized(stackLock) {
            if (redoStack.isEmpty()) return
            val current = _uiState.value.maskBitmap
            if (current != null) {
                val copy = current.copy(current.config ?: Bitmap.Config.ALPHA_8, true)
                if (copy != null) pushToUndoLocked(copy)
            }
            next = redoStack.removeLast()
        }
        val original = _uiState.value.originalBitmap
        newProcessed = if (original != null && !original.isRecycled && !next.isRecycled) {
            applyMaskToOriginalSync(original, next)
        } else {
            _uiState.value.processedBitmap
        }
        val oldProcessed = _uiState.value.processedBitmap
        synchronized(stackLock) {
            _uiState.update { it.copy(maskBitmap = next, processedBitmap = newProcessed) }
        }
        if (oldProcessed != null && oldProcessed != newProcessed && !oldProcessed.isRecycled) oldProcessed.recycle()
        _maskVersion.value += 1
    }

    private fun applyMaskToOriginalSync(original: Bitmap, mask: Bitmap): Bitmap {
        val scaledMask = if (mask.width != original.width || mask.height != original.height) {
            Bitmap.createScaledBitmap(mask, original.width, original.height, true)
        } else {
            mask
        }
        val result = Bitmap.createBitmap(original.width, original.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(result)
        val paint = Paint()
        paint.isAntiAlias = true
        canvas.drawBitmap(scaledMask, 0f, 0f, paint)
        paint.xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC_IN)
        canvas.drawBitmap(original, 0f, 0f, paint)
        if (scaledMask != mask) {
            scaledMask.recycle()
        }
        return result
    }

    suspend fun saveImage(activity: Activity): Boolean {
        val bitmap = _uiState.value.processedBitmap ?: return false
        val uri = _uiState.value.selectedImageUri ?: return false
        _uiState.update { it.copy(isLoading = true) }

        return try {
            val fileName = "PicFixPro_BG_${System.currentTimeMillis()}"
            val filePath = BitmapUtils.saveBitmap(activity, bitmap, fileName, Bitmap.CompressFormat.PNG)
            repository.addHistory(
                History(
                    operationType = "Background Removed",
                    inputFilePath = uri.toString(),
                    filePath = filePath,
                    timestamp = System.currentTimeMillis()
                )
            )
            _uiState.update { it.copy(savedFilePath = filePath) }
            true
        } catch (ce: kotlinx.coroutines.CancellationException) {
            throw ce
        } catch (e: Exception) {
            if (com.dhanuk.photodoctorpro.BuildConfig.DEBUG) {
                android.util.Log.e("RemoveBGVM", "saveImage failed", e)
            }
            _uiState.update { it.copy(error = activity.getString(com.dhanuk.photodoctorpro.R.string.save_failed_fmt, e.message)) }
            false
        } finally {
            _uiState.update { it.copy(isLoading = false) }
        }
    }

    fun reset() {
        _uiState.value.originalBitmap?.takeIf { !it.isRecycled }?.recycle()
        _uiState.value.processedBitmap?.takeIf { !it.isRecycled }?.recycle()
        _uiState.value.maskBitmap?.takeIf { !it.isRecycled }?.recycle()
        synchronized(stackLock) {
            undoStack.forEach { if (!it.isRecycled) it.recycle() }
            redoStack.forEach { if (!it.isRecycled) it.recycle() }
            undoStack.clear()
            redoStack.clear()
        }
        _uiState.value = RemoveBackgroundUiState()
    }

    fun onErrorShown() {
        _uiState.update { it.copy(error = null) }
    }

    fun onSavedMessageShown() {
         _uiState.update { it.copy(savedFilePath = null) }
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
        synchronized(stackLock) {
            undoStack.forEach { if (!it.isRecycled) it.recycle() }
            redoStack.forEach { if (!it.isRecycled) it.recycle() }
            undoStack.clear()
            redoStack.clear()
        }
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
