package com.dhanuk.photodoctorpro.ui.screens

import android.app.Activity
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.dhanuk.photodoctorpro.data.local.History
import com.dhanuk.photodoctorpro.data.repository.HistoryRepository
import com.dhanuk.photodoctorpro.utils.AdManager
import com.dhanuk.photodoctorpro.utils.BitmapUtils
import com.dhanuk.photodoctorpro.utils.ImageEnhancer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class EnhanceImageViewModel(private val repository: HistoryRepository) : ViewModel() {

    private val _uiState = MutableStateFlow(EnhanceImageUiState())
    val uiState = _uiState.asStateFlow()

    private var enhanceJob: kotlinx.coroutines.Job? = null

    fun onImageSelected(uri: Uri, context: Context) {
        viewModelScope.launch {
            _uiState.value = EnhanceImageUiState(selectedImageUri = uri, isLoading = true)
            try {
                val bitmap = BitmapUtils.loadBitmapFromUri(uri, context)
                if (bitmap != null) {
                    val isLarge = (bitmap.width.toLong() * bitmap.height.toLong()) > 25_000_000
                    val old = _uiState.value.originalBitmap
                    _uiState.value = _uiState.value.copy(
                        originalBitmap = bitmap,
                        isLoading = false,
                        isLargeImage = isLarge
                    )
                    if (old != null && old != bitmap && !old.isRecycled) old.recycle()
                } else {
                    _uiState.value = _uiState.value.copy(isLoading = false, error = "Failed to load image")
                }
            } catch (e: Exception) {
                if (com.dhanuk.photodoctorpro.BuildConfig.DEBUG) {
                    android.util.Log.e("EnhanceVM", "onImageSelected failed", e)
                }
                _uiState.value = _uiState.value.copy(isLoading = false, error = "Load failed: ${e.message}")
            }
        }
    }

    fun enhanceImage(context: Context, scaleFactor: Int) {
        if (_uiState.value.isLargeImage && scaleFactor > 4) {
            _uiState.value = _uiState.value.copy(error = "Higher scales disabled for very large images.")
            return
        }

        val original = _uiState.value.originalBitmap ?: return
        _uiState.value = _uiState.value.copy(isLoading = true, error = null)

        enhanceJob?.cancel()
        enhanceJob = viewModelScope.launch {
            try {
                val enhanced = ImageEnhancer.enhanceImage(context, original, scaleFactor)
checkActive()
                val old = _uiState.value.enhancedBitmap
                _uiState.value = _uiState.value.copy(
                    enhancedBitmap = enhanced,
                    isLoading = false,
                    scaleFactor = scaleFactor
                )
                if (old != null && old != enhanced && !old.isRecycled) old.recycle()
            } catch (ce: kotlinx.coroutines.CancellationException) {
                throw ce
            } catch (e: Throwable) {
                if (com.dhanuk.photodoctorpro.BuildConfig.DEBUG) {
                    android.util.Log.e("EnhanceVM", "enhanceImage failed", e)
                }
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    error = "Error: ${e.localizedMessage ?: "Unknown error"}"
                )
            }
        }
    }

    private suspend fun checkActive() = kotlinx.coroutines.currentCoroutineContext().ensureActive()

    suspend fun saveImage(activity: Activity): Boolean {
        val bitmap = _uiState.value.enhancedBitmap ?: return false
        val uri = _uiState.value.selectedImageUri ?: return false
        _uiState.value = _uiState.value.copy(isLoading = true)

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
            _uiState.value = _uiState.value.copy(savedFilePath = filePath)
            true
        } catch (e: Exception) {
            if (com.dhanuk.photodoctorpro.BuildConfig.DEBUG) {
                android.util.Log.e("EnhanceVM", "saveImage failed", e)
            }
            _uiState.value = _uiState.value.copy(error = "Failed to save: ${e.message}")
            false
        } finally {
            _uiState.value = _uiState.value.copy(isLoading = false)
        }
    }

    fun reset() {
        _uiState.value = EnhanceImageUiState()
    }

    fun onErrorShown() {
        _uiState.value = _uiState.value.copy(error = null)
    }

    fun onSavedMessageShown() {
         _uiState.value = _uiState.value.copy(savedFilePath = null)
    }

    override fun onCleared() {
        super.onCleared()
        enhanceJob?.cancel()
        enhanceJob = null
        _uiState.value.originalBitmap?.takeIf { !it.isRecycled }?.recycle()
        _uiState.value.enhancedBitmap?.takeIf { !it.isRecycled }?.recycle()
    }
}

data class EnhanceImageUiState(
    val selectedImageUri: Uri? = null,
    val originalBitmap: Bitmap? = null,
    val enhancedBitmap: Bitmap? = null,
    val scaleFactor: Int = 2,
    val isLoading: Boolean = false,
    val error: String? = null,
    val savedFilePath: String? = null,
    val isLargeImage: Boolean = false
)
