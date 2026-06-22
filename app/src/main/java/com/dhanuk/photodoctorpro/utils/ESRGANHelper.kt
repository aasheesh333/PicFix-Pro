package com.dhanuk.photodoctorpro.utils

// WARNING: assets/models/esrgan_x2.tflite and esrgan_x4.tflite are
// intentional 1-byte placeholders. Replace with real TF-Lite ESRGAN
// models before shipping a release build. Without valid models,
// inference will fail at runtime.

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
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlin.math.min
import kotlin.math.max

class ESRGANHelper(private val context: Context, private val modelFilename: String) {

    private var interpreter: Interpreter? = null
    private var gpuDelegate: GpuDelegate? = null
    private var inputShape: IntArray? = null
    private var scaleFactor: Int = -1

    // IMPORTANT: this field MUST be declared before the `init` block.
    // Kotlin initialises instance properties and init blocks in source order;
    // if this lock is declared after the init block, it is `null` while
    // `init { initialize() }` runs. The init path calls `close()` on failure
    // (which takes this lock) — that would NPE on `ReentrantLock.lock()`.
    private val interpLock = ReentrantLock()

    init {
        initialize()
    }

    private fun initialize() {
        val loaded = runCatching { loadModelFile(modelFilename) }
        val modelFile = if (loaded.isSuccess) {
            loaded.getOrNull()
        } else {
            logError("Failed to load model file: ${loaded.exceptionOrNull()?.message}")
            null
        }

        if (modelFile != null) {
            val initResult = runCatching {
                val options = Interpreter.Options()
                tryGpuDelegate(options, CompatibilityList())
                interpreter = Interpreter(modelFile, options)
                inputShape = interpreter?.getInputTensor(0)?.shape()
                detectScaleFactor()
            }
            if (initResult.isFailure) {
                logError("Interpreter initialization failed: ${initResult.exceptionOrNull()?.message}")
                interpreter = null
                close()
            }
        }
    }

    companion object {
        private const val MAX_INPUT_PIXELS = 2_000_000L

        private fun logError(msg: String) {
            if (com.dhanuk.photodoctorpro.BuildConfig.DEBUG) {
                android.util.Log.e("ESRGANHelper", msg)
            }
        }
    }

    private fun tryGpuDelegate(options: Interpreter.Options, compatList: CompatibilityList) {
        try {
            if (compatList.isDelegateSupportedOnThisDevice) {
                val delegate = GpuDelegate()
                gpuDelegate = delegate
                options.addDelegate(delegate)
            } else {
                options.setNumThreads(4)
            }
        } catch (e: Exception) {
            logError("GPU delegate unavailable, using CPU: ${e.message}")
            options.setNumThreads(4)
        }
    }

    private fun loadModelFile(path: String): MappedByteBuffer {
        val fileDescriptor = context.assets.openFd("models/$path")
        return fileDescriptor.use { fd ->
            FileInputStream(fd.fileDescriptor).use { stream ->
                stream.channel.map(
                    FileChannel.MapMode.READ_ONLY,
                    fd.startOffset,
                    fd.declaredLength
                )
            }
        }
    }

    fun isReady(): Boolean = interpreter != null

    fun close() {
        interpLock.withLock {
            try {
                interpreter?.close()
            } catch (_: Exception) {}
            try {
                gpuDelegate?.close()
            } catch (_: Exception) {}
            interpreter = null
            gpuDelegate = null
        }
    }

    private fun detectScaleFactor() {
        val interp = interpreter ?: return
        val shape = inputShape

        val h = if (shape != null && shape.size > 2 && shape[1] > 0) shape[1] else 32
        val w = if (shape != null && shape.size > 2 && shape[2] > 0) shape[2] else 32

        if (shape != null && (shape[1] <= 0 || shape[2] <= 0)) {
             interp.resizeInput(0, intArrayOf(1, h, w, 3))
             interp.allocateTensors()
             // Refresh cached inputShape so subsequent tile inferences don't see stale values.
             inputShape = interp.getInputTensor(0).shape()
        }

        val outputTensor = interp.getOutputTensor(0)
        val outShape = outputTensor.shape()

        scaleFactor = if (outShape[1] > 0) outShape[1] / h
                      else if (modelFilename.contains("x2")) 2
                      else if (modelFilename.contains("x4")) 4
                      else 4
    }

    fun enhance(bitmap: Bitmap): Bitmap {
        // Double-checked locking: re-check interpreter inside the lock so a
        // concurrent close() can't cause an NPE on the way in.
        return interpLock.withLock {
            val interp = interpreter ?: throw IllegalStateException("Model not loaded")
            enhanceInternal(interp, bitmap)
        }
    }

    private fun enhanceInternal(interp: Interpreter, bitmap: Bitmap): Bitmap {
        val w = bitmap.width
        val h = bitmap.height

        // Guard: refuse inputs that would OOM. ImageEnhancer pre-downscales to
        // 1024×1024 for 6x/8x, but the model itself should also self-protect.
        if (w.toLong() * h.toLong() > MAX_INPUT_PIXELS) {
            throw IllegalArgumentException(
                "ESRGAN input too large: ${w}×${h} = ${w.toLong()*h.toLong()/1_000_000}MP. " +
                "Max is ${MAX_INPUT_PIXELS/1_000_000}MP."
            )
        }

        val shape = inputShape
        val modelH = if (shape != null && shape.size > 2 && shape[1] > 0) shape[1] else 0

        val isFixed = (modelH > 0)
        val TILE_SIZE = if (isFixed) modelH else 512
        var PADDING = 32

        if (TILE_SIZE <= 2 * PADDING) {
            PADDING = TILE_SIZE / 4
        }

        val VALID_INPUT_SIZE = TILE_SIZE - 2 * PADDING

        val targetScale = if (scaleFactor > 0) scaleFactor else 4

        val outputBitmap = Bitmap.createBitmap(w * targetScale, h * targetScale, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(outputBitmap)

        // Resize the model's input tensor *once* per enhance call, not per tile.
        // This avoids the cost of `allocateTensors()` running hundreds of times
        // for non-fixed models.
        if (!isFixed) {
            interp.resizeInput(0, intArrayOf(1, TILE_SIZE, TILE_SIZE, 3))
            interp.allocateTensors()
            inputShape = interp.getInputTensor(0).shape()
        }

        var y = 0
        while (y < h) {
            var x = 0
            while (x < w) {
                val left = x - PADDING
                val top = y - PADDING

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

                val outputTile = runInference(interp, inputTile, TILE_SIZE, TILE_SIZE)

                val outPadding = PADDING * targetScale
                val outValidSize = VALID_INPUT_SIZE * targetScale

                val outSrcRect = Rect(outPadding, outPadding, outPadding + outValidSize, outPadding + outValidSize)

                val outDstX = x * targetScale
                val outDstY = y * targetScale
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

    private fun runInference(interp: Interpreter, bitmap: Bitmap, w: Int, h: Int): Bitmap {
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
