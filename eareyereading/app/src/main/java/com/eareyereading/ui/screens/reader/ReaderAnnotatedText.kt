package com.eareyereading.ui.screens.reader

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import com.eareyereading.ui.theme.KnownWord
import com.eareyereading.ui.theme.Success
import com.eareyereading.ui.theme.WordLevelAdv
import com.eareyereading.ui.theme.WordLevelCore
import com.eareyereading.ui.theme.WordLevelIntmd
import com.eareyereading.ui.theme.WordLevelRare
import com.eareyereading.ui.theme.WordLevelUpper
import com.eareyereading.util.CollinsClassifier
import com.eareyereading.util.CollinsClassifier.WordLevel

// 词色分词与纯词判定：原本散落在各渲染分支的组合期内反复编译，
// 提为文件级常量后每次调用复用同一 Pattern
internal val WordSplitRegex = Regex("([a-zA-Z]+)|([^a-zA-Z]+)")
internal val PureWordRegex = Regex("^[a-zA-Z]+$")

/**
 * Collins 词频档位 → 主题色。整段渲染、跨页切片渲染、朗读句渲染三条分支
 * 此前各内联一份完全相同的 when，档位配色调整时极易漏改其中一两处。
 * 现为唯一来源。
 */
internal fun wordLevelColor(level: WordLevel, textColor: Color): Color = when (level) {
    WordLevel.CORE -> WordLevelCore
    WordLevel.INTERMEDIATE -> WordLevelIntmd
    WordLevel.UPPER_INTERMEDIATE -> WordLevelUpper
    WordLevel.ADVANCED -> WordLevelAdv
    WordLevel.RARE -> WordLevelRare
    WordLevel.UNKNOWN -> textColor.copy(alpha = 0.5f)
}

/**
 * 按词频档位把一个文本片段写入 AnnotatedString：
 *  - 词色开 → 逐词上色，纯词走 [wordLevelColor]，非词字符用低对比正文色；
 *  - 词色关 → 整段单色。
 * [alpha] 是该片段所在的整体透明度档位（朗读句已读/当前/未读）。
 * 朗读句渲染与切片朗读渲染共用，避免两处词色规则漂移。
 */
internal fun AnnotatedString.Builder.appendWordLevelColored(
    text: String,
    alpha: Float,
    textColor: Color,
    showWordLevelColors: Boolean,
    classifier: CollinsClassifier,
) {
    if (!showWordLevelColors) {
        withStyle(SpanStyle(color = textColor.copy(alpha = alpha))) { append(text) }
        return
    }
    WordSplitRegex.findAll(text).forEach { match ->
        val word = match.value
        if (PureWordRegex.matches(word)) {
            withStyle(
                SpanStyle(color = wordLevelColor(classifier.classify(word), textColor).copy(alpha = alpha)),
            ) { append(word) }
        } else {
            withStyle(SpanStyle(color = textColor.copy(alpha = alpha * 0.6f))) { append(word) }
        }
    }
}

/**
 * 段落/切片通用的词色 AnnotatedString 构建器：
 *  - 词频着色开 → Collins 词色（已认识词优先绿色）+ 用户高亮底色叠加；
 *  - 仅生词高亮 → 已认识/已学词着色 + 高亮叠加；
 *  - 都关 → 纯文本 + 用户高亮。
 * ReaderParagraphBlock 与 ReaderSliceParagraphBlock 共用，保证滚屏/翻页同款渲染。
 */
internal fun buildReaderAnnotated(
    text: String,
    textColor: Color,
    showWordLevelColors: Boolean,
    showKnownWordsHighlight: Boolean,
    knownWords: Set<String>,
    learnedWords: Set<String>,
    highlights: List<HighlightData>,
    classifier: CollinsClassifier,
): AnnotatedString = buildAnnotatedString {
    if (showWordLevelColors) {
        WordSplitRegex.findAll(text).forEach { match ->
            val word = match.value
            if (PureWordRegex.matches(word)) {
                val level = classifier.classify(word)
                val lower = word.lowercase()
                // 生词本优先：已认识的词用绿色
                val color = if (showKnownWordsHighlight && lower in knownWords) {
                    Success
                } else {
                    wordLevelColor(level, textColor)
                }
                withStyle(SpanStyle(color = color)) { append(word) }
            } else {
                withStyle(SpanStyle(color = textColor.copy(alpha = 0.6f))) { append(word) }
            }
        }
        // 词色之上叠加用户高亮背景
        highlights.forEach { h ->
            val s = h.startOffset.coerceIn(0, text.length)
            val e = h.endOffset.coerceIn(s, text.length)
            if (e > s) addStyle(SpanStyle(background = h.color.copy(alpha = 0.25f)), s, e)
        }
    } else if (showKnownWordsHighlight && knownWords.isNotEmpty()) {
        WordSplitRegex.findAll(text).forEach { match ->
            val word = match.value
            if (PureWordRegex.matches(word)) {
                val lower = word.lowercase()
                val color = when {
                    lower in knownWords -> Success
                    lower in learnedWords -> KnownWord
                    else -> textColor
                }
                withStyle(SpanStyle(color = color)) { append(word) }
            } else {
                withStyle(SpanStyle(color = textColor.copy(alpha = 0.6f))) { append(word) }
            }
        }
        highlights.forEach { h ->
            val s = h.startOffset.coerceIn(0, text.length)
            val e = h.endOffset.coerceIn(s, text.length)
            if (e > s) addStyle(SpanStyle(background = h.color.copy(alpha = 0.25f)), s, e)
        }
    } else {
        // 纯文本 + 高亮渲染：按 offset 顺序处理高亮区域；重叠高亮不重复
        // 输出重叠段，负值/反向/越界脏数据收敛后跳过
        var cursor = 0
        val sortedHighlights = highlights.sortedBy { it.startOffset }
        for (highlight in sortedHighlights) {
            val start = highlight.startOffset.coerceAtLeast(cursor)
            val end = highlight.endOffset.coerceIn(start, text.length)
            if (end <= start) continue
            if (cursor < start) {
                withStyle(SpanStyle(color = textColor.copy(alpha = 0.8f))) {
                    append(text.substring(cursor, start))
                }
            }
            withStyle(SpanStyle(
                background = highlight.color.copy(alpha = 0.25f),
                color = highlight.color,
            )) {
                append(text.substring(start, end))
            }
            cursor = end
        }
        if (cursor < text.length) {
            withStyle(SpanStyle(color = textColor.copy(alpha = 0.8f))) {
                append(text.substring(cursor))
            }
        }
    }
}
