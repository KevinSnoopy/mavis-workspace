package com.eareyereading.ui.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import com.eareyereading.domain.model.ReadingTheme

private val LightColorScheme = lightColorScheme(
    primary = Primary,
    onPrimary = OnPrimary,
    primaryContainer = PrimaryLight,
    onPrimaryContainer = Primary,
    secondary = Accent,
    onSecondary = OnPrimary,
    secondaryContainer = SuccessBg,
    onSecondaryContainer = Accent,
    tertiary = Color(0xFF6B8E9E),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFEDF3F5),
    onTertiaryContainer = Color(0xFF6B8E9E),
    background = Background,
    onBackground = OnBackground,
    surface = Surface,
    onSurface = OnSurface,
    surfaceVariant = SurfaceSecondary,
    onSurfaceVariant = OnSurfaceSecondary,
    outline = BorderStrong,
    outlineVariant = Border,
    error = Error,
    onError = OnPrimary,
    errorContainer = ErrorBg,
    onErrorContainer = Error,
)

private val DarkColorScheme = darkColorScheme(
    primary = Primary,
    onPrimary = OnPrimary,
    primaryContainer = PrimaryDark,
    onPrimaryContainer = PrimaryLight,
    secondary = Accent,
    onSecondary = OnPrimary,
    secondaryContainer = Color(0xFF1B4332),
    onSecondaryContainer = Accent,
    tertiary = Color(0xFF6B8E9E),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFF2D4550),
    onTertiaryContainer = Color(0xFF6B8E9E),
    background = DarkBg,
    onBackground = DarkText,
    surface = Color(0xFF252540),
    onSurface = DarkText,
    surfaceVariant = Color(0xFF303050),
    onSurfaceVariant = Color(0xFF9999BB),
    outline = Color(0xFF4A4A6A),
    outlineVariant = Color(0xFF3A3A5A),
    error = Error,
    onError = OnPrimary,
    errorContainer = Color(0xFF5C1A1A),
    onErrorContainer = Color(0xFFFFB4AB),
)

@Composable
fun EareyeReadingTheme(
    readingTheme: ReadingTheme = ReadingTheme.LIGHT,
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
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
                readingTheme != ReadingTheme.DARK && !darkTheme
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content
    )
}
