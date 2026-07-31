package com.dhanuk.photodoctorpro.utils

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.core.content.FileProvider
import androidx.documentfile.provider.DocumentFile
import androidx.exifinterface.media.ExifInterface
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream

object BitmapUtils {

    /**
     * Resolves a writable directory that always exists. Falls back to internal
     * app-specific storage if external storage is unavailable (emulator without
     * SD card, scoped storage quirks on some OEMs). Returns the directory and
     * the FileProvider authority already applied.
     */
    fun resolveWritableDir(context: Context, subdir: String = "PicFixPro"): File {
        val external = context.getExternalFilesDir(Environment.DIRECTORY_PICTURES)
        if (external != null) {
            val target = if (subdir.isBlank()) external else File(external, subdir)
            if (target.exists() || target.mkdirs()) return target
        }
        val internalPictures = File(context.filesDir, "Pictures")
        val target = if (subdir.isBlank()) internalPictures else File(internalPictures, subdir)
        if (!target.exists()) target.mkdirs()
        return target
    }

    suspend fun loadBitmapFromUri(uri: Uri, context: Context, maxDimension: Int = 3000, mutable: Boolean = true): Bitmap? = withContext(Dispatchers.IO) {
        var bitmap: Bitmap? = null
        try {
            // 1. Decode bounds only
            val boundsOptions = BitmapFactory.Options().apply {
                inJustDecodeBounds = true
            }
            context.contentResolver.openInputStream(uri)?.use { inputStream ->
                BitmapFactory.decodeStream(inputStream, null, boundsOptions)
            }

            // 2. Calculate inSampleSize
            boundsOptions.inSampleSize = calculateInSampleSize(boundsOptions, maxDimension, maxDimension)
            boundsOptions.inJustDecodeBounds = false
            boundsOptions.inPreferredConfig = Bitmap.Config.ARGB_8888
            boundsOptions.inMutable = mutable

            // 3. Decode full bitmap with subsampling
            context.contentResolver.openInputStream(uri)?.use { inputStream ->
                bitmap = BitmapFactory.decodeStream(inputStream, null, boundsOptions)
            }

            // 4. Handle EXIF Rotation
            if (bitmap != null) {
                context.contentResolver.openInputStream(uri)?.use { inputStream ->
                    val exifInterface = ExifInterface(inputStream)
                    val orientation = exifInterface.getAttributeInt(
                        ExifInterface.TAG_ORIENTATION,
                        ExifInterface.ORIENTATION_NORMAL
                    )
                    if (orientation != ExifInterface.ORIENTATION_NORMAL) {
                        bitmap = rotateBitmap(bitmap!!, orientation)
                    }
                }
            }

            return@withContext bitmap
        } catch (e: Exception) {
            if (com.dhanuk.photodoctorpro.BuildConfig.DEBUG) android.util.Log.e("BitmapUtils", "loadBitmapFromUri failed", e)
            bitmap?.takeIf { !it.isRecycled }?.recycle()
            return@withContext null
        }
    }

    private fun calculateInSampleSize(options: BitmapFactory.Options, reqWidth: Int, reqHeight: Int): Int {
        val (height: Int, width: Int) = options.run { outHeight to outWidth }
        var inSampleSize = 1

        if (height > reqHeight || width > reqWidth) {
            while ((height / inSampleSize) > reqHeight || (width / inSampleSize) > reqWidth) {
                inSampleSize *= 2
            }
        }
        return inSampleSize
    }

