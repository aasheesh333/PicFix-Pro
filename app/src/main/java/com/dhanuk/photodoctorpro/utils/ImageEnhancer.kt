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

object ImageEnhancer {

    private const val MAX_PIXELS = 50_000_000L
    private const val MAX_INPUT_PIXELS = 4_000_000L
    private const val MAX_INPUT_DIM_FOR_LARGE_SCALE = 1024

    fun shutdown() {
        // No-op: no native interpreters to close anymore
    }

    suspend fun enhanceImage(
        context: Context,
        bitmap: Bitmap,
        scaleFactor: Int,
        onProgress: ((Float) -> Unit)? = null
    ): Bitmap = withContext(Dispatchers.Default) {
        val inputPixels = bitmap.width.toLong() * bitmap.height.toLong()
        if (inputPixels > MAX_INPUT_PIXELS) {
            throw IllegalArgumentException(
                "Input image too large (${bitmap.width}x${bitmap.height} = ${inputPixels/1_000_000}MP). " +
                "Max allowed is ${MAX_INPUT_PIXELS/1_000_000}MP."
            )
        }

        val maxInputDim = if (scaleFactor >= 6) MAX_INPUT_DIM_FOR_LARGE_SCALE
            else kotlin.math.sqrt((MAX_PIXELS / (scaleFactor.toLong() * scaleFactor.toLong())).toDouble()).toInt()

        val downscale = OpenCVEnhancerFallback.downscaleIfNeeded(bitmap, maxInputDim)
        onProgress?.invoke(0.1f)

        val upscaled = OpenCVEnhancerFallback.enhance(downscale, scaleFactor)
        onProgress?.invoke(0.7f)

        val result = if (com.dhanuk.photodoctorpro.PicFixApplication.OpenCVInitialized) {
            OpenCVEnhancerFallback.applySharpening(upscaled)
        } else {
            upscaled
        }
        if (result != upscaled && result != bitmap && !upscaled.isRecycled && upscaled !== downscale) {
            upscaled.recycle()
        }
        if (downscale !== bitmap && !downscale.isRecycled) downscale.recycle()
        onProgress?.invoke(1.0f)

        return@withContext result
    }
}

object OpenCVEnhancerFallback {
    private const val MAX_PIXELS = 50_000_000L
    private const val MAX_INPUT_DIM_FOR_LARGE_SCALE = 1024

    fun downscaleIfNeeded(source: Bitmap, maxDim: Int): Bitmap {
        val w = source.width
        val h = source.height
        val longEdge = maxOf(w, h)
        if (longEdge <= maxDim) return source
        val scale = maxDim.toFloat() / longEdge.toFloat()
        val targetW = (w * scale).toInt().coerceAtLeast(1)
        val targetH = (h * scale).toInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(source, targetW, targetH, true)
    }

    fun enhance(bitmap: Bitmap, scaleFactor: Int): Bitmap {
        val width = bitmap.width
        val height = bitmap.height
        val targetWidth = width * scaleFactor
        val targetHeight = height * scaleFactor

        if (targetWidth.toLong() * targetHeight.toLong() > MAX_PIXELS) {
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
            throw IllegalArgumentException("Fallback: Result too large (${width}x${height}x${scaleFactor}).")
        }

        var current = bitmap
        var createdIntermediate = false

        val steps = buildStepPlan(scaleFactor)

        for ((stepMul, isDetailStep) in steps) {
            val upscaled = highQualityUpscale(current, stepMul)
            if (createdIntermediate && current != bitmap && !current.isRecycled) current.recycle()
            current = if (isDetailStep && com.dhanuk.photodoctorpro.PicFixApplication.OpenCVInitialized) {
                applySharpening(upscaled)
            } else {
                upscaled
            }
            createdIntermediate = true
        }

        if (com.dhanuk.photodoctorpro.PicFixApplication.OpenCVInitialized && current == bitmap) {
            current = applySharpening(current)
            createdIntermediate = true
        }

        return current
    }

    private fun buildStepPlan(scaleFactor: Int): List<Pair<Int, Boolean>> {
        return when (scaleFactor) {
            2 -> listOf(2 to true)
            4 -> listOf(2 to true, 2 to true)
            6 -> listOf(2 to true, 3 to false)
            8 -> listOf(2 to true, 2 to true, 2 to true)
            else -> listOf(scaleFactor to false)
        }
    }

    private fun highQualityUpscale(bitmap: Bitmap, factor: Int): Bitmap {
        if (factor <= 0) return bitmap
        val newW = bitmap.width * factor
        val newH = bitmap.height * factor

        if (com.dhanuk.photodoctorpro.PicFixApplication.OpenCVInitialized) {
            try {
                val src = Mat()
                val dst = Mat()
                try {
                    Utils.bitmapToMat(bitmap, src)
                    Imgproc.resize(src, dst, Size(newW.toDouble(), newH.toDouble()), 0.0, 0.0, Imgproc.INTER_LANCZOS4)
                    val result = Bitmap.createBitmap(newW, newH, Bitmap.Config.ARGB_8888)
                    Utils.matToBitmap(dst, result)
                    return result
                } finally {
                    src.release()
                    dst.release()
                }
            } catch (_: Exception) {}
        }

        val scaled = Bitmap.createBitmap(newW, newH, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(scaled)
        val paint = Paint()
        paint.isFilterBitmap = true
        paint.isAntiAlias = true
        val matrix = android.graphics.Matrix()
        matrix.postScale(factor.toFloat(), factor.toFloat())
        canvas.drawBitmap(bitmap, matrix, paint)
        return scaled
    }

    fun applySharpening(bitmap: Bitmap): Bitmap {
        try {
            val src = Mat()
            val dst = Mat()
            val blurred = Mat()
            val lab = Mat()
            val denoised = Mat()
            val channels = ArrayList<Mat>()
            try {
                Utils.bitmapToMat(bitmap, src)
                src.copyTo(dst)

                Imgproc.GaussianBlur(src, blurred, Size(0.0, 0.0), 2.0)
                Core.addWeighted(src, 2.0, blurred, -1.0, 0.0, dst)

                Imgproc.cvtColor(dst, lab, Imgproc.COLOR_BGR2Lab)
                Core.split(lab, channels)
                val clahe = Imgproc.createCLAHE()
                clahe.clipLimit = 2.0
                clahe.apply(channels[0], channels[0])
                Core.merge(channels, lab)
                Imgproc.cvtColor(lab, dst, Imgproc.COLOR_Lab2BGR)

                Imgproc.bilateralFilter(dst, denoised, 5, 30.0, 30.0)

                val result = Bitmap.createBitmap(denoised.cols(), denoised.rows(), Bitmap.Config.ARGB_8888)
                Utils.matToBitmap(denoised, result)
                if (result != bitmap && !bitmap.isRecycled) bitmap.recycle()
                return result
            } finally {
                src.release()
                dst.release()
                blurred.release()
                lab.release()
                denoised.release()
                channels.forEach { it.release() }
            }
        } catch (_: Exception) {
            return bitmap
        }
    }
}
