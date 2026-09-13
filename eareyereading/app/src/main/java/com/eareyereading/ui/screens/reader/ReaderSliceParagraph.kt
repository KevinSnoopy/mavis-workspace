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
    paraIndex: Int,
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
    // 长按高亮：回调收到的是切片内偏移，这里加回 charStart 换算成段落坐标
    onLongPressWord: (paragraphIndex: Int, startOffset: Int, endOffset: Int) -> Unit = { _, _, _ -> },
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
        val sentenceRanges = remember(para, currentSentences) { sentenceRangesOf(para, currentSentences) }

        // 段首/段尾内边距：两个分支都必须加上——旧实现的朗读分支漏了它，
        // 于是"正在朗读的段落"比相邻段落矮 12dp，与 paginateBook 的记账
        // 也对不上（翻页模式下表现为页内剩余空间抖动）
        val padModifier = Modifier.padding(
            top = if (isFirst) ReaderLayout.ParagraphPadding else 0.dp,
            bottom = if (isLast) ReaderLayout.ParagraphPadding else 0.dp,
        )

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
                // 长按高亮：切片内偏移 + charStart = 段落坐标
                onLongPress = { local ->
                    wordRangeAt(sliceText, local)?.let { r ->
                        onLongPressWord(paraIndex, start + r.first, start + r.last + 1)
                    }
                },
                modifier = padModifier.alpha(alpha),
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
                onLongPress = { local ->
                    wordRangeAt(sliceText, local)?.let { r ->
                        onLongPressWord(paraIndex, start + r.first, start + r.last + 1)
                    }
                },
                modifier = padModifier.alpha(alpha),
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
 * 逐句在段落文本里定位句子区间（朗读句级同步用）。
 * 任一句子在段落中找不到就整体返回 null——宁可退回无高亮的正常渲染，
 * 也不能拿错位的区间去上色。
 *
 * 单段渲染（[ReaderParagraphBlock]）与跨页切片渲染
 * （[ReaderSliceParagraphBlock]）共用，保证同一段落在两种分页形态下
 * 的句子划分完全一致。
 */
internal fun sentenceRangesOf(para: String, sentences: List<String>): List<IntArray>? {
    if (sentences.isEmpty()) return null
    var from = 0
    val ranges = mutableListOf<IntArray>()
    for (s in sentences) {
        val i = para.indexOf(s, from)
        if (i < 0) return null
        ranges.add(intArrayOf(i, i + s.length))
        from = i + s.length
    }
    return ranges
}

/**
 * 朗读中的段落文本：句子范围 ∩ 切片范围的分段 AnnotatedString。
 * 每段按句子档位（已读 0.45 / 当前 1f / 未读 0.6）上色，当前句带
 * 强调色底；词频着色开启时在句子档位之上再叠词色。
 *
 * 整段朗读同步必须收敛到**一个** Text：一句一个 Text/Surface 会额外引入
 * 逐句内边距与更窄的换行宽度，整段高度不再是 [paginateBook] 算出来的那个值。
 * 传整段（sliceStart = 0、sliceEnd = para.length）即整段渲染。
 */
internal fun buildAutoReadingSliceAnnotated(
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
