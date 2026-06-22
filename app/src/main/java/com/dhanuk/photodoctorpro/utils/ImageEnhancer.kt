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
    private const val MAX_INPUT_PIXELS = 4_000_000L  // Cap *input* size to prevent OOM during inference
    private const val MAX_INPUT_DIM_FOR_LARGE_SCALE = 1024  // For 6x/8x, downscale input to ≤1024 long edge

    private val esrganCache = mutableMapOf<String, ESRGANHelper>()
    private val esrganLock = Any()
    // Global lock to prevent two ESRGAN interpreters (x2 + x4) from both grabbing GPU memory
    // and the same input bitmap at the same time.
    private val inferenceLock = Any()

    private fun getEsrgan(context: Context, modelFile: String): ESRGANHelper {
        synchronized(esrganLock) {
            return esrganCache.getOrPut(modelFile) {
                ESRGANHelper(context.applicationContext, modelFile)
            }
        }
    }

    fun shutdown() {
        synchronized(esrganLock) {
            esrganCache.values.forEach { it.close() }
            esrganCache.clear()
        }
    }

    /**
     * Downscale the input bitmap so its long edge is at most [maxDim] pixels.
     * Returns the original bitmap if no scaling is needed, or a new ARGB_8888
     * bitmap (caller is responsible for recycling the returned bitmap when done).
     */
    private fun downscaleIfNeeded(source: Bitmap, maxDim: Int): Bitmap {
        val w = source.width
        val h = source.height
        val longEdge = maxOf(w, h)
        if (longEdge <= maxDim) return source
        val scale = maxDim.toFloat() / longEdge.toFloat()
        val targetW = (w * scale).toInt().coerceAtLeast(1)
        val targetH = (h * scale).toInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(source, targetW, targetH, true)
    }

    /**
     * Compute the maximum allowed long edge for the input bitmap so that the
     * output stays within [MAX_PIXELS]. For 2x/4x, the input is downscaled if
     * it would push the output over the limit. For 6x/8x, the input is
     * downscaled to [MAX_INPUT_DIM_FOR_LARGE_SCALE] (1024) regardless.
     */
    private fun maxInputDimForScale(scaleFactor: Int): Int {
        if (scaleFactor >= 6) return MAX_INPUT_DIM_FOR_LARGE_SCALE
        // For 2x/4x, derive a long-edge cap from the output budget.
        val maxInputPixels = MAX_PIXELS / (scaleFactor.toLong() * scaleFactor.toLong())
        // Worst case: square image. longEdge ~= sqrt(maxInputPixels).
        return kotlin.math.sqrt(maxInputPixels.toDouble()).toInt()
    }

    suspend fun enhanceImage(
        context: Context,
        bitmap: Bitmap,
        scaleFactor: Int,
        onProgress: ((Float) -> Unit)? = null
    ): Bitmap = withContext(Dispatchers.Default) {
        // Strict size checks on the *input* — prevent OOM before we allocate anything.
        val inputPixels = bitmap.width.toLong() * bitmap.height.toLong()
        if (inputPixels > MAX_INPUT_PIXELS) {
            throw IllegalArgumentException(
                "Input image too large (${bitmap.width}×${bitmap.height} = ${inputPixels/1_000_000}MP). " +
                "Max allowed is ${MAX_INPUT_PIXELS/1_000_000}MP."
            )
        }

        // Pre-flight downscale for large scales. For 6x/8x, cap at 1024 long edge.
        // For 2x/4x, derive a long-edge cap from the output pixel budget.
        val maxInputDim = maxInputDimForScale(scaleFactor)
        var workingBitmap: Bitmap? = null
        val inputForModel: Bitmap = downscaleIfNeeded(bitmap, maxInputDim).also { scaled ->
            if (scaled !== bitmap) workingBitmap = scaled
        }
        try {
            // Strict size check on the (possibly downscaled) output
            val targetW = inputForModel.width.toLong() * scaleFactor
            val targetH = inputForModel.height.toLong() * scaleFactor
            val targetPixels = targetW * targetH

            if (targetPixels > MAX_PIXELS) {
                throw IllegalArgumentException(
                    "Result too large (${targetPixels/1_000_000}MP). Max allowed is ${MAX_PIXELS/1_000_000}MP."
                )
            }

            // 1. Super Resolution (ESRGAN) — under a global lock so two ESRGAN
            // interpreters don't fight for the GPU delegate at the same time.
            onProgress?.invoke(0.1f)
            val upscaled = synchronized(inferenceLock) {
                runSuperResolution(context, inputForModel, scaleFactor)
            }
            onProgress?.invoke(0.6f)

            // 2. Face Enhancement (GFPGAN) — pass the *original* (small) image
            // as the face-detection source so we don't re-detect on the upscaled
            // bitmap (which is much larger and slower to scan).
            val faceEnhancer = FaceEnhancer.getInstance(context)
            val faceEnhanced = try {
                faceEnhancer.enhanceFaces(bitmap, upscaled, scaleFactor)
            } catch (e: Exception) {
                if (com.dhanuk.photodoctorpro.BuildConfig.DEBUG) {
                    android.util.Log.e("ImageEnhancer", "Face enhancement failed, using upscaled", e)
                }
                upscaled
            }
            if (upscaled != faceEnhanced && upscaled != inputForModel) upscaled.recycle()
            onProgress?.invoke(0.8f)

            // 3. Post Processing (OpenCV)
            val finalResult = applyPostProcessing(faceEnhanced)
            if (faceEnhanced != finalResult && faceEnhanced != inputForModel) faceEnhanced.recycle()
            onProgress?.invoke(1.0f)

            return@withContext finalResult

        } finally {
            // Recycle the intermediate downscale (if we created one) since the
            // model has finished with it. The caller's `bitmap` is never touched.
            val wb = workingBitmap
            if (wb != null && !wb.isRecycled) {
                wb.recycle()
            }
        }
    }

    private fun runSuperResolution(context: Context, bitmap: Bitmap, scaleFactor: Int): Bitmap {
        val esrganX2 = getEsrgan(context, "esrgan_x2.tflite")
        val esrganX4 = getEsrgan(context, "esrgan_x4.tflite")

        return when (scaleFactor) {
            2 -> {
                if (esrganX2.isReady()) esrganX2.enhance(bitmap)
                else OpenCVEnhancerFallback.enhance(bitmap, 2)
            }
            4 -> {
                if (esrganX4.isReady()) esrganX4.enhance(bitmap)
                else if (esrganX2.isReady()) {
                    val step1 = esrganX2.enhance(bitmap)
                    try {
                        val step2 = esrganX2.enhance(step1)
                        step1.recycle()
                        step2
                    } catch (e: Exception) {
                        step1.recycle()
                        throw e
                    }
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
    }

    private fun applyPostProcessing(bitmap: Bitmap): Bitmap {
        val src = Mat()
        val dst = Mat()
        val blurred = Mat()
        val lab = Mat()
        val denoised = Mat()
        val channels = ArrayList<Mat>()
        try {
            Utils.bitmapToMat(bitmap, src)
            // Android bitmaps are BGRA in native memory; OpenCV's bitmapToMat returns
            // BGRA. We must use COLOR_BGR2Lab here, not COLOR_RGB2Lab, otherwise the
            // red and blue channels are swapped in the output.
            src.copyTo(dst)

            // 1. Unsharp Mask
            Imgproc.GaussianBlur(src, blurred, Size(0.0, 0.0), 3.0)
            Core.addWeighted(src, 1.5, blurred, -0.5, 0.0, dst)

            // 2. Detail Enhancement (CLAHE)
            Imgproc.cvtColor(dst, lab, Imgproc.COLOR_BGR2Lab)
            Core.split(lab, channels)
            val clahe = Imgproc.createCLAHE()
            clahe.clipLimit = 2.0
            clahe.apply(channels[0], channels[0])
            Core.merge(channels, lab)
            Imgproc.cvtColor(lab, dst, Imgproc.COLOR_Lab2BGR)

            // 3. Denoise (Bilateral Filter)
            Imgproc.bilateralFilter(dst, denoised, 5, 50.0, 50.0)

            val result = Bitmap.createBitmap(denoised.cols(), denoised.rows(), Bitmap.Config.ARGB_8888)
            Utils.matToBitmap(denoised, result)

            return adjustVibrance(result, 1.1f)
        } finally {
            src.release()
            dst.release()
            blurred.release()
            lab.release()
            denoised.release()
            channels.forEach { it.release() }
        }
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
    private const val MAX_PIXELS = 50_000_000L
    private const val MAX_INPUT_DIM_FOR_LARGE_SCALE = 1024

    fun enhance(bitmap: Bitmap, scaleFactor: Int): Bitmap {
        val width = bitmap.width
        val height = bitmap.height
        val targetWidth = width * scaleFactor
        val targetHeight = height * scaleFactor

        if (targetWidth.toLong() * targetHeight.toLong() > MAX_PIXELS) {
            // Pre-flight downscale to bring the output under MAX_PIXELS.
            val longEdge = maxOf(width, height)
            if (longEdge > MAX_INPUT_DIM_FOR_LARGE_SCALE && scaleFactor >= 6) {
                val scale = MAX_INPUT_DIM_FOR_LARGE_SCALE.toFloat() / longEdge.toFloat()
                val smallW = (width * scale).toInt().coerceAtLeast(1)
                val smallH = (height * scale).toInt().coerceAtLeast(1)
                val downscaled = Bitmap.createScaledBitmap(bitmap, smallW, smallH, true)
                val out = enhance(downscaled, scaleFactor)
                if (downscaled !== bitmap && !downscaled.isRecycled) downscaled.recycle()
                return out
            }
            throw IllegalArgumentException("Fallback: Result too large (${width}×${height}×${scaleFactor}).")
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
