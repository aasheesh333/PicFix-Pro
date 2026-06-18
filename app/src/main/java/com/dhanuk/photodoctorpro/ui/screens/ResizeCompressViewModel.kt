package com.dhanuk.photodoctorpro.ui.screens

import android.app.Activity
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
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
import java.io.ByteArrayOutputStream

enum class ResizePreset(val label: String, val maxDim: Int, val quality: Int) {
    SMALL("Small - 1080px", 1080, 80),
    MEDIUM("Medium - 2048px", 2048, 88),
    LARGE("Large - 3200px", 3200, 92),
    ORIGINAL("Original size", 99999, 95)
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
    val savedFilePath: String? = null
)

class ResizeCompressViewModel : ViewModel() {

    private val _uiState = MutableStateFlow(ResizeUiState())
    val uiState: StateFlow<ResizeUiState> = _uiState.asStateFlow()

    private var appContext: Context? = null
    fun setContext(context: Context) { appContext = context }

    fun onImageSelected(uri: Uri) {
        _uiState.update { it.copy(selectedUri = uri, isLoading = true, error = null) }
        viewModelScope.launch {
            try {
                val context = appContext ?: return@launch
                val (bitmap, bytes) = withContext(Dispatchers.IO) {
                    val input = context.contentResolver.openInputStream(uri)
                    val bytes1 = input?.use { it.available().toLong() } ?: 0L
                    input?.close()
                    val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    context.contentResolver.openInputStream(uri)?.use {
                        BitmapFactory.decodeStream(it, null, opts)
                    }
                    val sample = calculateInSampleSize(opts.outWidth, opts.outHeight, 4000)
                    val decodeOpts = BitmapFactory.Options().apply {
                        inSampleSize = sample
                        inPreferredConfig = Bitmap.Config.ARGB_8888
                    }
                    val bmp = context.contentResolver.openInputStream(uri)?.use {
                        BitmapFactory.decodeStream(it, null, decodeOpts)
                    }
                    val realBytes = uri.toString().let { u ->
                        try {
                            context.contentResolver.openInputStream(Uri.parse(u))?.use { stream ->
                                stream.available().toLong()
                            } ?: 0L
                        } catch (_: Exception) { 0L }
                    }
                    Pair(bmp, if (realBytes > 0) realBytes else bytes1)
                }
                _uiState.update {
                    it.copy(
                        originalBitmap = bitmap,
                        processedBitmap = bitmap,
                        originalSizeBytes = bytes,
                        processedSizeBytes = estimateBytes(bitmap, _uiState.value.quality),
                        isLoading = false
                    )
                }
            } catch (e: Exception) {
                _uiState.update { it.copy(error = e.message, isLoading = false) }
            }
        }
    }

    fun onPresetSelected(preset: ResizePreset) {
        _uiState.update { it.copy(preset = preset) }
        applyPreset(preset, _uiState.value.quality)
    }

    fun onQualityChanged(quality: Float) {
        _uiState.update { it.copy(quality = quality) }
        applyPreset(_uiState.value.preset, quality)
    }

    private fun applyPreset(preset: ResizePreset, quality: Float) {
        val original = _uiState.value.originalBitmap ?: return
        _uiState.update { it.copy(isProcessing = true) }
        viewModelScope.launch {
            val processed = withContext(Dispatchers.Default) {
                resizeBitmap(original, preset.maxDim)
            }
            val processedBytes = estimateBytes(processed, quality)
            _uiState.update {
                it.copy(processedBitmap = processed, processedSizeBytes = processedBytes, isProcessing = false)
            }
        }
    }

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

    private fun calculateInSampleSize(width: Int, height: Int, req: Int): Int {
        var inSampleSize = 1
        if (height > req || width > req) {
            val halfHeight = height / 2
            val halfWidth = width / 2
            while ((halfHeight / inSampleSize) >= req && (halfWidth / inSampleSize) >= req) {
                inSampleSize *= 2
            }
        }
        return inSampleSize
    }

    fun saveImage(context: Context) {
        val state = _uiState.value
        val bitmap = state.processedBitmap ?: return
        viewModelScope.launch {
            try {
                val format = if (state.preset == ResizePreset.ORIGINAL)
                    Bitmap.CompressFormat.PNG else Bitmap.CompressFormat.JPEG
                val ext = if (format == Bitmap.CompressFormat.PNG) ".png" else ".jpg"
                val presetTag = state.preset.name.lowercase()
                val savedPath = BitmapSaver.save(
                    context = context,
                    bitmap = bitmap,
                    baseName = "PDPro_${presetTag}_${System.currentTimeMillis()}$ext",
                    subdir = "PhotoDoctorPro",
                    format = format,
                    quality = (state.quality * 100).toInt().coerceIn(1, 100)
                )
                _uiState.update { it.copy(savedFilePath = savedPath) }

                try {
                    val db = AppDatabase.getDatabase(context)
                    db.historyDao().insert(
                        History(
                            operationType = "Resize (${state.preset.label})",
                            inputFilePath = state.selectedUri?.toString() ?: "",
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

    fun onErrorShown() { _uiState.update { it.copy(error = null) } }
    fun onSavedMessageShown() { _uiState.update { it.copy(savedFilePath = null) } }
}
