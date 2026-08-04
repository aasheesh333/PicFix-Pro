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
    // "CPU · realesr-general-x4v3 · 4 tiles · 12.4s" — lets the UI (and
    // debugging) show exactly which engine produced the result.
    @Volatile private var lastEngineInfo: String = ""
    val engineInfo: String
        get() = lastEngineInfo

    private const val MAX_OUTPUT_PIXELS = 40_000_000L
    // Fast-mode hard cap. HD mode goes higher per scale (see effectiveHdCap
    // below) so the model genuinely adds more detail at 6x/8x instead of just
    // bilinear-stretching the same capped output.
    private const val MAX_INPUT_DIM_FAST = 768
    // Absolute MaxInputDim clamp (a guardrail against the worst-case memory).
    // Real HD caps per scale: 2x/4x=900, 6x=1200, 8x=1500 — chosen so the x4
    // model output stays under MAX_OUTPUT_PIXELS (1500*4=6000 px → 36M px ✓).
    private const val MAX_INPUT_DIM_HARD = 1600
    private const val MAX_INPUT_DIM_HD_2X = 900
    private const val MAX_INPUT_DIM_HD_6X = 1200
    private const val MAX_INPUT_DIM_HD_8X = 1500

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
        modelDir: String = "realesr-general-x4v3",
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
        // and within the user's chosen time/quality tier.
        //  - Fast mode: 768px cap for every scale — fast and consistent.
        //  - HD mode: scale-aware. 2x/4x use 900; 6x uses 1200; 8x uses 1500 —
        //    so a 6x/8x request actually feeds more of the original image to
        //    the model and gains genuinely finer detail. (Previously all
        //    scales shared the same 900 cap → the model produced 3600px output
        //    regardless of x; higher scales just bilinearly stretched → "saare
        //    x options same result".)
        val inputCap = if (fastMode) {
            MAX_INPUT_DIM_FAST
        } else {
            when (targetScale) {
                6 -> MAX_INPUT_DIM_HD_6X
                8 -> MAX_INPUT_DIM_HD_8X
                else -> MAX_INPUT_DIM_HD_2X // 2x and 4x share 900
            }
        }
        var maxDim = maxOf(preW, preH)
        val outDim = maxDim * currentScale
        if (outDim.toLong() * outDim.toLong() > MAX_OUTPUT_PIXELS || maxDim > inputCap) {
            val maxInputForBudget = kotlin.math.sqrt(MAX_OUTPUT_PIXELS.toDouble()).toInt() / currentScale
            val safeMax = minOf(maxInputForBudget.coerceIn(256, MAX_INPUT_DIM_HARD), inputCap)
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
