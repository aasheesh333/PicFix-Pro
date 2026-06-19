package com.dhanuk.photodoctorpro.ui.navigation

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import com.dhanuk.photodoctorpro.ui.screens.*

@Composable
fun AppNavigation(navController: NavHostController, modifier: Modifier = Modifier) {
    NavHost(navController = navController, startDestination = "home", modifier = modifier) {
        composable("home") { HomeScreen(navController) }
        composable("history") { HistoryScreen(navController) }
        composable("settings") { SettingsScreen(navController) }
        composable("remove_background") { RemoveBackgroundScreen(navController) }
        composable("object_eraser") { ObjectEraserScreen(navController) }
        composable("enhance_image") { EnhanceImageScreen(navController) }
        composable("image_to_pdf") { ImageToPdfScreen(navController) }
        composable("color_adjustments") { ColorAdjustmentsScreen(navController) }
        composable("exif_stripper") { ExifStripperScreen(navController) }
        composable("perspective_crop") { PerspectiveCropScreen(navController) }
        composable("resize_compress") { ResizeCompressScreen(navController) }
        composable("meme_maker") { MemeMakerScreen(navController) }
        composable("privacy_policy") { PrivacyPolicyScreen(navController) }
        composable("terms_and_conditions") { TermsAndConditionsScreen(navController) }
    }
}
