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
    bookmarkedParagraphs: Set<Int> = emptySet(),
    highlights: Map<Int, List<HighlightData>> = emptyMap(),
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
    LaunchedEffect(currentIndex) {
        // 目标段已在可见窗口内就不发起程序化滚动：反向同步把用户滑动
        // 经过的段落写回 currentIndex 后，这里若再 animateScrollToItem
        // 会在甩动（fling）途中反复打断惯性、把视口拽回段首
        if (currentIndex in paragraphs.indices &&
            listState.layoutInfo.visibleItemsInfo.none { it.index == currentIndex }
        ) {
            listState.animateScrollToItem(currentIndex)
        }
    }
    // 反向同步：用户滑动阅读时把可见段落回报给 VM，
    // 让底栏/滑杆/进度/统计跟上视口（播放中由播放循环主导，VM 侧会忽略）
    LaunchedEffect(listState) {
        snapshotFlow { listState.firstVisibleItemIndex }
            .collect { idx -> onVisibleParagraphChanged(idx) }
    }

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
                classifier = classifier,
                bookId = bookId,
            )
        }
    }
}
