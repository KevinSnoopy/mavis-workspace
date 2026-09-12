package com.eareyereading.ui.screens.reader

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
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

/**
 * 译文获取状态行：获取中转圈提示，失败时给"译文不可用 + 点击重试"入口。
 *
 * ── 重构说明（DRY）──
 * 回译视图与分栏视图此前各写一份逐行相同的实现（含"不转假圈、给重试入口"
 * 这条 issue 修复），且两处转圈尺寸已经漂移成 16dp / 14dp——正是重复代码
 * 漏改的典型症状。现收敛到此处，尺寸由调用方显式声明。
 *
 * @param isTranslating 是否正在获取译文，决定渲染转圈还是失败重试
 * @param accent 强调色（重试按钮与转圈着色）
 * @param textColor 正文色，用于提示文案的降透明度显示
 * @param onRetry 点击重试回调
 * @param progressSize 转圈尺寸
 */
@Composable
internal fun TranslationStatusRow(
    isTranslating: Boolean,
    accent: Color,
    textColor: Color,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
    progressSize: Dp = 14.dp,
) {
    if (isTranslating) {
        Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(
                modifier = Modifier.size(progressSize),
                strokeWidth = 2.dp,
                color = accent,
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                "正在获取译文...",
                style = MaterialTheme.typography.bodySmall,
                color = textColor.copy(alpha = 0.5f),
            )
        }
    } else {
        Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
            Text(
                "译文不可用",
                style = MaterialTheme.typography.bodySmall,
                color = textColor.copy(alpha = 0.5f),
            )
            Spacer(modifier = Modifier.width(8.dp))
            TextButton(
                onClick = onRetry,
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
            ) {
                Text(
                    "点击重试",
                    style = MaterialTheme.typography.bodySmall,
                    color = accent,
                )
            }
        }
    }
}
