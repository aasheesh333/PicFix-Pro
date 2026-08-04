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

    // Filled in after each successful nativeEnhance with e.g.
    // "CPU · realesr-animevideov3-x4 · 4 tiles · 12.4s" — lets the UI (and
    // debugging) show exactly which engine produced the result.
    @Volatile private var lastEngineInfo: String = ""
    val engineInfo: String
        get() = lastEngineInfo

    private const val MAX_OUTPUT_PIXELS = 36_000_000L
    private const val MAX_INPUT_DIM = 900 // 900*4=3600 → 3600*3600 ≈ 13M px (well under 36M)
    // Fast mode caps the input lower: 768*4 = 3072px output is plenty for
    // gallery viewing and roughly halves inference time vs 900px input.
    private const val MAX_INPUT_DIM_FAST = 768

    @Volatile private var fastMode = false

    fun setFastMode(fast: Boolean) {
        fastMode = fast
    }

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

    private external fun nativeCancel()

    private external fun nativeGetLastEngineInfo(): String

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
        modelDir: String = "realesr-animevideov3-x4",
        modelName: String = "realesr-animevideov3-x4",
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
        targetScale: Int,
        onProgress: ((Float) -> Unit)? = null
    ): Bitmap? {
        if (!nativeAvailable || !isInitialized) return null

        // The model always upscales its input x4. For requested scales below 4
        // (e.g. 2x), feed the model a pre-scaled input so its x4 output is
        // already the final target size: ~4x less inference work for 2x and no
        // wasteful huge intermediate (previously a 3000px image at 2x built a
        // 12000x12000 = 576MB bitmap and often OOM'd, falling back to the slow
        // OpenCV path which caused the 5-10 minute hangs + glitches).
        val modelInputScale = targetScale / 4f

        var inputBitmap: Bitmap = bitmap
        var preW = bitmap.width
        var preH = bitmap.height
        if (targetScale < 4) {
            preW = (bitmap.width * modelInputScale).toInt().coerceAtLeast(1)
            preH = (bitmap.height * modelInputScale).toInt().coerceAtLeast(1)
            if (preW != bitmap.width || preH != bitmap.height) {
                inputBitmap = Bitmap.createScaledBitmap(bitmap, preW, preH, true)
            }
        }

        // Cap the model input so the x4 output stays within the memory budget
        // (and time budget on the heavy x4plus model).
        val inputCap = if (fastMode) MAX_INPUT_DIM_FAST else MAX_INPUT_DIM
        var maxDim = maxOf(preW, preH)
        val outDim = maxDim * currentScale
        if (outDim.toLong() * outDim.toLong() > MAX_OUTPUT_PIXELS || maxDim > inputCap) {
            val maxInputForBudget = kotlin.math.sqrt(MAX_OUTPUT_PIXELS.toDouble()).toInt() / currentScale
            val safeMax = minOf(maxInputForBudget.coerceIn(256, MAX_INPUT_DIM), inputCap)
            if (maxDim > safeMax) {
                val scale = safeMax.toFloat() / maxDim
                val newW = (preW * scale).toInt().coerceAtLeast(1)
                val newH = (preH * scale).toInt().coerceAtLeast(1)
                if (newW != preW || newH != preH) {
                    val scaled = Bitmap.createScaledBitmap(inputBitmap, newW, newH, true)
                    if (inputBitmap !== bitmap) inputBitmap.recycle()
                    inputBitmap = scaled
                }
                maxDim = maxOf(newW, newH)
            }
        }

        val outPixels = inputBitmap.width.toLong() * currentScale * inputBitmap.height.toLong() * currentScale
        // Tiling bounds ncnn's internal memory, and the caller (ViewModel)
        // already guards the FINAL output size, so a relaxed budget is safe.
        // The old maxMemory/4 here rejected common photo sizes and silently
        // dropped this method to the slow OpenCV fallback.
        val heapBudget = Runtime.getRuntime().maxMemory() / 2L
        if (outPixels > MAX_OUTPUT_PIXELS || outPixels > heapBudget) return null

        val callback = onProgress?.let { cb ->
            object : ProgressCallback {
                override fun onProgress(progress: Float) {
                    cb.invoke(progress)
                }
            }
        }

        val result = nativeEnhance(inputBitmap, callback)

        if (inputBitmap !== bitmap && inputBitmap.isRecycled) return null

        if (result != null) {
            lastEngineInfo = try { nativeGetLastEngineInfo() } catch (_: Exception) { "" }
        }

        // Restore to the user's expected output size (original x targetScale) —
        // never bitmap.width * 4, which created the huge intermediate.
        if (result != null) {
            val targetW = bitmap.width * targetScale
            val targetH = bitmap.height * targetScale
            if (result.width == targetW && result.height == targetH) {
                if (inputBitmap !== bitmap) inputBitmap.recycle()
                return result
            }
            val upscaled = Bitmap.createScaledBitmap(result, targetW, targetH, true)
            result.recycle()
            if (inputBitmap !== bitmap) inputBitmap.recycle()
            return upscaled
        }

        if (inputBitmap !== bitmap) inputBitmap.recycle()
        return null
    }

    /**
     * Ask the native tile loop to abort at the next tile boundary. Safe to call
     * from any thread; the flag is a plain volatile bool on the C++ side.
     * Called from the ViewModel's coroutine cancellation handler so closing the
     * app mid-enhance does not leave native code running at 100% CPU.
     */
    fun cancelEnhance() {
        if (nativeAvailable) {
            try { nativeCancel() } catch (_: UnsatisfiedLinkError) {}
        }
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
