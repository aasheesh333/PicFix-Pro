package com.dhanuk.photodoctorpro.utils

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RadialGradient
import android.graphics.Rect
import android.graphics.Shader
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import kotlinx.coroutines.tasks.await
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.gpu.CompatibilityList
import org.tensorflow.lite.gpu.GpuDelegate
import java.io.FileInputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import kotlin.math.max
import kotlin.math.min

class FaceEnhancer(private val context: Context) {

    private val faceDetector by lazy {
        val options = FaceDetectorOptions.Builder()
            .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_ACCURATE)
            .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_NONE)
            .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_NONE)
            .build()
        FaceDetection.getClient(options)
    }

    private var interpreter: Interpreter? = null
    private var gpuDelegate: GpuDelegate? = null
    private val MODEL_SIZE = 512

    init {
        try {
            val options = Interpreter.Options()
            val compatList = CompatibilityList()
            if (compatList.isDelegateSupportedOnThisDevice) {
                val delegate = GpuDelegate()
                gpuDelegate = delegate
                options.addDelegate(delegate)
            } else {
                options.setNumThreads(4)
            }

            try {
                val modelFile = loadModelFile("gfpgan.tflite")
                interpreter = Interpreter(modelFile, options)
            } catch (e: Throwable) {
                 // Ignore, fallback will skip face enhancement
                 close()
            }
        } catch (e: Throwable) {
            e.printStackTrace()
            close()
        }
    }

    fun close() {
        interpreter?.close()
        gpuDelegate?.close()
        try {
             faceDetector.close()
        } catch (e: Exception) {}
        interpreter = null
        gpuDelegate = null
    }

    private fun loadModelFile(path: String): java.nio.MappedByteBuffer {
        val fileDescriptor = context.assets.openFd("models/$path")
        if (fileDescriptor.declaredLength <= 0) {
            throw IOException("Model file $path is empty")
        }
        val inputStream = FileInputStream(fileDescriptor.fileDescriptor)
        val fileChannel = inputStream.channel
        val startOffset = fileDescriptor.startOffset
        val declaredLength = fileDescriptor.declaredLength
        return fileChannel.map(FileChannel.MapMode.READ_ONLY, startOffset, declaredLength)
    }

    suspend fun enhanceFaces(original: Bitmap, upscaled: Bitmap, scaleFactor: Int): Bitmap {
        if (interpreter == null) return upscaled

        val inputImage = InputImage.fromBitmap(original, 0)
        val faces = try {
            faceDetector.process(inputImage).await()
        } catch (e: Exception) {
            return upscaled
        }

        if (faces.isEmpty()) return upscaled

        val resultBitmap = upscaled.copy(Bitmap.Config.ARGB_8888, true)
        val canvas = Canvas(resultBitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)

        for (face in faces) {
            val box = face.boundingBox
            val margin = 0.2f
            val w = box.width()
            val h = box.height()
            val cx = box.centerX()
            val cy = box.centerY()

            val side = max(w, h)
            val paddedSide = (side * (1 + margin * 2)).toInt()

            val left = max(0, cx - paddedSide / 2)
            val top = max(0, cy - paddedSide / 2)
            val right = min(original.width, left + paddedSide)
            val bottom = min(original.height, top + paddedSide)

            if (right <= left || bottom <= top) continue

            val crop = Bitmap.createBitmap(original, left, top, right - left, bottom - top)
            val scaledInput = Bitmap.createScaledBitmap(crop, MODEL_SIZE, MODEL_SIZE, true)
            val enhancedScaled = runFaceInference(scaledInput)

            // Blending Logic
            val targetW = (crop.width * scaleFactor)
            val targetH = (crop.height * scaleFactor)

            val destRect = Rect(
                (left * scaleFactor),
                (top * scaleFactor),
                (left * scaleFactor) + targetW,
                (top * scaleFactor) + targetH
            )

            // Create Masked Patch
            val patch = Bitmap.createBitmap(targetW, targetH, Bitmap.Config.ARGB_8888)
            val patchCanvas = Canvas(patch)

            // Draw Enhanced Face onto Patch
            patchCanvas.drawBitmap(enhancedScaled, null, Rect(0, 0, targetW, targetH), null)

            // Create Radial Gradient Mask
            val maskPaint = Paint(Paint.ANTI_ALIAS_FLAG)
            maskPaint.xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN)

            val radius = min(targetW, targetH) / 2f
            val gradient = RadialGradient(
                targetW / 2f, targetH / 2f,
                radius,
                intArrayOf(Color.WHITE, Color.WHITE, Color.TRANSPARENT),
                floatArrayOf(0f, 0.6f, 1f), // Solid center 60%, fade out
                Shader.TileMode.CLAMP
            )
            maskPaint.shader = gradient

            patchCanvas.drawRect(0f, 0f, targetW.toFloat(), targetH.toFloat(), maskPaint)

            // Composite Patch onto Result
            canvas.drawBitmap(patch, null, destRect, paint)

            // Cleanup
            patch.recycle()
            crop.recycle()
            scaledInput.recycle()
            if (enhancedScaled != scaledInput) enhancedScaled.recycle()
        }

        return resultBitmap
    }

    private fun runFaceInference(bitmap: Bitmap): Bitmap {
        val interp = interpreter ?: return bitmap

        val input = ByteBuffer.allocateDirect(1 * MODEL_SIZE * MODEL_SIZE * 3 * 4)
        input.order(ByteOrder.nativeOrder())

        val intValues = IntArray(MODEL_SIZE * MODEL_SIZE)
        bitmap.getPixels(intValues, 0, MODEL_SIZE, 0, 0, MODEL_SIZE, MODEL_SIZE)

        for (pixel in intValues) {
            input.putFloat(((pixel shr 16) and 0xFF) / 255.0f)
            input.putFloat(((pixel shr 8) and 0xFF) / 255.0f)
            input.putFloat((pixel and 0xFF) / 255.0f)
        }

        val output = ByteBuffer.allocateDirect(1 * MODEL_SIZE * MODEL_SIZE * 3 * 4)
        output.order(ByteOrder.nativeOrder())

        interp.run(input, output)
        output.rewind()

        val outBitmap = Bitmap.createBitmap(MODEL_SIZE, MODEL_SIZE, Bitmap.Config.ARGB_8888)
        val outIntValues = IntArray(MODEL_SIZE * MODEL_SIZE)

        for (i in 0 until MODEL_SIZE * MODEL_SIZE) {
            val r = (output.float * 255).toInt().coerceIn(0, 255)
            val g = (output.float * 255).toInt().coerceIn(0, 255)
            val b = (output.float * 255).toInt().coerceIn(0, 255)
            outIntValues[i] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
        }

        outBitmap.setPixels(outIntValues, 0, MODEL_SIZE, 0, 0, MODEL_SIZE, MODEL_SIZE)
        return outBitmap
    }
}
