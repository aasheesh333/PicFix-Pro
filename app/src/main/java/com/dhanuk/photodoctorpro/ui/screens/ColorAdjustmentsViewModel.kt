package com.dhanuk.photodoctorpro.ui.screens

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.dhanuk.photodoctorpro.data.repository.HistoryRepository
import com.dhanuk.photodoctorpro.data.local.History
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
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

class ColorAdjustmentsViewModel(private val repository: com.dhanuk.photodoctorpro.data.repository.HistoryRepository) : ViewModel() {
    private val _uiState = MutableStateFlow(ColorAdjustmentsUiState())
    val uiState: StateFlow<ColorAdjustmentsUiState> = _uiState.asStateFlow()

    private val adjustmentTrigger = MutableSharedFlow<Unit>(replay = 1, extraBufferCapacity = 1)
    private var adjustmentJob: Job? = null
    private var originalBitmapCopy: Bitmap? = null

    init {
        @OptIn(FlowPreview::class)
        adjustmentJob = viewModelScope.launch {
            adjustmentTrigger
                .debounce(50)
                .collect {
                    runAdjustment()
                }
        }
    }

    fun setOriginal(uri: Uri, context: Context) {
        _uiState.value = _uiState.value.copy(selectedImageUri = uri, isLoading = true, error = null)
        viewModelScope.launch {
            try {
                val bitmap = withContext(Dispatchers.IO) {
                    context.contentResolver.openInputStream(uri)?.use { stream ->
                        BitmapFactory.decodeStream(stream)
                    }
                }
                if (bitmap == null) {
                    _uiState.update { it.copy(error = "Could not decode image", isLoading = false) }
                    return@launch
                }
                val argb = bitmap.copy(Bitmap.Config.ARGB_8888, true)
                if (argb == null) {
                    if (!bitmap.isRecycled) bitmap.recycle()
                    _uiState.update { it.copy(error = "Could not allocate bitmap", isLoading = false) }
                    return@launch
                }
                val newOriginal = argb
                originalBitmapCopy?.takeIf { !it.isRecycled }?.recycle()
                if (argb != bitmap && !bitmap.isRecycled) bitmap.recycle()
                originalBitmapCopy = newOriginal
                _uiState.update {
                    it.copy(
                        selectedImageUri = uri,
                        originalBitmap = newOriginal,
                        processedBitmap = newOriginal,
                        isLoading = false
                    )
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
        _uiState.update { it.copy(brightness = value) }
        adjustmentTrigger.tryEmit(Unit)
    }

    fun updateContrast(value: Float) {
        _uiState.update { it.copy(contrast = value) }
        adjustmentTrigger.tryEmit(Unit)
    }

    fun updateSaturation(value: Float) {
        _uiState.update { it.copy(saturation = value) }
        adjustmentTrigger.tryEmit(Unit)
    }

    fun updateWarmth(value: Float) {
        _uiState.update { it.copy(warmth = value) }
        adjustmentTrigger.tryEmit(Unit)
    }

    fun reset() {
        _uiState.update {
            it.copy(
                brightness = 0f,
                contrast = 1f,
                saturation = 1f,
                warmth = 0f
            )
        }
        adjustmentTrigger.tryEmit(Unit)
    }

    private suspend fun runAdjustment() {
        val state = _uiState.value
        val original = state.originalBitmap ?: return
        try {
            val output = withContext(Dispatchers.Default) {
                applyColorMatrix(original, state.brightness, state.contrast, state.saturation, state.warmth)
            }
            val old = _uiState.value.processedBitmap
            _uiState.update { it.copy(processedBitmap = output) }
            if (old != null && old != output && !old.isRecycled) old.recycle()
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
        viewModelScope.launch {
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
        _uiState.value.originalBitmap?.takeIf { !it.isRecycled }?.recycle()
        _uiState.value.processedBitmap?.takeIf { !it.isRecycled && it != originalBitmapCopy }?.recycle()
        originalBitmapCopy?.takeIf { !it.isRecycled }?.recycle()
        originalBitmapCopy = null
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
