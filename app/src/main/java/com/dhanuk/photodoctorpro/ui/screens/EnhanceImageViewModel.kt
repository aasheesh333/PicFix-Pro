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
import java.lang.Integer.max

class EnhanceImageViewModel(private val repository: HistoryRepository) : ViewModel() {

    private val _uiState = MutableStateFlow(EnhanceImageUiState())
    val uiState = _uiState.asStateFlow()

    // Safety limit for output resolution (approx 35MP)
    private val MAX_OUTPUT_PIXELS = 35_000_000L

    fun onImageSelected(uri: Uri, context: Context) {
        viewModelScope.launch {
            // Load safely with subsampling (max 3000px dimension)
            val bitmap = BitmapUtils.loadBitmapFromUri(uri, context, 3000)
            if (bitmap != null) {
                val pixelCount = bitmap.width.toLong() * bitmap.height.toLong()
                val isLarge = pixelCount > 12_000_000 // >12MP

                _uiState.value = EnhanceImageUiState(
                    selectedImageUri = uri,
                    originalBitmap = bitmap,
                    isLargeImage = isLarge
                )
            } else {
                _uiState.value = _uiState.value.copy(error = "Failed to load image. It might be corrupted or too large.")
            }
        }
    }

    fun enhanceImage(activity: Activity) {
        val bitmap = _uiState.value.originalBitmap ?: return
        val scale = _uiState.value.scaleFactor

        // Final Safety Check
        val pixelCount = bitmap.width.toLong() * bitmap.height.toLong()
        val outputPixels = pixelCount * scale * scale

        if (outputPixels > MAX_OUTPUT_PIXELS) {
            _uiState.value = _uiState.value.copy(error = "Resulting image is too large (${outputPixels/1_000_000}MP). Please reduce enhancement level.")
            return
        }

        _uiState.value = _uiState.value.copy(isEnhancing = true, error = null)

        viewModelScope.launch {
            try {
                val enhancedBitmap = withContext(Dispatchers.Default) {
                    ImageEnhancer.enhance(bitmap, scale)
                }
                _uiState.value = _uiState.value.copy(
                    isEnhancing = false,
                    processedBitmap = enhancedBitmap,
                    showingOriginal = false
                )
            } catch (e: Exception) {
                e.printStackTrace()
                _uiState.value = _uiState.value.copy(
                    isEnhancing = false,
                    error = "Enhancement failed: ${e.message}" // OOM might be caught here too
                )
            }
        }
    }

    fun onScaleChanged(scale: Int) {
        val bitmap = _uiState.value.originalBitmap
        if (bitmap != null) {
            val pixelCount = bitmap.width.toLong() * bitmap.height.toLong()
            val outputPixels = pixelCount * scale * scale

            if (outputPixels > MAX_OUTPUT_PIXELS) {
                 _uiState.value = _uiState.value.copy(error = "Resulting image would be too large (${outputPixels/1_000_000}MP). Please choose a lower scale.")
                 return
            }
        }
        _uiState.value = _uiState.value.copy(scaleFactor = scale)
    }

    fun setShowingOriginal(show: Boolean) {
        _uiState.value = _uiState.value.copy(showingOriginal = show)
    }

    suspend fun saveImage(activity: Activity): Boolean {
        val bitmap = _uiState.value.processedBitmap ?: return false
        val uri = _uiState.value.selectedImageUri ?: return false

        _uiState.value = _uiState.value.copy(isEnhancing = true)

        return try {
            val fileName = "PhotoDoctor_Enhance_${System.currentTimeMillis()}"
            val filePath = BitmapUtils.saveBitmap(activity, bitmap, fileName, Bitmap.CompressFormat.JPEG)

            repository.addHistory(
                History(
                    operationType = "Image Enhanced",
                    inputFilePath = uri.toString(),
                    filePath = filePath,
                    timestamp = System.currentTimeMillis()
                )
            )
            AdManager.showInterstitialAd(activity)
            _uiState.value = _uiState.value.copy(savedFilePath = filePath)
            true
        } catch (e: Exception) {
            _uiState.value = _uiState.value.copy(error = "Failed to save image: ${e.message}")
            false
        } finally {
            _uiState.value = _uiState.value.copy(isEnhancing = false)
        }
    }

    fun onErrorShown() {
        _uiState.value = _uiState.value.copy(error = null)
    }

    fun onSavedMessageShown() {
         _uiState.value = _uiState.value.copy(savedFilePath = null)
    }

    fun reset() {
        _uiState.value = EnhanceImageUiState()
    }
}

data class EnhanceImageUiState(
    val selectedImageUri: Uri? = null,
    val originalBitmap: Bitmap? = null,
    val processedBitmap: Bitmap? = null,
    val isEnhancing: Boolean = false,
    val isLargeImage: Boolean = false,
    val error: String? = null,
    val savedFilePath: String? = null,
    val scaleFactor: Int = 2,
    val showingOriginal: Boolean = false
)
