package com.dhanuk.photodoctorpro.utils

import android.content.Context
import android.graphics.Bitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.opencv.android.Utils
import org.opencv.core.Core
import org.opencv.core.Mat
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import org.opencv.photo.Photo
import com.dhanuk.photodoctorpro.nativ.RealESRGANNativeLib
import com.dhanuk.photodoctorpro.nativ.ProgressCallback

object ImageEnhancer {

    private var tierInitialized = 0
    private const val MODEL_DIR_STANDARD = "realesrgan-x4plus-anime"
    private const val MODEL_DIR_HD = "realesrgan-x4plus"
    private const val MODEL_NAME = "x4"
    private const val MODEL_SCALE = 4

    private var currentModelDir: String = MODEL_DIR_STANDARD

    fun shutdown() {
        RealESRGANNativeLib.cleanup()
        tierInitialized = 0
        currentModelDir = MODEL_DIR_STANDARD
    }

    suspend fun initializeIfNeeded(context: Context, modelDir: String) = withContext(Dispatchers.Default) {
        val resolvedDir = when (modelDir) {
            "hd" -> MODEL_DIR_HD
            else -> MODEL_DIR_STANDARD
        }
        if (resolvedDir == currentModelDir && tierInitialized != 0) return@withContext
        RealESRGANNativeLib.cleanup()
        tierInitialized = 0
        currentModelDir = resolvedDir
        tryInitNcnn(context, useGpu = true)
        if (tierInitialized == 0) {
            tryInitNcnn(context, useGpu = false)
        }
    }

    suspend fun enhanceImage(
        context: Context,
        bitmap: Bitmap,
        scaleFactor: Int,
        onProgress: ((Float) -> Unit)? = null
    ): Bitmap = withContext(Dispatchers.Default) {
        onProgress?.invoke(0.05f)

        if (tryInitNcnn(context, useGpu = true)) {
            try {
                val result = enhanceWithNcnn(bitmap, scaleFactor, onProgress)
                if (result != null) {
                    onProgress?.invoke(1.0f)
                    return@withContext result
                }
            } catch (_: Exception) {}
        }

        if (tryInitNcnn(context, useGpu = false)) {
            try {
                val result = enhanceWithNcnn(bitmap, scaleFactor, onProgress)
                if (result != null) {
                    onProgress?.invoke(1.0f)
                    return@withContext result
                }
            } catch (_: Exception) {}
        }

        onProgress?.invoke(0.3f)
        val result = enhanceWithOpenCv(bitmap, scaleFactor)
        onProgress?.invoke(1.0f)
        return@withContext result
    }

    private fun tryInitNcnn(context: Context, useGpu: Boolean): Boolean {
        if (useGpu && tierInitialized == 1) return true
        if (!useGpu && tierInitialized == 2) return true

        val initialized = try {
            RealESRGANNativeLib.initialize(context, currentModelDir, MODEL_NAME, MODEL_SCALE, useGpu)
        } catch (_: Exception) {
            false
        }

        if (initialized) {
            tierInitialized = if (useGpu) 1 else 2
            return true
        }
        return false
    }

    private suspend fun enhanceWithNcnn(
        bitmap: Bitmap,
        scaleFactor: Int,
        onProgress: ((Float) -> Unit)? = null
    ): Bitmap? {
        val progressWrapper = object : ProgressCallback {
            override fun onProgress(progress: Float) {
                onProgress?.invoke(progress * 0.9f)
            }
        }

        val x4Result = RealESRGANNativeLib.enhance(bitmap, progressWrapper) ?: return null

        return when (scaleFactor) {
            2 -> {
                val w = bitmap.width * 2
                val h = bitmap.height * 2
                Bitmap.createScaledBitmap(x4Result, w, h, true).also {
                    if (it !== x4Result) x4Result.recycle()
                }
            }
            4 -> x4Result
            6, 8 -> {
                val targetW = bitmap.width * scaleFactor
                val targetH = bitmap.height * scaleFactor

                if (com.dhanuk.photodoctorpro.PicFixApplication.OpenCVInitialized) {
                    val src = Utils.bitmapToMat(x4Result)
                    val dst = Mat()
                    Imgproc.resize(src, dst, Size(targetW.toDouble(), targetH.toDouble()), 0.0, 0.0, Imgproc.INTER_LANCZOS4)
                    val result = Bitmap.createBitmap(targetW, targetH, Bitmap.Config.ARGB_8888)
                    Utils.matToBitmap(dst, result)
                    src.release()
                    dst.release()
                    if (result !== x4Result) x4Result.recycle()
                    result
                } else {
                    Bitmap.createScaledBitmap(x4Result, targetW, targetH, true).also {
                        if (it !== x4Result) x4Result.recycle()
                    }
                }
            }
            else -> {
                val targetW = bitmap.width * scaleFactor
                val targetH = bitmap.height * scaleFactor
                Bitmap.createScaledBitmap(x4Result, targetW, targetH, true).also {
                    if (it !== x4Result) x4Result.recycle()
                }
            }
        }
    }

    private fun enhanceWithOpenCv(bitmap: Bitmap, scaleFactor: Int): Bitmap {
        if (!com.dhanuk.photodoctorpro.PicFixApplication.OpenCVInitialized) {
            return Bitmap.createScaledBitmap(
                bitmap,
                bitmap.width * scaleFactor,
                bitmap.height * scaleFactor,
                true
            )
        }

        val src = Mat()
        val upscaled = Mat()
        Utils.bitmapToMat(bitmap, src)
        val targetW = bitmap.width * scaleFactor
        val targetH = bitmap.height * scaleFactor
        Imgproc.resize(src, upscaled, Size(targetW.toDouble(), targetH.toDouble()), 0.0, 0.0, Imgproc.INTER_LANCZOS4)
        src.release()

        var current = upscaled
        var ownsCurrent = true

        val denoised = Mat()
        try {
            Photo.edgePreservingFilter(current, denoised, Photo.RECURS_FILTER, 60.0, 0.4)
            if (ownsCurrent) current.release()
            current = denoised
            ownsCurrent = false
        } catch (_: Exception) {}

        ownsCurrent = true
        val enhanced = Mat()
        try {
            Photo.detailEnhance(current, enhanced, 20.0f, 0.2f)
            if (ownsCurrent) current.release()
            current = enhanced
            ownsCurrent = true
        } catch (_: Exception) {
            ownsCurrent = true
        }

        val lab = Mat()
        val out = Mat()
        try {
            Imgproc.cvtColor(current, lab, Imgproc.COLOR_BGR2Lab)
            val channels = ArrayList<Mat>()
            Core.split(lab, channels)
            val clahe = Imgproc.createCLAHE()
            clahe.clipLimit = 1.2
            clahe.apply(channels[0], channels[0])
            Core.merge(channels, lab)
            channels.forEach { it.release() }
            Imgproc.cvtColor(lab, out, Imgproc.COLOR_Lab2BGR)
            lab.release()
            current.release()
            current = out
        } catch (_: Exception) {}

        val result = Bitmap.createBitmap(targetW, targetH, Bitmap.Config.ARGB_8888)
        Utils.matToBitmap(current, result)
        current.release()
        return result
    }
}
