package com.dhanuk.photodoctorpro.ui.screens

import android.app.Activity
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.net.Uri
import android.util.Log
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asAndroidPath
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.dhanuk.photodoctorpro.data.local.History
import com.dhanuk.photodoctorpro.data.repository.HistoryRepository
import com.dhanuk.photodoctorpro.utils.AdManager
import com.dhanuk.photodoctorpro.utils.BitmapUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.opencv.android.OpenCVLoader
import org.opencv.android.Utils
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Scalar
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import org.opencv.photo.Photo
import java.util.Stack
import kotlin.math.max

class ObjectEraserViewModel(private val repository: HistoryRepository) : ViewModel() {

    private val _uiState = MutableStateFlow(ObjectEraserUiState())
    val uiState = _uiState.asStateFlow()

    private val undoStack = Stack<Bitmap>()
    private val redoStack = Stack<Bitmap>()
    private val MAX_STACK_SIZE = 10

    init {
        if (!OpenCVLoader.initDebug()) {
            _uiState.value = _uiState.value.copy(error = "OpenCV initialization failed.")
        }
    }

    fun onImageSelected(uri: Uri, context: Context) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true)
            val bitmap = BitmapUtils.loadBitmapFromUri(uri, context, 2048)
            undoStack.clear()
            redoStack.clear()
            _uiState.value = ObjectEraserUiState(selectedImageUri = uri, originalBitmap = bitmap, isLoading = false)
        }
    }

    fun onBrushSizeChanged(newSize: Float) {
        _uiState.value = _uiState.value.copy(brushSize = newSize)
    }

    fun onFeatherChanged(newFeather: Float) {
        _uiState.value = _uiState.value.copy(feather = newFeather)
    }

    fun onPathsChanged(newPaths: List<Pair<Path, Float>>) {
        _uiState.value = _uiState.value.copy(paths = newPaths)
    }

    // LIVE UPDATE LOGIC
    // We need to support Undo/Redo of the EDIT ITSELF, not just the paths.
    // Actually, "Eraser" implies we select an area and then apply.
    // The requirement: "Brush stroke must update preview instantly. After pressing 'Erase', the result is clearly visible."
    // If we want "Instant" erasing (stroke by stroke), we need to run inpainting onDragEnd?
    // Or do we select the area (red mask) then hit "Apply/Erase"?
    // The previous code had `eraseObjects` triggered by button.
    // Requirement says "Make sure each brush stroke updates: The mask and preview are updated live."
    // This implies drawing the Red Mask live (which I did in RemoveBG).
    // And "After pressing 'Erase', the result is clearly visible."
    // So the flow is: Draw Mask -> Press Erase -> Inpaint.

    // BUT: "Live brushing does NOT update the preview" (from RemoveBG complaint)
    // For Object Eraser, the user draws on top.

    fun undo() {
        if (undoStack.isNotEmpty()) {
            val current = _uiState.value.processedBitmap ?: _uiState.value.originalBitmap
            if (current != null) {
                redoStack.push(current)
            }
            val prev = undoStack.pop()
            _uiState.value = _uiState.value.copy(
                processedBitmap = prev,
                canUndo = undoStack.isNotEmpty(),
                canRedo = true
            )
        }
    }

    fun redo() {
        if (redoStack.isNotEmpty()) {
            val current = _uiState.value.processedBitmap ?: _uiState.value.originalBitmap
            if (current != null) {
                pushToStack(undoStack, current)
            }
            val next = redoStack.pop()
            _uiState.value = _uiState.value.copy(
                processedBitmap = next,
                canUndo = true,
                canRedo = redoStack.isNotEmpty()
            )
        }
    }

    fun reset() {
        val uri = _uiState.value.selectedImageUri
        val bitmap = _uiState.value.originalBitmap
        undoStack.clear()
        redoStack.clear()
        _uiState.value = ObjectEraserUiState(
            selectedImageUri = uri,
            originalBitmap = bitmap,
            resetPerformed = true
        )
    }

    fun onResetMessageShown() {
        _uiState.value = _uiState.value.copy(resetPerformed = false)
    }

    fun eraseObjects() {
        val sourceBitmap = _uiState.value.processedBitmap ?: _uiState.value.originalBitmap ?: return
        val paths = _uiState.value.paths
        if (paths.isEmpty()) return
        val feather = _uiState.value.feather

        _uiState.value = _uiState.value.copy(isErasing = true, error = null)

        pushToStack(undoStack, sourceBitmap)
        redoStack.clear()

        viewModelScope.launch {
            try {
                // Ensure correct config
                val workingBitmap = sourceBitmap.copy(Bitmap.Config.ARGB_8888, true)

                // Create Soft Mask (with Gaussian Blur)
                val softMask = createMask(workingBitmap.width, workingBitmap.height, paths, feather)

                // Inpaint and Blend
                val resultBitmap = applyInpainting(workingBitmap, softMask, feather)

                _uiState.value = _uiState.value.copy(
                    isErasing = false,
                    processedBitmap = resultBitmap,
                    paths = emptyList(), // Clear paths after applying
                    canUndo = true,
                    canRedo = false
                )
            } catch (e: Exception) {
                e.printStackTrace()
                if (undoStack.isNotEmpty()) undoStack.pop()
                _uiState.value = _uiState.value.copy(isErasing = false, error = "Error: ${e.message}")
            }
        }
    }

    private fun pushToStack(stack: Stack<Bitmap>, bitmap: Bitmap) {
        if (stack.size >= MAX_STACK_SIZE) {
            stack.removeAt(0)
        }
        stack.push(bitmap)
    }

    private suspend fun createMask(width: Int, height: Int, paths: List<Pair<Path, Float>>, feather: Float): Bitmap = withContext(Dispatchers.Default) {
        val maskBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(maskBitmap)
        // Background black (no mask)
        canvas.drawColor(android.graphics.Color.BLACK)

        val paint = Paint().apply {
            color = android.graphics.Color.WHITE
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
            isAntiAlias = true
        }

        paths.forEach { (path, strokeWidth) ->
            paint.strokeWidth = strokeWidth
            canvas.drawPath(path.asAndroidPath(), paint)
        }

        // Apply Feather (Gaussian Blur)
        if (feather > 0) {
            val mat = Mat()
            Utils.bitmapToMat(maskBitmap, mat)

            // kSize must be odd
            var kVal = (feather * 2).toInt()
            if (kVal % 2 == 0) kVal++
            val kSize = Size(kVal.toDouble(), kVal.toDouble())

            Imgproc.GaussianBlur(mat, mat, kSize, 0.0)

            Utils.matToBitmap(mat, maskBitmap)
            mat.release()
        }

        return@withContext maskBitmap
    }

    private suspend fun applyInpainting(original: Bitmap, softMask: Bitmap, feather: Float): Bitmap = withContext(Dispatchers.Default) {
        val src = Mat()
        Utils.bitmapToMat(original, src)
        Imgproc.cvtColor(src, src, Imgproc.COLOR_RGBA2RGB)

        val softMaskMat = Mat()
        Utils.bitmapToMat(softMask, softMaskMat)
        Imgproc.cvtColor(softMaskMat, softMaskMat, Imgproc.COLOR_BGRA2GRAY)

        // Create Hard Mask for Inpaint (Threshold)
        // We need a binary mask for the Inpaint function itself
        val hardMaskMat = Mat()
        // Threshold: any non-zero becomes 255? Or maybe threshold at mid-point?
        // If we want to inpaint everything touched by the brush, we should use a low threshold.
        Imgproc.threshold(softMaskMat, hardMaskMat, 10.0, 255.0, Imgproc.THRESH_BINARY)

        val inpaintedMat = Mat()
        // Radius: The inpaint radius. Should be somewhat related to the brush/hole size?
        // Or feather? Usually small radius (3-5) is fine for Telea if the mask covers the object.
        val radius = max(5.0, feather.toDouble())
        Photo.inpaint(src, hardMaskMat, inpaintedMat, radius, Photo.INPAINT_TELEA)

        // BLENDING: result = original * (1 - mask) + inpaint * mask
        // This blends the seam.
        // Convert Soft Mask to Float 0..1
        val softMaskFloat = Mat()
        softMaskMat.convertTo(softMaskFloat, CvType.CV_32F, 1.0/255.0)

        // Expand mask to 3 channels
        val mask3 = Mat()
        Imgproc.cvtColor(softMaskFloat, mask3, Imgproc.COLOR_GRAY2RGB)

        // Inverted mask (1 - mask)
        val invMask3 = Mat()
        Core.subtract(Mat(mask3.size(), mask3.type(), Scalar(1.0, 1.0, 1.0)), mask3, invMask3)

        val srcFloat = Mat()
        src.convertTo(srcFloat, CvType.CV_32F)

        val inpaintedFloat = Mat()
        inpaintedMat.convertTo(inpaintedFloat, CvType.CV_32F)

        // Multiply
        val part1 = Mat()
        Core.multiply(srcFloat, invMask3, part1)

        val part2 = Mat()
        Core.multiply(inpaintedFloat, mask3, part2)

        val resultFloat = Mat()
        Core.add(part1, part2, resultFloat)

        val finalMat = Mat()
        resultFloat.convertTo(finalMat, CvType.CV_8U)

        val resultBitmap = Bitmap.createBitmap(finalMat.cols(), finalMat.rows(), Bitmap.Config.ARGB_8888)
        Utils.matToBitmap(finalMat, resultBitmap)

        // Release
        src.release()
        softMaskMat.release()
        hardMaskMat.release()
        inpaintedMat.release()
        softMaskFloat.release()
        mask3.release()
        invMask3.release()
        srcFloat.release()
        inpaintedFloat.release()
        part1.release()
        part2.release()
        resultFloat.release()
        finalMat.release()

        resultBitmap
    }

    suspend fun saveImage(activity: Activity): Boolean {
        val bitmap = _uiState.value.processedBitmap ?: return false
        val uri = _uiState.value.selectedImageUri ?: return false
        _uiState.value = _uiState.value.copy(isLoading = true)

        return try {
            val fileName = "PhotoDoctorPro_Erased_${System.currentTimeMillis()}"
            val filePath = BitmapUtils.saveBitmap(activity, bitmap, fileName, Bitmap.CompressFormat.JPEG)
            repository.addHistory(
                History(
                    operationType = "Object Erased",
                    inputFilePath = uri.toString(),
                    filePath = filePath,
                    timestamp = System.currentTimeMillis()
                )
            )
            AdManager.showInterstitialAd(activity)
            _uiState.value = _uiState.value.copy(savedFilePath = filePath)
            true
        } catch (e: Exception) {
            _uiState.value = _uiState.value.copy(error = "Failed to save image: ${e.message}")
            false
        } finally {
            _uiState.value = _uiState.value.copy(isLoading = false)
        }
    }

    fun onErrorShown() {
        _uiState.value = _uiState.value.copy(error = null)
    }

    fun onSavedMessageShown() {
        _uiState.value = _uiState.value.copy(savedFilePath = null)
    }
}

data class ObjectEraserUiState(
    val selectedImageUri: Uri? = null,
    val originalBitmap: Bitmap? = null,
    val processedBitmap: Bitmap? = null,
    val paths: List<Pair<Path, Float>> = emptyList(),
    val brushSize: Float = 40f,
    val feather: Float = 0f,
    val isErasing: Boolean = false,
    val isLoading: Boolean = false,
    val error: String? = null,
    val savedFilePath: String? = null,
    val resetPerformed: Boolean = false,
    val canUndo: Boolean = false,
    val canRedo: Boolean = false
)
