package com.eareyereading.ui.screens.reader

import androidx.compose.animation.*
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.eareyereading.ui.theme.*
import com.eareyereading.util.BookImages
import com.eareyereading.util.CollinsClassifier
import com.eareyereading.util.CollinsClassifier.WordLevel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 左右翻页阅读视图（仿书页 HorizontalPager）：分页算法、跨页切片渲染与朗读句高亮。
 */
// ── 左右翻页阅读视图（仿书页） ────────────────────

/**
 * 分页切片：一个段落可按 StaticLayout 的行边界拆成多个切片跨页渲染，
 * "放不下的行"自然流到下一页（真书式排版，替代旧的整段独占页 + 页内滚动）。
 */
private data class PageSlice(
    val paraIndex: Int,
    val charStart: Int,
    val charEnd: Int,
    val isFirstOfPara: Boolean,
    val isLastOfPara: Boolean,
)

/**
 * 仿书页横向翻页阅读：HorizontalPager 逐页渲染。
 *
 * 分页排版（[paginateBook]）：后台线程用 StaticLayout 按"行"精确测量整书，
 * 段落可跨页按行拆分——放不下的内容自动流到下一页（而非旧行为的
 * 整段塞进一页 + 页内滚动兜底）；只有估算与渲染的极小偏差才落入
 * 页内 verticalScroll 兜底。段落渲染与滚动视图共用 [ReaderParagraphBlock]
 * ——词色/生词高亮/用户高亮/译文/朗读同步完全一致；被拆分的段用
 * [ReaderSliceParagraphBlock]（同款渲染，offset 平移）。
 *
 * 同步语义（与滚动视图对齐）：
 *  - 翻页 settle 后把该页首切片的段落回报 VM（底栏滑杆/进度/统计跟上）；
 *  - 程序推进（朗读/滑杆/章节跳转）时翻到目标段首个切片所在页；
 *  - 相邻页动画翻页，跨页跳转（如续读恢复）瞬时定位。
 */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun PagedReadingView(
    paragraphs: List<String>,
    currentIndex: Int,
    fontSize: Int,
    textColor: Color,
    showTranslation: Boolean,
    paragraphTranslations: Map<Int, String>,
    translationAlpha: Float,
    showWordLevelColors: Boolean,
    showKnownWordsHighlight: Boolean,
    knownWords: Set<String>,
    learnedWords: Set<String>,
    isAutoReading: Boolean = false,
    currentSentences: List<String> = emptyList(),
    currentSentenceIndex: Int = 0,
    onWordClick: (String) -> Unit,
    onSentenceDoubleTap: (String) -> Unit,
    onVisibleParagraphChanged: (Int) -> Unit = {},
    bookmarkedParagraphs: Set<Int> = emptySet(),
    highlights: Map<Int, List<HighlightData>> = emptyMap(),
    // 仿电子书装饰：页眉书名 + 页脚页码；中键点击回调（左右边缘被翻页区占用）
    bookTitle: String = "",
    onCenterTap: () -> Unit = {},
    // VM 注入的 CollinsClassifier 单例（与滚动视图共用，见 NormalReadingView 注释）
    classifier: CollinsClassifier,
    // 插图渲染用：[[IMG:n]] 标记解析到本书的落盘图片目录
    bookId: Long = 0L,
) {
    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val density = LocalDensity.current
        val contentWidthPx = with(density) { maxWidth.roundToPx() }
        // 页高预算：视口高 - 上下 8dp 留白 - 仿电子书页眉页脚预留
        // （书名 running header ~18sp + 页码 footer ~16sp + 上下间距）
        val pageBudgetPx = with(density) { (maxHeight - 16.dp).roundToPx() } -
            with(density) { 52.dp.roundToPx() }
        val serif = LocalReaderFontFamily.current != FontFamily.Default
        // 各项 px 尺寸（密度/字号变化时 produceState 的 key 一起变）
        val fontSizePx = with(density) { fontSize.sp.toPx() }
        val transFontSizePx = with(density) { (fontSize - 2).sp.toPx() }
        val paragraphPadPx = with(density) { 6.dp.toPx() }      // 段首/段尾切片的纵向 padding
        val bookmarkRowPx = with(density) { 25.dp.toPx() }      // 书签标记行
        val transBlockPadPx = with(density) { 20.dp.toPx() }    // 译文 4 + 2*2 + 12
        // 插图段固定估高：720px 解码宽 × 常见屏宽 → 约 200dp 显示高 + 边距，
        // 独占一页过浪费，给中等预算让图文同页
        val imageBlockPx = with(density) { 220.dp.toPx() }

        // 分页排版：整书 StaticLayout 按行测量 + 贪心装箱（行粒度）。
        // 放 Default 调度器：大书几百段的测量同步做在组合期会顶掉帧
        // （字号滑杆逐像素回调时尤甚），后台算完一次性替换，期间保留旧分页不闪空
        val pages by produceState(
            initialValue = emptyList<List<PageSlice>>(),
            paragraphs, fontSizePx, transFontSizePx, showTranslation,
            paragraphTranslations, contentWidthPx, pageBudgetPx, serif,
            bookmarkedParagraphs,
        ) {
            value = withContext(Dispatchers.Default) {
                paginateBook(
                    paragraphs = paragraphs,
                    contentWidthPx = contentWidthPx,
                    pageBudgetPx = pageBudgetPx.toFloat(),
                    fontSizePx = fontSizePx,
                    transFontSizePx = transFontSizePx,
                    serif = serif,
                    showTranslation = showTranslation,
                    translations = paragraphTranslations,
                    bookmarked = bookmarkedParagraphs,
                    imageBlockPx = imageBlockPx,
                    bookmarkRowPx = bookmarkRowPx,
                    paragraphPadPx = paragraphPadPx,
                    transBlockPadPx = transBlockPadPx,
                )
            }
        }

        val pagerState = rememberPagerState(pageCount = { pages.size })

        // 翻页回报：页 settle 后把该页首切片的段落回报 VM
        // （底栏滑杆/进度/阅读统计跟上视口，播放中由播放循环主导，VM 侧会忽略）
        LaunchedEffect(pagerState, pages) {
            snapshotFlow { pagerState.currentPage }
                .collect { page ->
                    pages.getOrNull(page)?.firstOrNull()?.let { onVisibleParagraphChanged(it.paraIndex) }
                }
        }
        // 程序推进跟随：朗读/滑杆/章节跳转把 currentIndex 推走时翻到
        // 目标段首个切片所在页。远距离（续读恢复/跳章）瞬时定位，相邻页动画翻页
        LaunchedEffect(currentIndex, pages) {
            if (pages.isEmpty()) return@LaunchedEffect
            val target = pages.indexOfFirst { page -> page.any { it.paraIndex == currentIndex } }
            if (target >= 0 && target != pagerState.currentPage && !pagerState.isScrollInProgress) {
                if (kotlin.math.abs(target - pagerState.currentPage) > 1) {
                    pagerState.scrollToPage(target)
                } else {
                    pagerState.animateScrollToPage(target)
                }
            }
        }

        if (pages.isEmpty()) {
            // 分页排版计算中（后台整书测量，通常 <100ms）：保持空白防跳变
            Box(modifier = Modifier.fillMaxSize())
        } else {
            // 仿电子书点击翻页协程作用域
            val pageFlipScope = rememberCoroutineScope()
            HorizontalPager(
                state = pagerState,
                modifier = Modifier
                    .fillMaxSize()
                    // 仿电子书点击翻页区：左 30% 上一页、右 30% 下一页、
                    // 中间 40% 切换顶底栏显隐（Kindle 式热区）。段落文字上的
                    // 点词/双击翻译由内层手势先消费，只有空白处的点击落到这里
                    .pointerInput(pages.size) {
                        detectTapGestures { offset ->
                            val edge = size.width * 0.3f
                            val cur = pagerState.currentPage
                            when {
                                offset.x < edge && cur > 0 -> pageFlipScope.launch {
                                    pagerState.animateScrollToPage(cur - 1)
                                }
                                offset.x > size.width - edge && cur < pages.size - 1 ->
                                    pageFlipScope.launch {
                                        pagerState.animateScrollToPage(cur + 1)
                                    }
                                else -> onCenterTap()
                            }
                        }
                    },
            ) { page ->
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(vertical = 8.dp),
                ) {
                    // 仿电子书页眉：书名 running header（纸书式页顶书名）
                    if (bookTitle.isNotBlank()) {
                        Text(
                            text = bookTitle,
                            style = MaterialTheme.typography.labelSmall,
                            color = textColor.copy(alpha = 0.45f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            textAlign = TextAlign.Center,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(bottom = 2.dp),
                        )
                    }
                    // verticalScroll 兜底：行高估算与 Compose 实际渲染的
                    // 极小偏差导致的内容溢出仍可滚动查看
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth()
                            .verticalScroll(rememberScrollState()),
                    ) {
                        pages[page].forEach { slice ->
                            val para = paragraphs[slice.paraIndex]
                            when {
                                // 插图段：整块渲染为图片（与滚动视图一致，不参与分片）
                                BookImages.isImageMarker(para) -> ReaderImageBlock(
                                    ref = BookImages.markerRef(para).orEmpty(),
                                    bookId = bookId,
                                )
                                // 整段都在本页：走与滚动视图完全一致的段落渲染
                                slice.charStart == 0 && slice.charEnd >= para.length ->
                                    ReaderParagraphBlock(
                                        para = para,
                                        isCurrent = slice.paraIndex == currentIndex,
                                        isBookmarked = slice.paraIndex in bookmarkedParagraphs,
                                        paraHighlights = highlights[slice.paraIndex] ?: emptyList(),
                                        alpha = if (slice.paraIndex == currentIndex) 1f
                                        else if (slice.paraIndex < currentIndex) 0.4f else 0.7f,
                                        fontSize = fontSize,
                                        textColor = textColor,
                                        showTranslation = showTranslation,
                                        translation = paragraphTranslations[slice.paraIndex],
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
                                // 跨页切片：行级拆分渲染
                                else -> ReaderSliceParagraphBlock(
                                    para = para,
                                    charStart = slice.charStart,
                                    charEnd = slice.charEnd,
                                    isCurrent = slice.paraIndex == currentIndex,
                                    isAutoReading = isAutoReading,
                                    currentSentences = currentSentences,
                                    currentSentenceIndex = currentSentenceIndex,
                                    alpha = if (slice.paraIndex == currentIndex) 1f
                                    else if (slice.paraIndex < currentIndex) 0.4f else 0.7f,
                                    fontSize = fontSize,
                                    textColor = textColor,
                                    showTranslation = showTranslation,
                                    translation = paragraphTranslations[slice.paraIndex],
                                    translationAlpha = translationAlpha,
                                    showWordLevelColors = showWordLevelColors,
                                    showKnownWordsHighlight = showKnownWordsHighlight,
                                    knownWords = knownWords,
                                    learnedWords = learnedWords,
                                    onWordClick = onWordClick,
                                    onSentenceDoubleTap = onSentenceDoubleTap,
                                    classifier = classifier,
                                    sliceHighlights = highlights[slice.paraIndex] ?: emptyList(),
                                    showBookmarkMark = slice.isFirstOfPara &&
                                        slice.paraIndex in bookmarkedParagraphs,
                                )
                            }
                        }
                    }
                    // 仿电子书页脚：页码 + 全书进度百分比
                    Text(
                        text = "${page + 1} / ${pages.size} · ${(page + 1) * 100 / pages.size}%",
                        style = MaterialTheme.typography.labelSmall,
                        color = textColor.copy(alpha = 0.45f),
                        textAlign = TextAlign.Center,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 2.dp),
                    )
                }
            }
        }
    }
}