    private fun rotateBitmap(bitmap: Bitmap, orientation: Int): Bitmap {
        val matrix = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
            ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
            ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
            else -> return bitmap
        }
        return try {
            val rotated = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
            if (rotated != bitmap) {
                bitmap.recycle()
            }
            rotated
        } catch (e: OutOfMemoryError) {
            if (com.dhanuk.photodoctorpro.BuildConfig.DEBUG) android.util.Log.e("BitmapUtils", "operation failed", e)
            bitmap
        }
    }

    /**
     * Saves the bitmap to the user's preferred directory or falls back to DCIM/PicFixPro.
     * Returns the URI string of the saved file.
     * Handles Auto-Increment for filename collisions.
     */
    suspend fun saveBitmap(context: Context, bitmap: Bitmap, fileName: String, format: Bitmap.CompressFormat): String =
        saveBitmap(context, bitmap, fileName, SaveOptions(format = SaveFormat.entries.first { it.compressFormat == format }, quality = 95, bgColor = null))

    /**
     * v2 save flow: honours the user-selected [SaveOptions] (format, quality, background fill).
     *
     * When [SaveFormat.supportsAlpha] is false and the source bitmap has an alpha channel,
     * transparency is flattened onto [SaveOptions.bgColor] (default white) so JPEG/HEIF output
     * is not rendered with a black background.
     */
    suspend fun saveBitmap(context: Context, bitmap: Bitmap, fileName: String, options: SaveOptions): String = withContext(Dispatchers.IO) {
        val format = options.format
        val outputBitmap = flattenAlphaIfNeeded(bitmap, format)
        val ownsOutput = outputBitmap !== bitmap

        val saveDirUriString = UserPreferences.getSaveDirectory(context)
        val mimeType = format.mimeType
        val extension = format.extension

        // Ensure filename doesn't have extension duplicated
        val baseName = if (fileName.endsWith(extension, ignoreCase = true)) fileName.dropLast(extension.length) else fileName

        try {
            // 1. Try Custom User Directory (SAF)
            if (!saveDirUriString.isNullOrEmpty()) {
                try {
                    val treeUri = Uri.parse(saveDirUriString)
                    val docFile = DocumentFile.fromTreeUri(context, treeUri)

                    if (docFile != null && docFile.canWrite()) {
                        var finalName = "$baseName$extension"
                        var counter = 1
                        while (docFile.findFile(finalName) != null) {
                            finalName = "$baseName($counter)$extension"
                            counter++
                        }

                        val file = docFile.createFile(mimeType, finalName)
                        if (file != null) {
                            context.contentResolver.openOutputStream(file.uri)?.use { out ->
                                compressWithQuality(outputBitmap, format, options.quality, out)
                            }
                            return@withContext file.uri.toString()
                        }
                    }
                } catch (e: Exception) {
                    if (com.dhanuk.photodoctorpro.BuildConfig.DEBUG) android.util.Log.e("BitmapUtils", "operation failed", e)
                    // Fallback will happen below
                }
            }

            // 2. Fallback to MediaStore (DCIM/PicFixPro)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val finalName = "$baseName$extension"

                val contentValues = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, finalName)
                    put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
                    put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DCIM + "/PicFixPro")
                    put(MediaStore.MediaColumns.IS_PENDING, 1)
                }

                val uri = context.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, contentValues)
                if (uri != null) {
                    try {
                        context.contentResolver.openOutputStream(uri)?.use { out ->
                            compressWithQuality(outputBitmap, format, options.quality, out)
                        }
                        contentValues.clear()
                        contentValues.put(MediaStore.MediaColumns.IS_PENDING, 0)
                        context.contentResolver.update(uri, contentValues, null, null)
                        return@withContext uri.toString()
                    } catch (e: Exception) {
                        context.contentResolver.delete(uri, null, null)
                        throw e
                    }
                }
            }

            // 3. Fallback for older Android versions (Direct File)
            val imagesDir = resolveWritableDir(context, "PicFixPro")
            if (!imagesDir.exists()) imagesDir.mkdirs()

            var finalName = "$baseName$extension"
            var counter = 1
            var file = File(imagesDir, finalName)
            while (file.exists()) {
                finalName = "$baseName($counter)$extension"
                file = File(imagesDir, finalName)
                counter++
            }

            try {
                FileOutputStream(file).use { out ->
                    compressWithQuality(outputBitmap, format, options.quality, out)
                }
            } catch (e: Exception) {
                if (file.exists()) file.delete()
                throw e
            }

            MediaScannerConnectionWrapper.scan(context, file.absolutePath)

            return@withContext FileProvider.getUriForFile(
                context,
                "${context.packageName}.provider",
                file
            ).toString()
        } finally {
            if (ownsOutput && !outputBitmap.isRecycled) outputBitmap.recycle()
        }
    }

    private fun compressWithQuality(bitmap: Bitmap, format: SaveFormat, quality: Int, out: OutputStream) {
        when (format) {
            SaveFormat.PNG, SaveFormat.WEBP_LOSSLESS -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && format == SaveFormat.WEBP_LOSSLESS) {
                    bitmap.compress(Bitmap.CompressFormat.WEBP_LOSSLESS, 100, out)
                } else {
                    bitmap.compress(format.compressFormat, 100, out)
                }
            }
            else -> bitmap.compress(format.compressFormat, quality.coerceIn(1, 100), out)
        }
    }

    /**
     * Flattens transparency onto a solid background when the target format cannot store alpha.
     */
    private fun flattenAlphaIfNeeded(source: Bitmap, format: SaveFormat): Bitmap {
        if (format.supportsAlpha) return source
        if (!source.hasAlpha()) return source
        val bg = 0xFFFFFFFF.toInt()
        val result = Bitmap.createBitmap(source.width, source.height, Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(result)
        canvas.drawColor(bg)
        canvas.drawBitmap(source, 0f, 0f, null)
        return result
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
            if (com.dhanuk.photodoctorpro.BuildConfig.DEBUG) android.util.Log.e("BitmapUtils", "operation failed", e)
        }
    }
}

