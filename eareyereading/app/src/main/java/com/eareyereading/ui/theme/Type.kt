package com.eareyereading.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.eareyereading.R

/**
 * 字体方案（双轨制）
 *
 * 设计判断：一款英语阅读应用，正文排版是核心体验。
 * - UI 字体：Inter（屏幕无衬线，4 个 weight）
 * - 阅读字体：Literata（屏幕衬线，4 个 weight，专为屏幕阅读优化）
 *
 * 来源：design-system/SPEC.md §3.2 + res/font/ 下的 ttf 文件
 */

// ── UI 字体（按钮 / 标题 / 标签 / 卡片等所有非阅读场景）──
val InterFontFamily = FontFamily(
    Font(R.font.inter_regular, FontWeight.Normal),
    Font(R.font.inter_medium, FontWeight.Medium),
    Font(R.font.inter_semibold, FontWeight.SemiBold),
    Font(R.font.inter_bold, FontWeight.Bold),
)

// ── 阅读字体（Reader 正文 / 长段落）──
val LiterataFontFamily = FontFamily(
    Font(R.font.literata_regular, FontWeight.Normal),
    Font(R.font.literata_medium, FontWeight.Medium),
    Font(R.font.literata_semibold, FontWeight.SemiBold),
    Font(R.font.literata_bold, FontWeight.Bold),
)

/**
 * Material 3 Typography：所有 M3 组件默认用 Inter。
 *
 * 字号阶梯与 M3 标准对齐，仅替换 fontFamily。
 */
val Typography = Typography(
    displayLarge = TextStyle(fontFamily = InterFontFamily, fontWeight = FontWeight.Bold, fontSize = 57.sp, lineHeight = 64.sp),
    displayMedium = TextStyle(fontFamily = InterFontFamily, fontWeight = FontWeight.Bold, fontSize = 45.sp, lineHeight = 52.sp),
    displaySmall = TextStyle(fontFamily = InterFontFamily, fontWeight = FontWeight.Bold, fontSize = 36.sp, lineHeight = 44.sp),
    headlineLarge = TextStyle(fontFamily = InterFontFamily, fontWeight = FontWeight.Bold, fontSize = 34.sp, lineHeight = 41.sp, letterSpacing = (-0.8).sp),
    headlineMedium = TextStyle(fontFamily = InterFontFamily, fontWeight = FontWeight.SemiBold, fontSize = 28.sp, lineHeight = 36.sp),
    headlineSmall = TextStyle(fontFamily = InterFontFamily, fontWeight = FontWeight.SemiBold, fontSize = 24.sp, lineHeight = 32.sp),
    titleLarge = TextStyle(fontFamily = InterFontFamily, fontWeight = FontWeight.SemiBold, fontSize = 22.sp, lineHeight = 28.sp, letterSpacing = (-0.3).sp),
    titleMedium = TextStyle(fontFamily = InterFontFamily, fontWeight = FontWeight.Medium, fontSize = 16.sp, lineHeight = 24.sp),
    titleSmall = TextStyle(fontFamily = InterFontFamily, fontWeight = FontWeight.Medium, fontSize = 14.sp, lineHeight = 20.sp),
    bodyLarge = TextStyle(fontFamily = InterFontFamily, fontWeight = FontWeight.Normal, fontSize = 16.sp, lineHeight = 24.sp),
    bodyMedium = TextStyle(fontFamily = InterFontFamily, fontWeight = FontWeight.Normal, fontSize = 14.sp, lineHeight = 20.sp),
    bodySmall = TextStyle(fontFamily = InterFontFamily, fontWeight = FontWeight.Normal, fontSize = 13.sp, lineHeight = 18.sp),
    labelLarge = TextStyle(fontFamily = InterFontFamily, fontWeight = FontWeight.Medium, fontSize = 14.sp, lineHeight = 20.sp),
    labelMedium = TextStyle(fontFamily = InterFontFamily, fontWeight = FontWeight.Medium, fontSize = 12.sp, lineHeight = 16.sp),
    labelSmall = TextStyle(fontFamily = InterFontFamily, fontWeight = FontWeight.Medium, fontSize = 11.sp, lineHeight = 16.sp, letterSpacing = 0.3.sp),
)

/**
 * 阅读专用 TextStyle 阶梯。
 *
 * Reader 页用 MaterialTheme(typography = ReadingTypography) 覆盖，
 * 让 bodyLarge/bodyMedium 用 Literata 提升长文可读性，
 * 而 UI 控件（按钮/标签）仍用 Inter（默认 Typography）。
 *
 * 来源：design-system/SPEC.md §3.2 + §6.3
 */
val ReadingTypography = Typography(
    bodyLarge = TextStyle(
        fontFamily = LiterataFontFamily,
        fontWeight = FontWeight.Normal,
        fontSize = 18.sp,
        lineHeight = 30.sp,   // 1.65 行高：屏幕衬线阅读最佳
    ),
    bodyMedium = TextStyle(
        fontFamily = LiterataFontFamily,
        fontWeight = FontWeight.Normal,
        fontSize = 16.sp,
        lineHeight = 26.sp,
    ),
    // 其余阶梯沿用 UI Typography 的 Inter，保证 UI 元素一致性
    displayLarge = Typography.displayLarge,
    displayMedium = Typography.displayMedium,
    displaySmall = Typography.displaySmall,
    headlineLarge = Typography.headlineLarge,
    headlineMedium = Typography.headlineMedium,
    headlineSmall = Typography.headlineSmall,
    titleLarge = Typography.titleLarge,
    titleMedium = Typography.titleMedium,
    titleSmall = Typography.titleSmall,
    bodySmall = Typography.bodySmall,
    labelLarge = Typography.labelLarge,
    labelMedium = Typography.labelMedium,
    labelSmall = Typography.labelSmall,
)

/**
 * 区段小标题样式（保留原 API，仅替换字体）
 */
val SectionTitle = TextStyle(
    fontFamily = InterFontFamily,
    fontWeight = FontWeight.SemiBold,
    fontSize = 12.sp,
    lineHeight = 16.sp,
    letterSpacing = 0.8.sp,
    color = OnSurfaceTertiary,
)