/**
 * 整书分页：StaticLayout 按行测量（与 Compose 渲染同一文本、同一宽度、
 * 同一字体），行高按 readerParagraphStyle 的倍数 + 2% 安全余量估算，
 * 贪心装箱到每页预算内。段落可跨页拆分（行粒度），书签行/译文高度
 * 计入所属切片（书签在段首切片、译文在段尾切片）。
 *
 * 必须在后台线程调用（数百次 StaticLayout 构建耗时几十毫秒）。
 */
private fun paginateBook(
    paragraphs: List<String>,
    contentWidthPx: Int,
    pageBudgetPx: Float,
    fontSizePx: Float,
    transFontSizePx: Float,
    serif: Boolean,
    showTranslation: Boolean,
    translations: Map<Int, String>,
    bookmarked: Set<Int>,
    imageBlockPx: Float,
    bookmarkRowPx: Float,
    paragraphPadPx: Float,
    transBlockPadPx: Float,
): List<List<PageSlice>> {
    val typeface = if (serif) android.graphics.Typeface.SERIF else android.graphics.Typeface.DEFAULT
    val bodyPaint = android.text.TextPaint().apply {
        isAntiAlias = true
        this.textSize = fontSizePx
        this.typeface = typeface
    }
    val transPaint = android.text.TextPaint().apply {
        isAntiAlias = true
        textSize = transFontSizePx
        this.typeface = typeface
    }
    // 行高对齐 readerParagraphStyle：正文 1.8 倍、译文 1.5 倍；
    // 2% 余量宁可页尾略空，也不让渲染高度反超估算
    val bodyLineH = fontSizePx * 1.8f * 1.02f
    val transLineH = transFontSizePx * 1.5f * 1.02f

    fun lineBounds(text: String, paint: android.text.TextPaint): List<IntArray> {
        if (text.isEmpty()) return emptyList()
        val layout = android.text.StaticLayout.Builder
            .obtain(text, 0, text.length, paint, contentWidthPx)
            .setAlignment(android.text.Layout.Alignment.ALIGN_NORMAL)
            .setLineSpacing(0f, 1f)
            .setIncludePad(false)
            .build()
        return (0 until layout.lineCount).map { i ->
            intArrayOf(layout.getLineStart(i), layout.getLineEnd(i))
        }
    }

    fun translationHeight(idx: Int): Float {
        val t = if (showTranslation) translations[idx] else null
        if (t.isNullOrBlank()) return 0f
        val lines = lineBounds(t, transPaint).size.coerceAtLeast(1)
        return lines * transLineH + transBlockPadPx
    }

    val pages = mutableListOf<List<PageSlice>>()
    var current = mutableListOf<PageSlice>()
    var used = 0f

    fun closePage() {
        if (current.isNotEmpty()) {
            pages.add(current)
            current = mutableListOf()
            used = 0f
        }
    }

    paragraphs.forEachIndexed { idx, para ->
        // 插图标记段按固定图块高度整块参与装箱（无文本可测）
        if (BookImages.isImageMarker(para)) {
            if (used + imageBlockPx > pageBudgetPx) closePage()
            current.add(PageSlice(idx, 0, para.length, isFirstOfPara = true, isLastOfPara = true))
            used += imageBlockPx
            return@forEachIndexed
        }
        val lines = lineBounds(para, bodyPaint)
        if (lines.isEmpty()) {
            // 空段占位（维持段落节奏）
            if (used + fontSizePx > pageBudgetPx) closePage()
            current.add(PageSlice(idx, 0, 0, isFirstOfPara = true, isLastOfPara = true))
            used += fontSizePx
            return@forEachIndexed
        }
        val transH = translationHeight(idx)
        var i = 0
        var first = true
        while (i < lines.size) {
            val isLastChunk = i + 1 >= lines.size
            // 本切片的附加高度：段首/段尾 padding、书签行（段首切片）、译文（段尾切片）
            val extra = (if (first) paragraphPadPx else 0f) +
                (if (isLastChunk) paragraphPadPx else 0f) +
                (if (first && idx in bookmarked) bookmarkRowPx else 0f) +
                (if (isLastChunk) transH else 0f)
            var fit = (((pageBudgetPx - used) - extra).coerceAtLeast(0f) / bodyLineH).toInt()
            if (fit <= 0) {
                // 当前页连一行都放不下：换页重算；仍放不下（附加块超高）则
                // 单行兜底——该页 verticalScroll 可滚动查看
                if (current.isNotEmpty()) {
                    closePage()
                    fit = (((pageBudgetPx - used) - extra).coerceAtLeast(0f) / bodyLineH).toInt()
                }
                if (fit <= 0) fit = 1
            }
            val take = minOf(fit, lines.size - i)
            current.add(
                PageSlice(
                    paraIndex = idx,
                    charStart = lines[i][0],
                    charEnd = lines[i + take - 1][1],
                    isFirstOfPara = first,
                    isLastOfPara = i + take >= lines.size,
                ),
            )
            used += extra + take * bodyLineH
            i += take
            first = false
        }
    }
    closePage()
    return pages
}

