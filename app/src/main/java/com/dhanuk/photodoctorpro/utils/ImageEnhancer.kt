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

        // Use tiling for images larger than 4MP
        if (width * height <= 4_000_000) {
            return@withContext processMat(bitmap, scaleFactor)
        }

        val TILE_SIZE = 512
        val targetWidth = width * scaleFactor
        val targetHeight = height * scaleFactor

        // Try allocation
        val outBitmap = try {
            Bitmap.createBitmap(targetWidth, targetHeight, Bitmap.Config.ARGB_8888)
        } catch (e: OutOfMemoryError) {
            throw IllegalArgumentException("Not enough memory for ${scaleFactor}x enhancement.")
        }

        val canvas = Canvas(outBitmap)
        // Ensure opaque background to avoid "Black Spots" if transparency issues arise
        canvas.drawColor(android.graphics.Color.BLACK)

        val cols = kotlin.math.ceil(width.toFloat() / TILE_SIZE).toInt()
        val rows = kotlin.math.ceil(height.toFloat() / TILE_SIZE).toInt()

        for (r in 0 until rows) {
            for (c in 0 until cols) {
                // Exact Tiling (No Overlap for stability)
                val x = c * TILE_SIZE
                val y = r * TILE_SIZE
                val w = kotlin.math.min(TILE_SIZE, width - x)
                val h = kotlin.math.min(TILE_SIZE, height - y)

                // Crop Source
                val srcTile = Bitmap.createBitmap(bitmap, x, y, w, h)

                // Process
                val processedTile = processMat(srcTile, scaleFactor)
                srcTile.recycle()

                // Draw to Dest
                val dstX = x * scaleFactor
                val dstY = y * scaleFactor
                // Use exact size of processed tile (should be w*scale, h*scale)
                val destRect = Rect(dstX, dstY, dstX + processedTile.width, dstY + processedTile.height)

                canvas.drawBitmap(processedTile, null, destRect, null)
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

        // Bicubic Upscale
        Imgproc.resize(src, dst, Size(newWidth, newHeight), 0.0, 0.0, Imgproc.INTER_CUBIC)

        // Sharpen (Stronger for "Real AI" look)
        val gaussian = Mat()
        Imgproc.GaussianBlur(dst, gaussian, Size(0.0, 0.0), 3.0)
        Core.addWeighted(dst, 2.0, gaussian, -1.0, 0.0, dst) // Stronger sharpening

        // CLAHE
        val lab = Mat()
        Imgproc.cvtColor(dst, lab, Imgproc.COLOR_RGB2Lab)
        val channels = ArrayList<Mat>()
        Core.split(lab, channels)

        val clahe = Imgproc.createCLAHE()
        clahe.clipLimit = 3.0 // Stronger contrast
        clahe.apply(channels[0], channels[0])

        Core.merge(channels, lab)
        Imgproc.cvtColor(lab, dst, Imgproc.COLOR_Lab2RGB)

        val result = Bitmap.createBitmap(dst.cols(), dst.rows(), Bitmap.Config.ARGB_8888)
        Utils.matToBitmap(dst, result)

        src.release()
        dst.release()
        gaussian.release()
        lab.release()
        channels.forEach { it.release() }

        // Boost Vibrance
        return adjustVibrance(result, 1.2f)
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
        val MAX_PIXELS = 60_000_000L
        val targetPixels = (bitmap.width.toLong() * scaleFactor) * (bitmap.height.toLong() * scaleFactor)

        if (targetPixels > MAX_PIXELS) {
            throw IllegalArgumentException("Resulting image too large. Max 60MP.")
        }

        return try {
             TFLiteEnhancer(context).enhance(bitmap, scaleFactor)
        } catch (e: Exception) {
             OpenCVEnhancer().enhance(bitmap, scaleFactor)
        }
    }
}
