package com.eareyereading.ui.screens.reader

import androidx.compose.animation.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.eareyereading.ui.theme.*
import com.eareyereading.util.CollinsClassifier

/**
 * 普通上下滚动阅读视图（NORMAL 模式）。
 */
// ── 普通阅读视图 ───────────────────────────────

@Composable
fun NormalReadingView(
    paragraphs: List<String>,
    currentIndex: Int,
    fontSize: Int,
    textColor: Color,
    showTranslation: Boolean,
    paragraphTranslations: Map<Int, String>,
    translationAlpha: Float = 0.85f,
    showWordLevelColors: Boolean = false,
    showKnownWordsHighlight: Boolean = true,
    knownWords: Set<String> = emptySet(),
    learnedWords: Set<String> = emptySet(),
    isAutoReading: Boolean = false,
    currentSentences: List<String> = emptyList(),
    currentSentenceIndex: Int = 0,
    onWordClick: (String) -> Unit,
    onSentenceDoubleTap: (String) -> Unit,
    onVisibleParagraphChanged: (Int) -> Unit = {},
    // 可见区间上报（first..last）：翻译上屏据此把"正在看的这一屏"整体延后
    onVisibleRangeChanged: (Int, Int) -> Unit = { _, _ -> },
    bookmarkedParagraphs: Set<Int> = emptySet(),
    highlights: Map<Int, List<HighlightData>> = emptyMap(),
    // 长按高亮：块内解析词区间后回调，最终由 VM 打开高亮抽屉
    onLongPressWord: (paragraphIndex: Int, startOffset: Int, endOffset: Int) -> Unit = { _, _, _ -> },
    // VM 注入的 CollinsClassifier 单例：词表全 App 一份，避免视图内手动
    // new 造成双份内存 + 组合期构建卡首帧
    classifier: CollinsClassifier,
    // 插图渲染用：[[IMG:n]] 标记解析到本书的落盘图片目录
    bookId: Long = 0L,
) {
    // LazyColumn：只布局可见段落。原实现整书 eager Column + 每次重组全文重排版，
    // 播放时每个句子 tick 都是 O(book) 开销。
    // LaunchedEffect 让视口跟随当前段落：滑杆/章节/上下段跳转与自动朗读推进
    // 都会滚动到目标段（此前跳转只改索引，视口从不移动）
    val listState = rememberLazyListState()
    // 视口双向同步（含"首帧可见区间不得覆盖已恢复进度"的对齐闸门），
    // 详见 ReaderViewportSync 的文件头说明
    ReaderViewportSync(
        listState = listState,
        currentIndex = currentIndex,
        paragraphCount = paragraphs.size,
        paragraphOffset = 0,
        onVisibleParagraphChanged = onVisibleParagraphChanged,
        onVisibleRangeChanged = onVisibleRangeChanged,
    )

    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(vertical = 8.dp),
    ) {
        itemsIndexed(
            items = paragraphs,
            key = { index, _ -> index }, // 段落按书加载后不可变，index 是稳定身份
        ) { index, para ->
            ReaderParagraphBlock(
                paraIndex = index,
                para = para,
                isCurrent = index == currentIndex,
                isBookmarked = index in bookmarkedParagraphs,
                paraHighlights = highlights[index] ?: emptyList(),
                alpha = if (index == currentIndex) 1f else if (index < currentIndex) 0.4f else 0.7f,
                fontSize = fontSize,
                textColor = textColor,
                showTranslation = showTranslation,
                translation = paragraphTranslations[index],
                translationAlpha = translationAlpha,
                showWordLevelColors = showWordLevelColors,
                showKnownWordsHighlight = showKnownWordsHighlight,
                knownWords = knownWords,
                learnedWords = learnedWords,
                isAutoReading = isAutoReading,
                currentSentences = currentSentences,
                currentSentenceIndex = currentSentenceIndex,
                onWordClick = onWordClick,
                onSentenceDoubleTap = onSentenceDoubleTap,
                onLongPressWord = onLongPressWord,
                classifier = classifier,
                bookId = bookId,
            )
        }
    }
}
