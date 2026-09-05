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
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.eareyereading.ui.theme.*
import com.eareyereading.util.BookImages
import com.eareyereading.util.ClozeWord

/**
 * 练习类阅读视图：挖空（Cloze）/ 模糊（Fuzzy）/ 回译（BackTranslation）/ 听写（Dictation）。
 */
// ── 挖空练习视图 ────────────────────────────────
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun ClozeReadingView(
    clozeWords: List<ClozeWord>,
    fontSize: Int,
    textColor: Color,
    showTranslation: Boolean,
    translationAlpha: Float = 0.85f,
    currentTranslation: String?,
    onReveal: () -> Unit,
    onWordClick: (String) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(vertical = 8.dp),
    ) {
        // FlowRow 行内排布：原实现把每个词放进纵向 Column，
        // 一段话被渲染成一列单词，完全不可读。
        // 揭示是渐进的：VM 每按一次"显示答案"清除一个隐藏词标记
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
        ) {
            clozeWords.forEach { clozeWord ->
                if (clozeWord.isWord) {
                    if (clozeWord.isHidden) {
                        Text(
                            text = "____",
                            color = textColor.copy(alpha = 0.5f),
                            fontSize = fontSize.sp,
                            modifier = Modifier
                                .padding(horizontal = 4.dp, vertical = 2.dp)
                                // 挖空词本身可点击揭示，扩大可点区并补上此前缺的
                                // 点击入口（issue 3.5）
                                .clickable { onReveal() },
                        )
                    } else {
                        Text(
                            text = clozeWord.text,
                            color = textColor,
                            fontSize = fontSize.sp,
                            modifier = Modifier
                                .padding(horizontal = 4.dp, vertical = 2.dp)
                                .clickable { onWordClick(clozeWord.text) },
                        )
                    }
                } else {
                    // 分隔符 token 原样输出，保证词间距/标点自然
                    Text(
                        text = clozeWord.text,
                        color = textColor.copy(alpha = 0.7f),
                        fontSize = fontSize.sp,
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(24.dp))
        val remainingHidden = clozeWords.count { it.isWord && it.isHidden }
        FilledTonalButton(
            onClick = onReveal,
            enabled = remainingHidden > 0,
            modifier = Modifier.align(Alignment.CenterHorizontally),
        ) {
            Icon(Icons.Default.Visibility, null)
            Spacer(modifier = Modifier.width(8.dp))
            Text(if (remainingHidden > 0) "显示答案（剩 $remainingHidden 空）" else "已全部揭示")
        }

        if (showTranslation && !currentTranslation.isNullOrBlank()) {
            Spacer(modifier = Modifier.height(16.dp))
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = LocalReaderAccent.current.copy(alpha = 0.1f)),
            ) {
                Text(
                    text = currentTranslation,
                    modifier = Modifier.padding(12.dp),
                    color = LocalReaderAccent.current.copy(alpha = translationAlpha),
                    fontSize = (fontSize - 2).sp,
                )
            }
        }
    }
}

// ── 模糊阅读视图 ────────────────────────────────
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun FuzzyReadingView(
    fuzzyWords: List<com.eareyereading.util.FuzzyWord>,
    fontSize: Int,
    textColor: Color,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(vertical = 8.dp),
    ) {
        // 同挖空视图：FlowRow 行内排布，不再一词一行
        FlowRow(modifier = Modifier.fillMaxWidth()) {
            fuzzyWords.forEach { fuzzyWord ->
                Text(
                    text = fuzzyWord.text,
                    color = if (fuzzyWord.isBlurred) textColor.copy(alpha = 0.15f) else textColor,
                    fontSize = fontSize.sp,
                    modifier = if (fuzzyWord.isBlurred) Modifier.blur(8.dp) else Modifier,
                )
            }
        }
    }
}

