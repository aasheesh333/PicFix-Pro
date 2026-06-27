package com.dhanuk.photodoctorpro.ui.theme

import android.app.Activity
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

private val LuminaLightColors = lightColorScheme(
    primary = PhotoGreen,
    onPrimary = OnPhotoGreen,
    primaryContainer = PhotoGreenContainer,
    onPrimaryContainer = OnPhotoGreenContainer,
    background = LightBackground,
    onBackground = LightOnSurface,
    surface = LightSurface,
    onSurface = LightOnSurface,
    surfaceVariant = LightSurface,
    onSurfaceVariant = LightSecondary,
    secondary = LightSecondary,
    onSecondary = LightOnSurface,
    outline = Color(0xFFB0B8B0),
    outlineVariant = Color(0xFFD9DDD9),
    error = ErrorColor,
    onError = OnErrorColor,
    errorContainer = ErrorContainerColor,
    onErrorContainer = OnPhotoGreen
)

private val LuminaDarkColors = darkColorScheme(
    primary = PhotoGreen,
    onPrimary = OnPhotoGreen,
    primaryContainer = PhotoGreenContainer,
    onPrimaryContainer = OnPhotoGreenContainer,
    background = SurfaceDim,
    onBackground = OnSurface,
    surface = Surface,
    onSurface = OnSurface,
    surfaceVariant = SurfaceVariant,
    onSurfaceVariant = OnSurfaceVariant,
    secondary = OnSurfaceVariant,
    onSecondary = OnSurface,
    outline = Outline,
    outlineVariant = OutlineVariant,
    error = ErrorColor,
    onError = OnErrorColor,
    errorContainer = ErrorContainerColor,
    onErrorContainer = OnErrorColor,
    tertiary = TertiaryColor,
    onTertiary = OnTertiaryColor
)

@Composable
fun PicFixProTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    animateThemeChange: Boolean = false,
    content: @Composable () -> Unit
) {
    val targetColorScheme = if (darkTheme) LuminaDarkColors else LuminaLightColors

    val animatedBackground by animateColorAsState(
        targetValue = targetColorScheme.background,
        animationSpec = tween(300),
        label = "bg"
    )
    val animatedSurface by animateColorAsState(
        targetValue = targetColorScheme.surface,
        animationSpec = tween(300),
        label = "surface"
    )
    val animatedOnBackground by animateColorAsState(
        targetValue = targetColorScheme.onBackground,
        animationSpec = tween(300),
        label = "onBg"
    )
    val animatedOnSurface by animateColorAsState(
        targetValue = targetColorScheme.onSurface,
        animationSpec = tween(300),
        label = "onSurface"
    )
    val animatedSurfaceVariant by animateColorAsState(
        targetValue = targetColorScheme.surfaceVariant,
        animationSpec = tween(300),
        label = "surfaceVar"
    )
    val animatedOnSurfaceVariant by animateColorAsState(
        targetValue = targetColorScheme.onSurfaceVariant,
        animationSpec = tween(300),
        label = "onSurfaceVar"
    )
    val animatedOutline by animateColorAsState(
        targetValue = targetColorScheme.outline,
        animationSpec = tween(300),
        label = "outline"
    )

    val colorScheme = if (animateThemeChange) {
        targetColorScheme.copy(
            background = animatedBackground,
            surface = animatedSurface,
            onBackground = animatedOnBackground,
            onSurface = animatedOnSurface,
            surfaceVariant = animatedSurfaceVariant,
            onSurfaceVariant = animatedOnSurfaceVariant,
            outline = animatedOutline
        )
    } else {
        targetColorScheme
    }

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            window.statusBarColor = colorScheme.background.toArgb()
            window.navigationBarColor = colorScheme.background.toArgb()
            WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = !darkTheme
            WindowCompat.getInsetsController(window, view).isAppearanceLightNavigationBars = !darkTheme
        }

        val window = (view.context as Activity).window
        val bgArgb = targetColorScheme.background.toArgb()
        if (window.statusBarColor != bgArgb) {
            window.statusBarColor = bgArgb
            window.navigationBarColor = bgArgb
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = LuminaTypography,
        content = content
    )
}
