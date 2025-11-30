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

    // Process Full Image if small enough, else Tile
    override suspend fun enhance(bitmap: Bitmap, scaleFactor: Int): Bitmap = withContext(Dispatchers.Default) {
        val width = bitmap.width
        val height = bitmap.height

        // Threshold for Tiling: if output > 12MP roughly (3000*4000), tile it.
        // Actually, large bitmaps in OpenCV can be heavy. Let's tile if side > 1024 or so?
        // Or if total pixels > 4MP?
        // Let's be safe. If total pixels > 2_000_000 (2MP), tile.
        val TILE_SIZE = 512
        val PADDING = 32

        if (width * height <= 2_000_000) {
            return@withContext processMat(bitmap, scaleFactor)
        }

        // TILING LOGIC
        val targetWidth = width * scaleFactor
        val targetHeight = height * scaleFactor
        val outBitmap = Bitmap.createBitmap(targetWidth, targetHeight, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(outBitmap)

        // Number of tiles
        val cols = kotlin.math.ceil(width.toFloat() / TILE_SIZE).toInt()
        val rows = kotlin.math.ceil(height.toFloat() / TILE_SIZE).toInt()

        for (r in 0 until rows) {
            for (c in 0 until cols) {
                // Calculate Source Rect with Padding
                val x = c * TILE_SIZE
                val y = r * TILE_SIZE

                // Actual Tile Rect (without padding)
                val tileW = kotlin.math.min(TILE_SIZE, width - x)
                val tileH = kotlin.math.min(TILE_SIZE, height - y)

                // Padded Rect
                val padLeft = if (x > 0) PADDING else 0
                val padTop = if (y > 0) PADDING else 0
                val padRight = if (x + tileW < width) PADDING else 0
                val padBottom = if (y + tileH < height) PADDING else 0

                val srcX = x - padLeft
                val srcY = y - padTop
                val srcW = tileW + padLeft + padRight
                val srcH = tileH + padTop + padBottom

                // Crop Source Tile
                val srcTile = Bitmap.createBitmap(bitmap, srcX, srcY, srcW, srcH)

                // Process Tile
                val processedTile = processMat(srcTile, scaleFactor)

                // Calculate Dest Rect (where to draw without padding)
                // We need to crop the padding out of the processed tile
                val outPadLeft = padLeft * scaleFactor
                val outPadTop = padTop * scaleFactor
                val outW = tileW * scaleFactor
                val outH = tileH * scaleFactor

                val cropRect = Rect(outPadLeft, outPadTop, outPadLeft + outW, outPadTop + outH)
                val destRect = Rect(x * scaleFactor, y * scaleFactor, (x * scaleFactor) + outW, (y * scaleFactor) + outH)

                canvas.drawBitmap(processedTile, cropRect, destRect, null)

                srcTile.recycle()
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

        // Sharpen
        val gaussian = Mat()
        Imgproc.GaussianBlur(dst, gaussian, Size(0.0, 0.0), 2.0)
        Core.addWeighted(dst, 1.5, gaussian, -0.5, 0.0, dst)

        // CLAHE (L channel)
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

        if (bmp != bitmap) bitmap.recycle()
        return bmp
    }
}

object ImageEnhancer {

    suspend fun enhanceImage(context: Context, bitmap: Bitmap, scaleFactor: Int): Bitmap {
        // Increase Max Limit to 100MP (approx 10000x10000)
        // 12MP * 4 = 48MP. 12MP * 8 = 96MP.
        val MAX_PIXELS = 100_000_000
        val targetPixels = (bitmap.width.toLong() * scaleFactor) * (bitmap.height.toLong() * scaleFactor)

        if (targetPixels > MAX_PIXELS) {
            throw IllegalArgumentException("Resulting image too large (${targetPixels/1_000_000}MP). Limit is 100MP.")
        }

        return try {
             TFLiteEnhancer(context).enhance(bitmap, scaleFactor)
        } catch (e: Exception) {
             OpenCVEnhancer().enhance(bitmap, scaleFactor)
        }
    }
}
