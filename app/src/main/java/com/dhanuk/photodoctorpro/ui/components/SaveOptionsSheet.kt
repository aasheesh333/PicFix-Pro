package com.dhanuk.photodoctorpro.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.dhanuk.photodoctorpro.R
import com.dhanuk.photodoctorpro.utils.SaveFormat
import com.dhanuk.photodoctorpro.utils.SaveOptions

/**
 * v2 save flow: lets the user pick output format, quality, and (for formats
 * without alpha) the background fill colour used to flatten transparency.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SaveOptionsSheet(
    initial: SaveOptions,
    hasTransparency: Boolean,
    onConfirm: (SaveOptions) -> Unit,
    onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var format by remember { mutableStateOf(initial.format) }
    var quality by remember { mutableStateOf(initial.quality.toFloat()) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .padding(bottom = 24.dp)
        ) {
            Text(
                text = stringResource(R.string.save_options_title),
                style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.SemiBold),
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(Modifier.height(16.dp))

            Text(
                text = stringResource(R.string.save_format),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(8.dp))
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                val availableFormats = remember {
                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
                        listOf(SaveFormat.JPEG, SaveFormat.PNG, SaveFormat.WEBP_LOSSLESS)
                    } else {
                        listOf(SaveFormat.JPEG, SaveFormat.PNG, SaveFormat.WEBP)
                    }
                }
                availableFormats.forEach { f ->
                    FilterChip(
                        selected = format == f,
                        onClick = { format = f },
                        label = { Text(formatLabel(f)) }
                    )
                }
            }

            if (!format.supportsAlpha && hasTransparency) {
                Spacer(Modifier.height(12.dp))
                Text(
                    text = stringResource(R.string.save_format_no_alpha_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Spacer(Modifier.height(16.dp))
            val qualityRelevant = format != SaveFormat.PNG && format != SaveFormat.WEBP_LOSSLESS
            Text(
                text = stringResource(R.string.save_quality, quality.toInt()),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Slider(
                value = quality,
                onValueChange = { quality = it },
                valueRange = 10f..100f,
                steps = 17,
                enabled = qualityRelevant,
                onValueChangeFinished = {},
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(Modifier.height(20.dp))

            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                androidx.compose.material3.TextButton(
                    onClick = onDismiss,
                    modifier = Modifier.weight(1f)
                ) { Text(stringResource(R.string.cancel)) }
                androidx.compose.material3.Button(
                    onClick = {
                        onConfirm(
                            SaveOptions(
                                format = format,
                                quality = quality.toInt().coerceIn(1, 100),
                                bgColor = if (!format.supportsAlpha) initial.bgColor ?: 0xFFFFFFFF.toInt() else null
                            )
                        )
                    },
                    modifier = Modifier.weight(1f)
                ) { Text(stringResource(R.string.action_save)) }
            }
        }
    }
}

@Composable
private fun formatLabel(format: SaveFormat): String = when (format) {
    SaveFormat.JPEG -> stringResource(R.string.format_jpeg)
    SaveFormat.PNG -> stringResource(R.string.format_png)
    SaveFormat.WEBP, SaveFormat.WEBP_LOSSLESS -> stringResource(R.string.format_webp)
}
