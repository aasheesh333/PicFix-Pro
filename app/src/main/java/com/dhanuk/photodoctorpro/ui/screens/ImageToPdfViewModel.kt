package com.dhanuk.photodoctorpro.ui.screens

import android.app.Activity
import android.content.Context
import android.graphics.pdf.PdfDocument
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.dhanuk.photodoctorpro.data.local.History
import com.dhanuk.photodoctorpro.data.repository.HistoryRepository
import com.dhanuk.photodoctorpro.utils.AdManager
import com.dhanuk.photodoctorpro.utils.BitmapUtils
import com.dhanuk.photodoctorpro.utils.viewModelExceptionHandler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.dhanuk.photodoctorpro.utils.UserPreferences
import java.io.File
import java.io.FileOutputStream

class ImageToPdfViewModel(
    private val repository: HistoryRepository,
    private val savedStateHandle: SavedStateHandle
) : ViewModel() {

    private val _uiState = MutableStateFlow(
        ImageToPdfUiState(
            selectedImageUris = savedStateHandle.get<List<String>>(KEY_URIS)
                ?.mapNotNull { runCatching { Uri.parse(it) }.getOrNull() }
                ?: emptyList()
        )
    )
    val uiState = _uiState.asStateFlow()

    fun onImagesSelected(uris: List<Uri>) {
        savedStateHandle[KEY_URIS] = uris.map { it.toString() }
        _uiState.value = _uiState.value.copy(selectedImageUris = uris)
    }

    fun onImageReordered(from: Int, to: Int) {
        val currentList = _uiState.value.selectedImageUris.toMutableList()
        if (from !in currentList.indices) return
        val item = currentList.removeAt(from)
        val adjustedTo = if (from < to) to - 1 else to
        val target = adjustedTo.coerceIn(0, currentList.size)
        currentList.add(target, item)
        _uiState.value = _uiState.value.copy(selectedImageUris = currentList)
    }

    fun createPdf(activity: Activity) {
        val uris = _uiState.value.selectedImageUris
        if (uris.isEmpty()) return

        _uiState.value = _uiState.value.copy(isCreating = true)

        viewModelScope.launch(viewModelExceptionHandler("ImageToPdfVM")) {
            try {
                val pdfDocument = PdfDocument()
                val pageWidth = 595
                val pageHeight = 842
                val pageMargin = 24
                val drawableWidth = (pageWidth - 2 * pageMargin).toFloat()
                val drawableHeight = (pageHeight - 2 * pageMargin).toFloat()

                uris.forEachIndexed { index, uri ->
                    val bitmap = BitmapUtils.loadBitmapFromUri(uri, activity)
                    if (bitmap != null) {
                        try {
                            val pageInfo = PdfDocument.PageInfo.Builder(pageWidth, pageHeight, index + 1).create()
                            val page = pdfDocument.startPage(pageInfo)
                            val pageCanvas = page.canvas

                            val sourceRatio = bitmap.width.toFloat() / bitmap.height.toFloat()
                            val targetRatio = drawableWidth / drawableHeight
                            var scaledW = drawableWidth
                            var scaledH = drawableHeight
                            if (sourceRatio > targetRatio) {
                                scaledH = drawableWidth / sourceRatio
                            } else {
                                scaledW = drawableHeight * sourceRatio
                            }
                            val left = pageMargin + (drawableWidth - scaledW) / 2f
                            val top = pageMargin + (drawableHeight - scaledH) / 2f

                            val destRect = android.graphics.RectF(left, top, left + scaledW, top + scaledH)
                            pageCanvas.drawBitmap(bitmap, null, destRect, null)
                            pdfDocument.finishPage(page)
                        } finally {
                            if (!bitmap.isRecycled) bitmap.recycle()
                        }
                    }
                }

                val filePath = savePdf(activity, pdfDocument)
                pdfDocument.close()

                repository.addHistory(
                    History(
                        operationType = "PDF Created",
                        inputFilePath = uris.joinToString(","),
                        filePath = filePath,
                        timestamp = System.currentTimeMillis()
                    )
                )
                _uiState.value = _uiState.value.copy(isCreating = false, pdfCreationSuccess = true, savedFilePath = filePath)
                AdManager.showInterstitialAd(activity)
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(isCreating = false, error = e.message)
            }
        }
    }

    private suspend fun savePdf(context: Context, document: PdfDocument): String = withContext(Dispatchers.IO) {
        val fileName = "PhotoDoctorPro_${System.currentTimeMillis()}.pdf"
        val saveDirUriString = UserPreferences.getSaveDirectory(context)
        if (saveDirUriString != null) {
            try {
                val treeUri = Uri.parse(saveDirUriString)
                val docFile = DocumentFile.fromTreeUri(context, treeUri)
                val file = docFile?.createFile("application/pdf", fileName)
                if (file != null) {
                    context.contentResolver.openOutputStream(file.uri)?.use {
                        document.writeTo(it)
                    }
                    return@withContext file.uri.toString()
                }
            } catch (e: Exception) {
                if (com.dhanuk.photodoctorpro.BuildConfig.DEBUG) android.util.Log.e("ImageToPdfVM", "operation failed", e)
            }
        }

        val dir = com.dhanuk.photodoctorpro.utils.BitmapUtils.resolveWritableDir(context, "PhotoDoctorPro")
        if (!dir.exists()) dir.mkdirs()
        val file = File(dir, fileName)
        FileOutputStream(file).use { outStream ->
            document.writeTo(outStream)
        }
        file.absolutePath
    }

    fun onErrorShown() {
         _uiState.value = _uiState.value.copy(error = null)
    }

    fun onSavedMessageShown() {
        _uiState.value = _uiState.value.copy(savedFilePath = null, pdfCreationSuccess = false)
    }

    override fun onCleared() {
        super.onCleared()
    }
}

data class ImageToPdfUiState(
    val selectedImageUris: List<Uri> = emptyList(),
    val isCreating: Boolean = false,
    val pdfCreationSuccess: Boolean = false,
    val error: String? = null,
    val savedFilePath: String? = null
)

private const val KEY_URIS = "selectedImageUris"
