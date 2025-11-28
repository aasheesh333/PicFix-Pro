package com.dhanuk.photodoctorpro.ui.screens

import android.app.Activity
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.dhanuk.photodoctorpro.data.local.History
import com.dhanuk.photodoctorpro.data.repository.HistoryRepository
import com.dhanuk.photodoctorpro.utils.AdManager
import com.dhanuk.photodoctorpro.utils.BitmapUtils
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.segmentation.subject.SubjectSegmentation
import com.google.mlkit.vision.segmentation.subject.SubjectSegmenterOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext

class RemoveBackgroundViewModel(private val repository: HistoryRepository) : ViewModel() {

    private val _uiState = MutableStateFlow(RemoveBackgroundUiState())
    val uiState = _uiState.asStateFlow()

    fun onImageSelected(uri: Uri) {
        _uiState.value = RemoveBackgroundUiState(selectedImageUri = uri)
    }

    fun removeBackground(context: Context) {
        val uri = _uiState.value.selectedImageUri ?: return
        _uiState.value = _uiState.value.copy(isLoading = true, error = null)

        viewModelScope.launch {
            try {
                // 1. Load Bitmap safely
                var bitmap = BitmapUtils.loadBitmapFromUri(uri, context)
                if (bitmap != null) {
                    // 2. Downscale if too large to prevent OOM
                    if (bitmap.width > 2048 || bitmap.height > 2048) {
                        val scale = 2048f / kotlin.math.max(bitmap.width, bitmap.height)
                        val newWidth = (bitmap.width * scale).toInt()
                        val newHeight = (bitmap.height * scale).toInt()
                        bitmap = Bitmap.createScaledBitmap(bitmap, newWidth, newHeight, true)
                    }

                    // 3. Process
                    val result = processImage(bitmap)
                    _uiState.value = _uiState.value.copy(isLoading = false, processedBitmap = result)
                } else {
                    _uiState.value = _uiState.value.copy(isLoading = false, error = "Failed to load image")
                }
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(isLoading = false, error = e.message ?: "Unknown error")
            }
        }
    }

    private suspend fun processImage(bitmap: Bitmap): Bitmap = withContext(Dispatchers.Default) {
        val options = SubjectSegmenterOptions.Builder()
            .enableForegroundBitmap()
            .build()
        val segmenter = SubjectSegmentation.getClient(options)
        val inputImage = InputImage.fromBitmap(bitmap, 0)

        try {
            val result = segmenter.process(inputImage).await()
            val foreground = result.foregroundBitmap
            if (foreground != null) {
                // Ensure ARGB_8888
                if (foreground.config != Bitmap.Config.ARGB_8888) {
                    return@withContext foreground.copy(Bitmap.Config.ARGB_8888, true)
                }
                return@withContext foreground
            } else {
                throw Exception("Could not segment subject - No foreground detected.")
            }
        } catch (e: Exception) {
            e.printStackTrace()
            // Fallback: If ML Kit fails, return original? Or throw?
            // User requested fallback method or nice error.
            throw Exception("Segmentation failed: ${e.message}")
        }
    }

    fun saveImage(activity: Activity) {
        val bitmap = _uiState.value.processedBitmap ?: return
        val uri = _uiState.value.selectedImageUri ?: return
        _uiState.value = _uiState.value.copy(isLoading = true) // Reuse loading state for saving

        viewModelScope.launch {
            try {
                // Use new BitmapUtils with PNG format
                val filePath = BitmapUtils.saveBitmap(activity, bitmap, "PhotoDoctorPro_BG_${System.currentTimeMillis()}", Bitmap.CompressFormat.PNG)

                repository.addHistory(
                    History(
                        operationType = "Background Removed",
                        inputFilePath = uri.toString(),
                        filePath = filePath,
                        timestamp = System.currentTimeMillis()
                    )
                )

                AdManager.showInterstitialAd(activity)
                _uiState.value = _uiState.value.copy(savedFilePath = filePath)
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(error = "Failed to save image: ${e.message}")
            } finally {
                _uiState.value = _uiState.value.copy(isLoading = false)
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

data class RemoveBackgroundUiState(
    val selectedImageUri: Uri? = null,
    val processedBitmap: Bitmap? = null,
    val isLoading: Boolean = false,
    val error: String? = null,
    val savedFilePath: String? = null
)
