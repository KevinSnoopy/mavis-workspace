package com.eareyereading.ui.screens.reader

import com.eareyereading.util.BookImages

/**
 * 分页切片：一个段落可按 StaticLayout 的行边界拆成多个切片跨页渲染，
 * "放不下的行"自然流到下一页（真书式排版，替代旧的整段独占页 + 页内滚动）。
 */
internal data class PageSlice(
    val paraIndex: Int,
    val charStart: Int,
    val charEnd: Int,
    val isFirstOfPara: Boolean,
    val isLastOfPara: Boolean,
)

/**
 * 整书分页：StaticLayout 按行测量（与 Compose 渲染同一文本、同一宽度、
 * 同一字体、同一换行策略），行高按 [readerParagraphStyle] 的倍数 + 安全余量
 * 估算，贪心装箱到每页预算内。段落可跨页拆分（行粒度），书签行/译文高度
 * 计入所属切片（书签在段首切片、译文在段尾切片）。
 *
 * ── 页高必须严丝合缝（本文件的核心约束）──
 * 分页算出来的每一页，最终是交给 [PagedReadingView] 的 `weight(1f)` 视口
 * 一次性画完的——超出的部分只能靠页内 `verticalScroll` 兜底，用户就得
 * 滚动才能读完一页，页眉页脚还会被一起滚走。所以"本页所有切片高度之和
 * ≤ pageBudgetPx"必须恒成立，而这个不等式唯一的漏洞就是**译文块高度**：
 *
 * 译文挂在段尾切片上，可"本切片是不是段尾"又取决于这一片装了多少行——
 * 先有鸡还是先有蛋。旧实现用 `i + 1 >= lines.size` 猜"本切片是段尾"，
 * 等价于假设每片只放一行，于是**除了恰好只剩一行的段落，所有译文块的
 * 高度都被漏算**。结果就是每页照"无译文"的高度塞满正文，译文块再叠加上去
 * ——一页顶出一屏，正是"开了翻译就得滚动"的直接来源。
 *
 * 现在改成两遍试算（见下方 `fitLines`）：先按"本片是段尾（含译文）"保守
 * 试算，若发现吃不满段尾，再按"不是段尾（不含译文）"放宽，同时给下一页
 * 留足一行，保证两遍收敛且不来回判定。
 *
 * 必须在后台线程调用（数百次 StaticLayout 构建耗时几十毫秒）。
 */
internal fun paginateBook(
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
    // 安全余量宁可页尾略空，也不让渲染高度反超估算
    val bodyLineH = fontSizePx * ReaderLayout.BODY_LINE_MULTIPLIER * ReaderLayout.LINE_SAFETY
    val transLineH = transFontSizePx * ReaderLayout.TRANS_LINE_MULTIPLIER * ReaderLayout.LINE_SAFETY

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

    /** 段尾切片的译文块高度：0 表示本段没有可渲染的译文。 */
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

    /** 切片固定开销：段首/段尾内边距 + 段首书签行（不含译文块）。 */
    fun fixedExtra(first: Boolean, last: Boolean, idx: Int): Float =
        (if (first) paragraphPadPx else 0f) +
            (if (last) paragraphPadPx else 0f) +
            (if (first && idx in bookmarked) bookmarkRowPx else 0f)

    /** 当前页剩余预算内，扣掉 extra 后还能放几行。 */
    fun linesFitting(extra: Float, cap: Int): Int =
        (((pageBudgetPx - used) - extra).coerceAtLeast(0f) / bodyLineH)
            .toInt()
            .coerceIn(0, cap)

    /**
     * 算出本切片该放多少行（[left] 为段落剩余行数）。
     *
     * 两遍试算：
     *  1. 先按"本切片吃满段尾"试算——额外装下段尾内边距 + 译文块。若结果
     *     恰好等于 [left]，说明确实是段尾切片，译文块已计入，直接采用；
     *  2. 否则本切片不是段尾：译文块不占本片高度，去掉它按"中间切片"放宽，
     *     但最多只能吃到 [left] - 1 行——把最后一行留给下一页，否则下一轮
     *     又会判成本片是段尾，两遍来回摇摆。
     *
     * 返回值 0 表示本页放不下（调用方换页后重试）。
     */
    fun fitLines(left: Int, first: Boolean, idx: Int, transH: Float): Int {
        val tail = linesFitting(fixedExtra(first, last = true, idx) + transH, left)
        if (tail == left) return tail
        // 只剩一行时本切片必然是段尾：装不下就换页，不允许放宽
        if (left <= 1) return tail
        return minOf(linesFitting(fixedExtra(first, last = false, idx), left), left - 1)
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
            val left = lines.size - i
            var take = fitLines(left, first, idx, transH)
            if (take <= 0) {
                // 当前页连一行都放不下：换页重算；仍放不下（单段 + 其译文
                // 本身超过一页）则单行兜底——这一页确实需要页内滚动，
                // 属于"内容物理上装不进一屏"的极端情形，不是排版误差
                if (current.isNotEmpty()) {
                    closePage()
                    take = fitLines(left, first, idx, transH)
                }
                if (take <= 0) take = 1
            }
            val isLast = i + take >= lines.size
            current.add(
                PageSlice(
                    paraIndex = idx,
                    charStart = lines[i][0],
                    charEnd = lines[i + take - 1][1],
                    isFirstOfPara = first,
                    isLastOfPara = isLast,
                ),
            )
            used += fixedExtra(first, isLast, idx) +
                (if (isLast) transH else 0f) +
                take * bodyLineH
            i += take
            first = false
        }
    }
    closePage()
    return pages
}
