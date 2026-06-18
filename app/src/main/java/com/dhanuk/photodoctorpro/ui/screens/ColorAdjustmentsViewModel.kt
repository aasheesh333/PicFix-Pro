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
import com.dhanuk.photodoctorpro.data.local.AppDatabase
import com.dhanuk.photodoctorpro.data.local.History
import com.dhanuk.photodoctorpro.utils.BitmapSaver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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

class ColorAdjustmentsViewModel : ViewModel() {
    private val _uiState = MutableStateFlow(ColorAdjustmentsUiState())
    val uiState: StateFlow<ColorAdjustmentsUiState> = _uiState.asStateFlow()

    fun setOriginal(uri: Uri, context: Context) {
        _uiState.value = _uiState.value.copy(selectedImageUri = uri, isLoading = true, error = null)
        viewModelScope.launch {
            try {
                val bitmap = withContext(Dispatchers.IO) {
                    context.contentResolver.openInputStream(uri)?.use { stream ->
                        BitmapFactory.decodeStream(stream)
                    }
                }
                _uiState.update {
                    it.copy(
                        selectedImageUri = uri,
                        originalBitmap = bitmap,
                        processedBitmap = bitmap,
                        isLoading = false
                    )
                }
            } catch (e: Exception) {
                _uiState.update { it.copy(error = e.message, isLoading = false) }
            }
        }
    }

    fun updateBrightness(value: Float) {
        _uiState.update { it.copy(brightness = value) }
        applyAdjustments()
    }

    fun updateContrast(value: Float) {
        _uiState.update { it.copy(contrast = value) }
        applyAdjustments()
    }

    fun updateSaturation(value: Float) {
        _uiState.update { it.copy(saturation = value) }
        applyAdjustments()
    }

    fun updateWarmth(value: Float) {
        _uiState.update { it.copy(warmth = value) }
        applyAdjustments()
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
        applyAdjustments()
    }

    private fun applyAdjustments() {
        val state = _uiState.value
        val original = state.originalBitmap ?: return
        viewModelScope.launch(Dispatchers.Default) {
            val output = applyColorMatrix(original, state.brightness, state.contrast, state.saturation, state.warmth)
            _uiState.update { it.copy(processedBitmap = output) }
        }
    }

    fun onErrorShown() { _uiState.update { it.copy(error = null) } }
    fun onSavedMessageShown() { _uiState.update { it.copy(savedFilePath = null) } }

    fun saveImage(context: Context) {
        val state = _uiState.value
        val bitmap = state.processedBitmap ?: return
        viewModelScope.launch {
            try {
                val savedPath = BitmapSaver.save(
                    context = context,
                    bitmap = bitmap,
                    baseName = "PhotoDoctorPro_Color_${System.currentTimeMillis()}",
                    subdir = "PhotoDoctorPro",
                    format = Bitmap.CompressFormat.PNG,
                    quality = 100
                )
                _uiState.update { it.copy(savedFilePath = savedPath) }

                try {
                    val db = AppDatabase.getDatabase(context)
                    db.historyDao().insert(
                        History(
                            operationType = "Color Adjustments",
                            inputFilePath = state.selectedImageUri?.toString() ?: "",
                            filePath = savedPath,
                            timestamp = System.currentTimeMillis()
                        )
                    )
                } catch (_: Exception) {}
            } catch (e: Exception) {
                _uiState.update { it.copy(error = e.message) }
            }
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
