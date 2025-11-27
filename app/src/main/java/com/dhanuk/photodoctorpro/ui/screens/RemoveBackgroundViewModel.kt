package com.dhanuk.photodoctorpro.ui.screens

import android.app.Activity
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import androidx.core.graphics.applyCanvas
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
import kotlinx.coroutines.withContext

class RemoveBackgroundViewModel(private val repository: HistoryRepository) : ViewModel() {

    private val _uiState = MutableStateFlow(RemoveBackgroundUiState())
    val uiState = _uiState.asStateFlow()

    private val segmenterOptions = SubjectSegmenterOptions.Builder()
        .build()
    private val segmenter = SubjectSegmentation.getClient(segmenterOptions)

    fun onImageSelected(uri: Uri) {
        _uiState.value = RemoveBackgroundUiState(selectedImageUri = uri)
    }

    fun removeBackground(context: Context) {
        val uri = _uiState.value.selectedImageUri ?: return
        _uiState.value = _uiState.value.copy(isLoading = true)

        viewModelScope.launch {
            try {
                val inputImage = InputImage.fromFilePath(context, uri)
                val originalBitmap = inputImage.bitmapInternal
                if (originalBitmap == null) {
                    _uiState.value = _uiState.value.copy(isLoading = false, error = "Failed to load image.")
                    return@launch
                }

                segmenter.process(inputImage)
                    .addOnSuccessListener { result ->
                        viewModelScope.launch {
                            val foreground = result.foregroundBitmap
                            if (foreground != null) {
                                val processedBitmap = processMask(foreground, originalBitmap)
                                _uiState.value = _uiState.value.copy(isLoading = false, processedBitmap = processedBitmap)
                            } else {
                                _uiState.value = _uiState.value.copy(isLoading = false, error = "Could not segment subject")
                            }
                        }
                    }
                    .addOnFailureListener { e ->
                        _uiState.value = _uiState.value.copy(isLoading = false, error = e.message)
                    }
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(isLoading = false, error = e.message)
            }
        }
    }

    private suspend fun processMask(foregroundBitmap: Bitmap, originalBitmap: Bitmap): Bitmap = withContext(Dispatchers.IO) {
        val resultBitmap = Bitmap.createBitmap(originalBitmap.width, originalBitmap.height, Bitmap.Config.ARGB_8888).applyCanvas {
            drawColor(Color.TRANSPARENT)
            drawBitmap(foregroundBitmap, 0f, 0f, null)
        }
        resultBitmap
    }

    fun saveImage(activity: Activity) {
        val bitmap = _uiState.value.processedBitmap ?: return
        val uri = _uiState.value.selectedImageUri ?: return
        viewModelScope.launch {
            val file = BitmapUtils.saveBitmap(activity, bitmap, "PhotoDoctorPro_${System.currentTimeMillis()}.png", Bitmap.CompressFormat.PNG)
            repository.addHistory(
                History(
                    operationType = "Background Removed",
                    inputFilePath = uri.toString(),
                    filePath = file.absolutePath,
                    timestamp = System.currentTimeMillis()
                )
            )
            AdManager.showInterstitialAd(activity)
            _uiState.value = _uiState.value.copy(processedBitmap = null, selectedImageUri = null)
        }
    }
}

data class RemoveBackgroundUiState(
    val selectedImageUri: Uri? = null,
    val processedBitmap: Bitmap? = null,
    val isLoading: Boolean = false,
    val error: String? = null
)
