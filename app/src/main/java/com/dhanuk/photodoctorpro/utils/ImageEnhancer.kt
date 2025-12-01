package com.dhanuk.photodoctorpro.utils

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.opencv.android.Utils
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import org.opencv.photo.Photo
import java.io.IOException

object ImageEnhancer {

    suspend fun enhanceImage(context: Context, bitmap: Bitmap, scaleFactor: Int): Bitmap = withContext(Dispatchers.Default) {
        val MAX_PIXELS = 60_000_000L
        // Check input size to prevent OOM early
        if (bitmap.width * bitmap.height > 25_000_000 && scaleFactor > 4) {
             // Fallback or throw?
             // UI should have prevented this, but let's cap it or throw.
             // We'll throw to be safe, UI handles it.
             throw IllegalArgumentException("Image too large for high scaling factors.")
        }

        try {
            // 1. Super Resolution (ESRGAN)
            val upscaled = runSuperResolution(context, bitmap, scaleFactor)

            // 2. Face Enhancement (GFPGAN)
            val faceEnhanced = FaceEnhancer(context).enhanceFaces(bitmap, upscaled, scaleFactor)
            if (upscaled != faceEnhanced && upscaled != bitmap) upscaled.recycle()

            // 3. Post Processing (OpenCV)
            val finalResult = applyPostProcessing(faceEnhanced)
            if (faceEnhanced != finalResult && faceEnhanced != bitmap) faceEnhanced.recycle()

            return@withContext finalResult

        } catch (e: Exception) {
            e.printStackTrace()
            // Fallback to pure OpenCV Bicubic if generic error
            return@withContext OpenCVEnhancerFallback.enhance(bitmap, scaleFactor)
        }
    }

    private fun runSuperResolution(context: Context, bitmap: Bitmap, scaleFactor: Int): Bitmap {
        // Models
        val esrganX2 = ESRGANHelper(context, "esrgan_x2.tflite")
        val esrganX4 = ESRGANHelper(context, "esrgan_x4.tflite")

        return when (scaleFactor) {
            2 -> {
                if (esrganX2.isReady()) esrganX2.enhance(bitmap)
                else OpenCVEnhancerFallback.enhance(bitmap, 2)
            }
            4 -> {
                if (esrganX4.isReady()) esrganX4.enhance(bitmap)
                else if (esrganX2.isReady()) {
                    val step1 = esrganX2.enhance(bitmap)
                    val step2 = esrganX2.enhance(step1)
                    step1.recycle()
                    step2
                }
                else OpenCVEnhancerFallback.enhance(bitmap, 4)
            }
            6 -> {
                // 2x + 3x interpolation
                 val step1 = if (esrganX2.isReady()) esrganX2.enhance(bitmap) else OpenCVEnhancerFallback.enhance(bitmap, 2)
                 // Resize 3x using OpenCV/Bicubic
                 val w = step1.width * 3
                 val h = step1.height * 3
                 val step2 = Bitmap.createScaledBitmap(step1, w, h, true) // Filter=true is bilinear/bicubic
                 if (step1 != bitmap) step1.recycle()
                 step2
            }
            8 -> {
                // 4x + 2x
                val step1 = if (esrganX4.isReady()) esrganX4.enhance(bitmap)
                            else if (esrganX2.isReady()) {
                                val s1 = esrganX2.enhance(bitmap)
                                val s2 = esrganX2.enhance(s1)
                                s1.recycle()
                                s2
                            } else OpenCVEnhancerFallback.enhance(bitmap, 4)

                val step2 = if (esrganX2.isReady()) esrganX2.enhance(step1)
                            else OpenCVEnhancerFallback.enhance(step1, 2) // Should imply resizing

                // If fallback returns, it scales from input.
                // Wait, fallback takes "scaleFactor". OpenCVEnhancerFallback scales from Input.
                // If I call fallback(step1, 2), it scales step1 by 2x. Correct.

                if (step1 != bitmap) step1.recycle()
                step2
            }
            else -> OpenCVEnhancerFallback.enhance(bitmap, scaleFactor)
        }
    }

    private fun applyPostProcessing(bitmap: Bitmap): Bitmap {
        val src = Mat()
        Utils.bitmapToMat(bitmap, src)
        val dst = Mat()
        src.copyTo(dst)

        // 1. Unsharp Mask (Sharpening)
        val blurred = Mat()
        Imgproc.GaussianBlur(src, blurred, Size(0.0, 0.0), 3.0)
        Core.addWeighted(src, 1.5, blurred, -0.5, 0.0, dst)

        // 2. Detail Enhancement (Simple CLAHE on L channel)
        val lab = Mat()
        Imgproc.cvtColor(dst, lab, Imgproc.COLOR_RGB2Lab)
        val channels = ArrayList<Mat>()
        Core.split(lab, channels)
        val clahe = Imgproc.createCLAHE()
        clahe.clipLimit = 2.0
        clahe.apply(channels[0], channels[0])
        Core.merge(channels, lab)
        Imgproc.cvtColor(lab, dst, Imgproc.COLOR_Lab2RGB)

        // 3. Denoise (Bilateral Filter for smoothness)
        val denoised = Mat()
        Imgproc.bilateralFilter(dst, denoised, 5, 50.0, 50.0)
        // Release intermediate
        dst.release()

        val result = Bitmap.createBitmap(denoised.cols(), denoised.rows(), Bitmap.Config.ARGB_8888)
        Utils.matToBitmap(denoised, result)
        denoised.release()

        src.release()
        dst.release()
        blurred.release()
        lab.release()
        channels.forEach { it.release() }
        clahe.collectGarbage()

        // 4. Vibrance (using ColorMatrix)
        return adjustVibrance(result, 1.1f) // Slight boost
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
             // Don't recycle input here as it might be needed by caller?
             // Actually input was 'result' from Mat, safe to recycle.
             bitmap.recycle()
        }
        return bmp
    }
}

object OpenCVEnhancerFallback {
    fun enhance(bitmap: Bitmap, scaleFactor: Int): Bitmap {
        val width = bitmap.width
        val height = bitmap.height
        val targetWidth = width * scaleFactor
        val targetHeight = height * scaleFactor

        // Bicubic Resize
        val scaled = Bitmap.createBitmap(targetWidth, targetHeight, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(scaled)
        val paint = Paint()
        paint.isFilterBitmap = true
        paint.isAntiAlias = true
        // Matrix scale?
        val matrix = android.graphics.Matrix()
        matrix.postScale(scaleFactor.toFloat(), scaleFactor.toFloat())
        canvas.drawBitmap(bitmap, matrix, paint)

        return scaled
    }
}