/**
 * 跨页段落切片渲染：渲染 [para] 的 [charStart, charEnd) 行片段。
 * 词色/生词高亮/用户高亮（offset 平移到切片坐标系）/译文（段尾切片）/
 * 朗读句级同步（句子与切片求交，跨页句子在两页各显示各自片段）——
 * 与 [ReaderParagraphBlock] 同一套逻辑。
 */
@Composable
private fun ReaderSliceParagraphBlock(
    para: String,
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
        if (showBookmarkMark) {
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

        // 朗读句级同步：句子范围与切片求交，逐句分档透明度渲染
        val sentenceRanges = remember(para, currentSentences) {
            var from = 0
            val ranges = mutableListOf<IntArray>()
            for (s in currentSentences) {
                val i = para.indexOf(s, from)
                if (i < 0) return@remember null
                ranges.add(intArrayOf(i, i + s.length))
                from = i + s.length
            }
            ranges
        }

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
                modifier = Modifier.alpha(1f),
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
                modifier = Modifier
                    .padding(
                        top = if (isFirst) 6.dp else 0.dp,
                        bottom = if (isLast) 6.dp else 0.dp,
                    )
                    .alpha(alpha),
                style = readerParagraphStyle(fontSize),
            )
        }

        // 译文跟随段尾切片（与整段渲染一致）
        if (isLast && showTranslation && !translation.isNullOrBlank()) {
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
            Spacer(modifier = Modifier.height(12.dp))
        }
    }
}

/**
 * 朗读中的切片文本：句子范围 ∩ 切片范围的分段 AnnotatedString。
 * 每段按句子档位（已读 0.45 / 当前 1f / 未读 0.6）上色，当前句带
 * 强调色底；词频着色开启时在句子档位之上再叠词色（与
 * [AutoReadingSentenceText] 同一套规则）。
 */
private fun buildAutoReadingSliceAnnotated(
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
            if (showWordLevelColors) {
                WordSplitRegex.findAll(fragment).forEach { match ->
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
            } else {
                withStyle(SpanStyle(color = textColor.copy(alpha = sAlpha))) { append(fragment) }
            }
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
