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

        // Tile if > 2MP approx
        if (width * height <= 2_000_000) {
            return@withContext processMat(bitmap, scaleFactor)
        }

        val TILE_SIZE = 512
        val PADDING = 32

        val targetWidth = width * scaleFactor
        val targetHeight = height * scaleFactor

        // OOM Safety Check happens in ImageEnhancer wrapper, but we check allocation here too
        // We need huge memory for the result bitmap.
        // 100MP RGBA = 400MB. This is risky but requested.

        val outBitmap = Bitmap.createBitmap(targetWidth, targetHeight, Bitmap.Config.ARGB_8888)
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
                // We map padding to output scale
                val padLeft = (x - startX) * scaleFactor
                val padTop = (y - startY) * scaleFactor
                val validW = w * scaleFactor
                val validH = h * scaleFactor

                // Source Rect (Center of processed tile)
                val srcRect = Rect(padLeft, padTop, padLeft + validW, padTop + validH)

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
        // Sigma 2.0 is decent
        Imgproc.GaussianBlur(dst, gaussian, Size(0.0, 0.0), 2.0)
        // src * 1.5 + blurred * -0.5 = sharpened
        Core.addWeighted(dst, 1.5, gaussian, -0.5, 0.0, dst)

        // CLAHE for Detail/Contrast
        val lab = Mat()
        Imgproc.cvtColor(dst, lab, Imgproc.COLOR_RGB2Lab)
        val channels = ArrayList<Mat>()
        Core.split(lab, channels)

        val clahe = Imgproc.createCLAHE()
        clahe.clipLimit = 2.0
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
             // Don't recycle passed-in bitmap here if it's used later?
             // In this flow, 'bitmap' is created in processMat logic (result) so we can recycle it.
             bitmap.recycle()
        }
        return bmp
    }
}

object ImageEnhancer {

    suspend fun enhanceImage(context: Context, bitmap: Bitmap, scaleFactor: Int): Bitmap {
        val MAX_PIXELS = 100_000_000L // 100MP
        val targetPixels = (bitmap.width.toLong() * scaleFactor) * (bitmap.height.toLong() * scaleFactor)

        if (targetPixels > MAX_PIXELS) {
            throw IllegalArgumentException("Resulting image too large (${targetPixels/1_000_000}MP). Max 100MP.")
        }

        // If > 25MP source, maybe block 6x/8x?
        val sourcePixels = bitmap.width.toLong() * bitmap.height.toLong()
        if (sourcePixels > 25_000_000L && scaleFactor > 4) {
             throw IllegalArgumentException("Image too large (>25MP) for ${scaleFactor}x enhancement.")
        }

        return try {
             TFLiteEnhancer(context).enhance(bitmap, scaleFactor)
        } catch (e: Exception) {
             OpenCVEnhancer().enhance(bitmap, scaleFactor)
        }
    }
}
