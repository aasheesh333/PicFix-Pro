package com.dhanuk.photodoctorpro.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp

@Composable
fun BeforeAfterSlider(
    beforeImage: ImageBitmap,
    afterImage: ImageBitmap,
    modifier: Modifier = Modifier
) {
    val animatable = remember { Animatable(0.5f) }
    var targetPosition by remember { mutableFloatStateOf(0.5f) }

    LaunchedEffect(targetPosition) {
        animatable.animateTo(
            targetValue = targetPosition,
            animationSpec = tween(150)
        )
    }

    val sliderPosition by animatable.asState()
    val beforeAlpha = (1f - sliderPosition * 2f).coerceIn(0f, 1f)
    val afterAlpha = ((sliderPosition - 0.5f) * 2f).coerceIn(0f, 1f)

    Box(
        modifier = modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                detectDragGestures { change, dragAmount ->
                    change.consume()
                    targetPosition = (targetPosition + dragAmount.x / size.width).coerceIn(0f, 1f)
                }
            }
            .pointerInput(Unit) {
                detectTapGestures { offset ->
                    targetPosition = (offset.x / size.width).coerceIn(0f, 1f)
                }
            }
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val canvasWidth = size.width
            val canvasHeight = size.height

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

            drawImage(
                image = afterImage,
                dstOffset = dstOffset,
                dstSize = dstSize
            )

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

            val sliderX = canvasWidth * sliderPosition
            drawLine(
                color = Color.White,
                start = Offset(sliderX, 0f),
                end = Offset(sliderX, canvasHeight),
                strokeWidth = 3f
            )
            drawCircle(
                color = Color.White,
                radius = 18f,
                center = Offset(sliderX, canvasHeight / 2)
            )
            drawCircle(
                color = Color.Black.copy(alpha = 0.3f),
                radius = 22f,
                center = Offset(sliderX, canvasHeight / 2)
            )

            drawLine(
                color = Color.Black.copy(alpha = 0.5f),
                start = Offset(sliderX - 7f, canvasHeight / 2),
                end = Offset(sliderX - 2f, canvasHeight / 2),
                strokeWidth = 2.5f
            )
            drawLine(
                color = Color.Black.copy(alpha = 0.5f),
                start = Offset(sliderX + 2f, canvasHeight / 2),
                end = Offset(sliderX + 7f, canvasHeight / 2),
                strokeWidth = 2.5f
            )
        }

        if (beforeAlpha > 0.15f) {
            Text(
                text = "Before",
                color = Color.White.copy(alpha = beforeAlpha * 0.7f),
                style = MaterialTheme.typography.labelMedium,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(16.dp)
            )
        }

        if (afterAlpha > 0.15f) {
            Text(
                text = "After",
                color = Color.White.copy(alpha = afterAlpha * 0.7f),
                style = MaterialTheme.typography.labelMedium,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(16.dp)
            )
        }
    }
}
