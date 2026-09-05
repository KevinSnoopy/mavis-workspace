package com.eareyereading.ui.screens.reader

import androidx.compose.animation.*
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import coil.compose.AsyncImagePainter
import coil.request.ImageRequest
import com.eareyereading.ui.theme.*
import com.eareyereading.util.BookImages
import com.eareyereading.util.CollinsClassifier
import com.eareyereading.util.CollinsClassifier.WordLevel

/**
 * 阅读器共享渲染基建：字体/强调色 CompositionLocal、段落样式、图文块渲染、
 * 点按命中解析与 AnnotatedString 构建。被其余阅读视图文件复用。
 */
/**
 * 阅读器正文字体（衬线切换）：ReaderScreen 顶层 provide，
 * 所有阅读模式视图经 [readerParagraphStyle] 消费，一处切换全局生效。
 */
internal val LocalReaderFontFamily = androidx.compose.runtime.staticCompositionLocalOf {
    FontFamily.Default
}

/**
 * 阅读器正文强调色（译文/高亮底/模式标签）：随（书内主题 + 系统深色）变化——
 * 深色下用更亮的赤陶 Accent，浅色/护眼用暖棕 Primary。
 * 深层视图（ReaderParagraphBlock/SplitReadingView 等）经
 * `LocalReaderAccent.current` 消费，免逐层透传参数。
 */
internal val LocalReaderAccent = androidx.compose.runtime.staticCompositionLocalOf { Primary }

/**
 * 段落正文样式统一入口：字号 + 行高（倍数）+ 可选衬线。
 * 保证普通/分栏/回译/成分分析等渲染视图的字形一致切换。
 */
@Composable
internal fun readerParagraphStyle(fontSize: Int, lineMultiplier: Float = 1.8f): TextStyle = TextStyle(
    fontSize = fontSize.sp,
    lineHeight = (fontSize * lineMultiplier).sp,
    fontFamily = LocalReaderFontFamily.current,
)

/**
 * 段落插图渲染：`[[IMG:n]]`（EPUB 落盘降采样 JPEG）/ `[[IMG:url]]`（文章/RSS 在线图）。
 *
 * 性能（用户确认"可以展示模糊一些"）：
 *  - Coil ImageRequest 固定 size(720)——按需解码 720px 宽的缩略位图，
 *    原图尺寸再大也不在阅读滚动路径上进出内存；
 *  - EPUB 图导入期已重编码为小 JPEG，文章图按 720 解码，memoryCacheKey 稳定
 *    派生，Coil 磁盘缓存默认开启——单图解码内存 ~≤2MB，翻页/滚动时缓存直接命中。
 *
 * 版式（issue：真实阅读源图片加载时文字错位）：
 *  - 加载中先占位（浅底 + minHeight），段落高度不再从 0 突变到图片高度，
 *    后文不会先"上移占位"再被图片顶下去；
 *  - 失败显示紧凑占位条（图片加载失败），保留段落节奏，后文不塌陷。
 */
