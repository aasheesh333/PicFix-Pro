package com.dhanuk.photodoctorpro.ui.navigation

import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import com.dhanuk.photodoctorpro.ui.screens.*

private const val ANIM_DURATION = 300

@Composable
fun AppNavigation(navController: NavHostController, modifier: Modifier = Modifier) {
    val slideEnter: AnimatedContentTransitionScope<*>.() -> androidx.compose.animation.EnterTransition = {
        slideIntoContainer(
            AnimatedContentTransitionScope.SlideDirection.Left,
            animationSpec = tween(ANIM_DURATION)
        ) + fadeIn(tween(ANIM_DURATION))
    }
    val slidePopExit: AnimatedContentTransitionScope<*>.() -> androidx.compose.animation.ExitTransition = {
        slideOutOfContainer(
            AnimatedContentTransitionScope.SlideDirection.Right,
            animationSpec = tween(ANIM_DURATION / 2)
        ) + fadeOut(tween(ANIM_DURATION / 2))
    }
    val fadeExit: AnimatedContentTransitionScope<*>.() -> androidx.compose.animation.ExitTransition = {
        fadeOut(tween(ANIM_DURATION / 2))
    }
    val fadePopEnter: AnimatedContentTransitionScope<*>.() -> androidx.compose.animation.EnterTransition = {
        fadeIn(tween(ANIM_DURATION))
    }

    val tabRoutes = setOf("home", "history", "settings")
    val instantEnter: AnimatedContentTransitionScope<*>.() -> androidx.compose.animation.EnterTransition = {
        fadeIn(tween(0))
    }
    val instantExit: AnimatedContentTransitionScope<*>.() -> androidx.compose.animation.ExitTransition = {
        fadeOut(tween(0))
    }

    NavHost(navController = navController, startDestination = "home", modifier = modifier) {
        composable(
            "home",
            enterTransition = { if (initialState.destination.route in tabRoutes) instantEnter else fadeIn(tween(ANIM_DURATION)) },
            exitTransition = { if (targetState.destination.route in tabRoutes) instantExit else fadeOut(tween(ANIM_DURATION / 2)) }
        ) { HomeScreen(navController) }

        composable("history",
            enterTransition = { if (initialState.destination.route in tabRoutes) instantEnter else slideEnter },
            exitTransition = { if (targetState.destination.route in tabRoutes) instantExit else fadeExit },
            popEnterTransition = fadePopEnter, popExitTransition = slidePopExit
        ) { HistoryScreen(navController) }

        composable("settings",
            enterTransition = { if (initialState.destination.route in tabRoutes) instantEnter else slideEnter },
            exitTransition = { if (targetState.destination.route in tabRoutes) instantExit else fadeExit },
            popEnterTransition = fadePopEnter, popExitTransition = slidePopExit
        ) { SettingsScreen(navController) }

        composable("remove_background",
            enterTransition = slideEnter, exitTransition = fadeExit,
            popEnterTransition = fadePopEnter, popExitTransition = slidePopExit
        ) { RemoveBackgroundScreen(navController) }

        composable("object_eraser",
            enterTransition = slideEnter, exitTransition = fadeExit,
            popEnterTransition = fadePopEnter, popExitTransition = slidePopExit
        ) { ObjectEraserScreen(navController) }

        composable("enhance_image",
            enterTransition = slideEnter, exitTransition = fadeExit,
            popEnterTransition = fadePopEnter, popExitTransition = slidePopExit
        ) { EnhanceImageScreen(navController) }

        composable("image_to_pdf",
            enterTransition = slideEnter, exitTransition = fadeExit,
            popEnterTransition = fadePopEnter, popExitTransition = slidePopExit
        ) { ImageToPdfScreen(navController) }

        composable("color_adjustments",
            enterTransition = slideEnter, exitTransition = fadeExit,
            popEnterTransition = fadePopEnter, popExitTransition = slidePopExit
        ) { ColorAdjustmentsScreen(navController) }

        composable("exif_stripper",
            enterTransition = slideEnter, exitTransition = fadeExit,
            popEnterTransition = fadePopEnter, popExitTransition = slidePopExit
        ) { ExifStripperScreen(navController) }

        composable("perspective_crop",
            enterTransition = slideEnter, exitTransition = fadeExit,
            popEnterTransition = fadePopEnter, popExitTransition = slidePopExit
        ) { PerspectiveCropScreen(navController) }

        composable("resize_compress",
            enterTransition = slideEnter, exitTransition = fadeExit,
            popEnterTransition = fadePopEnter, popExitTransition = slidePopExit
        ) { ResizeCompressScreen(navController) }
    }
}
