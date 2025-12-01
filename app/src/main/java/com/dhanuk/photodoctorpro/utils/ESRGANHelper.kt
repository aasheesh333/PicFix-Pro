package com.dhanuk.photodoctorpro.utils

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.gpu.CompatibilityList
import org.tensorflow.lite.gpu.GpuDelegate
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel
import kotlin.math.min
import kotlin.math.max

class ESRGANHelper(private val context: Context, private val modelFilename: String) {

    private var interpreter: Interpreter? = null
    private var inputShape: IntArray? = null
    private var scaleFactor: Int = -1

    init {
        try {
            val options = Interpreter.Options()
            val compatList = CompatibilityList()
            if (compatList.isDelegateSupportedOnThisDevice) {
                options.addDelegate(GpuDelegate())
            } else {
                options.setNumThreads(4)
            }

            val modelFile = loadModelFile(modelFilename)
            interpreter = Interpreter(modelFile, options)
            inputShape = interpreter?.getInputTensor(0)?.shape()

            detectScaleFactor()
        } catch (e: Exception) {
            e.printStackTrace()
            interpreter = null
        }
    }

    private fun loadModelFile(path: String): MappedByteBuffer {
        val fileDescriptor = context.assets.openFd("models/$path")
        val inputStream = FileInputStream(fileDescriptor.fileDescriptor)
        val fileChannel = inputStream.channel
        val startOffset = fileDescriptor.startOffset
        val declaredLength = fileDescriptor.declaredLength
        return fileChannel.map(FileChannel.MapMode.READ_ONLY, startOffset, declaredLength)
    }

    fun isReady(): Boolean = interpreter != null

    private fun detectScaleFactor() {
        val interp = interpreter ?: return

        val h = if (inputShape != null && inputShape!!.size > 2 && inputShape!![1] > 0) inputShape!![1] else 32
        val w = if (inputShape != null && inputShape!!.size > 2 && inputShape!![2] > 0) inputShape!![2] else 32

        // Prepare input
        if (inputShape != null && (inputShape!![1] <= 0 || inputShape!![2] <= 0)) {
             interp.resizeInput(0, intArrayOf(1, h, w, 3))
             interp.allocateTensors()
        }

        val outputTensor = interp.getOutputTensor(0)
        val outShape = outputTensor.shape()

        if (outShape[1] > 0) {
            scaleFactor = outShape[1] / h
        } else {
            scaleFactor = if (modelFilename.contains("x2")) 2 else if (modelFilename.contains("x4")) 4 else 4
        }
    }

    fun enhance(bitmap: Bitmap): Bitmap {
        val interp = interpreter ?: throw IllegalStateException("Model not loaded")

        val modelH = if (inputShape != null && inputShape!!.size > 2 && inputShape!![1] > 0) inputShape!![1] else 0

        val isFixed = (modelH > 0)
        val TILE_SIZE = if (isFixed) modelH else 512
        var PADDING = 32

        // Ensure padding isn't too large
        if (TILE_SIZE <= 2 * PADDING) {
            PADDING = TILE_SIZE / 4
        }

        val VALID_INPUT_SIZE = TILE_SIZE - 2 * PADDING

        val w = bitmap.width
        val h = bitmap.height
        val targetScale = if (scaleFactor > 0) scaleFactor else 4

        val outputBitmap = Bitmap.createBitmap(w * targetScale, h * targetScale, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(outputBitmap)

        var y = 0
        while (y < h) {
            var x = 0
            while (x < w) {
                val left = x - PADDING
                val top = y - PADDING

                // Draw Source Tile
                val inputTile = Bitmap.createBitmap(TILE_SIZE, TILE_SIZE, Bitmap.Config.ARGB_8888)
                val tileCanvas = Canvas(inputTile)

                val srcLeft = max(0, left)
                val srcTop = max(0, top)
                val srcRight = min(w, left + TILE_SIZE)
                val srcBottom = min(h, top + TILE_SIZE)

                val srcRect = Rect(srcLeft, srcTop, srcRight, srcBottom)

                val dstLeft = srcLeft - left
                val dstTop = srcTop - top
                val dstRight = dstLeft + (srcRight - srcLeft)
                val dstBottom = dstTop + (srcBottom - srcTop)

                val dstRect = Rect(dstLeft, dstTop, dstRight, dstBottom)

                tileCanvas.drawBitmap(bitmap, srcRect, dstRect, null)

                // Inference
                val outputTile = runInference(interp, inputTile, isFixed, TILE_SIZE, TILE_SIZE)

                // Crop Valid Region
                val outPadding = PADDING * targetScale
                val outValidSize = VALID_INPUT_SIZE * targetScale

                // Ensure we don't crop outside outputTile bounds (if scale mismatch)
                // (Assumes runInference returns TILE_SIZE * Scale)

                val outSrcRect = Rect(outPadding, outPadding, outPadding + outValidSize, outPadding + outValidSize)

                val outDstX = x * targetScale
                val outDstY = y * targetScale
                // We must clip dst rect to image bounds
                val outDstRect = Rect(outDstX, outDstY, outDstX + outValidSize, outDstY + outValidSize)

                canvas.drawBitmap(outputTile, outSrcRect, outDstRect, null)

                inputTile.recycle()
                outputTile.recycle()

                x += VALID_INPUT_SIZE
            }
            y += VALID_INPUT_SIZE
        }

        return outputBitmap
    }

    private fun runInference(interp: Interpreter, bitmap: Bitmap, isFixed: Boolean, w: Int, h: Int): Bitmap {
        if (!isFixed) {
             interp.resizeInput(0, intArrayOf(1, h, w, 3))
             interp.allocateTensors()
        }

        val input = ByteBuffer.allocateDirect(1 * h * w * 3 * 4)
        input.order(ByteOrder.nativeOrder())

        val intValues = IntArray(w * h)
        bitmap.getPixels(intValues, 0, w, 0, 0, w, h)

        for (pixel in intValues) {
            input.putFloat(((pixel shr 16) and 0xFF) / 255.0f)
            input.putFloat(((pixel shr 8) and 0xFF) / 255.0f)
            input.putFloat((pixel and 0xFF) / 255.0f)
        }

        val outH = h * scaleFactor
        val outW = w * scaleFactor
        val output = ByteBuffer.allocateDirect(1 * outH * outW * 3 * 4)
        output.order(ByteOrder.nativeOrder())

        interp.run(input, output)
        output.rewind()

        val outBitmap = Bitmap.createBitmap(outW, outH, Bitmap.Config.ARGB_8888)
        val outIntValues = IntArray(outW * outH)

        for (i in 0 until outW * outH) {
            val r = (output.float * 255).toInt().coerceIn(0, 255)
            val g = (output.float * 255).toInt().coerceIn(0, 255)
            val b = (output.float * 255).toInt().coerceIn(0, 255)
            outIntValues[i] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
        }

        outBitmap.setPixels(outIntValues, 0, outW, 0, 0, outW, outH)
        return outBitmap
    }
}
