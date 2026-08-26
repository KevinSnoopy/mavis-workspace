package com.eareyereading.ui.theme
@file:Suppress("WildcardImport", "FunctionNaming", "UnusedParameter", "MatchingDeclarationName", "EmptyFunctionBlock", "UnusedPrivateProperty")

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import com.eareyereading.domain.model.ReadingTheme

private val LightColorScheme = lightColorScheme(
    primary = Primary,
    onPrimary = OnPrimary,
    secondary = Secondary,
    onSecondary = OnSecondary,
    background = Background,
    onBackground = OnBackground,
    surface = Surface,
    onSurface = OnSurface,
    surfaceVariant = SurfaceVariant,
    onSurfaceVariant = OnSurfaceVariant,
    error = Error,
)

private val DarkColorScheme = darkColorScheme(
    primary = Primary,
    onPrimary = OnPrimary,
    secondary = Secondary,
    onSecondary = OnSecondary,
    background = DarkBg,
    onBackground = DarkText,
    surface = Color(0xFF252540),
    onSurface = DarkText,
    surfaceVariant = Color(0xFF303050),
    onSurfaceVariant = Color(0xFF9999BB),
    error = Error,
)

@Composable
fun EareyeReadingTheme(
    readingTheme: ReadingTheme = ReadingTheme.LIGHT,
    content: @Composable () -> Unit,
) {
    val darkTheme = isSystemInDarkTheme()
    val colorScheme = when (readingTheme) {
        ReadingTheme.DARK -> DarkColorScheme.copy(
            background = DarkBg,
            surface = Color(0xFF252540),
        )
        ReadingTheme.LIGHT -> LightColorScheme
        ReadingTheme.SEPIA -> LightColorScheme.copy(
            background = SepiaBg,
            surface = Color(0xFFF5E6C8),
            onBackground = SepiaText,
            onSurface = SepiaText,
        )
    }

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            window.statusBarColor = colorScheme.background.toArgb()
            WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars =
                readingTheme != ReadingTheme.DARK
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content
    )
}

private val Color = androidx.compose.ui.graphics.Color
