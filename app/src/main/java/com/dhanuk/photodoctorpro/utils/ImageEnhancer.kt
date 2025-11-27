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

    fun enhance(bitmap: Bitmap, scaleFactor: Int): Bitmap {
        val src = Mat()
        Utils.bitmapToMat(bitmap, src)
        Imgproc.cvtColor(src, src, Imgproc.COLOR_RGBA2RGB)

        // 1. Upscale
        val upscaled = Mat()
        Imgproc.resize(src, upscaled, Size(src.cols() * scaleFactor.toDouble(), src.rows() * scaleFactor.toDouble()), 0.0, 0.0, Imgproc.INTER_CUBIC)

        // 2. Sharpening using Unsharp Mask
        val blur = Mat()
        Imgproc.GaussianBlur(upscaled, blur, Size(0.0, 0.0), 3.0)
        val sharpened = Mat()
        Core.addWeighted(upscaled, 1.5, blur, -0.5, 0.0, sharpened)

        // 3. Subtle Contrast and Brightness (Natural look)
        // alpha = 1.05 (contrast), beta = 5 (brightness)
        val finalMat = Mat()
        sharpened.convertTo(finalMat, -1, 1.05, 5.0)

        val resultBitmap = Bitmap.createBitmap(finalMat.cols(), finalMat.rows(), Bitmap.Config.ARGB_8888)
        Utils.matToBitmap(finalMat, resultBitmap)

        // Cleanup
        src.release()
        upscaled.release()
        blur.release()
        sharpened.release()
        finalMat.release()

        return resultBitmap
    }
}
