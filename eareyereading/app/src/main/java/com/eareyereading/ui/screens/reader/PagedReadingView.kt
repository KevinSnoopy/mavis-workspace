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
import kotlinx.coroutines.flow.distinctUntilChanged
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
 *  - 相邻页动画翻页，跨页跳转（如续读恢复）瞬时定位；
 *  - 重新分页后按"阅读锚点段落"重新对齐视口：分页输入来自
 *    [com.eareyereading.ui.screens.reader.ReaderUiState.readerTranslations]
 *    这份上屏层译文，视口内（当前页）的段落要等这一页翻完才一起上屏，
 *    所以正在读的这一页不会逐段抽搐；用户翻页时新页直接显示最新译文。
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
    // 当前页覆盖的段落区间上报：翻译上屏据此把这一页整体延后刷新
    onVisibleRangeChanged: (Int, Int) -> Unit = { _, _ -> },
    bookmarkedParagraphs: Set<Int> = emptySet(),
    highlights: Map<Int, List<HighlightData>> = emptyMap(),
    // 长按高亮：与滚动视图同一入口；跨页切片由切片块把局部偏移加回 charStart
    onLongPressWord: (paragraphIndex: Int, startOffset: Int, endOffset: Int) -> Unit = { _, _, _ -> },
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
        // 页高预算：视口高 - [ReaderLayout.PageBudgetReserve]（正文区上下留白 +
        // 页眉页脚 + 半行安全余量）。这里与渲染侧共享同一常量，
        // 让"一页正好一屏"这个不变量只在一处定义
        val pageBudgetPx = with(density) { (maxHeight - ReaderLayout.PageBudgetReserve).roundToPx() }
        val serif = LocalReaderFontFamily.current != FontFamily.Default
        // 各项 px 尺寸（密度/字号变化时 produceState 的 key 一起变）。
        // 全部取自 ReaderLayout——分页记账与渲染逐项对应，不能各写一份
        val fontSizePx = with(density) { fontSize.sp.toPx() }
        val transFontSizePx = with(density) { (fontSize - ReaderLayout.TRANS_FONT_DELTA).sp.toPx() }
        val paragraphPadPx = with(density) { ReaderLayout.ParagraphPadding.toPx() }
        val bookmarkRowPx = with(density) { ReaderLayout.BookmarkRowHeight.toPx() }
        val transBlockPadPx = with(density) { ReaderLayout.TransBlockHeight.toPx() }
        val imageBlockPx = with(density) { ReaderLayout.ImageBlockHeight.toPx() }

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
        // 分页结果快照：下面的 effect 要读 pages，但不能把它当启动 key 的
        // 全部含义——分页一变就重启会让"用户翻页"与"分页重算"混为一谈
        val latestPages by rememberUpdatedState(pages)

        // 阅读锚点：视口应对齐到的段落。用户翻页时实时更新；程序推进
        // （朗读/滑杆/章节跳转）跟随 currentIndex。它把"视口位置"从
        // "页码"改成"内容"，重新分页后同一段落会漂到别的页，
        // 只盯页码就会读到一半被换成别的内容
        var anchorParaIndex by remember { mutableIntStateOf(-1) }

        // 程序化定位的目标页：锚点对齐发起的翻页**不回报**可见段落。
        //
        // 分页的输入（译文上屏 / 字号 / 模式）一变，同一个页码就指向别的内容。
        // 若把对齐后的"页首段"写回 VM，已保存的阅读位置每次都会被改写成更早
        // 的段落——页首段必然 ≤ 锚点段，于是每次重进/每次重排都往回退一页，
        // 这就是"再次进入阅读进度回滚"的直接来源。用户手势翻页不设此标记，
        // 照常回报。
        var alignedPage by remember { mutableIntStateOf(-1) }

        // 翻页回报：页 settle 后把该页首切片段落回报 VM（底栏滑杆/进度/
        // 阅读统计跟上视口，播放中由播放循环主导，VM 侧会忽略）并刷新锚点；
        // 同时上报本页覆盖的段落区间，供翻译上屏决策（见 commitReaderTranslations）
        LaunchedEffect(pagerState) {
            snapshotFlow { pagerState.currentPage }
                .distinctUntilChanged()
                .collect { page ->
                    val slices = latestPages.getOrNull(page)?.takeIf { it.isNotEmpty() }
                        ?: return@collect
                    // 区间上报与阅读位置无关：它描述"这一屏有哪些段落"，
                    // 翻译上屏据此决定攒/放，程序化对齐时同样必须上报
                    onVisibleRangeChanged(slices.first().paraIndex, slices.last().paraIndex)
                    if (alignedPage == page) {
                        // 本次换页是锚点对齐发起的：视口刚回到"当前阅读位置"，
                        // 不是用户翻到了新位置，既不回报也不移动锚点
                        alignedPage = -1
                        return@collect
                    }
                    anchorParaIndex = slices.first().paraIndex
                    onVisibleParagraphChanged(slices.first().paraIndex)
                }
        }
        // 程序推进：currentIndex 被推走时锚点跟随（用户翻页触发的
        // onVisibleParagraphChanged 回写不会带来额外位移，目标页不变）
        LaunchedEffect(currentIndex) {
            if (currentIndex in paragraphs.indices) anchorParaIndex = currentIndex
        }
        // 锚点定位：锚点或分页结果变化时把视口对齐到锚点所在页。
        // 这是唯一的自动翻页入口——旧实现把 pages 当触发条件、
        // 按"目标页 ≠ 当前页"就翻，导致任何一次重新分页（译文上屏、
        // 字号调整、书签增删）都会把用户正在读的页面强行翻走
        LaunchedEffect(pages, anchorParaIndex) {
            // 每次重新评估先作废上一次的待消费标记：对齐中途被打断时
            // 标记不该留给后续的用户翻页去消费
            alignedPage = -1
            if (pages.isEmpty() || anchorParaIndex < 0) return@LaunchedEffect
            if (pagerState.isScrollInProgress) return@LaunchedEffect
            val target = pages.indexOfFirst { page -> page.any { it.paraIndex == anchorParaIndex } }
            if (target < 0 || target == pagerState.currentPage) return@LaunchedEffect
            alignedPage = target
            // 远距离（重排后位置漂移）瞬时定位，相邻页动画翻页
            if (kotlin.math.abs(target - pagerState.currentPage) > 1) {
                pagerState.scrollToPage(target)
            } else {
                pagerState.animateScrollToPage(target)
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
                        .padding(vertical = ReaderLayout.PageVerticalPadding),
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
                    // verticalScroll 兜底：正常情况下 [paginateBook] 已把每页
                    // 高度卡在预算内，这一层永远滚不动。保留它只为"单段 +
                    // 其译文本身超过一页"这种内容物理上装不进一屏的极端段落。
                    // 一旦发现日常阅读能滚，就是分页记账与渲染几何又对不上了。
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
                                        paraIndex = slice.paraIndex,
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
                                        onLongPressWord = onLongPressWord,
                                        classifier = classifier,
                                        bookId = bookId,
                                    )
                                // 跨页切片：行级拆分渲染
                                else -> ReaderSliceParagraphBlock(
                                    para = para,
                                    paraIndex = slice.paraIndex,
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
                                    onLongPressWord = onLongPressWord,
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
