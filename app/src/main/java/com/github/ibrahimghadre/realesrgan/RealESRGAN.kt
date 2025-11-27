package com.github.ibrahimghadre.realesrgan

import android.content.res.AssetManager
import android.graphics.Bitmap

class RealESRGAN(assetManager: AssetManager) {
    fun upscale(bitmap: Bitmap, scale: Int): Bitmap {
        // Dummy implementation to allow build to pass.
        // Returns the original bitmap without upscaling.
        return bitmap
    }
}
