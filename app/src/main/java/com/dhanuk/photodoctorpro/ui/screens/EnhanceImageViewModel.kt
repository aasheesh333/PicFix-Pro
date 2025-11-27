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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class EnhanceImageViewModel(private val repository: HistoryRepository) : ViewModel() {

    private val _uiState = MutableStateFlow(EnhanceImageUiState())
    val uiState = _uiState.asStateFlow()

    fun onImageSelected(uri: Uri, context: Context) {
        viewModelScope.launch {
            val bitmap = BitmapUtils.loadBitmapFromUri(uri, context)
            if (bitmap != null) {
                val showSuggestion = bitmap.width < 1280 || bitmap.height < 1280
                _uiState.value = EnhanceImageUiState(
                    selectedImageUri = uri,
                    originalBitmap = bitmap,
                    showEnhanceSuggestion = showSuggestion
                )
            } else {
                _uiState.value = _uiState.value.copy(error = "Failed to load image.")
            }
        }
    }

    fun enhanceImage(activity: Activity) {
        val bitmap = _uiState.value.originalBitmap ?: return
        _uiState.value = _uiState.value.copy(isEnhancing = true, error = null)

        viewModelScope.launch {
            try {
                // Use the new helper with OpenCV
                val enhancedBitmap = withContext(Dispatchers.Default) {
                    ImageEnhancer.enhance(bitmap)
                }
                _uiState.value = _uiState.value.copy(isEnhancing = false, processedBitmap = enhancedBitmap)
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(isEnhancing = false, error = "Enhancement failed: ${e.message}")
            }
        }
    }

    fun saveImage(activity: Activity) {
        val bitmap = _uiState.value.processedBitmap ?: return
        val uri = _uiState.value.selectedImageUri ?: return
        _uiState.value = _uiState.value.copy(isEnhancing = true) // Reuse loading state
        viewModelScope.launch {
            try {
                val file = BitmapUtils.saveBitmap(activity, bitmap, "PhotoDoctorPro_Enhanced_${System.currentTimeMillis()}.jpg", Bitmap.CompressFormat.JPEG)
                repository.addHistory(
                    History(
                        operationType = "Image Enhanced",
                        inputFilePath = uri.toString(),
                        filePath = file.absolutePath,
                        timestamp = System.currentTimeMillis()
                    )
                )
                AdManager.showInterstitialAd(activity)
                // Do not reset state completely, just notify success
                _uiState.value = _uiState.value.copy(savedFilePath = file.absolutePath)
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(error = "Failed to save image: ${e.message}")
            } finally {
                _uiState.value = _uiState.value.copy(isEnhancing = false)
            }
        }
    }

    fun onErrorShown() {
        _uiState.value = _uiState.value.copy(error = null)
    }

    fun onSavedMessageShown() {
         _uiState.value = _uiState.value.copy(savedFilePath = null)
    }
}

data class EnhanceImageUiState(
    val selectedImageUri: Uri? = null,
    val originalBitmap: Bitmap? = null,
    val processedBitmap: Bitmap? = null,
    val isEnhancing: Boolean = false,
    val showEnhanceSuggestion: Boolean = false,
    val error: String? = null,
    val savedFilePath: String? = null
)
