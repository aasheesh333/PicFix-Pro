package com.dhanuk.photodoctorpro.utils

import android.graphics.Bitmap
import android.graphics.Canvas
import org.opencv.android.Utils
import org.opencv.core.Core
import org.opencv.core.Mat
import org.opencv.core.Rect
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import kotlin.math.max
import kotlin.math.min

object ImageEnhancer {

    private const val TILE_SIZE = 512
    private const val PADDING = 32

    /**
     * Enhances the image by upscaling (bicubic) and applying sharpening/contrast.
     * Uses tiling to handle larger images and prevent OOM during processing steps.
     * Note: The final result must still fit in memory.
     */
    fun enhance(bitmap: Bitmap, scaleFactor: Int): Bitmap {
        // If the output is relatively small (< 4MP), process fully for speed.
        val width = bitmap.width
        val height = bitmap.height
        if (width * height * scaleFactor * scaleFactor < 4_000_000) {
             return processFull(bitmap, scaleFactor)
        }
        return processTiled(bitmap, scaleFactor)
    }

    private fun processFull(bitmap: Bitmap, scaleFactor: Int): Bitmap {
        val src = Mat()
        Utils.bitmapToMat(bitmap, src)
        Imgproc.cvtColor(src, src, Imgproc.COLOR_RGBA2RGB)

        val dst = processMat(src, scaleFactor)

        val result = Bitmap.createBitmap(dst.cols(), dst.rows(), Bitmap.Config.ARGB_8888)
        Utils.matToBitmap(dst, result)

        src.release()
        dst.release()
        return result
    }

    private fun processTiled(bitmap: Bitmap, scaleFactor: Int): Bitmap {
        val width = bitmap.width
        val height = bitmap.height
        val targetWidth = width * scaleFactor
        val targetHeight = height * scaleFactor

        // Create result bitmap
        val resultBitmap = Bitmap.createBitmap(targetWidth, targetHeight, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(resultBitmap)

        val src = Mat()
        Utils.bitmapToMat(bitmap, src)
        Imgproc.cvtColor(src, src, Imgproc.COLOR_RGBA2RGB)

        var y = 0
        while (y < height) {
            var x = 0
            while (x < width) {
                // Calculate tile with padding
                val startX = max(0, x - PADDING)
                val startY = max(0, y - PADDING)
                val endX = min(width, x + TILE_SIZE + PADDING)
                val endY = min(height, y + TILE_SIZE + PADDING)

                val tileRect = Rect(startX, startY, endX - startX, endY - startY)
                val tileMat = src.submat(tileRect)

                // Process Tile
                val processedTile = processMat(tileMat, scaleFactor)

                // Crop valid region
                val validSrcX = (x - startX) * scaleFactor
                val validSrcY = (y - startY) * scaleFactor
                val validWidth = min(TILE_SIZE, width - x) * scaleFactor
                val validHeight = min(TILE_SIZE, height - y) * scaleFactor

                // Ensure the crop rect is within the processed tile
                // The processed tile size corresponds to tileMat size * scaleFactor
                // tileMat width = endX - startX.
                // processedTile width = (endX - startX) * scaleFactor.
                // validSrcX = (x - startX) * scaleFactor.
                // If x=startX (no padding left), validSrcX=0.
                // validWidth = TILE_SIZE * scaleFactor (or less).

                val cropRect = Rect(validSrcX, validSrcY, validWidth, validHeight)
                val cropped = processedTile.submat(cropRect)

                val tempBitmap = Bitmap.createBitmap(validWidth, validHeight, Bitmap.Config.ARGB_8888)
                Utils.matToBitmap(cropped, tempBitmap)

                // Draw at target position
                canvas.drawBitmap(tempBitmap, (x * scaleFactor).toFloat(), (y * scaleFactor).toFloat(), null)

                tempBitmap.recycle()
                processedTile.release()
                cropped.release()
                tileMat.release()

                x += TILE_SIZE
            }
            y += TILE_SIZE
        }

        src.release()
        return resultBitmap
    }

    private fun processMat(src: Mat, scaleFactor: Int): Mat {
        val dst = Mat()
        val newSize = Size(src.cols() * scaleFactor.toDouble(), src.rows() * scaleFactor.toDouble())

        // 1. Bicubic Upscale
        Imgproc.resize(src, dst, newSize, 0.0, 0.0, Imgproc.INTER_CUBIC)

        // 2. Unsharp Mask (Sharpening)
        // Blur -> Subtract -> Add back
        val blurred = Mat()
        // Gaussian kernel size must be odd. 0.0 implies calculated from sigma.
        Imgproc.GaussianBlur(dst, blurred, Size(0.0, 0.0), 2.0)
        Core.addWeighted(dst, 1.4, blurred, -0.4, 0.0, dst)
        blurred.release()

        // 3. Contrast / Clarity
        // Alpha > 1.0 increases contrast. Beta adds brightness.
        // Don't add too much brightness.
        dst.convertTo(dst, -1, 1.05, 2.0)

        return dst
    }
}
