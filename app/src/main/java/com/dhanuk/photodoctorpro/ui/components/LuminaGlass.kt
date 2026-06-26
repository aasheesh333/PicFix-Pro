package com.dhanuk.photodoctorpro.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Glassmorphism surface per the Lumina Edit design system.
 * Provides translucent fill + subtle white inner top-edge glow + 1px outline.
 */
@Composable
fun Modifier.luminaGlass(
    shape: androidx.compose.ui.graphics.Shape = RoundedCornerShape(20.dp),
    cornerRadius: Dp = 20.dp,
    alpha: Float = 0.06f,
    borderAlpha: Float = 0.10f
): Modifier {
    val isLight = MaterialTheme.colorScheme.background.luminance() > 0.5f
    val fill: Color
    val highlight: Color
    val border: Color
    if (isLight) {
        fill = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.65f)
        highlight = MaterialTheme.colorScheme.outline.copy(alpha = 0.12f)
        border = MaterialTheme.colorScheme.outline.copy(alpha = borderAlpha.coerceAtLeast(0.18f))
    } else {
        fill = Color.White.copy(alpha = alpha)
        highlight = Color.White.copy(alpha = borderAlpha)
        border = MaterialTheme.colorScheme.outline.copy(alpha = borderAlpha)
    }
    return this
        .clip(shape)
        .background(fill)
        .border(
            width = 1.dp,
            brush = Brush.verticalGradient(listOf(highlight, border)),
            shape = RoundedCornerShape(cornerRadius)
        )
}
