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
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.eareyereading.ui.theme.*
import com.eareyereading.util.BookImages

/**
 * 分栏对照视图：原文与译文左右（或上下）并列，逐段对齐。
 * 从 AssistedReadingViews.kt 抽出的单模式视图（SRP）。
 */

@Composable
fun SplitReadingView(
    paragraphs: List<String>,
    translations: Map<Int, String>,
    currentIndex: Int,
    fontSize: Int,
    textColor: Color,
    translationAlpha: Float = 0.85f,
    onWordClick: (String) -> Unit,
    onVisibleParagraphChanged: (Int) -> Unit = {},
    isTranslating: Boolean = false,
    onRetryTranslate: () -> Unit = {},
    // 插图渲染用：[[IMG:n]] 标记解析到本书的落盘图片目录
    bookId: Long = 0L,
) {
    // 单滚动容器 + 逐段并排：原实现左右两个独立滚动列，
    // 滚一边另一边不动，原文第 N 段会对上译文第 M 段。
    // LazyColumn 化：整书 eager Column 只布局可见段（与 NORMAL 同型修复）；
    // 视口跟随当前段，滑动阅读反向回报 VM
    val listState = rememberLazyListState()
    // 视口双向同步：item 0 是表头，段落索引 = item 索引 - 1（offset = 1）。
    // 含"首帧可见区间不得覆盖已恢复进度"的对齐闸门，见 ReaderViewportSync
    ReaderViewportSync(
        listState = listState,
        currentIndex = currentIndex,
        paragraphCount = paragraphs.size,
        paragraphOffset = 1,
        onVisibleParagraphChanged = onVisibleParagraphChanged,
    )
    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
    ) {
        item {
            Row(modifier = Modifier.fillMaxWidth()) {
                Text(
                    "原文",
                    style = MaterialTheme.typography.labelSmall,
                    color = LocalReaderAccent.current,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    "译文",
                    style = MaterialTheme.typography.labelSmall,
                    color = LocalReaderAccent.current,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f),
                )
            }
            // 与回译视图同款失败/加载态：setReadingMode 对本模式也会自动触发
            // 全书翻译，全空失败时 toast 一闪而过，这里给可发现的重试入口
            // issue 8.3：空白段会写 "" 占位，isEmpty() 不再是可靠失败信号
            if (translations.values.none { it.isNotBlank() }) {
                Spacer(modifier = Modifier.height(4.dp))
                TranslationStatusRow(
                    isTranslating = isTranslating,
                    accent = LocalReaderAccent.current,
                    textColor = textColor,
                    onRetry = onRetryTranslate,
                )
            }
            Spacer(modifier = Modifier.height(8.dp))
        }
        itemsIndexed(
            items = paragraphs,
            key = { index, _ -> index }, // 段落按书加载后不可变，index 是稳定身份
        ) { index, para ->
            val alpha = if (index == currentIndex) 1f else 0.5f
            val translation = translations[index]
            // 插图标记段：整宽渲染插图（无文本可对照，也不参与译文列）
            val imageRef = BookImages.markerRef(para)
            if (imageRef != null) {
                ReaderImageBlock(ref = imageRef, bookId = bookId)
            } else {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    TappableParagraphText(
                        text = AnnotatedString(para),
                        paragraph = para,
                        onWordClick = onWordClick,
                        onSentenceDoubleTap = {},
                        modifier = Modifier
                            .weight(1f)
                            .padding(vertical = 4.dp),
                        style = readerParagraphStyle(fontSize).copy(
                            color = textColor.copy(alpha = alpha),
                        ),
                    )
                    Text(
                        text = translation ?: "（无译文）",
                        modifier = Modifier
                            .weight(1f)
                            .padding(vertical = 4.dp),
                        style = readerParagraphStyle(fontSize).copy(
                            color = if (translation != null) {
                                LocalReaderAccent.current.copy(alpha = alpha * translationAlpha)
                            } else {
                                textColor.copy(alpha = alpha * 0.4f)
                            },
                        ),
                    )
                }
            }
            if (index < paragraphs.lastIndex) {
                Divider(
                    modifier = Modifier.padding(vertical = 8.dp),
                    color = textColor.copy(alpha = 0.1f),
                )
            }
        }
    }
}

// ── 成分分析视图（词性着色）───────────────────────
