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
    val outline = MaterialTheme.colorScheme.outline.copy(alpha = borderAlpha)
    val highlight = Color.White.copy(alpha = borderAlpha)
    val fill = Color.White.copy(alpha = alpha)
    return this
        .clip(shape)
        .background(fill)
        .border(
            width = 1.dp,
            brush = Brush.verticalGradient(listOf(highlight, outline)),
            shape = RoundedCornerShape(cornerRadius)
        )
}
