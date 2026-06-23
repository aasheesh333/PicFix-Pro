package com.dhanuk.photodoctorpro.ui.screens

import android.app.Activity
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Paint
import android.net.Uri
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asAndroidPath
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.dhanuk.photodoctorpro.PhotoDoctorApplication
import com.dhanuk.photodoctorpro.data.local.History
import com.dhanuk.photodoctorpro.data.repository.HistoryRepository
import com.dhanuk.photodoctorpro.utils.AdManager
import com.dhanuk.photodoctorpro.utils.BitmapUtils
import com.dhanuk.photodoctorpro.utils.viewModelExceptionHandler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.opencv.android.Utils
import org.opencv.core.Mat
import org.opencv.imgproc.Imgproc
import org.opencv.photo.Photo
import java.util.ArrayDeque
import kotlin.math.max

// Data class for a stroke
data class EraserPath(
    val path: Path,
    val strokeWidth: Float,
    val softness: Float
)

class ObjectEraserViewModel(
    private val repository: HistoryRepository,
    private val savedStateHandle: SavedStateHandle
) : ViewModel() {

    private val _uiState = MutableStateFlow(
        ObjectEraserUiState(
            selectedImageUri = savedStateHandle.get<String>(KEY_URI)?.let {
                runCatching { Uri.parse(it) }.getOrNull()
            },
            brushSize = savedStateHandle.get<Float>(KEY_BRUSH) ?: 40f,
            brushSoftness = savedStateHandle.get<Float>(KEY_SOFT) ?: 0f
        )
    )
    val uiState = _uiState.asStateFlow()

    private val undoStack = ArrayDeque<Pair<Bitmap, List<EraserPath>>>()
    private val redoStack = ArrayDeque<Pair<Bitmap, List<EraserPath>>>()
    private val stackLock = Any()
    private val MAX_STACK_SIZE = 10

    fun onImageSelected(uri: Uri, context: Context) {
        savedStateHandle[KEY_URI] = uri.toString()
        eraseJob?.cancel()
        eraseJob = null
        viewModelScope.launch(viewModelExceptionHandler("ObjectEraserVM") + Dispatchers.IO) {
            _uiState.update { it.copy(isLoading = true, progress = 0f) }
            val bitmap = BitmapUtils.loadBitmapFromUri(uri, context, 2048)
            synchronized(stackLock) {
                undoStack.forEach { (bmp, _) -> if (!bmp.isRecycled) bmp.recycle() }
                redoStack.forEach { (bmp, _) -> if (!bmp.isRecycled) bmp.recycle() }
                undoStack.clear()
                redoStack.clear()
                val oldOriginal = _uiState.value.originalBitmap
                val oldProcessed = _uiState.value.processedBitmap
                _uiState.value = ObjectEraserUiState(selectedImageUri = uri, originalBitmap = bitmap, isLoading = false, progress = 0f)
                if (oldProcessed != null && oldProcessed !== oldOriginal && !oldProcessed.isRecycled) oldProcessed.recycle()
                if (oldOriginal != null && oldOriginal !== bitmap && !oldOriginal.isRecycled) oldOriginal.recycle()
            }
        }
    }

    fun onBrushSizeChanged(newSize: Float) {
        savedStateHandle[KEY_BRUSH] = newSize
        _uiState.update { it.copy(brushSize = newSize) }
    }

    fun onBrushSoftnessChanged(newSoftness: Float) {
        savedStateHandle[KEY_SOFT] = newSoftness
        _uiState.update { it.copy(brushSoftness = newSoftness) }
    }

    fun onPathsChanged(newPaths: List<EraserPath>) {
        _uiState.update { it.copy(paths = newPaths) }
    }

    fun addPath(path: EraserPath) {
        val currentPaths = _uiState.value.paths
        _uiState.update { it.copy(paths = currentPaths + path) }
    }

    fun undo() {
        val prevBitmap: Bitmap
        val prevPaths: List<EraserPath>
        val canUndo: Boolean
        synchronized(stackLock) {
            if (undoStack.isEmpty()) return
            val currentBitmap = _uiState.value.processedBitmap ?: _uiState.value.originalBitmap
            val currentPaths = _uiState.value.paths
            if (currentBitmap != null) {
                val copy = currentBitmap.copy(currentBitmap.config ?: Bitmap.Config.ARGB_8888, true)
                if (copy != null) pushToStackLocked(redoStack, copy to currentPaths)
            }
            val prev = undoStack.removeLast()
            prevBitmap = prev.first
            prevPaths = prev.second
            canUndo = undoStack.isNotEmpty()
        }
        val old = _uiState.value.processedBitmap
        _uiState.update { it.copy(
            processedBitmap = prevBitmap,
            paths = prevPaths,
            canUndo = canUndo,
            canRedo = true
        ) }
        if (old != null && old != prevBitmap && !old.isRecycled) old.recycle()
    }

    fun redo() {
        val nextBitmap: Bitmap
        val nextPaths: List<EraserPath>
        val canRedo: Boolean
        synchronized(stackLock) {
            if (redoStack.isEmpty()) return
            val currentBitmap = _uiState.value.processedBitmap ?: _uiState.value.originalBitmap
            val currentPaths = _uiState.value.paths
            if (currentBitmap != null) {
                val copy = currentBitmap.copy(currentBitmap.config ?: Bitmap.Config.ARGB_8888, true)
                if (copy != null) pushToStackLocked(undoStack, copy to currentPaths)
            }
            val next = redoStack.removeLast()
            nextBitmap = next.first
            nextPaths = next.second
            canRedo = redoStack.isNotEmpty()
        }
        val old = _uiState.value.processedBitmap
        _uiState.update { it.copy(
            processedBitmap = nextBitmap,
            paths = nextPaths,
            canUndo = true,
            canRedo = canRedo
        ) }
        if (old != null && old != nextBitmap && !old.isRecycled) old.recycle()
    }

    fun reset() {
        val uri = _uiState.value.selectedImageUri
        val bitmap = _uiState.value.originalBitmap
        synchronized(stackLock) {
            undoStack.forEach { (bmp, _) -> if (!bmp.isRecycled) bmp.recycle() }
            redoStack.forEach { (bmp, _) -> if (!bmp.isRecycled) bmp.recycle() }
            undoStack.clear()
            redoStack.clear()
        }
        _uiState.value = ObjectEraserUiState(
            selectedImageUri = uri,
            originalBitmap = bitmap,
            resetPerformed = true,
            progress = 0f
        )
    }

    fun onResetMessageShown() {
        _uiState.update { it.copy(resetPerformed = false) }
    }

    fun eraseObjects() {
        if (_uiState.value.paths.isEmpty()) return
        eraseJob?.cancel()
        eraseJob = viewModelScope.launch(viewModelExceptionHandler("ObjectEraserVM")) {
            eraseObjectsSuspend()
        }
    }

    private var eraseJob: kotlinx.coroutines.Job? = null

    private suspend fun checkActive() = kotlinx.coroutines.currentCoroutineContext().ensureActive()

    /**
     * Suspend variant of [eraseObjects] that completes before returning.
     * Used by the unsaved-changes dialog to ensure the erase finishes before
     * the save step runs.
     */
    suspend fun eraseObjectsSuspend() {
        val sourceBitmap = _uiState.value.processedBitmap ?: _uiState.value.originalBitmap ?: return
        val paths = _uiState.value.paths
        if (paths.isEmpty()) return

        if (!PhotoDoctorApplication.OpenCVInitialized) {
            _uiState.update { it.copy(
                error = com.dhanuk.photodoctorpro.utils.getOpenCvNotReadyMessage()
            ) }
            return
        }

        _uiState.update { it.copy(isErasing = true, error = null, progress = 0f) }

        val sourceForStack = synchronized(stackLock) {
            val copy = sourceBitmap.copy(sourceBitmap.config ?: Bitmap.Config.ARGB_8888, true)
            if (copy != null) pushToStackLocked(undoStack, copy to paths)
            redoStack.clear()
        }

        var workingBitmap: Bitmap? = null
        var softMask: Bitmap? = null
        try {
            workingBitmap = if (sourceBitmap.config != Bitmap.Config.ARGB_8888 || !sourceBitmap.isMutable) {
                sourceBitmap.copy(Bitmap.Config.ARGB_8888, true)
            } else {
                sourceBitmap
            }
            if (workingBitmap == null) {
                _uiState.update { it.copy(isErasing = false, progress = 0f, error = com.dhanuk.photodoctorpro.utils.getBitmapAllocFailedMessage()) }
                return
            }
            checkActive()
            _uiState.update { it.copy(progress = 0.2f) }

            val safeWorking = workingBitmap!!
            softMask = createMask(safeWorking.width, safeWorking.height, paths)
            checkActive()
            _uiState.update { it.copy(progress = 0.5f) }

            val maxStroke = paths.maxOfOrNull { it.strokeWidth } ?: 20f
            val maxSoftness = paths.maxOfOrNull { it.softness } ?: 0f
            val dynamicRadius = max(15.0, maxStroke / 3.0) + (maxSoftness / 2.0)

            val resultBitmap = applyInpainting(safeWorking, softMask!!, dynamicRadius)
            checkActive()
            _uiState.update { it.copy(progress = 0.95f) }

            softMask = null
            if (safeWorking != sourceBitmap) safeWorking.recycle()
            workingBitmap = null

            val oldProcessed = _uiState.value.processedBitmap
            _uiState.update { it.copy(
                isErasing = false,
                processedBitmap = resultBitmap,
                paths = emptyList(),
                canUndo = true,
                canRedo = false,
                progress = 1f
            ) }
            if (oldProcessed != null && oldProcessed != resultBitmap && !oldProcessed.isRecycled) {
                oldProcessed.recycle()
            }
        } catch (ce: kotlinx.coroutines.CancellationException) {
            softMask?.recycle()
            if (workingBitmap != null && workingBitmap != sourceBitmap) workingBitmap.recycle()
            _uiState.update { it.copy(progress = 0f) }
            throw ce
        } catch (e: Exception) {
            if (com.dhanuk.photodoctorpro.BuildConfig.DEBUG) {
                android.util.Log.e("ObjectEraserVM", "eraseObjects failed", e)
            }
            if (synchronized(stackLock) { undoStack.isNotEmpty() }) synchronized(stackLock) { undoStack.removeLast() }
            _uiState.update { it.copy(isErasing = false, progress = 0f, error = e.message) }
        }
    }

    private fun pushToStackLocked(stack: ArrayDeque<Pair<Bitmap, List<EraserPath>>>, item: Pair<Bitmap, List<EraserPath>>) {
        if (stack.size >= MAX_STACK_SIZE) {
            val evicted = stack.removeFirst()
            val currentProcessed = _uiState.value.processedBitmap
            val currentOriginal = _uiState.value.originalBitmap
            if (evicted.first !== currentProcessed && evicted.first !== currentOriginal && !evicted.first.isRecycled) {
                evicted.first.recycle()
            }
        }
        stack.addLast(item)
    }

    private suspend fun createMask(width: Int, height: Int, paths: List<EraserPath>): Bitmap = withContext(Dispatchers.Default) {
        val maskBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(maskBitmap)
        // Background black (no mask)
        canvas.drawColor(android.graphics.Color.BLACK)

        val paint = Paint().apply {
            color = android.graphics.Color.WHITE
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
            isAntiAlias = true
        }

        paths.forEach { eraserPath ->
            paint.strokeWidth = eraserPath.strokeWidth

            // Apply Softness per stroke
            if (eraserPath.softness > 0) {
                val radius = eraserPath.softness + 0.1f
                paint.maskFilter = BlurMaskFilter(radius, BlurMaskFilter.Blur.NORMAL)
            } else {
                paint.maskFilter = null
            }

            canvas.drawPath(eraserPath.path.asAndroidPath(), paint)
        }

        return@withContext maskBitmap
    }

    private suspend fun applyInpainting(original: Bitmap, softMask: Bitmap, radius: Double): Bitmap = withContext(Dispatchers.Default) {
        val src = Mat()
        val softMaskMat = Mat()
        val hardMaskMat = Mat()
        val inpaintedMat = Mat()
        try {
            Utils.bitmapToMat(original, src)
            Imgproc.cvtColor(src, src, Imgproc.COLOR_BGRA2BGR)

            Utils.bitmapToMat(softMask, softMaskMat)
            Imgproc.cvtColor(softMaskMat, softMaskMat, Imgproc.COLOR_BGRA2GRAY)

            // Use a reasonable threshold (128) for the hard mask so soft edges
            // are not fully inpainted. The soft transition is handled by alpha
            // blending the original and inpainted images using the soft mask.
            Imgproc.threshold(softMaskMat, hardMaskMat, 128.0, 255.0, Imgproc.THRESH_BINARY)

            Photo.inpaint(src, hardMaskMat, inpaintedMat, radius, Photo.INPAINT_TELEA)

            // Convert inpainted BGR back to BGRA for blending
            Imgproc.cvtColor(inpaintedMat, inpaintedMat, Imgproc.COLOR_BGR2BGRA)

            val resultBitmap = Bitmap.createBitmap(inpaintedMat.cols(), inpaintedMat.rows(), Bitmap.Config.ARGB_8888)
            Utils.matToBitmap(inpaintedMat, resultBitmap)

            return@withContext resultBitmap
        } finally {
            src.release()
            softMaskMat.release()
            hardMaskMat.release()
            inpaintedMat.release()
        }
    }

    suspend fun saveImage(activity: Activity): Boolean {
        val bitmap = _uiState.value.processedBitmap ?: return false
        val uri = _uiState.value.selectedImageUri ?: return false
        _uiState.update { it.copy(isLoading = true) }

        return try {
            val fileName = "PhotoDoctorPro_Erased_${System.currentTimeMillis()}"
            val filePath = BitmapUtils.saveBitmap(activity, bitmap, fileName, Bitmap.CompressFormat.PNG)
            repository.addHistory(
                History(
                    operationType = "Object Erased",
                    inputFilePath = uri.toString(),
                    filePath = filePath,
                    timestamp = System.currentTimeMillis()
                )
            )
            AdManager.showInterstitialAd(activity)
            _uiState.update { it.copy(savedFilePath = filePath) }
            true
        } catch (ce: kotlinx.coroutines.CancellationException) {
            throw ce
        } catch (e: Exception) {
            if (com.dhanuk.photodoctorpro.BuildConfig.DEBUG) {
                android.util.Log.e("ObjectEraserVM", "saveImage failed", e)
            }
            _uiState.update { it.copy(error = "Failed to save image: ${e.message}") }
            false
        } finally {
            _uiState.update { it.copy(isLoading = false) }
        }
    }

    fun onErrorShown() {
        _uiState.update { it.copy(error = null) }
    }

    fun onSavedMessageShown() {
        _uiState.update { it.copy(savedFilePath = null) }
    }

    override fun onCleared() {
        super.onCleared()
        eraseJob?.cancel()
        eraseJob = null
        val original = _uiState.value.originalBitmap
        val processed = _uiState.value.processedBitmap
        if (processed != null && processed !== original && !processed.isRecycled) processed.recycle()
        if (original != null && !original.isRecycled) original.recycle()
        synchronized(stackLock) {
            undoStack.forEach { (bmp, _) -> if (!bmp.isRecycled) bmp.recycle() }
            redoStack.forEach { (bmp, _) -> if (!bmp.isRecycled) bmp.recycle() }
            undoStack.clear()
            redoStack.clear()
        }
    }
}

data class ObjectEraserUiState(
    val selectedImageUri: Uri? = null,
    val originalBitmap: Bitmap? = null,
    val processedBitmap: Bitmap? = null,
    val paths: List<EraserPath> = emptyList(),
    val brushSize: Float = 40f,
    val brushSoftness: Float = 0f,
    val isErasing: Boolean = false,
    val isLoading: Boolean = false,
    val error: String? = null,
    val savedFilePath: String? = null,
    val resetPerformed: Boolean = false,
    val canUndo: Boolean = false,
    val canRedo: Boolean = false,
    val progress: Float = 0f
)

private const val KEY_URI = "selectedImageUri"
private const val KEY_BRUSH = "brushSize"
private const val KEY_SOFT = "brushSoftness"
