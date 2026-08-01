package com.dhanuk.photodoctorpro.utils

import android.content.Context
import android.graphics.Bitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.opencv.android.Utils
import org.opencv.core.Core
import org.opencv.core.Mat
import org.opencv.imgproc.Imgproc
import org.opencv.photo.Photo
import com.dhanuk.photodoctorpro.nativ.RealESRGANNativeLib

object ImageEnhancer {

    @Volatile private var tierInitialized = 0
    private const val MODEL_DIR_STANDARD = "realesrgan-x4plus-anime"
    private const val MODEL_DIR_HD = "realesrgan-x4plus"
    private const val MODEL_NAME = "x4"
    private const val MODEL_SCALE = 4

    @Volatile private var currentModelDir: String = MODEL_DIR_STANDARD

    private val enhanceLock = Mutex()

    fun shutdown() {
        RealESRGANNativeLib.cleanup()
        tierInitialized = 0
        currentModelDir = MODEL_DIR_STANDARD
    }

    suspend fun initializeIfNeeded(context: Context, modelDir: String) = withContext(Dispatchers.Default) {
        enhanceLock.withLock {
            val resolvedDir = when (modelDir) {
                "hd" -> MODEL_DIR_HD
                else -> MODEL_DIR_STANDARD
            }
            if (resolvedDir == currentModelDir && tierInitialized != 0) return@withLock
            RealESRGANNativeLib.cleanup()
            tierInitialized = 0
            currentModelDir = resolvedDir
            tryInitNcnn(context, useGpu = true)
            if (tierInitialized == 0) {
                tryInitNcnn(context, useGpu = false)
            }
        }
    }

    suspend fun enhanceImage(
        context: Context,
        bitmap: Bitmap,
        scaleFactor: Int,
        onProgress: ((Float) -> Unit)? = null
    ): Bitmap = withContext(Dispatchers.Default) {
        enhanceLock.withLock {
            onProgress?.invoke(0.05f)

            // If already initialized, go straight to inference.
            if (tierInitialized != 0) {
                try {
                    val result = enhanceWithNcnn(bitmap, scaleFactor, onProgress)
                    if (result != null) {
                        onProgress?.invoke(1.0f)
                        return@withLock result
                    }
                } catch (_: Exception) {}
            }

            // Not initialized or ncnn failed — try GPU init first.
            if (tierInitialized == 0 && tryInitNcnn(context, useGpu = true)) {
                try {
                    val result = enhanceWithNcnn(bitmap, scaleFactor, onProgress)
                    if (result != null) {
                        onProgress?.invoke(1.0f)
                        return@withLock result
                    }
                } catch (_: Exception) {}
            }

            // GPU path failed: release the (possibly GPU) net and try CPU.
            if (tierInitialized != 2) {
                RealESRGANNativeLib.cleanup()
                tierInitialized = 0
                if (tryInitNcnn(context, useGpu = false)) {
                    try {
                        val result = enhanceWithNcnn(bitmap, scaleFactor, onProgress)
                        if (result != null) {
                            onProgress?.invoke(1.0f)
                            return@withLock result
                        }
                    } catch (_: Exception) {}
                }
            }

            onProgress?.invoke(0.3f)
            val result = enhanceWithOpenCv(bitmap, scaleFactor)
            onProgress?.invoke(1.0f)
            result
        }
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
        val progressLambda: ((Float) -> Unit)? = if (onProgress != null) {
            { progress: Float -> onProgress(progress * 0.9f) }
        } else null

        val x4Result = RealESRGANNativeLib.enhance(bitmap, progressLambda) ?: return null

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
                // Simple Android-native resize — no channel-order headaches
                Bitmap.createScaledBitmap(x4Result, targetW, targetH, true).also {
                    if (it !== x4Result) x4Result.recycle()
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
        val targetW = bitmap.width * scaleFactor
        val targetH = bitmap.height * scaleFactor

        if (!com.dhanuk.photodoctorpro.PicFixApplication.OpenCVInitialized) {
            return Bitmap.createScaledBitmap(bitmap, targetW, targetH, true)
        }

        // Use Android's built-in Lanczos via createScaledBitmap for the resize
        // (fast, no channel-order issues), then apply OpenCV enhancement filters.
        val upscaled = Bitmap.createScaledBitmap(bitmap, targetW, targetH, true)

        val src = Mat()
        Utils.bitmapToMat(upscaled, src)
        // bitmapToMat gives RGBA; OpenCV Photo/Imgproc expect BGR
        Imgproc.cvtColor(src, src, Imgproc.COLOR_RGBA2BGR)
        upscaled.recycle()

        var current = src

        val denoised = Mat()
        try {
            Photo.edgePreservingFilter(current, denoised, Photo.RECURS_FILTER, 60.0f, 0.4f)
            current.release()
            current = denoised
        } catch (_: Exception) {}

        val enhanced = Mat()
        try {
            Photo.detailEnhance(current, enhanced, 20.0f, 0.2f)
            current.release()
            current = enhanced
        } catch (_: Exception) {}

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

        // BGR → RGBA for matToBitmap
        val rgba = Mat()
        Imgproc.cvtColor(current, rgba, Imgproc.COLOR_BGR2RGBA)
        current.release()
        val result = Bitmap.createBitmap(targetW, targetH, Bitmap.Config.ARGB_8888)
        Utils.matToBitmap(rgba, result)
        rgba.release()
        return result
    }
}