// ── 中译英回译视图 ───────────────────────────────
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
    LaunchedEffect(currentIndex) {
        val target = currentIndex + 1
        if (currentIndex in paragraphs.indices &&
            listState.layoutInfo.visibleItemsInfo.none { it.index == target }
        ) {
            listState.animateScrollToItem(target)
        }
    }
    // 反向同步（与 SPLIT 同款）：item 0 是表头，段落索引 = item 索引 - 1。
    // 缺失时用户在回译模式里滑多远，退出后进度/统计都停在旧位置
    LaunchedEffect(listState) {
        snapshotFlow { listState.firstVisibleItemIndex }
            .collect { idx -> onVisibleParagraphChanged((idx - 1).coerceAtLeast(0)) }
    }

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
                    if (isTranslating) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                strokeWidth = 2.dp,
                                color = primaryColor,
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                "正在获取译文...",
                                style = MaterialTheme.typography.bodySmall,
                                color = textColor.copy(alpha = 0.5f),
                            )
                        }
                    } else {
                        // 翻译失败/未触发时不转假圈：给出明确状态 + 重试入口
                        // （旧实现只看 translations.isEmpty()，失败后再无动静，
                        // 用户对着永远转不完的 spinner 没有任何可做的事）
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                "译文不可用",
                                style = MaterialTheme.typography.bodySmall,
                                color = textColor.copy(alpha = 0.5f),
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            TextButton(
                                onClick = onRetryTranslate,
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                            ) {
                                Text(
                                    "点击重试",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = primaryColor,
                                )
                            }
                        }
                    }
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
@Composable
fun DictationReadingView(
    clozeWords: List<ClozeWord>,
    answer: String?,
    fontSize: Int,
    textColor: Color,
    onCheckAnswer: (String) -> Boolean,
    onStartDictation: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        if (clozeWords.isEmpty()) {
            // 还没开始听写
            Icon(
                Icons.Default.RecordVoiceOver,
                null,
                modifier = Modifier.size(64.dp),
                tint = LocalReaderAccent.current,
            )
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                "听写练习",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                "点击下方按钮开始，\n系统会随机隐藏段落中的部分单词",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            Spacer(modifier = Modifier.height(24.dp))
            FilledTonalButton(onClick = onStartDictation) {
                Icon(Icons.Default.PlayArrow, null)
                Spacer(modifier = Modifier.width(8.dp))
                Text("开始听写")
            }
        } else {
            // 听写进行中
            Text(
                "请填写划线单词",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = LocalReaderAccent.current,
            )
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = buildAnnotatedString {
                    clozeWords.forEach { word ->
                        if (word.isWord) {
                            if (word.isHidden) {
                                // 填空占位：原实现直接把答案单词加下划线原样输出，
                                // 等于把答案写在题面上
                                withStyle(SpanStyle(
                                    textDecoration = TextDecoration.Underline,
                                    fontWeight = FontWeight.Bold,
                                    color = LocalReaderAccent.current,
                                )) { append("____") }
                            } else {
                                withStyle(SpanStyle(color = textColor)) { append(word.text) }
                            }
                        } else {
                            withStyle(SpanStyle(color = textColor.copy(alpha = 0.7f))) { append(word.text) }
                        }
                    }
                },
                style = readerParagraphStyle(fontSize, 2f),
            )
            Spacer(modifier = Modifier.height(24.dp))

            // 核对答案：输入与下一个隐藏词匹配才揭示（VM 侧判定并反馈）
            val hiddenWords = clozeWords.filter { it.isHidden }.map { it.text }
            if (hiddenWords.isNotEmpty()) {
                var inputWord by remember { mutableStateOf("") }
                OutlinedTextField(
                    value = inputWord,
                    onValueChange = { inputWord = it },
                    label = { Text("填写答案") },
                    modifier = Modifier.fillMaxWidth(0.8f),
                    singleLine = true,
                )
                Spacer(modifier = Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilledTonalButton(onClick = {
                        if (inputWord.isNotBlank() && onCheckAnswer(inputWord)) {
                            inputWord = ""
                        }
                    }) {
                        Text("核对答案")
                    }
                    OutlinedButton(onClick = {
                        inputWord = ""
                        onStartDictation()
                    }) {
                        Text("重新出题")
                    }
                }
                if (answer != null) {
                    Spacer(modifier = Modifier.height(12.dp))
                    Card(
                        colors = CardDefaults.cardColors(containerColor = Success.copy(alpha = 0.1f)),
                    ) {
                        Text(
                            "答案: $answer",
                            modifier = Modifier.padding(12.dp),
                            color = Success,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                }
            }
        }
    }
}
