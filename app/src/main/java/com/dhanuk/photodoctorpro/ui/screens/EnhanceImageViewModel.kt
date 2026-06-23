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
            scaleFactor = savedStateHandle.get<Int>(KEY_SCALE) ?: 2
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
                    val isLarge = (bitmap.width.toLong() * bitmap.height.toLong()) > 25_000_000
                    // Free the old bitmaps (if any) before replacing.
                    val oldOriginal = _uiState.value.originalBitmap
                    val oldEnhanced = _uiState.value.enhancedBitmap
                    _uiState.update { it.copy(
                        originalBitmap = bitmap,
                        enhancedBitmap = null,
                        isLoading = false,
                        isLargeImage = isLarge,
                        progress = 0f,
                        savedFilePath = null,
                        selectedImageUri = uri
                    ) }
                    if (oldOriginal != null && oldOriginal !== bitmap && !oldOriginal.isRecycled) oldOriginal.recycle()
                    if (oldEnhanced != null && oldEnhanced !== bitmap && !oldEnhanced.isRecycled) oldEnhanced.recycle()
                } else {
                    _uiState.update { it.copy(isLoading = false, error = "Failed to load image", selectedImageUri = uri) }
                }
            } catch (ce: kotlinx.coroutines.CancellationException) {
                throw ce
            } catch (e: Exception) {
                if (com.dhanuk.photodoctorpro.BuildConfig.DEBUG) {
                    android.util.Log.e("EnhanceVM", "onImageSelected failed", e)
                }
                _uiState.update { it.copy(isLoading = false, error = "Load failed: ${e.message}", selectedImageUri = uri) }
            }
        }
    }

    fun enhanceImage(context: Context, scaleFactor: Int) {
        val state = _uiState.value
        if (state.isLargeImage && scaleFactor > 4) {
            _uiState.value = state.copy(error = "Higher scales disabled for very large images.")
            return
        }

        val original = state.originalBitmap ?: return
        if (original.isRecycled) return
        if (state.isLargeImage && scaleFactor > 4) {
            _uiState.value = state.copy(error = com.dhanuk.photodoctorpro.utils.getHigherScalesDisabledMessage())
            return
        }

        _uiState.value = state.copy(isLoading = true, error = null, progress = 0f)

        enhanceJob?.cancel()
        enhanceJob = viewModelScope.launch(viewModelExceptionHandler("EnhanceVM")) {
            try {
                val enhanced = ImageEnhancer.enhanceImage(context, original, scaleFactor) { progress ->
                    _uiState.update { it.copy(progress = progress) }
                }
                checkActive()

                // Downscale for safe display: cap the long edge to 4096 px
                // to prevent OOM when the UI renders the bitmap. The full
                // resolution is preserved for saving.
                val displayBitmap = if (maxOf(enhanced.width, enhanced.height) > 4096) {
                    val scale = 4096f / maxOf(enhanced.width, enhanced.height)
                    val w = (enhanced.width * scale).toInt().coerceAtLeast(1)
                    val h = (enhanced.height * scale).toInt().coerceAtLeast(1)
                    val downscaled = Bitmap.createScaledBitmap(enhanced, w, h, true)
                    // Keep the enhanced bitmap for save, use downscaled for display
                    downscaled
                } else {
                    enhanced
                }

                if (displayBitmap != enhanced) {
                    // Store full-res for saving, downscaled for display
                    val oldEnhance = _uiState.value.enhancedBitmap
                    savedStateHandle[KEY_SCALE] = scaleFactor
                    _uiState.update { it.copy(
                        enhancedBitmap = displayBitmap,
                        fullResBitmap = enhanced,
                        isLoading = false,
                        scaleFactor = scaleFactor,
                        progress = 1f
                    ) }
                    if (oldEnhance != null && oldEnhance !== original && oldEnhance != displayBitmap && !oldEnhance.isRecycled) oldEnhance.recycle()
                } else {
                    val old = _uiState.value.enhancedBitmap
                    savedStateHandle[KEY_SCALE] = scaleFactor
                    _uiState.update { it.copy(
                        enhancedBitmap = enhanced,
                        isLoading = false,
                        scaleFactor = scaleFactor,
                        progress = 1f
                    ) }
                    if (old != null && old !== original && old != enhanced && !old.isRecycled) old.recycle()
                }
            } catch (ce: kotlinx.coroutines.CancellationException) {
                throw ce
            } catch (e: Exception) {
                if (com.dhanuk.photodoctorpro.BuildConfig.DEBUG) {
                    android.util.Log.e("EnhanceVM", "enhanceImage failed", e)
                }
                _uiState.update { it.copy(
                    isLoading = false,
                    progress = 0f,
                    error = "Enhance failed: ${e.localizedMessage ?: e.javaClass.simpleName}"
                ) }
            }
        }
    }

    private suspend fun checkActive() = kotlinx.coroutines.currentCoroutineContext().ensureActive()

    suspend fun saveImage(activity: Activity): Boolean {
        // Prefer full-res bitmap for saving if available, fall back to display bitmap
        val state = _uiState.value
        val bitmap = state.fullResBitmap ?: state.enhancedBitmap ?: return false
        val uri = state.selectedImageUri ?: return false
        _uiState.update { it.copy(isLoading = true) }

        return try {
            val fileName = "PhotoDoctorPro_Enhanced_${System.currentTimeMillis()}"
            val filePath = BitmapUtils.saveBitmap(activity, bitmap, fileName, Bitmap.CompressFormat.PNG)
            repository.addHistory(
                History(
                    operationType = "Enhance x${_uiState.value.scaleFactor}",
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
                android.util.Log.e("EnhanceVM", "saveImage failed", e)
            }
            _uiState.update { it.copy(error = "Failed to save: ${e.message}") }
            false
        } finally {
            _uiState.update { it.copy(isLoading = false) }
        }
    }

    fun reset() {
        val oldState = _uiState.value
        val fullRes = oldState.fullResBitmap
        val enhanced = oldState.enhancedBitmap
        val original = oldState.originalBitmap
        if (fullRes != null && fullRes !== enhanced && !fullRes.isRecycled) fullRes.recycle()
        if (enhanced != null && enhanced !== original && enhanced !== fullRes && !enhanced.isRecycled) enhanced.recycle()
        if (original != null && !original.isRecycled) original.recycle()
        _uiState.value = EnhanceImageUiState(scaleFactor = oldState.scaleFactor)
    }

    fun onErrorShown() {
        _uiState.update { it.copy(error = null) }
    }

    fun onSavedMessageShown() {
         _uiState.update { it.copy(savedFilePath = null) }
    }

    override fun onCleared() {
        super.onCleared()
        enhanceJob?.cancel()
        enhanceJob = null
        val state = _uiState.value
        val original = state.originalBitmap
        val enhanced = state.enhancedBitmap
        val fullRes = state.fullResBitmap
        if (fullRes != null && fullRes !== enhanced && !fullRes.isRecycled) fullRes.recycle()
        if (enhanced != null && enhanced !== original && enhanced !== fullRes && !enhanced.isRecycled) enhanced.recycle()
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
    val isLargeImage: Boolean = false,
    val progress: Float = 0f
)

private const val KEY_URI = "selectedImageUri"
private const val KEY_SCALE = "scaleFactor"