@Composable
internal fun ReaderImageBlock(
    ref: String,
    bookId: Long,
    modifier: Modifier = Modifier,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    // 旧导入数据可能残留 JS 占位 src（resolve 后变成 …/undefined），这些
    // URL 拉回 HTML 错误页 → BitmapFactory null → "图片加载失败"。
    // 渲染期兜底跳过，不渲染图片也不渲染标记文本。
    if (!BookImages.isLoadableImageRef(ref)) return
    val model = remember(ref, bookId) {
        ref.toIntOrNull()
            ?.let { BookImages.localImageFile(context, bookId, it) }
            ?: ref
    }
    // null = 首帧还没回调（视为加载中）：占位先顶住段落高度
    var imageState by remember(ref, bookId) {
        mutableStateOf<AsyncImagePainter.State?>(null)
    }
    val failed = imageState is AsyncImagePainter.State.Error
    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        // 加载中/失败占位：Success 前 minHeight 先占住版式高度，后文不被
        // 突然弹出的图片顶下去（错位感）；失败保留紧凑提示条不塌陷
        if (imageState !is AsyncImagePainter.State.Success) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = if (failed) 48.dp else 160.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(
                        MaterialTheme.colorScheme.onSurface.copy(
                            alpha = if (failed) 0.06f else 0.10f,
                        ),
                    ),
                contentAlignment = Alignment.Center,
            ) {
                if (failed) {
                    Text(
                        text = "图片加载失败",
                        style = MaterialTheme.typography.labelMedium,
                        color = LocalContentColor.current.copy(alpha = 0.55f),
                        modifier = Modifier.padding(vertical = 8.dp),
                    )
                }
            }
        }
        AsyncImage(
            model = ImageRequest.Builder(context)
                .data(model)
                .size(720)
                // MIUI/HyperOS HWUI image decoder 原生 AImageDecoder_Create 返回
                // "unimplemented"，硬件位图（Bitmap.Config.HARDWARE）虽解码成功但
                // RenderThread 无法上传 GPU 纹理 → 空白/报错。allowHardware(false)
                // 强制软件位图（ARGB_8888），绕开该原生路径。
                .allowHardware(false)
                .memoryCacheKey("reader_img_${bookId}_${ref.takeLast(64)}")
                .crossfade(180)
                .build(),
            contentDescription = "插图",
            contentScale = ContentScale.Fit,
            onState = { state ->
                if (state is AsyncImagePainter.State.Error) {
                    android.util.Log.e(
                        "ReaderImage",
                        "image load failed: ref=$ref model=$model",
                        state.result.throwable,
                    )
                }
                imageState = state
            },
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(8.dp)),
        )
    }
}

/**
 * 单个段落的完整渲染块：书签标记行 + 正文（朗读句子级同步 / Collins 词色 /
 * 生词高亮 / 用户高亮四分支）+ 译文。从滚动视图的 LazyColumn item 抽出，
 * 供滚动（NormalReadingView）与左右翻页（PagedReadingView）两视图共用，
 * 保证两种阅读方式的段落渲染完全一致。
 * 插图标记段（[[IMG:n]]）直接渲染为图片，不走文本分支。
 */
@Composable
internal fun ReaderParagraphBlock(
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
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .then(
                if (isCurrent && isAutoReading) {
                    Modifier
                        .background(LocalReaderAccent.current.copy(alpha = 0.06f), RoundedCornerShape(8.dp))
                        .padding(horizontal = 6.dp, vertical = 2.dp)
                } else {
                    Modifier
                }
            ),
    ) {
        // 书签段落标记行
        if (isBookmarked) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp),
                horizontalArrangement = Arrangement.Start,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    Icons.Default.Bookmark,
                    "已书签",
                    modifier = Modifier.size(16.dp),
                    tint = Secondary,
                )
                Spacer(modifier = Modifier.width(4.dp))
                Divider(
                    modifier = Modifier.weight(1f),
                    thickness = 1.dp,
                    color = Secondary.copy(alpha = 0.3f),
                )
            }
        }

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
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = translation,
                modifier = Modifier
                    .padding(vertical = 2.dp)
                    .alpha(alpha),
                style = readerParagraphStyle(fontSize - 2, 1.5f).copy(
                    color = LocalReaderAccent.current.copy(alpha = translationAlpha),
                ),
            )
            // 只有实际有译文才留间距：原实现把 Spacer 放在判空之外，
            // 未翻译段落也多出一截空白，节奏不齐
            Spacer(modifier = Modifier.height(12.dp))
        }
    }
}

/**
 * 朗读中的单个句子渲染：词级配色 AnnotatedString 按
 * （句子文本, 档位透明度, 配色开关）缓存——句索引推进时只有档位
 * 变化的两句重建，其余命中 remember。
 */
