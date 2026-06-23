package com.dhanuk.photodoctorpro.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.Spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Error
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

enum class SnackbarType { SUCCESS, ERROR, INFO }

@Composable
fun AnimatedSnackbar(
    message: String,
    type: SnackbarType = SnackbarType.INFO,
    visible: Boolean,
    modifier: Modifier = Modifier
) {
    val backgroundColor = when (type) {
        SnackbarType.SUCCESS -> MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)
        SnackbarType.ERROR -> MaterialTheme.colorScheme.error.copy(alpha = 0.15f)
        SnackbarType.INFO -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.9f)
    }
    val contentColor = when (type) {
        SnackbarType.SUCCESS -> MaterialTheme.colorScheme.primary
        SnackbarType.ERROR -> MaterialTheme.colorScheme.error
        SnackbarType.INFO -> MaterialTheme.colorScheme.onSurface
    }
    val iconVector = when (type) {
        SnackbarType.SUCCESS -> Icons.Filled.CheckCircle
        SnackbarType.ERROR -> Icons.Filled.Error
        SnackbarType.INFO -> Icons.Filled.Error
    }

    AnimatedVisibility(
        visible = visible,
        enter = slideInVertically(
            animationSpec = spring(
                dampingRatio = Spring.DampingRatioMediumBouncy,
                stiffness = Spring.StiffnessMedium
            ),
            initialOffsetY = { it }
        ) + fadeIn(),
        exit = slideOutVertically(
            animationSpec = spring(stiffness = Spring.StiffnessHigh),
            targetOffsetY = { it }
        ) + fadeOut(),
        modifier = modifier
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(backgroundColor)
                .padding(horizontal = 16.dp, vertical = 12.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Start
            ) {
                Icon(
                    imageVector = iconVector,
                    contentDescription = null,
                    tint = contentColor,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(Modifier.width(10.dp))
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodyMedium,
                    color = contentColor
                )
            }
        }
    }
}
