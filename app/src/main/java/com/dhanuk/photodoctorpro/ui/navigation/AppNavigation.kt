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
    val enterTransition = fadeIn(animationSpec = tween(ANIM_DURATION)) +
            androidx.compose.animation.slideInVertically(
                animationSpec = tween(ANIM_DURATION),
                initialOffsetY = { it / 20 }
            )
    val exitTransition = fadeOut(animationSpec = tween(ANIM_DURATION / 2)) +
            androidx.compose.animation.slideOutVertically(
                animationSpec = tween(ANIM_DURATION / 2),
                targetOffsetY = { -it / 20 }
            )
    val popEnterTransition = fadeIn(animationSpec = tween(ANIM_DURATION)) +
            androidx.compose.animation.slideInVertically(
                animationSpec = tween(ANIM_DURATION),
                initialOffsetY = { -it / 20 }
            )
    val popExitTransition = fadeOut(animationSpec = tween(ANIM_DURATION / 2)) +
            androidx.compose.animation.slideOutVertically(
                animationSpec = tween(ANIM_DURATION / 2),
                targetOffsetY = { it / 20 }
            )

    NavHost(navController = navController, startDestination = "home", modifier = modifier) {
        composable(
            "home",
            enterTransition = { fadeIn(tween(ANIM_DURATION)) },
            exitTransition = { fadeOut(tween(ANIM_DURATION / 2)) }
        ) { HomeScreen(navController) }

        composable(
            "history",
            enterTransition = { enterTransition },
            exitTransition = { exitTransition },
            popEnterTransition = { popEnterTransition },
            popExitTransition = { popExitTransition }
        ) { HistoryScreen(navController) }

        composable(
            "settings",
            enterTransition = { enterTransition },
            exitTransition = { exitTransition },
            popEnterTransition = { popEnterTransition },
            popExitTransition = { popExitTransition }
        ) { SettingsScreen(navController) }

        composable(
            "remove_background",
            enterTransition = { AnimatedContentTransitionScope.SlideDirection.Left slideInto tween(ANIM_DURATION) + fadeIn(tween(ANIM_DURATION)) },
            exitTransition = { fadeOut(tween(ANIM_DURATION / 2)) },
            popEnterTransition = { fadeIn(tween(ANIM_DURATION)) },
            popExitTransition = { AnimatedContentTransitionScope.SlideDirection.Right slideOut tween(ANIM_DURATION / 2) + fadeOut(tween(ANIM_DURATION / 2)) }
        ) { RemoveBackgroundScreen(navController) }

        composable(
            "object_eraser",
            enterTransition = { AnimatedContentTransitionScope.SlideDirection.Left slideInto tween(ANIM_DURATION) + fadeIn(tween(ANIM_DURATION)) },
            exitTransition = { fadeOut(tween(ANIM_DURATION / 2)) },
            popEnterTransition = { fadeIn(tween(ANIM_DURATION)) },
            popExitTransition = { AnimatedContentTransitionScope.SlideDirection.Right slideOut tween(ANIM_DURATION / 2) + fadeOut(tween(ANIM_DURATION / 2)) }
        ) { ObjectEraserScreen(navController) }

        composable(
            "enhance_image",
            enterTransition = { AnimatedContentTransitionScope.SlideDirection.Left slideInto tween(ANIM_DURATION) + fadeIn(tween(ANIM_DURATION)) },
            exitTransition = { fadeOut(tween(ANIM_DURATION / 2)) },
            popEnterTransition = { fadeIn(tween(ANIM_DURATION)) },
            popExitTransition = { AnimatedContentTransitionScope.SlideDirection.Right slideOut tween(ANIM_DURATION / 2) + fadeOut(tween(ANIM_DURATION / 2)) }
        ) { EnhanceImageScreen(navController) }

        composable(
            "image_to_pdf",
            enterTransition = { AnimatedContentTransitionScope.SlideDirection.Left slideInto tween(ANIM_DURATION) + fadeIn(tween(ANIM_DURATION)) },
            exitTransition = { fadeOut(tween(ANIM_DURATION / 2)) },
            popEnterTransition = { fadeIn(tween(ANIM_DURATION)) },
            popExitTransition = { AnimatedContentTransitionScope.SlideDirection.Right slideOut tween(ANIM_DURATION / 2) + fadeOut(tween(ANIM_DURATION / 2)) }
        ) { ImageToPdfScreen(navController) }

        composable(
            "color_adjustments",
            enterTransition = { AnimatedContentTransitionScope.SlideDirection.Left slideInto tween(ANIM_DURATION) + fadeIn(tween(ANIM_DURATION)) },
            exitTransition = { fadeOut(tween(ANIM_DURATION / 2)) },
            popEnterTransition = { fadeIn(tween(ANIM_DURATION)) },
            popExitTransition = { AnimatedContentTransitionScope.SlideDirection.Right slideOut tween(ANIM_DURATION / 2) + fadeOut(tween(ANIM_DURATION / 2)) }
        ) { ColorAdjustmentsScreen(navController) }

        composable(
            "exif_stripper",
            enterTransition = { AnimatedContentTransitionScope.SlideDirection.Left slideInto tween(ANIM_DURATION) + fadeIn(tween(ANIM_DURATION)) },
            exitTransition = { fadeOut(tween(ANIM_DURATION / 2)) },
            popEnterTransition = { fadeIn(tween(ANIM_DURATION)) },
            popExitTransition = { AnimatedContentTransitionScope.SlideDirection.Right slideOut tween(ANIM_DURATION / 2) + fadeOut(tween(ANIM_DURATION / 2)) }
        ) { ExifStripperScreen(navController) }

        composable(
            "perspective_crop",
            enterTransition = { AnimatedContentTransitionScope.SlideDirection.Left slideInto tween(ANIM_DURATION) + fadeIn(tween(ANIM_DURATION)) },
            exitTransition = { fadeOut(tween(ANIM_DURATION / 2)) },
            popEnterTransition = { fadeIn(tween(ANIM_DURATION)) },
            popExitTransition = { AnimatedContentTransitionScope.SlideDirection.Right slideOut tween(ANIM_DURATION / 2) + fadeOut(tween(ANIM_DURATION / 2)) }
        ) { PerspectiveCropScreen(navController) }

        composable(
            "resize_compress",
            enterTransition = { AnimatedContentTransitionScope.SlideDirection.Left slideInto tween(ANIM_DURATION) + fadeIn(tween(ANIM_DURATION)) },
            exitTransition = { fadeOut(tween(ANIM_DURATION / 2)) },
            popEnterTransition = { fadeIn(tween(ANIM_DURATION)) },
            popExitTransition = { AnimatedContentTransitionScope.SlideDirection.Right slideOut tween(ANIM_DURATION / 2) + fadeOut(tween(ANIM_DURATION / 2)) }
        ) { ResizeCompressScreen(navController) }

        composable(
            "privacy_policy",
            enterTransition = { AnimatedContentTransitionScope.SlideDirection.Left slideInto tween(ANIM_DURATION) + fadeIn(tween(ANIM_DURATION)) },
            exitTransition = { fadeOut(tween(ANIM_DURATION / 2)) },
            popEnterTransition = { fadeIn(tween(ANIM_DURATION)) },
            popExitTransition = { AnimatedContentTransitionScope.SlideDirection.Right slideOut tween(ANIM_DURATION / 2) + fadeOut(tween(ANIM_DURATION / 2)) }
        ) { PrivacyPolicyScreen(navController) }

        composable(
            "terms_and_conditions",
            enterTransition = { AnimatedContentTransitionScope.SlideDirection.Left slideInto tween(ANIM_DURATION) + fadeIn(tween(ANIM_DURATION)) },
            exitTransition = { fadeOut(tween(ANIM_DURATION / 2)) },
            popEnterTransition = { fadeIn(tween(ANIM_DURATION)) },
            popExitTransition = { AnimatedContentTransitionScope.SlideDirection.Right slideOut tween(ANIM_DURATION / 2) + fadeOut(tween(ANIM_DURATION / 2)) }
        ) { TermsAndConditionsScreen(navController) }
    }
}

private infix fun AnimatedContentTransitionScope.SlideDirection.slideInto(duration: Int) =
    slideIntoContainer(this, animationSpec = tween(duration))

private infix fun AnimatedContentTransitionScope.SlideDirection.slideOut(duration: Int) =
    slideOutOfContainer(this, animationSpec = tween(duration))