fun resolveFileUri(path: String, context: Context): Uri {
    return if (path.startsWith("content://")) {
        Uri.parse(path)
    } else {
        try {
            val cleanPath = if (path.startsWith("file://")) Uri.parse(path).path ?: path else path
            val file = File(cleanPath)
            FileProvider.getUriForFile(context, "${context.packageName}.provider", file)
        } catch (e: IllegalArgumentException) {
            if (com.dhanuk.photodoctorpro.BuildConfig.DEBUG) {
                android.util.Log.e("BitmapUtils", "resolveFileUri failed for path: $path", e)
            }
            Uri.fromFile(File(path))
        }
    }
}

fun createShareIntent(path: String, context: Context, packageName: String? = null, mimeType: String = "image/*"): Intent {
    val uri = resolveFileUri(path, context)
    val resolvedPackage = if (packageName == WHATSAPP_PACKAGE) resolveWhatsAppPackage(context) else packageName
    return Intent(Intent.ACTION_SEND).apply {
        type = mimeType
        putExtra(Intent.EXTRA_STREAM, uri as android.os.Parcelable)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        if (resolvedPackage != null) setPackage(resolvedPackage)
    }
}

fun resolveWhatsAppPackage(context: Context): String? {
    val pm = context.packageManager
    val candidates = listOf("com.whatsapp", "com.whatsapp.w4b")
    for (pkg in candidates) {
        try {
            pm.getPackageInfo(pkg, 0)
            return pkg
        } catch (_: Exception) {
        }
    }
    return null
}

private const val WHATSAPP_PACKAGE = "com.whatsapp"

fun createOpenIntent(path: String, context: Context): Intent {
    val uri = resolveFileUri(path, context)
    return Intent(Intent.ACTION_VIEW).apply {
        setDataAndType(uri, "image/*")
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
}

fun mapToBitmap(x: Float, y: Float, layoutW: Float, layoutH: Float, original: Bitmap): Pair<Float, Float>? {
    if (layoutW <= 0 || layoutH <= 0) return null
    val viewAspectRatio = layoutW / layoutH
    val imageAspectRatio = original.width.toFloat() / original.height.toFloat()
    var drawWidth = layoutW
    var drawHeight = layoutH
    var drawX = 0f
    var drawY = 0f
    if (imageAspectRatio > viewAspectRatio) {
        drawHeight = drawWidth / imageAspectRatio
        drawY = (layoutH - drawHeight) / 2f
    } else {
        drawWidth = drawHeight * imageAspectRatio
        drawX = (layoutW - drawWidth) / 2f
    }
    val localX = x - drawX
    val localY = y - drawY
    val bitmapX = (localX / drawWidth) * original.width
    val bitmapY = (localY / drawHeight) * original.height
    return Pair(bitmapX, bitmapY)
}

fun calculateScaleFactor(layoutW: Float, layoutH: Float, original: Bitmap): Float {
    if (layoutW <= 0 || layoutH <= 0) return 1f
    val viewAspectRatio = layoutW / layoutH
    val imageAspectRatio = original.width.toFloat() / original.height.toFloat()
    var drawWidth = layoutW
    if (imageAspectRatio <= viewAspectRatio) {
        drawWidth = layoutH * imageAspectRatio
    }
    return drawWidth / original.width
}
