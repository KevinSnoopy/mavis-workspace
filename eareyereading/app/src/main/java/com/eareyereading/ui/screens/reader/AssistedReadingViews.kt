package com.eareyereading.ui.screens.reader

import androidx.compose.animation.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.eareyereading.ui.theme.*
import com.eareyereading.util.BookImages
import com.eareyereading.util.PosTag
import com.eareyereading.util.WordAnalyzer

/**
 * 辅助阅读视图：RSVP 仿生 / 快速阅读（Speed）/ 分栏对照（Split）/ 成分分析（PosAnalysis）。
 */
// ── RSVP 仿生阅读视图 ──────────────────────────
@Composable
fun RsvpReadingView(
    paragraph: String,
    currentWordIndex: Int,
    fontSize: Int,
    textColor: Color,
    isPlaying: Boolean,
    rsvpStrength: Int = 3,
) {
    val wordAnalyzer = remember { WordAnalyzer() }
    // 必须与 ReaderViewModel.getCurrentParagraphWords()（wordAnalyzer.extractWords，
    // 即 [a-zA-Z]+ 分词）使用完全相同的分词器：原实现按空白切分，
    // 遇到 "don't" 这类缩写时两边词数不一致，播放中显示空白且进度条超过 100%
    val words = remember(paragraph) { wordAnalyzer.extractWords(paragraph) }
    val currentWord = words.getOrNull(currentWordIndex) ?: ""

    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        if (currentWord.isNotEmpty()) {
            val (bold, normal) = wordAnalyzer.processRsvpWord(currentWord, rsvpStrength)
            Text(
                text = buildAnnotatedString {
                    withStyle(SpanStyle(fontWeight = FontWeight.Bold, color = textColor)) {
                        append(bold)
                    }
                    withStyle(SpanStyle(fontWeight = FontWeight.Normal, color = textColor.copy(alpha = 0.7f))) {
                        append(normal)
                    }
                },
                fontSize = (fontSize * 3).sp,
                textAlign = TextAlign.Center,
            )
            // 强度指示：纯展示徽章。原实现是 onClick={} 的 AssistChip，
            // TalkBack 会把它读成"没反应的按钮"
            Surface(
                shape = RoundedCornerShape(50),
                color = LocalReaderAccent.current.copy(alpha = 0.1f),
                modifier = Modifier.padding(top = 8.dp),
            ) {
                Text(
                    "强度 $rsvpStrength",
                    color = LocalReaderAccent.current,
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                )
            }
        } else {
            Text("点击播放按钮开始", color = textColor.copy(alpha = 0.5f))
        }
        Spacer(modifier = Modifier.height(24.dp))
        LinearProgressIndicator(
            progress = if (words.isNotEmpty()) {
                ((currentWordIndex + 1).toFloat() / words.size).coerceIn(0f, 1f)
            } else 0f,
            modifier = Modifier.width(200.dp),
        )
        if (words.isNotEmpty()) {
            Text(
                text = "${(currentWordIndex + 1).coerceAtMost(words.size)} / ${words.size}",
                color = textColor.copy(alpha = 0.5f),
                style = MaterialTheme.typography.labelSmall,
            )
        }
    }
}

// ── 快速阅读视图 ───────────────────────────────
@Composable
fun SpeedReadingView(
    paragraph: String,
    fontSize: Int,
    textColor: Color,
    isPlaying: Boolean,
    currentSentences: List<String> = emptyList(),
    currentSentenceIndex: Int = 0,
) {
    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        if (isPlaying && currentSentences.isNotEmpty()) {
            // VM 的速读链按句驱动（切句/回调/索引都有），视图按句渲染：
            // 已读句变淡、当前句高亮、未读句正常。原实现播放中只显示一个"●"
            Column(modifier = Modifier.fillMaxWidth()) {
                currentSentences.forEachIndexed { idx, sentence ->
                    val isCurrent = idx == currentSentenceIndex
                    val alpha = when {
                        idx < currentSentenceIndex -> 0.45f
                        isCurrent -> 1f
                        else -> 0.6f
                    }
                    Text(
                        text = sentence,
                        color = textColor.copy(alpha = alpha),
                        fontSize = fontSize.sp,
                        fontWeight = if (isCurrent) FontWeight.SemiBold else FontWeight.Normal,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 3.dp)
                            .then(
                                if (isCurrent) {
                                    Modifier
                                        .background(LocalReaderAccent.current.copy(alpha = 0.10f), RoundedCornerShape(4.dp))
                                        .padding(horizontal = 6.dp, vertical = 4.dp)
                                } else Modifier
                            ),
                    )
                }
            }
        } else {
            Text(
                text = paragraph.take(80),
                color = textColor,
                fontSize = fontSize.sp,
                textAlign = TextAlign.Center,
            )
        }
    }
}

