package com.dhanuk.photodoctorpro.ui.screens

import androidx.compose.animation.core.tween
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.clickable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.dhanuk.photodoctorpro.R
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.shape.RoundedCornerShape
import com.dhanuk.photodoctorpro.ui.components.BannerAd

// To change the app logo, replace app/src/main/res/drawable/app_logo.png with a new PNG using the SAME name.

@Composable
fun HomeScreen(navController: NavController) {
    val features = listOf(
        Feature(stringResource(R.string.remove_background), stringResource(R.string.one_tap_cutout), Icons.Rounded.CropFree, "remove_background"),
        Feature(stringResource(R.string.object_eraser), stringResource(R.string.remove_unwanted_objects), Icons.Rounded.Brush, "object_eraser"),
        Feature(stringResource(R.string.enhance_image), stringResource(R.string.upscale_clean), Icons.Rounded.AutoAwesome, "enhance_image"),
        Feature(stringResource(R.string.image_to_pdf), stringResource(R.string.multi_page_pdf), Icons.Rounded.PictureAsPdf, "image_to_pdf")
    )

    Column(modifier = Modifier.fillMaxSize()) {
        LazyColumn(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
            contentPadding = PaddingValues(16.dp)
        ) {
            item {
                Spacer(modifier = Modifier.height(32.dp))
                Image(
                    painter = painterResource(id = R.drawable.app_logo),
                    contentDescription = stringResource(R.string.app_logo),
                    modifier = Modifier
                        .size(120.dp)
                        .clip(RoundedCornerShape(32.dp))
                )
                Text(stringResource(R.string.app_name), style = MaterialTheme.typography.headlineMedium)
                Text(stringResource(R.string.smart_tools_for_your_photos), style = MaterialTheme.typography.bodyMedium)
                Spacer(modifier = Modifier.height(32.dp))
            }
            itemsIndexed(features) { index, feature ->
                FeatureCard(
                    feature = feature,
                    navController = navController,
                    animationDelay = index * 100
                )
            }
        }
        BannerAd()
    }
}

@Composable
fun FeatureCard(feature: Feature, navController: NavController, animationDelay: Int) {
    var pressed by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(if (pressed) 0.95f else 1f)

    AnimatedVisibility(
        visible = true,
        enter = slideInVertically(
            initialOffsetY = { it },
            animationSpec = tween(durationMillis = 300, delayMillis = animationDelay)
        )
    ) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 8.dp)
                .scale(scale)
                .clickable {
                    pressed = true
                    navController.navigate(feature.route)
                },
            shape = RoundedCornerShape(24.dp),
            elevation = CardDefaults.cardElevation(defaultElevation = 4.dp),
        ) {
            Row(
                modifier = Modifier.padding(24.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(feature.icon, contentDescription = null, modifier = Modifier.size(40.dp))
                Spacer(modifier = Modifier.width(16.dp))
                Column {
                    Text(feature.title, style = MaterialTheme.typography.titleLarge)
                    Text(feature.subtitle, style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
    }
}

data class Feature(val title: String, val subtitle: String, val icon: ImageVector, val route: String)
