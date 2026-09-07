package com.eareyereading.ui.screens.reader

import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.sp
import com.eareyereading.ui.theme.Primary

/**
 * 阅读器共享渲染基建：字体/强调色 CompositionLocal、段落样式。
 * 被其余阅读视图文件复用。
 */
/**
 * 阅读器正文字体（衬线切换）：ReaderScreen 顶层 provide，
 * 所有阅读模式视图经 [readerParagraphStyle] 消费，一处切换全局生效。
 */
internal val LocalReaderFontFamily = staticCompositionLocalOf {
    FontFamily.Default
}

/**
 * 阅读器正文强调色（译文/高亮底/模式标签）：随（书内主题 + 系统深色）变化——
 * 深色下用更亮的赤陶 Accent，浅色/护眼用暖棕 Primary。
 * 深层视图（ReaderParagraphBlock/SplitReadingView 等）经
 * `LocalReaderAccent.current` 消费，免逐层透传参数。
 */
internal val LocalReaderAccent = staticCompositionLocalOf { Primary }

/**
 * 段落正文样式统一入口：字号 + 行高（倍数）+ 可选衬线。
 * 保证普通/分栏/回译/成分分析等渲染视图的字形一致切换。
 */
@androidx.compose.runtime.Composable
internal fun readerParagraphStyle(fontSize: Int, lineMultiplier: Float = 1.8f): TextStyle = TextStyle(
    fontSize = fontSize.sp,
    lineHeight = (fontSize * lineMultiplier).sp,
    fontFamily = LocalReaderFontFamily.current,
)
