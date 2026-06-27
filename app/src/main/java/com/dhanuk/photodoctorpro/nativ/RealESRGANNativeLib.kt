package com.dhanuk.photodoctorpro.nativ

import android.content.Context
import android.graphics.Bitmap

interface ProgressCallback {
    fun onProgress(progress: Float)
}

object RealESRGANNativeLib {

    private var isInitialized = false
    private var currentScale = 4

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
        assetManager: Any,
        modelDir: String,
        modelName: String,
        scale: Int,
        gpuId: Int
    ): Int

    private external fun nativeEnhance(
        bitmap: Bitmap,
        progressCallback: ProgressCallback?
    ): Bitmap?

    private external fun nativeCleanup()

    external fun isVulkanAvailable(): Boolean

    fun initialize(
        context: Context,
        modelDir: String = "realesrgan-x4plus-anime",
        modelName: String = "x4",
        scale: Int = 4,
        useGpu: Boolean = true
    ): Boolean {
        if (!nativeAvailable) return false
        if (isInitialized) return true

        val gpuId = if (useGpu && isVulkanAvailable()) 0 else -1
        val result = nativeInit(context.assets, modelDir, modelName, scale, gpuId)
        isInitialized = result == 0
        currentScale = scale
        return isInitialized
    }

    fun enhance(
        bitmap: Bitmap,
        onProgress: ((Float) -> Unit)? = null
    ): Bitmap? {
        if (!nativeAvailable || !isInitialized) return null

        val callback = onProgress?.let { cb ->
            object : ProgressCallback {
                override fun onProgress(progress: Float) {
                    cb.invoke(progress)
                }
            }
        }

        return nativeEnhance(bitmap, callback)
    }

    fun cleanup() {
        if (nativeAvailable && isInitialized) {
            nativeCleanup()
            isInitialized = false
        }
    }
}
