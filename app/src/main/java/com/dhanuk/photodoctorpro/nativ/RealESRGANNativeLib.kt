package com.dhanuk.photodoctorpro.nativ

import android.content.Context
import android.graphics.Bitmap
import java.io.File
import java.io.FileOutputStream

interface ProgressCallback {
    fun onProgress(progress: Float)
}

object RealESRGANNativeLib {

    @Volatile private var isInitialized = false
    private val initLock = Any()
    @Volatile private var currentScale = 4

    private const val MAX_OUTPUT_PIXELS = 36_000_000L
    private const val MAX_INPUT_DIM = 900 // 900*4=3600 → 3600*3600 ≈ 13M px (well under 36M)

    var nativeAvailable = true
        private set

    init {
        try {
            System.loadLibrary("ncnn")
        } catch (_: UnsatisfiedLinkError) {
            nativeAvailable = false
        }
        try {
            System.loadLibrary("realesrgan_jni")
        } catch (_: UnsatisfiedLinkError) {
            nativeAvailable = false
        }
    }

    private external fun nativeInit(
        paramPath: String,
        modelPath: String,
        scale: Int,
        gpuId: Int
    ): Int

    private external fun nativeEnhance(
        bitmap: Bitmap,
        progressCallback: ProgressCallback?
    ): Bitmap?

    private external fun nativeCleanup()

    external fun isVulkanAvailable(): Boolean

    private fun copyModelToCache(
        context: Context,
        modelDir: String,
        modelName: String
    ): Pair<String, String>? {
        val cacheDir = File(context.cacheDir, "ncnn_models/$modelDir")
        cacheDir.mkdirs()

        val paramFile = File(cacheDir, "$modelName.param")
        val binFile = File(cacheDir, "$modelName.bin")

        try {
            context.assets.open("models/$modelDir/$modelName.param").use { input ->
                FileOutputStream(paramFile).use { output -> input.copyTo(output) }
            }
            context.assets.open("models/$modelDir/$modelName.bin").use { input ->
                FileOutputStream(binFile).use { output -> input.copyTo(output) }
            }
        } catch (e: Exception) {
            return null
        }

        return Pair(paramFile.absolutePath, binFile.absolutePath)
    }

    fun initialize(
        context: Context,
        modelDir: String = "realesrgan-x4plus-anime",
        modelName: String = "x4",
        scale: Int = 4,
        useGpu: Boolean = true
    ): Boolean {
        if (!nativeAvailable) return false
        synchronized(initLock) {
            if (isInitialized) return true

            val paths = copyModelToCache(context, modelDir, modelName) ?: return false
            val gpuId = if (useGpu && isVulkanAvailable()) 0 else -1
            val result = nativeInit(paths.first, paths.second, scale, gpuId)
            isInitialized = result == 0
            currentScale = scale
            return isInitialized
        }
    }

    fun enhance(
        bitmap: Bitmap,
        onProgress: ((Float) -> Unit)? = null
    ): Bitmap? {
        if (!nativeAvailable || !isInitialized) return null

        // Downscale the input if the x4 output would exceed the memory budget.
        // This keeps ncnn viable for large phone photos instead of falling
        // through to the slow OpenCV path.
        var inputBitmap = bitmap
        var maxDim = maxOf(bitmap.width, bitmap.height)
        val outDim = maxDim * currentScale
        if (outDim.toLong() * outDim.toLong() > MAX_OUTPUT_PIXELS) {
            // Calculate the largest input dimension whose x4 output fits
            val maxInputForBudget = kotlin.math.sqrt(MAX_OUTPUT_PIXELS.toDouble()).toInt() / currentScale
            val safeMax = maxInputForBudget.coerceIn(256, MAX_INPUT_DIM)
            if (maxDim > safeMax) {
                val scale = safeMax.toFloat() / maxDim
                val newW = (bitmap.width * scale).toInt().coerceAtLeast(1)
                val newH = (bitmap.height * scale).toInt().coerceAtLeast(1)
                inputBitmap = Bitmap.createScaledBitmap(bitmap, newW, newH, true)
                maxDim = maxOf(newW, newH)
            }
        }

        val outPixels = inputBitmap.width.toLong() * currentScale * inputBitmap.height.toLong() * currentScale
        val heapBudget = Runtime.getRuntime().maxMemory() / 4L
        if (outPixels > MAX_OUTPUT_PIXELS || outPixels > heapBudget) return null

        val callback = onProgress?.let { cb ->
            object : ProgressCallback {
                override fun onProgress(progress: Float) {
                    cb.invoke(progress)
                }
            }
        }

        val result = nativeEnhance(inputBitmap, callback)

        // If we downscaled the input, upscale the ncnn result back to the
        // user's expected output size (original dimensions × scaleFactor).
        if (inputBitmap !== bitmap && result != null) {
            val targetW = bitmap.width * currentScale
            val targetH = bitmap.height * currentScale
            val upscaled = Bitmap.createScaledBitmap(result, targetW, targetH, true)
            result.recycle()
            return upscaled
        }

        return result
    }

    fun cleanup() {
        synchronized(initLock) {
            if (nativeAvailable && isInitialized) {
                nativeCleanup()
                isInitialized = false
            }
        }
    }
}
