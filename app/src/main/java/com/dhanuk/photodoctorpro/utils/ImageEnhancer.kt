package com.dhanuk.photodoctorpro.utils

import android.graphics.Bitmap
import org.opencv.android.Utils
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc

object ImageEnhancer {

    /**
     * Enhances the image by upscaling (bicubic) and applying sharpening/contrast.
     * Handles large images by downscaling if necessary before processing to avoid OOM,
     * unless tiling is implemented (for now, safe downscaling + upscaling).
     */
    fun enhance(bitmap: Bitmap, scaleFactor: Int): Bitmap {
        // 1. Convert Bitmap to Mat
        val src = Mat()
        Utils.bitmapToMat(bitmap, src)
        Imgproc.cvtColor(src, src, Imgproc.COLOR_RGBA2RGB)

        // 2. Check dimensions and safe downscale if input is huge to prevent OOM during upscale
        // If image is already 4000x4000 and we want 4x, that's 16000x16000 -> OOM.
        // Limit max output dimension to ~4096 (texture limit for many devices is 4096 or 8192).
        // Let's be safe with 3000px max output dimension.
        val maxDim = 3000.0
        val currentMax = kotlin.math.max(src.cols(), src.rows())
        val targetScale = if (currentMax * scaleFactor > maxDim) {
            maxDim / currentMax
        } else {
            scaleFactor.toDouble()
        }

        val dst = Mat()
        val newSize = Size(src.cols() * targetScale, src.rows() * targetScale)

        // 3. Bicubic Resize (Upscale)
        Imgproc.resize(src, dst, newSize, 0.0, 0.0, Imgproc.INTER_CUBIC)

        // 4. Sharpening (Unsharp Mask)
        // Blur -> Subtract -> Add back
        val blurred = Mat()
        Imgproc.GaussianBlur(dst, blurred, Size(0.0, 0.0), 3.0)
        Core.addWeighted(dst, 1.5, blurred, -0.5, 0.0, dst)

        // 5. Contrast / Clarity (Simple Alpha/Beta or CLAHE)
        // Using slight contrast boost
        dst.convertTo(dst, -1, 1.1, 10.0) // Alpha 1.1 (Contrast), Beta 10 (Brightness)

        // 6. Convert back to Bitmap
        val resultBitmap = Bitmap.createBitmap(dst.cols(), dst.rows(), Bitmap.Config.ARGB_8888)
        Utils.matToBitmap(dst, resultBitmap)

        // Cleanup
        src.release()
        dst.release()
        blurred.release()

        return resultBitmap
    }
}
