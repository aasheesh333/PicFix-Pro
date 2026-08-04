package com.dhanuk.photodoctorpro.ui.screens

import android.app.Activity
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.dhanuk.photodoctorpro.data.local.History
import com.dhanuk.photodoctorpro.data.repository.HistoryRepository
import com.dhanuk.photodoctorpro.utils.AdManager
import com.dhanuk.photodoctorpro.utils.BitmapUtils
import com.dhanuk.photodoctorpro.utils.ImageEnhancer
import com.dhanuk.photodoctorpro.utils.viewModelExceptionHandler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class EnhanceImageViewModel(
    private val repository: HistoryRepository,
    private val savedStateHandle: SavedStateHandle
) : ViewModel() {

    private val _uiState = MutableStateFlow(
        EnhanceImageUiState(
            selectedImageUri = savedStateHandle.get<String>(KEY_URI)?.let {
                runCatching { Uri.parse(it) }.getOrNull()
            },
            scaleFactor = savedStateHandle.get<Int>(KEY_SCALE) ?: 2,
            qualityMode = savedStateHandle.get<String>(KEY_QUALITY_MODE) ?: "standard"
        )
    )
    val uiState = _uiState.asStateFlow()

    private var enhanceJob: kotlinx.coroutines.Job? = null

    fun onImageSelected(uri: Uri, context: Context) {
        savedStateHandle[KEY_URI] = uri.toString()
        viewModelScope.launch(viewModelExceptionHandler("EnhanceVM") + Dispatchers.IO) {
            _uiState.update { it.copy(isLoading = true, error = null, selectedImageUri = uri) }
            try {
                val bitmap = BitmapUtils.loadBitmapFromUri(uri, context)
                if (bitmap != null) {
                    val oldOriginal = _uiState.value.originalBitmap
                    val oldEnhanced = _uiState.value.enhancedBitmap
                    _uiState.update { it.copy(
                        originalBitmap = bitmap,
                        enhancedBitmap = null,
                        isLoading = false,
                        progress = 0f,
                        savedFilePath = null,
                        selectedImageUri = uri
                    ) }
                    if (oldOriginal != null && oldOriginal !== bitmap && !oldOriginal.isRecycled) oldOriginal.recycle()
                    if (oldEnhanced != null && oldEnhanced !== bitmap && !oldEnhanced.isRecycled) oldEnhanced.recycle()
                } else {
                    _uiState.update { it.copy(isLoading = false, error = context.getString(com.dhanuk.photodoctorpro.R.string.image_load_failed), selectedImageUri = uri) }
                }
            } catch (ce: kotlinx.coroutines.CancellationException) {
                throw ce
            } catch (e: Exception) {
                if (com.dhanuk.photodoctorpro.BuildConfig.DEBUG) {
                    android.util.Log.e("EnhanceVM", "onImageSelected failed", e)
                }
                _uiState.update { it.copy(isLoading = false, error = context.getString(com.dhanuk.photodoctorpro.R.string.image_load_failed_with_reason, e.message), selectedImageUri = uri) }
            }
        }
    }

    fun setQualityMode(mode: String) {
        savedStateHandle[KEY_QUALITY_MODE] = mode
        _uiState.update { it.copy(qualityMode = mode, isModelReady = false) }
    }

    fun enhanceImage(context: Context, scaleFactor: Int) {
        val original = _uiState.value.originalBitmap ?: return
        if (original.isRecycled) return

        val maxOutputPixels = maxOutputPixelsForDevice(context)
        val outputPixels = original.width.toLong() * original.height.toLong() * scaleFactor.toLong()
        if (outputPixels > maxOutputPixels) {
            _uiState.update { it.copy(
                error = context.getString(com.dhanuk.photodoctorpro.R.string.enhance_too_large, scaleFactor)
            ) }
            return
        }

        _uiState.update { it.copy(isLoading = true, error = null, progress = 0f) }

        enhanceJob?.cancel()
        enhanceJob = viewModelScope.launch(viewModelExceptionHandler("EnhanceVM")) {
            try {
                val modelDir = _uiState.value.qualityMode
                if (!_uiState.value.isModelReady) {
                    ImageEnhancer.initializeIfNeeded(context, modelDir)
                    _uiState.update { it.copy(isModelReady = true) }
                }

                val enhanced = ImageEnhancer.enhanceImage(context, original, scaleFactor) { progress ->
                    _uiState.update { it.copy(progress = progress) }
                }
                checkActive()

                val oldEnhanced = _uiState.value.enhancedBitmap
                savedStateHandle[KEY_SCALE] = scaleFactor
                _uiState.update { it.copy(
                    enhancedBitmap = enhanced,
                    isLoading = false,
                    scaleFactor = scaleFactor,
                    progress = 1f,
                    engineInfo = com.dhanuk.photodoctorpro.nativ.RealESRGANNativeLib.engineInfo
                ) }
                if (oldEnhanced != null && oldEnhanced !== original && oldEnhanced !== enhanced && !oldEnhanced.isRecycled) oldEnhanced.recycle()
            } catch (ce: kotlinx.coroutines.CancellationException) {
                // Tell native code to stop the tile loop immediately, otherwise
                // it keeps running with the input bitmap's pixels locked and the
                // phone hangs at 100% CPU even after the app is closed.
                try { com.dhanuk.photodoctorpro.nativ.RealESRGANNativeLib.cancelEnhance() } catch (_: Exception) {}
                _uiState.update { it.copy(isLoading = false, progress = 0f) }
                throw ce
            } catch (e: Exception) {
                if (com.dhanuk.photodoctorpro.BuildConfig.DEBUG) {
                    android.util.Log.e("EnhanceVM", "enhanceImage failed", e)
                }
                _uiState.update { it.copy(
                    isLoading = false,
                    progress = 0f,
                    error = context.getString(com.dhanuk.photodoctorpro.R.string.enhance_failed_fmt, e.localizedMessage ?: e.javaClass.simpleName)
                ) }
            }
        }
    }

    private suspend fun checkActive() = kotlinx.coroutines.currentCoroutineContext().ensureActive()

    /**
     * Output-pixel budget derived from the device's per-app memory class. Prevents OOM
     * when the user requests a large upscale (e.g. 8x) on a big source bitmap.
     */
    private fun maxOutputPixelsForDevice(context: Context): Long {
        val activityManager = context.getSystemService(android.content.Context.ACTIVITY_SERVICE) as? android.app.ActivityManager
        // The manifest sets android:largeHeap="true", so getLargeMemoryClass()
        // reflects the real budget (previously getMemoryClass() under-estimated
        // and every 2x of a normal photo was rejected as "too big").
        val memoryClassMb = activityManager?.largeMemoryClass ?: 256
        // Reserve ~60% of the heap for one ARGB_8888 output bitmap (4 bytes/px).
        val usableBytes = (memoryClassMb.toLong() * 1024L * 1024L * 60L) / 100L
        return (usableBytes / 4L).coerceAtLeast(8_000_000L)
    }

    suspend fun saveImage(activity: Activity, options: com.dhanuk.photodoctorpro.utils.SaveOptions = com.dhanuk.photodoctorpro.utils.SaveOptions()): Boolean {
        val state = _uiState.value
        val bitmap = state.enhancedBitmap ?: return false
        val uri = state.selectedImageUri ?: return false
        _uiState.update { it.copy(isLoading = true) }

        return try {
            val baseName = options.fileNameHint?.takeIf { it.isNotBlank() }
                ?: "PicFixPro_Enhanced_${System.currentTimeMillis()}"
            val filePath = BitmapUtils.saveBitmap(activity, bitmap, baseName, options)
            repository.addHistory(
                History(
                    operationType = "Enhance x${_uiState.value.scaleFactor}",
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
                android.util.Log.e("EnhanceVM", "saveImage failed", e)
            }
            _uiState.update { it.copy(error = activity.getString(com.dhanuk.photodoctorpro.R.string.save_failed_fmt, e.message)) }
            false
        } finally {
            _uiState.update { it.copy(isLoading = false) }
        }
    }

    fun reset() {
        val oldState = _uiState.value
        val enhanced = oldState.enhancedBitmap
        val original = oldState.originalBitmap
        if (enhanced != null && enhanced !== original && !enhanced.isRecycled) enhanced.recycle()
        if (original != null && !original.isRecycled) original.recycle()
        _uiState.value = EnhanceImageUiState(scaleFactor = oldState.scaleFactor, qualityMode = oldState.qualityMode)
    }

    fun onErrorShown() {
        _uiState.update { it.copy(error = null) }
    }

    fun onSavedMessageShown() {
         _uiState.update { it.copy(savedFilePath = null) }
    }

    override fun onCleared() {
        super.onCleared()
        // Signal native cancellation first so the tile loop sees the flag before
        // the coroutine teardown completes.
        try { com.dhanuk.photodoctorpro.nativ.RealESRGANNativeLib.cancelEnhance() } catch (_: Exception) {}
        enhanceJob?.cancel()
        enhanceJob = null
        // Do not call ImageEnhancer.shutdown() here: onCleared fires on back-navigation,
        // which would tear down the shared native net mid-inference (use-after-free) and
        // force a costly re-init on return. MainActivity.onDestroy handles real teardown.
        val state = _uiState.value
        val original = state.originalBitmap
        val enhanced = state.enhancedBitmap
        if (enhanced != null && enhanced !== original && !enhanced.isRecycled) enhanced.recycle()
        if (original != null && !original.isRecycled) original.recycle()
    }
}

data class EnhanceImageUiState(
    val selectedImageUri: Uri? = null,
    val originalBitmap: Bitmap? = null,
    val enhancedBitmap: Bitmap? = null,
    val fullResBitmap: Bitmap? = null,
    val scaleFactor: Int = 2,
    val isLoading: Boolean = false,
    val error: String? = null,
    val savedFilePath: String? = null,
    val progress: Float = 0f,
    val qualityMode: String = "standard",
    val isModelReady: Boolean = false,
    val engineInfo: String = ""
)

private const val KEY_URI = "selectedImageUri"
private const val KEY_SCALE = "scaleFactor"
private const val KEY_QUALITY_MODE = "qualityMode"
