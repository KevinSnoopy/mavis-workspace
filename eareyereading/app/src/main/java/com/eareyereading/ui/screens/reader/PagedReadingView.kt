package com.eareyereading.ui.screens.reader

import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.eareyereading.util.BookImages
import com.eareyereading.util.CollinsClassifier
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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
                                        index = slice.paraIndex,
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