@Composable
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
    val sentenceText = if (showWordLevelColors) {
        remember(sentence, sAlpha, textColor, classifier) {
            buildAnnotatedString {
                val words = WordSplitRegex.findAll(sentence)
                words.forEach { match ->
                    val word = match.value
                    if (PureWordRegex.matches(word)) {
                        val level = classifier.classify(word)
                        val color = when (level) {
                            WordLevel.CORE -> WordLevelCore
                            WordLevel.INTERMEDIATE -> WordLevelIntmd
                            WordLevel.UPPER_INTERMEDIATE -> WordLevelUpper
                            WordLevel.ADVANCED -> WordLevelAdv
                            WordLevel.RARE -> WordLevelRare
                            WordLevel.UNKNOWN -> textColor.copy(alpha = 0.5f)
                        }
                        withStyle(SpanStyle(color = color.copy(alpha = sAlpha))) { append(word) }
                    } else {
                        withStyle(SpanStyle(color = textColor.copy(alpha = sAlpha * 0.6f))) { append(word) }
                    }
                }
            }
        }
    } else {
        remember(sentence, sAlpha, textColor) {
            buildAnnotatedString {
                withStyle(SpanStyle(color = textColor.copy(alpha = sAlpha))) { append(sentence) }
            }
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

/**
 * 段落/切片通用的词色 AnnotatedString 构建器：
 *  - 词频着色开 → Collins 词色（已认识词优先绿色）+ 用户高亮底色叠加；
 *  - 仅生词高亮 → 已认识/已学词着色 + 高亮叠加；
 *  - 都关 → 纯文本 + 用户高亮。
 * ReaderParagraphBlock 与 ReaderSliceParagraphBlock 共用，保证滚屏/翻页同款渲染。
 */
internal fun buildReaderAnnotated(
    text: String,
    textColor: Color,
    showWordLevelColors: Boolean,
    showKnownWordsHighlight: Boolean,
    knownWords: Set<String>,
    learnedWords: Set<String>,
    highlights: List<HighlightData>,
    classifier: CollinsClassifier,
): AnnotatedString = buildAnnotatedString {
    if (showWordLevelColors) {
        WordSplitRegex.findAll(text).forEach { match ->
            val word = match.value
            if (PureWordRegex.matches(word)) {
                val level = classifier.classify(word)
                val lower = word.lowercase()
                // 生词本优先：已认识的词用绿色
                val color = when {
                    showKnownWordsHighlight && lower in knownWords -> Success
                    else -> when (level) {
                        WordLevel.CORE -> WordLevelCore
                        WordLevel.INTERMEDIATE -> WordLevelIntmd
                        WordLevel.UPPER_INTERMEDIATE -> WordLevelUpper
                        WordLevel.ADVANCED -> WordLevelAdv
                        WordLevel.RARE -> WordLevelRare
                        WordLevel.UNKNOWN -> textColor.copy(alpha = 0.5f)
                    }
                }
                withStyle(SpanStyle(color = color)) { append(word) }
            } else {
                withStyle(SpanStyle(color = textColor.copy(alpha = 0.6f))) { append(word) }
            }
        }
        // 词色之上叠加用户高亮背景
        highlights.forEach { h ->
            val s = h.startOffset.coerceIn(0, text.length)
            val e = h.endOffset.coerceIn(s, text.length)
            if (e > s) addStyle(SpanStyle(background = h.color.copy(alpha = 0.25f)), s, e)
        }
    } else if (showKnownWordsHighlight && knownWords.isNotEmpty()) {
        WordSplitRegex.findAll(text).forEach { match ->
            val word = match.value
            if (PureWordRegex.matches(word)) {
                val lower = word.lowercase()
                val color = when {
                    lower in knownWords -> Success
                    lower in learnedWords -> KnownWord
                    else -> textColor
                }
                withStyle(SpanStyle(color = color)) { append(word) }
            } else {
                withStyle(SpanStyle(color = textColor.copy(alpha = 0.6f))) { append(word) }
            }
        }
        highlights.forEach { h ->
            val s = h.startOffset.coerceIn(0, text.length)
            val e = h.endOffset.coerceIn(s, text.length)
            if (e > s) addStyle(SpanStyle(background = h.color.copy(alpha = 0.25f)), s, e)
        }
    } else {
        // 纯文本 + 高亮渲染：按 offset 顺序处理高亮区域；重叠高亮不重复
        // 输出重叠段，负值/反向/越界脏数据收敛后跳过
        var cursor = 0
        val sortedHighlights = highlights.sortedBy { it.startOffset }
        for (highlight in sortedHighlights) {
            val start = highlight.startOffset.coerceAtLeast(cursor)
            val end = highlight.endOffset.coerceIn(start, text.length)
            if (end <= start) continue
            if (cursor < start) {
                withStyle(SpanStyle(color = textColor.copy(alpha = 0.8f))) {
                    append(text.substring(cursor, start))
                }
            }
            withStyle(SpanStyle(
                background = highlight.color.copy(alpha = 0.25f),
                color = highlight.color,
            )) {
                append(text.substring(start, end))
            }
            cursor = end
        }
        if (cursor < text.length) {
            withStyle(SpanStyle(color = textColor.copy(alpha = 0.8f))) {
                append(text.substring(cursor))
            }
        }
    }
}

// ── 段落点击辅助：把点击位置解析为单词 / 句子 ──────────────
private val WordRegex = Regex("[a-zA-Z]+")
// 词色分词与纯词判定：原本散落在各渲染分支的组合期内反复编译，
// 提为文件级常量后每次调用复用同一 Pattern
internal val WordSplitRegex = Regex("([a-zA-Z]+)|([^a-zA-Z]+)")
internal val PureWordRegex = Regex("^[a-zA-Z]+$")
// issue 8.6：同时认 ASCII 与 CJK 句末标点——旧实现只认 [.!?]，
// 双击中文/日文句子只会截到第一个英文句号，整句后半段丢失
private val SentenceEndRegex = Regex("[.!?。！？；]")

/**
 * 根据 TextLayoutResult 把点击位置反查成段落中命中位置的单词。
 * 若点击位置落在标点 / 空白，返回 null。
 */
private fun findWordAtOffset(paragraph: String, offset: Offset, layout: TextLayoutResult): String? {
    val charIndex = layout.getOffsetForPosition(offset)
    return WordRegex.findAll(paragraph).find { it.range.contains(charIndex) }?.value
}

/**
 * 根据 TextLayoutResult 把点击位置反查成包含该位置的句子。
 * 若无句子边界，返回整段。
 */
private fun findSentenceAtOffset(paragraph: String, offset: Offset, layout: TextLayoutResult): String {
    val charIndex = layout.getOffsetForPosition(offset)
    return findSentenceAtGlobalOffset(paragraph, charIndex)
}

/**
 * 按字符 offset 在段落里找包含该位置的完整句子（跨页切片双击翻译用：
 * 句子可能被分页切开，这里始终在完整段落文本上定位整句，避免拿到残句）。
 */
internal fun findSentenceAtGlobalOffset(paragraph: String, charOffset: Int): String {
    val matches = SentenceEndRegex.findAll(paragraph).toList()
    val start = matches.lastOrNull { it.range.first < charOffset }?.range?.last?.plus(1) ?: 0
    val end = matches.firstOrNull { charOffset < it.range.first }?.range?.first?.plus(1)
        ?: paragraph.length
    return paragraph.substring(start, end).trim()
}

/**
 * 支持"点击单词查释义 / 双击句子翻译"的段落 Text。
 * 使用 TextLayoutResult 反查命中位置，避免把整段当成一个单词。
 */
@Composable
internal fun TappableParagraphText(
    text: AnnotatedString,
    paragraph: String,
    onWordClick: (String) -> Unit,
    onSentenceDoubleTap: (String) -> Unit,
    modifier: Modifier = Modifier,
    style: TextStyle = TextStyle(),
    // 跨页切片用：双击时把切片内局部 offset 换算到完整段落坐标系找整句
    // （句子可能被分页切开，直接在切片文本上找只会得到残句）
    sentenceLookup: ((localCharOffset: Int) -> String)? = null,
) {
    // issue 3.7：remember(paragraph) 而非 remember{}——LazyColumn 会对滚出又滚回的
    // 可见 item 复用同一组合实例，不带 key 时会在换段后残留上一段的 TextLayoutResult，
    // 点击仍用旧布局反查坐标 → 段滚出再回来点击失效。
    val textLayoutResult = remember(paragraph) { mutableStateOf<TextLayoutResult?>(null) }
    Text(
        text = text,
        modifier = modifier
            .pointerInput(paragraph) {
                detectTapGestures(
                    onTap = { offset ->
                        textLayoutResult.value?.let { layout ->
                            findWordAtOffset(paragraph, offset, layout)?.let { onWordClick(it) }
                        }
                    },
                    onDoubleTap = { offset ->
                        textLayoutResult.value?.let { layout ->
                            val sentence = if (sentenceLookup != null) {
                                sentenceLookup(layout.getOffsetForPosition(offset))
                            } else {
                                findSentenceAtOffset(paragraph, offset, layout)
                            }
                            if (sentence.isNotBlank()) onSentenceDoubleTap(sentence)
                        }
                    },
                )
            },
        style = style,
        onTextLayout = { textLayoutResult.value = it },
    )
}
