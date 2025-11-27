package com.dhanuk.photodoctorpro.utils

import android.graphics.Bitmap
import org.opencv.android.OpenCVLoader
import org.opencv.android.Utils
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc

object ImageEnhancer {

    init {
        OpenCVLoader.initDebug()
    }

    fun enhance(bitmap: Bitmap): Bitmap {
        val src = Mat()
        Utils.bitmapToMat(bitmap, src)
        Imgproc.cvtColor(src, src, Imgproc.COLOR_RGBA2RGB)

        // 1. Sharpening using Unsharp Mask
        val blur = Mat()
        Imgproc.GaussianBlur(src, blur, Size(0.0, 0.0), 3.0)
        val sharpened = Mat()
        Core.addWeighted(src, 1.5, blur, -0.5, 0.0, sharpened)

        // 2. Increase Contrast and Brightness
        // alpha = 1.2 (contrast), beta = 10 (brightness)
        val contrast = Mat()
        sharpened.convertTo(contrast, -1, 1.2, 10.0)

        val resultBitmap = Bitmap.createBitmap(contrast.cols(), contrast.rows(), Bitmap.Config.ARGB_8888)
        Utils.matToBitmap(contrast, resultBitmap)

        // Cleanup
        src.release()
        blur.release()
        sharpened.release()
        contrast.release()

        return resultBitmap
    }
}
