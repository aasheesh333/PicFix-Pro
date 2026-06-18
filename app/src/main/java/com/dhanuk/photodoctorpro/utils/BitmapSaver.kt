package com.dhanuk.photodoctorpro.utils

import android.content.Context
import android.graphics.Bitmap
import java.io.File
import java.io.FileOutputStream

/**
 * Shared helper for ViewModels that save bitmaps to a file. Uses
 * [BitmapUtils.resolveWritableDir] so it always writes somewhere writable
 * (external app dir preferred, internal as fallback). Returns the absolute
 * file path on success.
 */
object BitmapSaver {

    suspend fun save(
        context: Context,
        bitmap: Bitmap,
        baseName: String,
        subdir: String,
        format: Bitmap.CompressFormat = Bitmap.CompressFormat.JPEG,
        quality: Int = 95
    ): String = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        val extension = if (format == Bitmap.CompressFormat.PNG) ".png" else ".jpg"
        val dir = BitmapUtils.resolveWritableDir(context, subdir)
        if (!dir.exists()) dir.mkdirs()

        val sanitized = if (baseName.endsWith(extension, ignoreCase = true))
            baseName else baseName + extension

        var finalName = sanitized
        var counter = 1
        var file = File(dir, finalName)
        while (file.exists()) {
            val stem = sanitized.removeSuffix(extension)
            finalName = "$stem($counter)$extension"
            file = File(dir, finalName)
            counter++
        }

        FileOutputStream(file).use { out ->
            bitmap.compress(format, quality, out)
        }
        file.absolutePath
    }
}
