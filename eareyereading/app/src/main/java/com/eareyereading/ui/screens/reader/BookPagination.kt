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
 * 同一字体），行高按 readerParagraphStyle 的倍数 + 2% 安全余量估算，
 * 贪心装箱到每页预算内。段落可跨页拆分（行粒度），书签行/译文高度
 * 计入所属切片（书签在段首切片、译文在段尾切片）。
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
