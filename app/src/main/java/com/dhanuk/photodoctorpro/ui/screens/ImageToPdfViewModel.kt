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
import androidx.documentfile.provider.DocumentFile
import com.dhanuk.photodoctorpro.utils.UserPreferences
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
                        try {
                            val pageInfo = PdfDocument.PageInfo.Builder(bitmap.width, bitmap.height, index + 1).create()
                            val page = pdfDocument.startPage(pageInfo)
                            page.canvas.drawBitmap(bitmap, 0f, 0f, null)
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
}

data class ImageToPdfUiState(
    val selectedImageUris: List<Uri> = emptyList(),
    val isCreating: Boolean = false,
    val pdfCreationSuccess: Boolean = false,
    val error: String? = null,
    val savedFilePath: String? = null
)
