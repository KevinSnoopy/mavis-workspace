package com.eareyereading.ui.screens.reader

import androidx.compose.animation.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.eareyereading.ui.theme.*
import com.eareyereading.util.BookImages

/**
 * 回译模式视图：上屏原文隐藏、显示译文，用户看着译文在脑中回译，
 * 点开逐段对照检查。从 PracticeReadingViews.kt 抽出的单模式视图（SRP）。
 */

@Composable
fun BackTranslationView(
    paragraphs: List<String>,
    translations: Map<Int, String>,
    currentIndex: Int,
    fontSize: Int,
    textColor: Color,
    primaryColor: Color,
    translationAlpha: Float = 0.85f,
    isTranslating: Boolean = false,
    onRetryTranslate: () -> Unit = {},
    onVisibleParagraphChanged: (Int) -> Unit = {},
    // 插图渲染用：[[IMG:n]] 标记解析到本书的落盘图片目录
    bookId: Long = 0L,
) {
    // 直接派生即可，无需 remember + LaunchedEffect 多一次组合跳转
    // issue 8.3：失败段不再写 "" 占位，但空白段会写 ""——全空白 Map
    // 同样视为"无译文"，重试按钮才可达
    val hasTranslation = translations.values.any { it.isNotBlank() }
    // 揭示是视图本地状态：原实现"查看原文"会 setReadingMode(NORMAL)，
    // 把用户踢出回译模式还持久化了模式切换。
    // 以段落列表为 key：换书后 revealed 必须复位，否则新书直接继承
    // 上一本书"已揭示"的状态（issue 3.3）
    var revealed by rememberSaveable(paragraphs) { mutableStateOf(false) }

    // 单 LazyColumn 逐段并排（译文 | 原文）：原实现左右两个独立滚动容器
    // 整书 eager 渲染，滚动不同步时原文第 N 段对上译文第 M 段（与分栏视图
    // 同型缺陷），且未揭示时全书每段都挂 blur 渲染层。改单列后段落严格对齐、
    // 只布局可见段、视口跟随当前段
    val listState = rememberLazyListState()
    // 反向同步（与 SPLIT 同款）：item 0 是表头，段落索引 = item 索引 - 1。
    // 缺失时用户在回译模式里滑多远，退出后进度/统计都停在旧位置。
    // 含对齐闸门（首帧可见区间不得覆盖已恢复进度），见 ReaderViewportSync
    ReaderViewportSync(
        listState = listState,
        currentIndex = currentIndex,
        paragraphCount = paragraphs.size,
        paragraphOffset = 1,
        onVisibleParagraphChanged = onVisibleParagraphChanged,
    )

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 12.dp),
    ) {
        // 顶部说明
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 8.dp),
            colors = CardDefaults.cardColors(containerColor = primaryColor.copy(alpha = 0.08f)),
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                Text(
                    "中译英回译练习",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = primaryColor,
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    "左侧阅读中文译文，尝试翻译成英文，然后点击查看原文对照",
                    style = MaterialTheme.typography.bodySmall,
                    color = textColor.copy(alpha = 0.7f),
                )
                if (!hasTranslation) {
                    Spacer(modifier = Modifier.height(8.dp))
                    // 翻译失败/未触发时不转假圈：给出明确状态 + 重试入口
                    // （旧实现只看 translations.isEmpty()，失败后再无动静，
                    // 用户对着永远转不完的 spinner 没有任何可做的事）
                    TranslationStatusRow(
                        isTranslating = isTranslating,
                        accent = primaryColor,
                        textColor = textColor,
                        onRetry = onRetryTranslate,
                        progressSize = 16.dp,
                    )
                }
            }
        }

        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxSize()
                .padding(bottom = 16.dp),
        ) {
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "中文译文",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = primaryColor,
                        modifier = Modifier.weight(1f),
                    )
                    Row(
                        modifier = Modifier.weight(1f),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            "英文原文",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = textColor.copy(alpha = 0.6f),
                        )
                        TextButton(
                            onClick = { revealed = !revealed },
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                        ) {
                            Icon(
                                if (revealed) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                null,
                                modifier = Modifier.size(14.dp),
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                if (revealed) "隐藏原文" else "查看原文",
                                style = MaterialTheme.typography.labelSmall,
                            )
                        }
                    }
                }
                Spacer(modifier = Modifier.height(8.dp))
            }
            itemsIndexed(
                items = paragraphs,
                key = { index, _ -> index },
            ) { index, para ->
                val translation = translations[index]
                val alpha = if (index == currentIndex) 1f else 0.5f
                // 插图标记段：整宽渲染插图（无译文/原文可对照）
                val imageRef = BookImages.markerRef(para)
                if (imageRef != null) {
                    ReaderImageBlock(ref = imageRef, bookId = bookId)
                } else {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(
                            text = translation ?: "...",
                            modifier = Modifier
                                .weight(1f)
                                .padding(vertical = 6.dp),
                            style = readerParagraphStyle(fontSize).copy(
                                color = primaryColor.copy(alpha = alpha * translationAlpha),
                            ),
                        )
                        Text(
                            text = para,
                            modifier = Modifier
                                .weight(1f)
                                .padding(vertical = 6.dp)
                                // 揭示后或译文还没加载完（没东西可挡）时不模糊
                                .blur(if (revealed || !hasTranslation) 0.dp else 6.dp),
                            style = readerParagraphStyle(fontSize).copy(
                                color = textColor.copy(alpha = if (revealed) alpha else alpha * 0.4f),
                            ),
                        )
                    }
                }
                if (index < paragraphs.lastIndex) {
                    Divider(
                        modifier = Modifier.padding(vertical = 6.dp),
                        color = textColor.copy(alpha = 0.1f),
                    )
                }
            }
        }
    }
}

// ── 听写练习视图 ─────────────────────────────────
