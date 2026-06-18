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
import org.opencv.core.Mat
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import java.io.IOException

object ImageEnhancer {

    private const val MAX_PIXELS = 50_000_000L

    private val esrganCache = mutableMapOf<String, ESRGANHelper>()
    private val esrganLock = Any()

    private fun getEsrgan(context: Context, modelFile: String): ESRGANHelper {
        synchronized(esrganLock) {
            return esrganCache.getOrPut(modelFile) {
                ESRGANHelper(context.applicationContext, modelFile)
            }
        }
    }

    suspend fun enhanceImage(context: Context, bitmap: Bitmap, scaleFactor: Int): Bitmap = withContext(Dispatchers.Default) {
        // Strict Size Check
        val targetW = bitmap.width.toLong() * scaleFactor
        val targetH = bitmap.height.toLong() * scaleFactor
        val targetPixels = targetW * targetH

        if (targetPixels > MAX_PIXELS) {
            throw IllegalArgumentException("Result too large (${targetPixels/1_000_000}MP). Max allowed is ${MAX_PIXELS/1_000_000}MP.")
        }

        try {
            // 1. Super Resolution (ESRGAN)
            val upscaled = runSuperResolution(context, bitmap, scaleFactor)

            // 2. Face Enhancement (GFPGAN)
            val faceEnhancer = FaceEnhancer.getInstance(context)
            val faceEnhanced = try {
                faceEnhancer.enhanceFaces(bitmap, upscaled, scaleFactor)
            } finally {
                // singleton - don't close
            }
            if (upscaled != faceEnhanced && upscaled != bitmap) upscaled.recycle()

            // 3. Post Processing (OpenCV)
            val finalResult = applyPostProcessing(faceEnhanced)
            if (faceEnhanced != finalResult && faceEnhanced != bitmap) faceEnhanced.recycle()

            return@withContext finalResult

        } catch (e: Throwable) {
            e.printStackTrace()
            // Fallback to pure OpenCV Bicubic if generic error or OOM
            // Ensure fallback handles size check too (redundant but safe)
            return@withContext OpenCVEnhancerFallback.enhance(bitmap, scaleFactor)
        }
    }

    private fun runSuperResolution(context: Context, bitmap: Bitmap, scaleFactor: Int): Bitmap {
        val esrganX2 = getEsrgan(context, "esrgan_x2.tflite")
        val esrganX4 = getEsrgan(context, "esrgan_x4.tflite")

        try {
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
                    val step1 = if (esrganX2.isReady()) esrganX2.enhance(bitmap) else OpenCVEnhancerFallback.enhance(bitmap, 2)
                    val w = step1.width * 3
                    val h = step1.height * 3
                    val step2 = Bitmap.createScaledBitmap(step1, w, h, true)
                    if (step1 != bitmap) step1.recycle()
                    step2
                }
                8 -> {
                    val step1 = if (esrganX4.isReady()) esrganX4.enhance(bitmap)
                                else if (esrganX2.isReady()) {
                                    val s1 = esrganX2.enhance(bitmap)
                                    val s2 = esrganX2.enhance(s1)
                                    s1.recycle()
                                    s2
                                } else OpenCVEnhancerFallback.enhance(bitmap, 4)

                    val step2 = if (esrganX2.isReady()) esrganX2.enhance(step1)
                                else OpenCVEnhancerFallback.enhance(step1, 2)

                    if (step1 != bitmap) step1.recycle()
                    step2
                }
                else -> OpenCVEnhancerFallback.enhance(bitmap, scaleFactor)
            }
        } catch (e: Throwable) {
            throw e
        }
    }

    private fun applyPostProcessing(bitmap: Bitmap): Bitmap {
        val src = Mat()
        Utils.bitmapToMat(bitmap, src)
        val dst = Mat()
        src.copyTo(dst)

        // 1. Unsharp Mask
        val blurred = Mat()
        Imgproc.GaussianBlur(src, blurred, Size(0.0, 0.0), 3.0)
        Core.addWeighted(src, 1.5, blurred, -0.5, 0.0, dst)

        // 2. Detail Enhancement (CLAHE)
        val lab = Mat()
        Imgproc.cvtColor(dst, lab, Imgproc.COLOR_RGB2Lab)
        val channels = ArrayList<Mat>()
        Core.split(lab, channels)
        val clahe = Imgproc.createCLAHE()
        clahe.clipLimit = 2.0
        clahe.apply(channels[0], channels[0])
        Core.merge(channels, lab)
        Imgproc.cvtColor(lab, dst, Imgproc.COLOR_Lab2RGB)

        // 3. Denoise (Bilateral Filter)
        val denoised = Mat()
        Imgproc.bilateralFilter(dst, denoised, 5, 50.0, 50.0)
        dst.release()

        val result = Bitmap.createBitmap(denoised.cols(), denoised.rows(), Bitmap.Config.ARGB_8888)
        Utils.matToBitmap(denoised, result)
        denoised.release()

        src.release()
        blurred.release()
        lab.release()
        channels.forEach { it.release() }
        clahe.collectGarbage()

        // 4. Vibrance
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

object OpenCVEnhancerFallback {
    fun enhance(bitmap: Bitmap, scaleFactor: Int): Bitmap {
        val width = bitmap.width
        val height = bitmap.height
        val targetWidth = width * scaleFactor
        val targetHeight = height * scaleFactor

        if (targetWidth.toLong() * targetHeight.toLong() > 50_000_000L) {
             throw IllegalArgumentException("Fallback: Result too large.")
        }

        val scaled = Bitmap.createBitmap(targetWidth, targetHeight, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(scaled)
        val paint = Paint()
        paint.isFilterBitmap = true
        paint.isAntiAlias = true
        val matrix = android.graphics.Matrix()
        matrix.postScale(scaleFactor.toFloat(), scaleFactor.toFloat())
        canvas.drawBitmap(bitmap, matrix, paint)

        return scaled
    }
}
