package com.dhanuk.photodoctorpro.utils

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
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

    suspend fun saveBitmap(context: Context, bitmap: Bitmap, fileName: String, format: Bitmap.CompressFormat): File = withContext(Dispatchers.IO) {
        val file = File(context.getExternalFilesDir(null), fileName)
        FileOutputStream(file).use {
            bitmap.compress(format, 95, it)
        }
        file
    }
}
