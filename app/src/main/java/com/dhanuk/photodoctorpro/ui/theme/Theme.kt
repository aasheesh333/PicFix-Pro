package com.dhanuk.photodoctorpro.ui.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
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
    surfaceVariant = SurfaceVariant,
    onSurfaceVariant = OnSurfaceVariant,
    secondary = LightSecondary,
    onSecondary = OnSurface,
    outline = Outline,
    outlineVariant = OutlineVariant,
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
    surfaceContainer = SurfaceContainer,
    surfaceContainerHigh = SurfaceContainerHigh,
    surfaceContainerHighest = SurfaceContainerHighest,
    surfaceContainerLow = SurfaceContainerLow,
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
fun PhotoDoctorProTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    val colorScheme = if (darkTheme) LuminaDarkColors else LuminaLightColors
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            window.statusBarColor = colorScheme.background.toArgb()
            window.navigationBarColor = colorScheme.background.toArgb()
            WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = !darkTheme
            WindowCompat.getInsetsController(window, view).isAppearanceLightNavigationBars = !darkTheme
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = LuminaTypography,
        content = content
    )
}
