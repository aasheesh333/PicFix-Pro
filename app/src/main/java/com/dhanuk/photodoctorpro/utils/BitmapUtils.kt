package com.dhanuk.photodoctorpro.utils

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

object BitmapUtils {
    suspend fun loadBitmapFromUri(uri: Uri, context: Context): Bitmap? = withContext(Dispatchers.IO) {
        return@withContext context.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it)
        }
    }

    suspend fun saveBitmap(context: Context, bitmap: Bitmap, fileName: String, format: Bitmap.CompressFormat): String = withContext(Dispatchers.IO) {
        val saveDirUriString = UserPreferences.getSaveDirectory(context)
        if (saveDirUriString != null) {
            try {
                val treeUri = Uri.parse(saveDirUriString)
                val docFile = DocumentFile.fromTreeUri(context, treeUri)
                val mimeType = if (format == Bitmap.CompressFormat.PNG) "image/png" else "image/jpeg"
                // Remove extension from fileName if DocumentFile adds it automatically?
                // DocumentFile usually adds based on mime type if not present.
                // But let's keep fileName as is.
                val file = docFile?.createFile(mimeType, fileName)
                if (file != null) {
                    context.contentResolver.openOutputStream(file.uri)?.use {
                        bitmap.compress(format, 95, it)
                    }
                    return@withContext file.uri.toString()
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        val file = File(context.getExternalFilesDir(null), fileName)
        FileOutputStream(file).use {
            bitmap.compress(format, 95, it)
        }
        file.absolutePath
    }
}
