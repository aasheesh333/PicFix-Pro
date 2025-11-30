package com.dhanuk.photodoctorpro.utils

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.graphics.Rect
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.opencv.android.Utils
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

interface ImageEnhancerEngine {
    suspend fun enhance(bitmap: Bitmap, scaleFactor: Int): Bitmap
}

class TFLiteEnhancer(private val context: Context) : ImageEnhancerEngine {
    private val modelName = "ESRGAN.tflite"

    override suspend fun enhance(bitmap: Bitmap, scaleFactor: Int): Bitmap {
        val assetManager = context.assets
        try {
            assetManager.open(modelName).close()
             throw NotImplementedError("TFLite model not yet integrated fully.")
        } catch (e: Exception) {
            throw e
        }
    }
}

class OpenCVEnhancer : ImageEnhancerEngine {

    override suspend fun enhance(bitmap: Bitmap, scaleFactor: Int): Bitmap = withContext(Dispatchers.Default) {
        val width = bitmap.width
        val height = bitmap.height

        // Tile if > 4MP (approx 2000x2000)
        // Processing smaller images in one go is faster and safer (no seams)
        if (width * height <= 4_000_000) {
            return@withContext processMat(bitmap, scaleFactor)
        }

        val TILE_SIZE = 512
        val PADDING = 32

        val targetWidth = width * scaleFactor
        val targetHeight = height * scaleFactor

        // Strict OOM Check before allocation
        // RGBA_8888 = 4 bytes per pixel.
        val requiredBytes = targetWidth.toLong() * targetHeight.toLong() * 4
        val maxMemory = Runtime.getRuntime().maxMemory()
        val freeMemory = Runtime.getRuntime().freeMemory()
        // Simple heuristic: If we need more than 70% of max memory (risky), throw or handle.
        // But Android heap grows.

        val outBitmap = try {
            Bitmap.createBitmap(targetWidth, targetHeight, Bitmap.Config.ARGB_8888)
        } catch (e: OutOfMemoryError) {
            throw IllegalArgumentException("Not enough memory to enhance this image at ${scaleFactor}x.")
        }

        val canvas = Canvas(outBitmap)

        val cols = kotlin.math.ceil(width.toFloat() / TILE_SIZE).toInt()
        val rows = kotlin.math.ceil(height.toFloat() / TILE_SIZE).toInt()

        for (r in 0 until rows) {
            for (c in 0 until cols) {
                // Base Tile coordinates in Input Image
                val x = c * TILE_SIZE
                val y = r * TILE_SIZE
                val w = kotlin.math.min(TILE_SIZE, width - x)
                val h = kotlin.math.min(TILE_SIZE, height - y)

                // Coordinates with Padding (Overlap)
                val startX = kotlin.math.max(0, x - PADDING)
                val startY = kotlin.math.max(0, y - PADDING)
                val endX = kotlin.math.min(width, x + w + PADDING)
                val endY = kotlin.math.min(height, y + h + PADDING)

                val srcW = endX - startX
                val srcH = endY - startY

                // Crop Source
                val srcTile = Bitmap.createBitmap(bitmap, startX, startY, srcW, srcH)

                // Process
                val processedTile = processMat(srcTile, scaleFactor)
                srcTile.recycle()

                // Determine the valid center region in the Processed Tile
                val padLeft = (x - startX) * scaleFactor
                val padTop = (y - startY) * scaleFactor
                val validW = w * scaleFactor
                val validH = h * scaleFactor

                // Source Rect (Center of processed tile)
                // Ensure we don't go out of bounds of processedTile
                val safeSrcRight = kotlin.math.min(processedTile.width, padLeft + validW)
                val safeSrcBottom = kotlin.math.min(processedTile.height, padTop + validH)

                val srcRect = Rect(padLeft, padTop, safeSrcRight, safeSrcBottom)

                // Dest Rect (Position in output bitmap)
                val dstX = x * scaleFactor
                val dstY = y * scaleFactor
                val dstRect = Rect(dstX, dstY, dstX + validW, dstY + validH)

                canvas.drawBitmap(processedTile, srcRect, dstRect, null)
                processedTile.recycle()
            }
        }

        return@withContext outBitmap
    }

    private fun processMat(bitmap: Bitmap, scaleFactor: Int): Bitmap {
        val src = Mat()
        Utils.bitmapToMat(bitmap, src)

        val dst = Mat()
        val newWidth = (src.cols() * scaleFactor).toDouble()
        val newHeight = (src.rows() * scaleFactor).toDouble()
        Imgproc.resize(src, dst, Size(newWidth, newHeight), 0.0, 0.0, Imgproc.INTER_CUBIC)

        // Sharpen (Unsharp Mask)
        val gaussian = Mat()
        Imgproc.GaussianBlur(dst, gaussian, Size(0.0, 0.0), 2.0)
        Core.addWeighted(dst, 1.5, gaussian, -0.5, 0.0, dst)

        // CLAHE for Detail/Contrast
        // Convert to Lab
        val lab = Mat()
        Imgproc.cvtColor(dst, lab, Imgproc.COLOR_RGB2Lab)
        val channels = ArrayList<Mat>()
        Core.split(lab, channels)

        val clahe = Imgproc.createCLAHE()
        clahe.clipLimit = 2.0
        clahe.apply(channels[0], channels[0])

        Core.merge(channels, lab)
        Imgproc.cvtColor(lab, dst, Imgproc.COLOR_Lab2RGB)

        // Ensure result is ARGB_8888
        val result = Bitmap.createBitmap(dst.cols(), dst.rows(), Bitmap.Config.ARGB_8888)
        Utils.matToBitmap(dst, result)

        src.release()
        dst.release()
        gaussian.release()
        lab.release()
        channels.forEach { it.release() }

        return adjustVibrance(result, 1.1f)
    }

    private fun adjustVibrance(bitmap: Bitmap, value: Float): Bitmap {
        val bmp = Bitmap.createBitmap(bitmap.width, bitmap.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        val paint = Paint()
        val colorMatrix = ColorMatrix()
        colorMatrix.setSaturation(value)
        val filter = ColorMatrixColorFilter(colorMatrix)
        paint.colorFilter = filter
        canvas.drawBitmap(bitmap, 0f, 0f, paint)

        if (bmp != bitmap && !bitmap.isRecycled) {
             bitmap.recycle()
        }
        return bmp
    }
}

object ImageEnhancer {

    suspend fun enhanceImage(context: Context, bitmap: Bitmap, scaleFactor: Int): Bitmap {
        val MAX_PIXELS = 60_000_000L // Reduced to 60MP for stability
        val targetPixels = (bitmap.width.toLong() * scaleFactor) * (bitmap.height.toLong() * scaleFactor)

        if (targetPixels > MAX_PIXELS) {
            throw IllegalArgumentException("Resulting image too large (${targetPixels/1_000_000}MP). Limit is 60MP to prevent crash.")
        }

        // Disable 6x/8x for large sources (>12MP)
        if (bitmap.width * bitmap.height > 12_000_000 && scaleFactor >= 6) {
             throw IllegalArgumentException("Image too large for ${scaleFactor}x enhancement.")
        }

        return try {
             TFLiteEnhancer(context).enhance(bitmap, scaleFactor)
        } catch (e: Exception) {
             OpenCVEnhancer().enhance(bitmap, scaleFactor)
        }
    }
}
