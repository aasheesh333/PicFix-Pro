package com.dhanuk.photodoctorpro.ui.screens

import android.app.Activity
import android.content.Context
import android.graphics.pdf.PdfDocument
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.dhanuk.photodoctorpro.data.local.History
import com.dhanuk.photodoctorpro.data.repository.HistoryRepository
import com.dhanuk.photodoctorpro.utils.AdManager
import com.dhanuk.photodoctorpro.utils.BitmapUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

class ImageToPdfViewModel(private val repository: HistoryRepository) : ViewModel() {

    private val _uiState = MutableStateFlow(ImageToPdfUiState())
    val uiState = _uiState.asStateFlow()

    fun onImagesSelected(uris: List<Uri>) {
        _uiState.value = _uiState.value.copy(selectedImageUris = uris)
    }

    fun onImageReordered(from: Int, to: Int) {
        val currentList = _uiState.value.selectedImageUris.toMutableList()
        currentList.add(to, currentList.removeAt(from))
        _uiState.value = _uiState.value.copy(selectedImageUris = currentList)
    }

    fun createPdf(activity: Activity) {
        val uris = _uiState.value.selectedImageUris
        if (uris.isEmpty()) return

        _uiState.value = _uiState.value.copy(isCreating = true)

        viewModelScope.launch {
            try {
                val pdfDocument = PdfDocument()
                uris.forEachIndexed { index, uri ->
                    val bitmap = BitmapUtils.loadBitmapFromUri(uri, activity)
                    if (bitmap != null) {
                        val pageInfo = PdfDocument.PageInfo.Builder(bitmap.width, bitmap.height, index + 1).create()
                        val page = pdfDocument.startPage(pageInfo)
                        page.canvas.drawBitmap(bitmap, 0f, 0f, null)
                        pdfDocument.finishPage(page)
                    }
                }

                val file = savePdf(activity, pdfDocument)
                pdfDocument.close()

                repository.addHistory(
                    History(
                        operationType = "PDF Created",
                        inputFilePath = uris.joinToString(","),
                        filePath = file.absolutePath,
                        timestamp = System.currentTimeMillis()
                    )
                )
                _uiState.value = _uiState.value.copy(isCreating = false, pdfCreationSuccess = true)
                AdManager.showInterstitialAd(activity)
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(isCreating = false, error = e.message)
            }
        }
    }

    private suspend fun savePdf(activity: Activity, document: PdfDocument): File = withContext(Dispatchers.IO) {
        val file = File(activity.getExternalFilesDir(null), "PhotoDoctorPro_${System.currentTimeMillis()}.pdf")
        document.writeTo(FileOutputStream(file))
        file
    }
}

data class ImageToPdfUiState(
    val selectedImageUris: List<Uri> = emptyList(),
    val isCreating: Boolean = false,
    val pdfCreationSuccess: Boolean = false,
    val error: String? = null
)
