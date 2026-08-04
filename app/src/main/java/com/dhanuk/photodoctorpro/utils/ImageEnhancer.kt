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
            // Tell the native lib whether to use the smaller fast-mode input cap.
            RealESRGANNativeLib.setFastMode(resolvedDir == MODEL_DIR_STANDARD)
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
                        val textured = applyRealisticTexture(result)
                        onProgress?.invoke(1.0f)
                        return@withLock textured
                    }
                } catch (_: Exception) {}
            }

            // Not initialized or ncnn failed — try GPU init first.
            if (tierInitialized == 0 && tryInitNcnn(context, useGpu = true)) {
                try {
                    val result = enhanceWithNcnn(bitmap, scaleFactor, onProgress)
                    if (result != null) {
                        val textured = applyRealisticTexture(result)
                        onProgress?.invoke(1.0f)
                        return@withLock textured
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
                            val textured = applyRealisticTexture(result)
                            onProgress?.invoke(1.0f)
                            return@withLock textured
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

    /**
     * Post-process the ncnn result so it looks like a real photo instead of the
     * classic RealESRGAN "plastic" output. Mirrors what apps like Picsart do:
     * restore micro-contrast and edge texture, and add a tiny amount of grain.
     *
     * Runs entirely in OpenCV on Dispatchers.Default — typically 150-400ms even
     * on a 4K output, so it does not regress the speed wins above.
     */
    private fun applyRealisticTexture(bitmap: Bitmap): Bitmap {
        if (!com.dhanuk.photodoctorpro.PicFixApplication.OpenCVInitialized) return bitmap

        val src = Mat()
        Utils.bitmapToMat(bitmap, src)
        if (src.empty()) { src.release(); return bitmap }
        // Work in BGR (bitmapToMat gives RGBA).
        Imgproc.cvtColor(src, src, Imgproc.COLOR_RGBA2BGR)

        var current = src

        // 1) Unsharp mask: out = src*1.3 - blur*0.3. Restores edge texture the
        //    model smoothed over (skin pores, hair, fabric) without halos or
        //    the over-baked look stronger amounts produced.
        try {
            val blurred = Mat()
            Imgproc.GaussianBlur(current, blurred, org.opencv.core.Size(0.0, 0.0), 2.0)
            val sharpened = Mat()
            Core.addWeighted(current, 1.3, blurred, -0.3, 0.0, sharpened)
            blurred.release()
            current.release()
            current = sharpened
        } catch (_: Exception) {}

        // 2) CLAHE on the L channel only (Lab): micro-contrast, no color shift.
        try {
            val lab = Mat()
            Imgproc.cvtColor(current, lab, Imgproc.COLOR_BGR2Lab)
            val channels = ArrayList<Mat>()
            Core.split(lab, channels)
            val clahe = Imgproc.createCLAHE()
            clahe.clipLimit = 1.1
            clahe.apply(channels[0], channels[0])
            Core.merge(channels, lab)
            channels.forEach { it.release() }
            val out = Mat()
            Imgproc.cvtColor(lab, out, Imgproc.COLOR_Lab2BGR)
            lab.release()
            current.release()
            current = out
        } catch (_: Exception) {}

        // 3) (grain pass removed — it made 2x results look noisy/dirty; CLAHE +
        //    the gentler unsharp above are enough texture for the "photo" look)

        // Restore the alpha channel — the passes above run on a BGR copy and
        // would otherwise make every pixel opaque.
        if (bitmap.hasAlpha()) {
            try {
                val alpha = extractAlphaChannel(bitmap, current.cols(), current.rows())
                if (alpha != null) {
                    val merged = Mat()
                    val bgraChannels = ArrayList<Mat>()
                    Core.split(current, bgraChannels)
                    bgraChannels.add(alpha)
                    Core.merge(bgraChannels, merged)
                    bgraChannels.forEach { it.release() }
                    current.release()
                    current = merged
                    // Skip the BGR->RGBA conversion below by converting BGRA->RGBA.
                    val rgbaOut = Mat()
                    Imgproc.cvtColor(current, rgbaOut, Imgproc.COLOR_BGRA2RGBA)
                    current.release()
                    val out = Bitmap.createBitmap(bitmap.width, bitmap.height, Bitmap.Config.ARGB_8888)
                    Utils.matToBitmap(rgbaOut, out)
                    rgbaOut.release()
                    if (!out.sameAs(bitmap)) bitmap.recycle()
                    return out
                }
            } catch (_: Exception) {}
        }

        // Back to RGBA for matToBitmap (opaque source).
        val rgba = Mat()
        Imgproc.cvtColor(current, rgba, Imgproc.COLOR_BGR2RGBA)
        current.release()
        val result = Bitmap.createBitmap(bitmap.width, bitmap.height, Bitmap.Config.ARGB_8888)
        Utils.matToBitmap(rgba, result)
        rgba.release()
        if (!result.sameAs(bitmap)) bitmap.recycle()
        return result
    }

    /** Pulls the alpha plane out of an ARGB_8888 bitmap into a single-channel Mat. */
    private fun extractAlphaChannel(bitmap: Bitmap, w: Int, h: Int): Mat? {
        if (bitmap.width != w || bitmap.height != h) return null
        return try {
            val src = Mat()
            Utils.bitmapToMat(bitmap, src)
            val channels = ArrayList<Mat>()
            Core.split(src, channels)
            val alpha = channels[3]
            channels[0].release(); channels[1].release(); channels[2].release()
            src.release()
            alpha
        } catch (_: Exception) { null }
    }

    private fun tryInitNcnn(context: Context, useGpu: Boolean): Boolean {
        if (useGpu && tierInitialized == 1) return true
        if (!useGpu && tierInitialized == 2) return true

        // Keep the fast-mode input cap in sync with whichever model dir is active.
        RealESRGANNativeLib.setFastMode(currentModelDir == MODEL_DIR_STANDARD)

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

        // The native lib now owns all scaling: it pre-scales the input for
        // scales < 4 so its x4 output is already the final target size, and
        // restores the result to bitmap.width/height x targetScale otherwise.
        return RealESRGANNativeLib.enhance(bitmap, scaleFactor, progressLambda)
    }

    private fun enhanceWithOpenCv(bitmap: Bitmap, scaleFactor: Int): Bitmap {
        val targetW = bitmap.width * scaleFactor
        val targetH = bitmap.height * scaleFactor

        if (!com.dhanuk.photodoctorpro.PicFixApplication.OpenCVInitialized) {
            return Bitmap.createScaledBitmap(bitmap, targetW, targetH, true)
        }

        // Fallback safety: run the expensive filters at a capped size (the
        // edgePreserving/detailEnhance/CLAHE stack is the slow part), then do
        // one fast native upscale to the final target. Without this, a 4x of a
        // big photo meant 100+MP Mats and multi-minute stalls.
        val workMaxDim = 4096
        val targetMaxDim = maxOf(targetW, targetH)
        val workScale = if (targetMaxDim > workMaxDim) workMaxDim.toFloat() / targetMaxDim else 1f
        val workW = (targetW * workScale).toInt().coerceAtLeast(1)
        val workH = (targetH * workScale).toInt().coerceAtLeast(1)

        // Use Android's built-in Lanczos via createScaledBitmap for the resize
        // (fast, no channel-order issues), then apply OpenCV enhancement filters.
        val upscaled = Bitmap.createScaledBitmap(bitmap, workW, workH, true)

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
        val filtered = Bitmap.createBitmap(workW, workH, Bitmap.Config.ARGB_8888)
        Utils.matToBitmap(rgba, filtered)
        rgba.release()

        // Final fast native upscale to the user's target size.
        if (workW == targetW && workH == targetH) return filtered
        return Bitmap.createScaledBitmap(filtered, targetW, targetH, true).also {
            if (it !== filtered) filtered.recycle()
        }
    }
}
