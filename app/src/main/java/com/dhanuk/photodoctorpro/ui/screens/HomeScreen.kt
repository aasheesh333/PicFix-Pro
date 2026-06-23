package com.dhanuk.photodoctorpro.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Brush
import androidx.compose.material.icons.rounded.Compress
import androidx.compose.material.icons.rounded.CropFree
import androidx.compose.material.icons.rounded.DocumentScanner
import androidx.compose.material.icons.rounded.PictureAsPdf
import androidx.compose.material.icons.rounded.Security
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.dhanuk.photodoctorpro.R
import com.dhanuk.photodoctorpro.ui.components.LuminaFeatureCard

@Composable
fun HomeScreen(navController: NavController) {
    val features = listOf(
        Feature(stringResource(R.string.remove_background), stringResource(R.string.one_tap_cutout), Icons.Rounded.CropFree, "remove_background"),
        Feature(stringResource(R.string.object_eraser), stringResource(R.string.remove_unwanted_objects), Icons.Rounded.Brush, "object_eraser"),
        Feature(stringResource(R.string.enhance_image), stringResource(R.string.upscale_clean), Icons.Rounded.AutoAwesome, "enhance_image"),
        Feature(stringResource(R.string.image_to_pdf), stringResource(R.string.multi_page_pdf), Icons.Rounded.PictureAsPdf, "image_to_pdf"),
        Feature(stringResource(R.string.color_adjustments), stringResource(R.string.color_adjustments_subtitle), Icons.Rounded.Tune, "color_adjustments"),
        Feature(stringResource(R.string.privacy_doctor), stringResource(R.string.privacy_doctor_subtitle), Icons.Rounded.Security, "exif_stripper"),
        Feature(stringResource(R.string.document_scanner), stringResource(R.string.document_scanner_subtitle), Icons.Rounded.DocumentScanner, "perspective_crop"),
        Feature(stringResource(R.string.resize_compress), stringResource(R.string.resize_compress_subtitle), Icons.Rounded.Compress, "resize_compress")
    )

    var visibleCount by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) {
        features.indices.forEach { index ->
            kotlinx.coroutines.delay(80L)
            visibleCount = index + 1
        }
    }

    BoxWithConstraints(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        val isLandscape = maxWidth > maxHeight
        val minCardWidth = 220.dp
        val columnCount = if (isLandscape) {
            (maxWidth / minCardWidth).toInt().coerceAtLeast(3)
        } else {
            2
        }
        val topPadding = if (isLandscape) 12.dp else 24.dp
        val logoSize = if (isLandscape) 72.dp else 96.dp
        val internalLogoSize = if (isLandscape) 56.dp else 72.dp

        LazyVerticalGrid(
            columns = GridCells.Fixed(columnCount),
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 20.dp, vertical = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            item(span = { GridItemSpan(columnCount) }) {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(top = topPadding, bottom = 12.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Box(
                        modifier = Modifier
                            .size(logoSize)
                            .clip(RoundedCornerShape(24.dp))
                            .background(MaterialTheme.colorScheme.surface),
                        contentAlignment = Alignment.Center
                    ) {
                        Image(
                            painter = painterResource(id = R.drawable.app_logo),
                            contentDescription = stringResource(R.string.app_logo),
                            modifier = Modifier.size(internalLogoSize)
                        )
                    }
                    Spacer(modifier = Modifier.height(if (isLandscape) 12.dp else 20.dp))
                    Text(
                        text = stringResource(R.string.app_name),
                        style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.Bold),
                        color = MaterialTheme.colorScheme.onBackground
                    )
                    Text(
                        text = stringResource(R.string.smart_tools_for_your_photos),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            itemsIndexed(features) { index, feature ->
                AnimatedVisibility(
                    visible = index < visibleCount,
                    enter = slideInVertically(
                        animationSpec = tween(400),
                        initialOffsetY = { it / 4 }
                    ) + fadeIn(
                        animationSpec = tween(400)
                    )
                ) {
                    LuminaFeatureCard(
                        title = feature.title,
                        subtitle = feature.subtitle,
                        icon = feature.icon,
                        onClick = { navController.navigate(feature.route) },
                        modifier = Modifier.fillMaxWidth().aspectRatio(if (isLandscape) 1.1f else 0.85f)
                    )
                }
            }
        }
    }
}

data class Feature(
    val title: String,
    val subtitle: String,
    val icon: androidx.compose.ui.graphics.vector.ImageVector,
    val route: String
)
