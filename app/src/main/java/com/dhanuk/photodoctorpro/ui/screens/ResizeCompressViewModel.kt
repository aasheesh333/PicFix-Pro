package com.dhanuk.photodoctorpro.ui.screens

import android.app.Activity
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.dhanuk.photodoctorpro.data.local.AppDatabase
import com.dhanuk.photodoctorpro.data.local.History
import com.dhanuk.photodoctorpro.data.repository.HistoryRepository
import com.dhanuk.photodoctorpro.utils.BitmapSaver
import com.dhanuk.photodoctorpro.utils.viewModelExceptionHandler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream

enum class ResizePreset(val label: String, val maxDim: Int, val quality: Int) {
    SMALL("Small - 1080px", 1080, 80),
    MEDIUM("Medium - 2048px", 2048, 88),
    LARGE("Large - 3200px", 3200, 92),
    ORIGINAL("Original size", 99999, 95),
    CUSTOM("Custom", 0, 88)
}

data class ResizeUiState(
    val selectedUri: Uri? = null,
    val originalBitmap: Bitmap? = null,
    val processedBitmap: Bitmap? = null,
    val preset: ResizePreset = ResizePreset.MEDIUM,
    val quality: Float = 0.88f,
    val originalSizeBytes: Long = 0L,
    val processedSizeBytes: Long = 0L,
    val isProcessing: Boolean = false,
    val isLoading: Boolean = false,
    val error: String? = null,
    val savedFilePath: String? = null,
    val customWidth: Int = 0,
    val customHeight: Int = 0,
    val customWidthText: String = "",
    val customHeightText: String = "",
    val maintainAspectRatio: Boolean = true
)

