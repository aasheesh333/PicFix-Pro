package com.dhanuk.photodoctorpro.utils

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream

object BitmapUtils {

    suspend fun loadBitmapFromUri(uri: Uri, context: Context): Bitmap? = withContext(Dispatchers.IO) {
        var inputStream: InputStream? = null
        try {
            inputStream = context.contentResolver.openInputStream(uri)
            val options = BitmapFactory.Options().apply {
                inPreferredConfig = Bitmap.Config.ARGB_8888
                inMutable = true
            }
            return@withContext BitmapFactory.decodeStream(inputStream, null, options)
        } catch (e: Exception) {
            e.printStackTrace()
            return@withContext null
        } finally {
            inputStream?.close()
        }
    }

    /**
     * Saves the bitmap to the user's preferred directory or falls back to DCIM/PhotoDoctorPro.
     * Returns the URI string of the saved file.
     */
    suspend fun saveBitmap(context: Context, bitmap: Bitmap, fileName: String, format: Bitmap.CompressFormat): String = withContext(Dispatchers.IO) {
        val saveDirUriString = UserPreferences.getSaveDirectory(context)
        val mimeType = if (format == Bitmap.CompressFormat.PNG) "image/png" else "image/jpeg"
        val extension = if (format == Bitmap.CompressFormat.PNG) ".png" else ".jpg"
        val finalFileName = if (fileName.endsWith(extension)) fileName else "$fileName$extension"

        // 1. Try Custom User Directory (SAF)
        if (!saveDirUriString.isNullOrEmpty()) {
            try {
                val treeUri = Uri.parse(saveDirUriString)
                val docFile = DocumentFile.fromTreeUri(context, treeUri)
                if (docFile != null && docFile.canWrite()) {
                    val file = docFile.createFile(mimeType, finalFileName)
                    if (file != null) {
                        context.contentResolver.openOutputStream(file.uri)?.use { out ->
                            bitmap.compress(format, 95, out)
                        }
                        return@withContext file.uri.toString()
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
                // Fallback will happen below
            }
        }

        // 2. Fallback to MediaStore (DCIM/PhotoDoctorPro)
        // This works for Android 10+ (Scoped Storage) and avoids "Permission Denied" on raw paths.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val contentValues = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, finalFileName)
                put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
                put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DCIM + "/PhotoDoctorPro")
            }

            val uri = context.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, contentValues)
            if (uri != null) {
                context.contentResolver.openOutputStream(uri)?.use { out ->
                    bitmap.compress(format, 95, out)
                }
                return@withContext uri.toString()
            }
        }

        // 3. Fallback for older Android versions or if MediaStore failed (Direct File)
        val imagesDir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM), "PhotoDoctorPro")
        if (!imagesDir.exists()) imagesDir.mkdirs()

        val file = File(imagesDir, finalFileName)
        FileOutputStream(file).use { out ->
            bitmap.compress(format, 95, out)
        }

        // Scan the file so it shows up in gallery
        MediaScannerConnectionWrapper.scan(context, file.absolutePath)

        return@withContext Uri.fromFile(file).toString()
    }
}

object MediaScannerConnectionWrapper {
    fun scan(context: Context, path: String) {
        try {
            android.media.MediaScannerConnection.scanFile(
                context,
                arrayOf(path),
                null
            ) { _, _ -> }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}
