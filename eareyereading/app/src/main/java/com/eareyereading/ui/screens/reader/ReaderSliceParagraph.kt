package com.eareyereading.ui.screens.reader

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.eareyereading.ui.theme.*
import com.eareyereading.util.CollinsClassifier

/**
 * 跨页段落切片渲染：渲染 [para] 的 [charStart, charEnd) 行片段。
 * 词色/生词高亮/用户高亮（offset 平移到切片坐标系）/译文（段尾切片）/
 * 朗读句级同步（句子与切片求交，跨页句子在两页各显示各自片段）——
 * 与 [ReaderParagraphBlock] 同一套逻辑。
 */
@Composable
internal fun ReaderSliceParagraphBlock(
    para: String,
    charStart: Int,
    charEnd: Int,
    isCurrent: Boolean,
    isAutoReading: Boolean,
    currentSentences: List<String>,
    currentSentenceIndex: Int,
    alpha: Float,
    fontSize: Int,
    textColor: Color,
    showTranslation: Boolean,
    translation: String?,
    translationAlpha: Float,
    showWordLevelColors: Boolean,
    showKnownWordsHighlight: Boolean,
    knownWords: Set<String>,
    learnedWords: Set<String>,
    onWordClick: (String) -> Unit,
    onSentenceDoubleTap: (String) -> Unit,
    classifier: CollinsClassifier,
    sliceHighlights: List<HighlightData>,
    showBookmarkMark: Boolean,
) {
    val start = charStart.coerceIn(0, para.length)
    val end = charEnd.coerceIn(start, para.length)
    val sliceText = remember(para, start, end) { para.substring(start, end) }
    val isFirst = start == 0
    val isLast = end >= para.length
    // 切片级高亮：原段落坐标系 → 切片坐标系（求交后平移）
    val shiftedHighlights = remember(para, sliceHighlights, start, end) {
        sliceHighlights.mapNotNull { h ->
            val s = h.startOffset.coerceIn(0, para.length)
            val e = h.endOffset.coerceIn(s, para.length)
            val ns = (s - start).coerceAtLeast(0)
            val ne = (e - start).coerceAtMost(sliceText.length)
            if (ne > ns) HighlightData(h.id, ns, ne, h.text, h.color) else null
        }
    }

    Column(modifier = readerParagraphContainerModifier(isCurrent, isAutoReading)) {
        if (showBookmarkMark) ReaderBookmarkMark()

        // 朗读句级同步：句子范围与切片求交，逐句分档透明度渲染
        val sentenceRanges = remember(para, currentSentences) {
            var from = 0
            val ranges = mutableListOf<IntArray>()
            for (s in currentSentences) {
                val i = para.indexOf(s, from)
                if (i < 0) return@remember null
                ranges.add(intArrayOf(i, i + s.length))
                from = i + s.length
            }
            ranges
        }

        if (isCurrent && isAutoReading && !sentenceRanges.isNullOrEmpty()) {
            val accent = LocalReaderAccent.current
            val annotated = remember(
                sliceText, sentenceRanges, currentSentenceIndex,
                showWordLevelColors, textColor, accent,
            ) {
                buildAutoReadingSliceAnnotated(
                    para = para,
                    sliceStart = start,
                    sliceEnd = end,
                    sentenceRanges = sentenceRanges,
                    currentSentenceIndex = currentSentenceIndex,
                    showWordLevelColors = showWordLevelColors,
                    textColor = textColor,
                    accent = accent,
                    classifier = classifier,
                )
            }
            TappableParagraphText(
                text = annotated,
                paragraph = sliceText,
                onWordClick = onWordClick,
                onSentenceDoubleTap = onSentenceDoubleTap,
                // 句子可能跨页：双击时用全局 offset 在完整段落里找整句
                sentenceLookup = { local -> findSentenceAtGlobalOffset(para, start + local) },
                modifier = Modifier.alpha(1f),
                style = readerParagraphStyle(fontSize),
            )
        } else {
            val annotatedText = remember(
                sliceText, textColor, showWordLevelColors,
                showKnownWordsHighlight, knownWords, learnedWords, shiftedHighlights,
            ) {
                buildReaderAnnotated(
                    text = sliceText,
                    textColor = textColor,
                    showWordLevelColors = showWordLevelColors,
                    showKnownWordsHighlight = showKnownWordsHighlight,
                    knownWords = knownWords,
                    learnedWords = learnedWords,
                    highlights = shiftedHighlights,
                    classifier = classifier,
                )
            }
            TappableParagraphText(
                text = annotatedText,
                paragraph = sliceText,
                onWordClick = onWordClick,
                onSentenceDoubleTap = onSentenceDoubleTap,
                sentenceLookup = { local -> findSentenceAtGlobalOffset(para, start + local) },
                modifier = Modifier
                    .padding(
                        top = if (isFirst) 6.dp else 0.dp,
                        bottom = if (isLast) 6.dp else 0.dp,
                    )
                    .alpha(alpha),
                style = readerParagraphStyle(fontSize),
            )
        }

        // 译文跟随段尾切片（与整段渲染一致）
        if (isLast && showTranslation && !translation.isNullOrBlank()) {
            ReaderTranslationBlock(
                translation = translation,
                fontSize = fontSize,
                alpha = alpha,
                translationAlpha = translationAlpha,
            )
        }
    }
}

/**
 * 朗读中的切片文本：句子范围 ∩ 切片范围的分段 AnnotatedString。
 * 每段按句子档位（已读 0.45 / 当前 1f / 未读 0.6）上色，当前句带
 * 强调色底；词频着色开启时在句子档位之上再叠词色（与
 * [AutoReadingSentenceText] 同一套规则）。
 */
private fun buildAutoReadingSliceAnnotated(
    para: String,
    sliceStart: Int,
    sliceEnd: Int,
    sentenceRanges: List<IntArray>,
    currentSentenceIndex: Int,
    showWordLevelColors: Boolean,
    textColor: Color,
    accent: Color,
    classifier: CollinsClassifier,
): AnnotatedString = buildAnnotatedString {
    var cursor = sliceStart
    sentenceRanges.forEachIndexed { sIdx, range ->
        val sStart = range[0]
        val sEnd = range[1]
        if (sEnd <= sliceStart || sStart >= sliceEnd) return@forEachIndexed
        // 句间空白（切片内部分）
        if (cursor < sStart) {
            val gapEnd = sStart.coerceAtMost(sliceEnd)
            if (gapEnd > cursor) {
                withStyle(SpanStyle(color = textColor.copy(alpha = 0.6f))) {
                    append(para.substring(cursor, gapEnd))
                }
            }
        }
        val fragStart = sStart.coerceAtLeast(sliceStart).coerceAtLeast(cursor)
        val fragEnd = sEnd.coerceAtMost(sliceEnd)
        if (fragEnd > fragStart) {
            val sAlpha = when {
                sIdx < currentSentenceIndex -> 0.45f
                sIdx == currentSentenceIndex -> 1f
                else -> 0.6f
            }
            val fragment = para.substring(fragStart, fragEnd)
            val fragOffset = length
            appendWordLevelColored(fragment, sAlpha, textColor, showWordLevelColors, classifier)
            if (sIdx == currentSentenceIndex) {
                addStyle(
                    SpanStyle(background = accent.copy(alpha = 0.10f)),
                    fragOffset, length,
                )
            }
            cursor = sEnd
        }
    }
    // 尾部空白
    if (cursor < sliceEnd) {
        withStyle(SpanStyle(color = textColor.copy(alpha = 0.6f))) {
            append(para.substring(cursor, sliceEnd))
        }
    }
}