class ResizeCompressViewModel(
    private val repository: com.dhanuk.photodoctorpro.data.repository.HistoryRepository,
    private val savedStateHandle: SavedStateHandle
) : ViewModel() {

    private val _uiState = MutableStateFlow(
        ResizeUiState(
            selectedUri = savedStateHandle.get<String>(KEY_URI)?.let {
                runCatching { Uri.parse(it) }.getOrNull()
            },
            preset = savedStateHandle.get<String>(KEY_PRESET)?.let { name ->
                runCatching { ResizePreset.valueOf(name) }.getOrNull()
            } ?: ResizePreset.MEDIUM,
            quality = savedStateHandle.get<Float>(KEY_QUALITY) ?: 0.88f,
            customWidth = savedStateHandle.get<Int>(KEY_CW) ?: 0,
            customHeight = savedStateHandle.get<Int>(KEY_CH) ?: 0,
            customWidthText = savedStateHandle.get<String>(KEY_CWT) ?: "",
            customHeightText = savedStateHandle.get<String>(KEY_CHT) ?: "",
            maintainAspectRatio = savedStateHandle.get<Boolean>(KEY_AR) ?: true
        )
    )
    val uiState: StateFlow<ResizeUiState> = _uiState.asStateFlow()

    fun onImageSelected(uri: Uri, context: Context) {
        savedStateHandle[KEY_URI] = uri.toString()
        _uiState.update { it.copy(selectedUri = uri, isLoading = true, error = null) }
        viewModelScope.launch(viewModelExceptionHandler("ResizeVM") + Dispatchers.IO) {
            try {
                val (bitmap, bytes) = run {
                    val realBytes = try {
                        context.contentResolver.openInputStream(uri)?.use { stream ->
                            stream.available().toLong()
                        } ?: 0L
                    } catch (_: Exception) { 0L }
                    val bmp = com.dhanuk.photodoctorpro.utils.BitmapUtils.loadBitmapFromUri(uri, context, 4000)
                    Pair(bmp, realBytes)
                }
                val oldOriginal = _uiState.value.originalBitmap
                val oldProcessed = _uiState.value.processedBitmap
                if (bitmap == null) {
                    _uiState.update { it.copy(error = "Could not decode image", isLoading = false) }
                    return@launch
                }
                _uiState.update {
                    it.copy(
                        originalBitmap = bitmap,
                        processedBitmap = bitmap,
                        originalSizeBytes = bytes,
                        processedSizeBytes = estimateBytes(bitmap, _uiState.value.quality),
                        customWidth = bitmap.width,
                        customHeight = bitmap.height,
                        customWidthText = bitmap.width.toString(),
                        customHeightText = bitmap.height.toString(),
                        isLoading = false
                    )
                }
                if (oldOriginal != null && oldOriginal != bitmap && !oldOriginal.isRecycled) oldOriginal.recycle()
                if (oldProcessed != null && oldProcessed != bitmap && !oldProcessed.isRecycled) oldProcessed.recycle()
            } catch (e: Exception) {
                if (com.dhanuk.photodoctorpro.BuildConfig.DEBUG) {
                    android.util.Log.e("ResizeVM", "onImageSelected failed", e)
                }
                _uiState.update { it.copy(error = e.message ?: "Load failed", isLoading = false) }
            }
        }
    }

    fun onPresetSelected(preset: ResizePreset) {
        val bitmap = _uiState.value.originalBitmap
        savedStateHandle[KEY_PRESET] = preset.name
        _uiState.update {
            it.copy(
                preset = preset,
                customWidth = bitmap?.width ?: it.customWidth,
                customHeight = bitmap?.height ?: it.customHeight,
                customWidthText = if (preset == ResizePreset.CUSTOM) (bitmap?.width?.toString() ?: "") else it.customWidthText,
                customHeightText = if (preset == ResizePreset.CUSTOM) (bitmap?.height?.toString() ?: "") else it.customHeightText
            )
        }
        applyPreset(preset, _uiState.value.quality)
    }

    fun onQualityChanged(quality: Float) {
        savedStateHandle[KEY_QUALITY] = quality
        _uiState.update { it.copy(quality = quality) }
        applyPreset(_uiState.value.preset, quality)
    }

    fun onCustomWidthChanged(text: String) {
        val w = text.toIntOrNull() ?: return
        val bitmap = _uiState.value.originalBitmap ?: return
        val h = if (_uiState.value.maintainAspectRatio && bitmap.width > 0) {
            (w.toFloat() / bitmap.width * bitmap.height).toInt()
        } else {
            _uiState.value.customHeight
        }
        savedStateHandle[KEY_CW] = w
        savedStateHandle[KEY_CWT] = text
        savedStateHandle[KEY_CH] = h
        savedStateHandle[KEY_CHT] = h.toString()
        _uiState.update { it.copy(customWidthText = text, customWidth = w, customHeight = h, customHeightText = h.toString()) }
        applyCustomResize(w, h)
    }

    fun onCustomHeightChanged(text: String) {
        val h = text.toIntOrNull() ?: return
        val bitmap = _uiState.value.originalBitmap ?: return
        val w = if (_uiState.value.maintainAspectRatio && bitmap.height > 0) {
            (h.toFloat() / bitmap.height * bitmap.width).toInt()
        } else {
            _uiState.value.customWidth
        }
        savedStateHandle[KEY_CH] = h
        savedStateHandle[KEY_CHT] = text
        savedStateHandle[KEY_CW] = w
        savedStateHandle[KEY_CWT] = w.toString()
        _uiState.update { it.copy(customHeightText = text, customHeight = h, customWidth = w, customWidthText = w.toString()) }
        applyCustomResize(w, h)
    }

    fun onMaintainAspectRatioChanged(enabled: Boolean) {
        savedStateHandle[KEY_AR] = enabled
        _uiState.update { it.copy(maintainAspectRatio = enabled) }
    }

    private var resizeJob: kotlinx.coroutines.Job? = null

    private fun applyCustomResize(width: Int, height: Int) {
        val original = _uiState.value.originalBitmap ?: return
        if (width <= 0 || height <= 0) return
        resizeJob?.cancel()
        _uiState.update { it.copy(isProcessing = true) }
        resizeJob = viewModelScope.launch(viewModelExceptionHandler("ResizeVM")) {
            try {
                val processed = withContext(Dispatchers.Default) {
                    Bitmap.createScaledBitmap(original, width, height, true)
                }
                checkActive()
                val processedBytes = estimateBytes(processed, _uiState.value.quality)
                val old = _uiState.value.processedBitmap
                _uiState.update {
                    it.copy(processedBitmap = processed, processedSizeBytes = processedBytes, isProcessing = false)
                }
                if (old != null && old != processed && old != original && !old.isRecycled) old.recycle()
            } catch (ce: kotlinx.coroutines.CancellationException) {
                throw ce
            } catch (e: Exception) {
                if (com.dhanuk.photodoctorpro.BuildConfig.DEBUG) {
                    android.util.Log.e("ResizeVM", "applyCustomResize failed", e)
                }
                _uiState.update { it.copy(isProcessing = false, error = "Resize failed: ${e.message}") }
            }
        }
    }

    private fun applyPreset(preset: ResizePreset, quality: Float) {
        val original = _uiState.value.originalBitmap ?: return
        if (preset == ResizePreset.CUSTOM) {
            val w = _uiState.value.customWidth
            val h = _uiState.value.customHeight
            if (w > 0 && h > 0) {
                applyCustomResize(w, h)
            }
            return
        }
        resizeJob?.cancel()
        _uiState.update { it.copy(isProcessing = true) }
        resizeJob = viewModelScope.launch(viewModelExceptionHandler("ResizeVM")) {
            try {
                val processed = withContext(Dispatchers.Default) {
                    resizeBitmap(original, preset.maxDim)
                }
                checkActive()
                val processedBytes = estimateBytes(processed, quality)
                val old = _uiState.value.processedBitmap
                _uiState.update {
                    it.copy(processedBitmap = processed, processedSizeBytes = processedBytes, isProcessing = false)
                }
                if (old != null && old != processed && old != original && !old.isRecycled) old.recycle()
            } catch (ce: kotlinx.coroutines.CancellationException) {
                throw ce
            } catch (e: Exception) {
                if (com.dhanuk.photodoctorpro.BuildConfig.DEBUG) {
                    android.util.Log.e("ResizeVM", "applyPreset failed", e)
                }
                _uiState.update { it.copy(isProcessing = false, error = "Resize failed: ${e.message}") }
            }
        }
    }

    private suspend fun checkActive() = kotlinx.coroutines.currentCoroutineContext().ensureActive()

    private fun estimateBytes(bitmap: Bitmap?, quality: Float): Long {
        if (bitmap == null) return 0L
        val baos = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, (quality * 100).toInt().coerceIn(1, 100), baos)
        return baos.size().toLong()
    }

    private fun resizeBitmap(source: Bitmap, maxDim: Int): Bitmap {
        if (maxDim >= 32000) return source.copy(Bitmap.Config.ARGB_8888, true)
        val w = source.width
        val h = source.height
        val scale = if (w >= h) maxDim.toFloat() / w else maxDim.toFloat() / h
        if (scale >= 1f) return source.copy(Bitmap.Config.ARGB_8888, true)
        val targetW = (w * scale).toInt().coerceAtLeast(1)
        val targetH = (h * scale).toInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(source, targetW, targetH, true)
    }

    fun saveImage(context: android.content.Context) {
        val state = _uiState.value
        val bitmap = state.processedBitmap ?: return
        viewModelScope.launch(viewModelExceptionHandler("ResizeVM") + Dispatchers.IO) {
            try {
                val format = if (state.preset == ResizePreset.ORIGINAL)
                    android.graphics.Bitmap.CompressFormat.PNG else android.graphics.Bitmap.CompressFormat.JPEG
                val savedPath = com.dhanuk.photodoctorpro.utils.UnifiedSaveHelper.saveAndRecordNoAd(
                    context = context,
                    bitmap = bitmap,
                    fileNamePrefix = "PDPro_${state.preset.name.lowercase()}",
                    operationType = "Resize (${state.preset.label})",
                    inputUriString = state.selectedUri?.toString() ?: "",
                    repository = repository,
                    format = format,
                )
                _uiState.update { it.copy(savedFilePath = savedPath) }
            } catch (e: Exception) {
                _uiState.update { it.copy(error = e.message) }
            }
        }
    }

    fun onErrorShown() { _uiState.update { it.copy(error = null) } }
    fun onSavedMessageShown() { _uiState.update { it.copy(savedFilePath = null) } }

    override fun onCleared() {
        super.onCleared()
        resizeJob?.cancel()
        resizeJob = null
        _uiState.value.originalBitmap?.takeIf { !it.isRecycled }?.recycle()
        _uiState.value.processedBitmap?.takeIf { !it.isRecycled }?.recycle()
    }
}

private const val KEY_URI = "selectedUri"
private const val KEY_PRESET = "preset"
private const val KEY_QUALITY = "quality"
private const val KEY_CW = "customWidth"
private const val KEY_CH = "customHeight"
private const val KEY_CWT = "customWidthText"
private const val KEY_CHT = "customHeightText"
private const val KEY_AR = "maintainAspectRatio"
