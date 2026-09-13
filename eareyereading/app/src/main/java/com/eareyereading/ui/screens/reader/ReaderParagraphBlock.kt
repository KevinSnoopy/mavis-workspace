package com.eareyereading.ui.screens.reader

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import com.eareyereading.util.BookImages
import com.eareyereading.util.CollinsClassifier

/**
 * 单个段落的完整渲染块：书签标记行 + 正文（朗读句子级同步 / Collins 词色 /
 * 生词高亮 / 用户高亮四分支）+ 译文。从滚动视图的 LazyColumn item 抽出，
 * 供滚动（NormalReadingView）与左右翻页（PagedReadingView）两视图共用，
 * 保证两种阅读方式的段落渲染完全一致。
 * 插图标记段（[[IMG:n]]）直接渲染为图片，不走文本分支。
 *
 * ── 几何约束（翻页模式）──
 * 本文件的每个内边距都会被 [paginateBook] 预先记账（[ReaderLayout]），
 * 任何"只改渲染不改分页"的几何改动都会让一页内容反超一屏。尤其注意
 * 朗读态的句子级高亮：必须用**一个** Text 承载，不能一句一个组件。
 */
@androidx.compose.runtime.Composable
internal fun ReaderParagraphBlock(
    paraIndex: Int,
    para: String,
    isCurrent: Boolean,
    isBookmarked: Boolean,
    paraHighlights: List<HighlightData>,
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
    isAutoReading: Boolean,
    currentSentences: List<String>,
    currentSentenceIndex: Int,
    onWordClick: (String) -> Unit,
    onSentenceDoubleTap: (String) -> Unit,
    classifier: CollinsClassifier,
    // 长按高亮：回调只带命中偏移，词区间在块内解析（块里有整段文本）。
    // 这是高亮链路断点修复后的唯一创建入口
    onLongPressWord: (paragraphIndex: Int, startOffset: Int, endOffset: Int) -> Unit = { _, _, _ -> },
    bookId: Long = 0L,
) {
    // 插图段：整块渲染为图片（书签标记照常保留），不参与词色/高亮/译文
    val imageRef = BookImages.markerRef(para)
    if (imageRef != null) {
        ReaderImageBlock(ref = imageRef, bookId = bookId)
        return
    }
    // 长按 → 词区间 → 交给上层开高亮抽屉；落在标点/空白时静默忽略
    val handleLongPress: (Int) -> Unit = { charOffset ->
        wordRangeAt(para, charOffset)?.let { range ->
            onLongPressWord(paraIndex, range.first, range.last + 1)
        }
    }
    // 朗读中的当前段落：背景直接加在内容容器上。
    // 原实现额外放了一个包 Text("") 的 Surface —— 零高度，背景永远不可见
    Column(modifier = readerParagraphContainerModifier(isCurrent, isAutoReading)) {
        // 书签段落标记行
        if (isBookmarked) ReaderBookmarkMark()

        // 句子级声文同步高亮（朗读中）：句子区间用 span 分档透明度，整段仍是
        // **一个** Text。旧实现一句一个 Surface + Text，每句额外吃掉 10dp
        // 内边距、可用宽度还窄 8dp（换行更多），整段实际高度比 paginateBook
        // 测出来的高一截——翻页模式下这就是又一处"页内还得滚"。跨页切片渲染
        // （ReaderSliceParagraphBlock）一直是单 Text 写法，这里与它对齐。
        val accent = LocalReaderAccent.current
        val sentenceRanges = remember(para, currentSentences, isCurrent, isAutoReading) {
            if (isCurrent && isAutoReading) sentenceRangesOf(para, currentSentences) else null
        }
        if (!sentenceRanges.isNullOrEmpty()) {
            val annotated = remember(
                para, sentenceRanges, currentSentenceIndex,
                showWordLevelColors, textColor, accent,
            ) {
                buildAutoReadingSliceAnnotated(
                    para = para,
                    sliceStart = 0,
                    sliceEnd = para.length,
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
                paragraph = para,
                onWordClick = onWordClick,
                onSentenceDoubleTap = onSentenceDoubleTap,
                modifier = Modifier
                    .padding(vertical = ReaderLayout.ParagraphPadding)
                    .alpha(alpha),
                style = readerParagraphStyle(fontSize),
                onLongPress = handleLongPress,
            )
        } else {
            // 词色/生词高亮/用户高亮统一构建（与翻页切片共用同一构建器，
            // 保证滚屏/翻页两种阅读方式渲染一致）
            val annotatedText = remember(
                para, textColor, showWordLevelColors,
                showKnownWordsHighlight, knownWords, learnedWords, paraHighlights,
            ) {
                buildReaderAnnotated(
                    text = para,
                    textColor = textColor,
                    showWordLevelColors = showWordLevelColors,
                    showKnownWordsHighlight = showKnownWordsHighlight,
                    knownWords = knownWords,
                    learnedWords = learnedWords,
                    highlights = paraHighlights,
                    classifier = classifier,
                )
            }
            TappableParagraphText(
                text = annotatedText,
                paragraph = para,
                onWordClick = onWordClick,
                onSentenceDoubleTap = onSentenceDoubleTap,
                modifier = Modifier
                    .padding(vertical = ReaderLayout.ParagraphPadding)
                    .alpha(alpha),
                style = readerParagraphStyle(fontSize),
                onLongPress = handleLongPress,
            )
        }

        // 翻译（透明度可调）
        if (showTranslation && !translation.isNullOrBlank()) {
            ReaderTranslationBlock(
                translation = translation,
                fontSize = fontSize,
                alpha = alpha,
                translationAlpha = translationAlpha,
            )
        }
    }
}