// ── 分栏对照阅读视图 ──────────────────────────────
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
    LaunchedEffect(currentIndex) {
        // item 0 是表头，段落索引 +1；目标已可见则不打断用户滚动
        val target = currentIndex + 1
        if (currentIndex in paragraphs.indices &&
            listState.layoutInfo.visibleItemsInfo.none { it.index == target }
        ) {
            listState.animateScrollToItem(target)
        }
    }
    LaunchedEffect(listState) {
        snapshotFlow { listState.firstVisibleItemIndex }
            .collect { idx -> onVisibleParagraphChanged((idx - 1).coerceAtLeast(0)) }
    }
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
                if (isTranslating) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(14.dp),
                            strokeWidth = 2.dp,
                            color = LocalReaderAccent.current,
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            "正在获取译文...",
                            style = MaterialTheme.typography.bodySmall,
                            color = textColor.copy(alpha = 0.5f),
                        )
                    }
                } else {
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
                                color = LocalReaderAccent.current,
                            )
                        }
                    }
                }
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
@Composable
fun PosAnalysisView(
    paragraphs: List<String>,
    currentIndex: Int,
    fontSize: Int,
    textColor: Color,
    onWordClick: (String) -> Unit,
    onVisibleParagraphChanged: (Int) -> Unit = {},
) {
    // 非词性着色（非单词 token、图例文字）跟随阅读主题色：
    // 原实现硬编码 app 级浅色 onSurface，深色主题下深底深字不可读
    fun posColor(tag: PosTag): Color = when (tag) {
        PosTag.NOUN -> Info      // 青灰 - 名词
        PosTag.VERB -> Error     // 赤褐 - 动词
        PosTag.ADJECTIVE -> Warning // 暖金 - 形容词
        PosTag.ADVERB -> Primary  // 暖棕 - 副词
        else -> textColor.copy(alpha = 0.85f)
    }

    // LazyColumn 化：整书 eager Column 每次重组都重排版全文；
    // 词性标注串按 (段落, 透明度, 主题色) 缓存，可见窗口外不参与布局
    val listState = rememberLazyListState()
    LaunchedEffect(currentIndex) {
        if (currentIndex in paragraphs.indices &&
            listState.layoutInfo.visibleItemsInfo.none { it.index == currentIndex }
        ) {
            listState.animateScrollToItem(currentIndex)
        }
    }
    // 反向同步（与 NORMAL/SPLIT 同款）：本视图无表头项，段落索引即 item 索引。
    // 缺失时用户在成分分析模式里滑多远，退出后进度/统计都停在旧位置
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
            key = { index, _ -> index },
        ) { index, para ->
            val isCurrent = index == currentIndex
            val alpha = if (isCurrent) 1f else 0.5f

            // 词性着色文本（remember 缓存：原实现在组合里裸建，
            // 任何状态变化都重新切词+分类整本书）
            val annotatedText = remember(para, alpha, textColor) {
                buildAnnotatedString {
                    val allMatches = Regex("([a-zA-Z]+)|([^a-zA-Z]+)").findAll(para).toList()
                    allMatches.forEach { match ->
                        val token = match.value
                        if (Regex("^[a-zA-Z]+$").matches(token)) {
                            val word = token.lowercase()
                            val tag = wordPosMap[word] ?: classifyBySuffix(word)
                            val color = posColor(tag).copy(alpha = alpha)
                            withStyle(SpanStyle(color = color)) { append(token) }
                        } else {
                            withStyle(SpanStyle(color = textColor.copy(alpha = alpha * 0.5f))) {
                                append(token)
                            }
                        }
                    }
                }
            }
            TappableParagraphText(
                text = annotatedText,
                paragraph = para,
                onWordClick = onWordClick,
                onSentenceDoubleTap = {},
                modifier = Modifier.padding(vertical = 6.dp, horizontal = 4.dp),
                style = readerParagraphStyle(fontSize),
            )

            if (index < paragraphs.lastIndex) {
                Divider(
                    modifier = Modifier.padding(vertical = 8.dp),
                    color = textColor.copy(alpha = 0.15f),
                )
            }
        }

        // 底部图例
        item {
            Spacer(modifier = Modifier.height(16.dp))
            Divider()
            Spacer(modifier = Modifier.height(8.dp))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                PosLegendItem("青灰", Info, "名词")
                PosLegendItem("赤褐", Error, "动词")
                PosLegendItem("暖金", Warning, "形容词")
                PosLegendItem("暖棕", Primary, "副词")
            }
        }
    }
}

