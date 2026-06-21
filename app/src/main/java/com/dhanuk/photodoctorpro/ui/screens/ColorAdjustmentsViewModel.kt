package com.dhanuk.photodoctorpro.ui.screens

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.net.Uri
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.dhanuk.photodoctorpro.data.repository.HistoryRepository
import com.dhanuk.photodoctorpro.data.local.History
import com.dhanuk.photodoctorpro.utils.viewModelExceptionHandler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class ColorAdjustmentsUiState(
    val selectedImageUri: Uri? = null,
    val originalBitmap: Bitmap? = null,
    val processedBitmap: Bitmap? = null,
    val brightness: Float = 0f,
    val contrast: Float = 1f,
    val saturation: Float = 1f,
    val warmth: Float = 0f,
    val isLoading: Boolean = false,
    val error: String? = null,
    val savedFilePath: String? = null
)

class ColorAdjustmentsViewModel(
    private val repository: com.dhanuk.photodoctorpro.data.repository.HistoryRepository,
    private val savedStateHandle: SavedStateHandle
) : ViewModel() {
    private val _uiState = MutableStateFlow(
        ColorAdjustmentsUiState(
            selectedImageUri = savedStateHandle.get<String>(KEY_URI)?.let {
                runCatching { Uri.parse(it) }.getOrNull()
            },
            brightness = savedStateHandle.get<Float>(KEY_BRIGHT) ?: 0f,
            contrast = savedStateHandle.get<Float>(KEY_CONTRAST) ?: 1f,
            saturation = savedStateHandle.get<Float>(KEY_SAT) ?: 1f,
            warmth = savedStateHandle.get<Float>(KEY_WARM) ?: 0f
        )
    )
    val uiState: StateFlow<ColorAdjustmentsUiState> = _uiState.asStateFlow()

    private val adjustmentTrigger = MutableSharedFlow<Unit>(replay = 0, extraBufferCapacity = 16, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    private var adjustmentJob: Job? = null

    init {
        @OptIn(FlowPreview::class)
        adjustmentJob = viewModelScope.launch(viewModelExceptionHandler("ColorVM")) {
            adjustmentTrigger
                .debounce(50)
                .collect {
                    runAdjustment()
                }
        }
    }

    fun setOriginal(uri: Uri, context: Context) {
        savedStateHandle[KEY_URI] = uri.toString()
        _uiState.value = _uiState.value.copy(selectedImageUri = uri, isLoading = true, error = null, processedBitmap = null)
        viewModelScope.launch(viewModelExceptionHandler("ColorVM") + Dispatchers.IO) {
            try {
                val bitmap = com.dhanuk.photodoctorpro.utils.BitmapUtils.loadBitmapFromUri(uri, context, 3000)
                if (bitmap == null) {
                    _uiState.update { it.copy(error = "Could not decode image", isLoading = false) }
                    return@launch
                }
                val argb = if (bitmap.config != Bitmap.Config.ARGB_8888 || !bitmap.isMutable) {
                    bitmap.copy(Bitmap.Config.ARGB_8888, true)
                } else {
                    bitmap
                }
                if (argb == null) {
                    bitmap.takeIf { !it.isRecycled }?.recycle()
                    _uiState.update { it.copy(error = "Could not allocate bitmap", isLoading = false) }
                    return@launch
                }
                if (argb != bitmap && !bitmap.isRecycled) bitmap.recycle()
                val oldOriginal = _uiState.value.originalBitmap
                val oldProcessed = _uiState.value.processedBitmap
                _uiState.update {
                    it.copy(
                        selectedImageUri = uri,
                        originalBitmap = argb,
                        processedBitmap = argb,
                        isLoading = false
                    )
                }
                // Recycle only the old *original* if it's no longer referenced; processedBitmap == argb now
                if (oldOriginal != null && oldOriginal !== argb && !oldOriginal.isRecycled) oldOriginal.recycle()
                if (oldProcessed != null && oldProcessed !== argb && oldProcessed !== oldOriginal && !oldProcessed.isRecycled) {
                    oldProcessed.recycle()
                }
            } catch (e: Exception) {
                if (com.dhanuk.photodoctorpro.BuildConfig.DEBUG) {
                    android.util.Log.e("ColorVM", "setOriginal failed", e)
                }
                _uiState.update { it.copy(error = e.message ?: "Failed to load", isLoading = false) }
            }
        }
    }

    fun updateBrightness(value: Float) {
        savedStateHandle[KEY_BRIGHT] = value
        _uiState.update { it.copy(brightness = value) }
        adjustmentTrigger.tryEmit(Unit)
    }

    fun updateContrast(value: Float) {
        savedStateHandle[KEY_CONTRAST] = value
        _uiState.update { it.copy(contrast = value) }
        adjustmentTrigger.tryEmit(Unit)
    }

    fun updateSaturation(value: Float) {
        savedStateHandle[KEY_SAT] = value
        _uiState.update { it.copy(saturation = value) }
        adjustmentTrigger.tryEmit(Unit)
    }

    fun updateWarmth(value: Float) {
        savedStateHandle[KEY_WARM] = value
        _uiState.update { it.copy(warmth = value) }
        adjustmentTrigger.tryEmit(Unit)
    }

    fun reset() {
        val oldProcessed = _uiState.value.processedBitmap
        val original = _uiState.value.originalBitmap
        savedStateHandle[KEY_BRIGHT] = 0f
        savedStateHandle[KEY_CONTRAST] = 1f
        savedStateHandle[KEY_SAT] = 1f
        savedStateHandle[KEY_WARM] = 0f
        _uiState.update {
            it.copy(
                brightness = 0f,
                contrast = 1f,
                saturation = 1f,
                warmth = 0f,
                processedBitmap = original
            )
        }
        if (oldProcessed != null && oldProcessed !== original && !oldProcessed.isRecycled) oldProcessed.recycle()
        adjustmentTrigger.tryEmit(Unit)
    }

    private suspend fun runAdjustment() {
        val state = _uiState.value
        val original = state.originalBitmap ?: return
        if (original.isRecycled) return
        try {
            val output = withContext(Dispatchers.Default) {
                applyColorMatrix(original, state.brightness, state.contrast, state.saturation, state.warmth)
            }
            val old = _uiState.value.processedBitmap
            _uiState.update { it.copy(processedBitmap = output) }
            // NEVER recycle the original bitmap (it's still in use as the source).
            // NEVER recycle `output` (it's the current processedBitmap).
            if (old != null && old !== original && old !== output && !old.isRecycled) {
                old.recycle()
            }
        } catch (ce: kotlinx.coroutines.CancellationException) {
            throw ce
        } catch (e: Exception) {
            if (com.dhanuk.photodoctorpro.BuildConfig.DEBUG) {
                android.util.Log.e("ColorVM", "applyAdjustments failed", e)
            }
            _uiState.update { it.copy(error = "Adjustment failed: ${e.message}") }
        }
    }

    fun onErrorShown() { _uiState.update { it.copy(error = null) } }
    fun onSavedMessageShown() { _uiState.update { it.copy(savedFilePath = null) } }

    fun saveImage(context: android.content.Context) {
        val state = _uiState.value
        val bitmap = state.processedBitmap ?: return
        viewModelScope.launch(viewModelExceptionHandler("ColorVM") + Dispatchers.IO) {
            try {
                val savedPath = com.dhanuk.photodoctorpro.utils.UnifiedSaveHelper.saveAndRecordNoAd(
                    context = context,
                    bitmap = bitmap,
                    fileNamePrefix = "PhotoDoctorPro_Color",
                    operationType = "Color Adjustments",
                    inputUriString = state.selectedImageUri?.toString() ?: "",
                    repository = repository,
                    format = android.graphics.Bitmap.CompressFormat.PNG
                )
                _uiState.update { it.copy(savedFilePath = savedPath) }
            } catch (e: Exception) {
                if (com.dhanuk.photodoctorpro.BuildConfig.DEBUG) {
                    android.util.Log.e("ColorVM", "saveImage failed", e)
                }
                _uiState.update { it.copy(error = e.message ?: "Save failed") }
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        adjustmentJob?.cancel()
        adjustmentJob = null
        val original = _uiState.value.originalBitmap
        val processed = _uiState.value.processedBitmap
        // Recycle only distinct bitmap instances. originalBitmap and processedBitmap
        // may be the same reference right after setOriginal() — do not double-recycle.
        if (processed != null && processed !== original && !processed.isRecycled) {
            processed.recycle()
        }
        if (original != null && !original.isRecycled) {
            original.recycle()
        }
    }

    companion object {
        fun applyColorMatrix(
            source: Bitmap,
            brightness: Float,
            contrast: Float,
            saturation: Float,
            warmth: Float
        ): Bitmap {
            val output = Bitmap.createBitmap(source.width, source.height, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(output)
            val paint = Paint().apply {
                isAntiAlias = true
                val cm = ColorMatrix()
                cm.setSaturation(saturation)
                val contrastMatrix = ColorMatrix(
                    floatArrayOf(
                        contrast, 0f, 0f, 0f, (brightness * 128),
                        0f, contrast, 0f, 0f, (brightness * 128),
                        0f, 0f, contrast, 0f, (brightness * 128),
                        0f, 0f, 0f, 1f, 0f
                    )
                )
                cm.postConcat(contrastMatrix)
                val warmthValue = warmth * 30
                val warmthMatrix = ColorMatrix(
                    floatArrayOf(
                        1f, 0f, 0f, 0f, warmthValue,
                        0f, 1f, 0f, 0f, 0f,
                        0f, 0f, 1f, 0f, -warmthValue,
                        0f, 0f, 0f, 1f, 0f
                    )
                )
                cm.postConcat(warmthMatrix)
                colorFilter = ColorMatrixColorFilter(cm)
            }
            canvas.drawBitmap(source, 0f, 0f, paint)
            return output
        }
    }
}

private const val KEY_URI = "selectedImageUri"
private const val KEY_BRIGHT = "brightness"
private const val KEY_CONTRAST = "contrast"
private const val KEY_SAT = "saturation"
private const val KEY_WARM = "warmth"
