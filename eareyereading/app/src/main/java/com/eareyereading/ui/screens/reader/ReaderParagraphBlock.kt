package com.eareyereading.ui.screens.reader

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.unit.dp
import com.eareyereading.util.BookImages
import com.eareyereading.util.CollinsClassifier

/**
 * 单个段落的完整渲染块：书签标记行 + 正文（朗读句子级同步 / Collins 词色 /
 * 生词高亮 / 用户高亮四分支）+ 译文。从滚动视图的 LazyColumn item 抽出，
 * 供滚动（NormalReadingView）与左右翻页（PagedReadingView）两视图共用，
 * 保证两种阅读方式的段落渲染完全一致。
 * 插图标记段（[[IMG:n]]）直接渲染为图片，不走文本分支。
 */
@androidx.compose.runtime.Composable
internal fun ReaderParagraphBlock(
    index: Int,
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
    bookId: Long = 0L,
) {
    // 插图段：整块渲染为图片（书签标记照常保留），不参与词色/高亮/译文
    val imageRef = BookImages.markerRef(para)
    if (imageRef != null) {
        ReaderImageBlock(ref = imageRef, bookId = bookId)
        return
    }
    // 朗读中的当前段落：背景直接加在内容容器上。
    // 原实现额外放了一个包 Text("") 的 Surface —— 零高度，背景永远不可见
    Column(modifier = readerParagraphContainerModifier(isCurrent, isAutoReading)) {
        // 书签段落标记行
        if (isBookmarked) ReaderBookmarkMark()

        // 句子级声文同步高亮（朗读中）
        if (isCurrent && isAutoReading && currentSentences.isNotEmpty()) {
            // 显示已读/当前/未读句子。每句独立组件 + remember：句索引推进时
            // 只有"刚读完"与"刚开始"两句的档位变化会重建 AnnotatedString，
            // 其余句子全部命中缓存（此前每句 tick 全段句子重新分词+编译正则）
            currentSentences.forEachIndexed { sIdx, sentence ->
                val sAlpha = when {
                    sIdx < currentSentenceIndex -> 0.45f  // 已读完
                    sIdx == currentSentenceIndex -> 1f      // 当前读
                    else -> 0.6f                           // 未读
                }
                val bgColor = if (sIdx == currentSentenceIndex)
                    LocalReaderAccent.current.copy(alpha = 0.10f) else Color.Transparent

                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 1.dp),
                    color = bgColor,
                    shape = RoundedCornerShape(4.dp),
                ) {
                    // 朗读中的句子也走 TappableParagraphText：朗读时点词查义
                    // 是核心功能，此前该分支只渲染纯 Text 完全不可点（issue 3.4）
                    AutoReadingSentenceText(
                        sentence = sentence,
                        sAlpha = sAlpha,
                        fontSize = fontSize,
                        textColor = textColor,
                        showWordLevelColors = showWordLevelColors,
                        classifier = classifier,
                        onWordClick = onWordClick,
                        onSentenceDoubleTap = onSentenceDoubleTap,
                    )
                }
            }
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
                    .padding(vertical = 6.dp)
                    .alpha(alpha),
                style = readerParagraphStyle(fontSize),
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

/**
 * 朗读中的单个句子渲染：词级配色 AnnotatedString 按
 * （句子文本, 档位透明度, 配色开关）缓存——句索引推进时只有档位
 * 变化的两句重建，其余命中 remember。
 */
@androidx.compose.runtime.Composable
private fun AutoReadingSentenceText(
    sentence: String,
    sAlpha: Float,
    fontSize: Int,
    textColor: Color,
    showWordLevelColors: Boolean,
    classifier: CollinsClassifier,
    onWordClick: (String) -> Unit,
    onSentenceDoubleTap: (String) -> Unit,
) {
    val sentenceText = remember(sentence, sAlpha, textColor, showWordLevelColors, classifier) {
        buildAnnotatedString {
            appendWordLevelColored(sentence, sAlpha, textColor, showWordLevelColors, classifier)
        }
    }
    TappableParagraphText(
        text = sentenceText,
        paragraph = sentence,
        onWordClick = onWordClick,
        onSentenceDoubleTap = onSentenceDoubleTap,
        modifier = Modifier.padding(vertical = 4.dp, horizontal = 4.dp),
        style = readerParagraphStyle(fontSize),
    )
}
