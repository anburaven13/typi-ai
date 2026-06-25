package com.typiai.theme

import android.app.Activity
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

private val LightColorScheme = lightColorScheme(
    primary = TypiPrimary,
    onPrimary = TypiOnPrimary,
    primaryContainer = TypiPrimaryContainer,
    onPrimaryContainer = TypiOnPrimaryContainer,
    secondary = TypiSecondary,
    onSecondary = TypiOnSecondary,
    secondaryContainer = TypiSecondaryContainer,
    onSecondaryContainer = TypiOnSecondaryContainer,
    tertiary = TypiTertiary,
    onTertiary = TypiOnTertiary,
    tertiaryContainer = TypiTertiaryContainer,
    onTertiaryContainer = TypiOnTertiaryContainer,
    error = TypiError,
    onError = TypiOnError,
    errorContainer = TypiErrorContainer,
    onErrorContainer = TypiOnErrorContainer,
    background = TypiBackground,
    onBackground = TypiOnBackground,
    surface = TypiSurface,
    onSurface = TypiOnSurface,
    surfaceVariant = TypiSurfaceVariant,
    onSurfaceVariant = TypiOnSurfaceVariant,
    outline = TypiOutline,
    outlineVariant = TypiOutlineVariant,
)

private val DarkColorScheme = darkColorScheme(
    primary = TypiPrimaryDark,
    onPrimary = TypiOnPrimaryDark,
    primaryContainer = TypiPrimaryContainerDark,
    onPrimaryContainer = TypiOnPrimaryContainerDark,
    secondary = TypiSecondaryDark,
    onSecondary = TypiOnSecondaryDark,
    secondaryContainer = TypiSecondaryContainerDark,
    onSecondaryContainer = TypiOnSecondaryContainerDark,
    tertiary = TypiTertiaryDark,
    onTertiary = TypiOnTertiaryDark,
    tertiaryContainer = TypiTertiaryContainerDark,
    onTertiaryContainer = TypiOnTertiaryContainerDark,
    error = TypiErrorDark,
    onError = TypiOnErrorDark,
    errorContainer = TypiErrorContainerDark,
    onErrorContainer = TypiOnErrorContainerDark,
    background = TypiBackgroundDark,
    onBackground = TypiOnBackgroundDark,
    surface = TypiSurfaceDark,
    onSurface = TypiOnSurfaceDark,
    surfaceVariant = TypiSurfaceVariantDark,
    onSurfaceVariant = TypiOnSurfaceVariantDark,
    outline = TypiOutlineDark,
    outlineVariant = TypiOutlineVariantDark,
)

@Composable
fun TypiAITheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit
) {
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> DarkColorScheme
        else -> LightColorScheme
    }

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            window.statusBarColor = colorScheme.surface.toArgb()
            WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = !darkTheme
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content
    )
}