@Composable
private fun PosLegendItem(colorName: String, color: Color, tag: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Surface(
            modifier = Modifier.size(12.dp),
            shape = CircleShape,
            color = color,
        ) {}
        Spacer(modifier = Modifier.width(4.dp))
        Text("$tag", style = MaterialTheme.typography.labelSmall)
    }
}

// 简单规则后缀分类（供 composable 使用）
private val wordPosMap = mapOf(
    "the" to PosTag.DETERMINER, "a" to PosTag.DETERMINER, "an" to PosTag.DETERMINER,
    "is" to PosTag.VERB, "am" to PosTag.VERB, "are" to PosTag.VERB,
    "was" to PosTag.VERB, "were" to PosTag.VERB, "be" to PosTag.VERB,
    "been" to PosTag.VERB, "being" to PosTag.VERB,
    "do" to PosTag.VERB, "does" to PosTag.VERB, "did" to PosTag.VERB,
    "have" to PosTag.VERB, "has" to PosTag.VERB, "had" to PosTag.VERB,
    "will" to PosTag.VERB, "would" to PosTag.VERB,
    "can" to PosTag.VERB, "could" to PosTag.VERB, "should" to PosTag.VERB,
    "and" to PosTag.CONJUNCTION, "but" to PosTag.CONJUNCTION, "or" to PosTag.CONJUNCTION,
    "in" to PosTag.PREPOSITION, "on" to PosTag.PREPOSITION, "at" to PosTag.PREPOSITION,
    "by" to PosTag.PREPOSITION, "for" to PosTag.PREPOSITION, "with" to PosTag.PREPOSITION,
    "to" to PosTag.PREPOSITION, "of" to PosTag.PREPOSITION, "from" to PosTag.PREPOSITION,
    "not" to PosTag.ADVERB, "no" to PosTag.ADVERB, "very" to PosTag.ADVERB,
    "also" to PosTag.ADVERB, "just" to PosTag.ADVERB, "only" to PosTag.ADVERB,
    "i" to PosTag.PRONOUN, "you" to PosTag.PRONOUN, "he" to PosTag.PRONOUN,
    "she" to PosTag.PRONOUN, "it" to PosTag.PRONOUN, "we" to PosTag.PRONOUN,
    "they" to PosTag.PRONOUN, "this" to PosTag.PRONOUN, "that" to PosTag.PRONOUN,
    "my" to PosTag.PRONOUN, "your" to PosTag.PRONOUN, "his" to PosTag.PRONOUN,
    "our" to PosTag.PRONOUN, "their" to PosTag.PRONOUN,
)

private fun classifyBySuffix(word: String): PosTag {
    val suffixes = listOf(
        "tion" to PosTag.NOUN, "sion" to PosTag.NOUN, "ment" to PosTag.NOUN,
        "ness" to PosTag.NOUN, "ity" to PosTag.NOUN, "ance" to PosTag.NOUN,
        "ence" to PosTag.NOUN, "er" to PosTag.NOUN, "or" to PosTag.NOUN, "ist" to PosTag.NOUN,
        "ing" to PosTag.VERB, "ed" to PosTag.VERB, "ify" to PosTag.VERB,
        "ful" to PosTag.ADJECTIVE, "less" to PosTag.ADJECTIVE,
        "ous" to PosTag.ADJECTIVE, "ive" to PosTag.ADJECTIVE,
        "able" to PosTag.ADJECTIVE, "ible" to PosTag.ADJECTIVE,
        "al" to PosTag.ADJECTIVE, "ical" to PosTag.ADJECTIVE,
        "ly" to PosTag.ADVERB,
    )
    for ((suffix, tag) in suffixes) {
        if (word.length > suffix.length + 2 && word.endsWith(suffix)) return tag
    }
    return PosTag.NOUN
}
