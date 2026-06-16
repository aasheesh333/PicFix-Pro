package com.dhanuk.photodoctorpro.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize

@Composable
fun BeforeAfterSlider(
    beforeImage: ImageBitmap,
    afterImage: ImageBitmap,
    modifier: Modifier = Modifier
) {
    var sliderPosition by remember { mutableStateOf(0.5f) }

    Box(
        modifier = modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                detectDragGestures { change, dragAmount ->
                    change.consume()
                    val newPosition = (sliderPosition + dragAmount.x / size.width).coerceIn(0f, 1f)
                    sliderPosition = newPosition
                }
            }
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val canvasWidth = size.width
            val canvasHeight = size.height

            // Calculate scaled sizes to fit both images
            val imageRatio = beforeImage.width.toFloat() / beforeImage.height.toFloat()
            val canvasRatio = canvasWidth / canvasHeight

            val drawWidth: Float
            val drawHeight: Float
            if (imageRatio > canvasRatio) {
                drawWidth = canvasWidth
                drawHeight = canvasWidth / imageRatio
            } else {
                drawHeight = canvasHeight
                drawWidth = canvasHeight * imageRatio
            }

            val xOffset = (canvasWidth - drawWidth) / 2f
            val yOffset = (canvasHeight - drawHeight) / 2f
            val dstSize = IntSize(drawWidth.toInt(), drawHeight.toInt())
            val dstOffset = IntOffset(xOffset.toInt(), yOffset.toInt())

            // Draw After Image (Background)
            drawImage(
                image = afterImage,
                dstOffset = dstOffset,
                dstSize = dstSize
            )

            // Draw Before Image (Foreground, clipped)
            val clipPath = Path().apply {
                addRect(Rect(0f, 0f, canvasWidth * sliderPosition, canvasHeight))
            }
            clipPath(clipPath) {
                drawImage(
                    image = beforeImage,
                    dstOffset = dstOffset,
                    dstSize = dstSize
                )
            }

            // Draw Slider Line
            val sliderX = canvasWidth * sliderPosition
            drawLine(
                color = Color.White,
                start = Offset(sliderX, 0f),
                end = Offset(sliderX, canvasHeight),
                strokeWidth = 4f
            )
            // Draw Slider Thumb
            drawCircle(
                color = Color.White,
                radius = 20f,
                center = Offset(sliderX, canvasHeight / 2)
            )
            drawCircle(
                color = Color.Black.copy(alpha = 0.3f),
                radius = 24f,
                center = Offset(sliderX, canvasHeight / 2)
            )
        }
    }
}