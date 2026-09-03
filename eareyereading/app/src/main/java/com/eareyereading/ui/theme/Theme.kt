package com.eareyereading.ui.theme

import android.app.Activity
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
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

// DARK / SEPIA 的 copy 结果提升为顶层单例：
// 旧实现在每次重组时 copy 出全新 ColorScheme 实例 → MaterialTheme
// 参数不相等 → 整棵 UI 树自顶向下全量重组
private val DarkAppColorScheme = DarkColorScheme.copy(
    background = DarkBg,
    surface = Color(0xFF252540),
)

private val SepiaColorScheme = LightColorScheme.copy(
    background = SepiaBg,
    surface = Color(0xFFF5E6C8),
    onBackground = SepiaText,
    onSurface = SepiaText,
)

@Composable
fun EareyeReadingTheme(
    readingTheme: ReadingTheme = ReadingTheme.LIGHT,
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit,
) {
    // 动态取色（Material You）：Android 12+ 跟随壁纸生成整套配色，
    // 品牌暖棕让位给系统色。低于 12 或未开启时保持品牌色不变。
    val context = LocalContext.current
    val useDynamic = dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
    val colorScheme = remember(context, useDynamic, darkTheme, readingTheme) {
        if (useDynamic) {
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        } else {
            // 深色模式覆盖 readingTheme 的颜色方案
            val effectiveTheme = if (darkTheme) ReadingTheme.DARK else readingTheme
            when (effectiveTheme) {
                ReadingTheme.DARK -> DarkAppColorScheme
                ReadingTheme.LIGHT -> LightColorScheme
                ReadingTheme.SEPIA -> SepiaColorScheme
            }
        }
    }
    // 是否深色由输入直接推导：旧实现的 colorScheme == DarkColorScheme 全字段
    // 结构比较在动态取色分支永远为 false（图标颜色判断错误），且每重组一次
    val isDark = if (useDynamic) darkTheme else darkTheme || readingTheme == ReadingTheme.DARK

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            window.statusBarColor = colorScheme.background.toArgb()
            // 深色主题强制深色状态栏；浅色 / SEPIA 强制浅色状态栏
            WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = !isDark
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content
    )
}
