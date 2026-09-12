package com.eareyereading.ui.screens.reader

import androidx.compose.ui.unit.dp

/**
 * 阅读器版式度量 —— **分页侧与渲染侧共用的唯一真实来源**。
 *
 * 翻页模式是"先算后画"：分页器 [paginateBook] 按这些常量把整书装箱，
 * 渲染器（[ReaderParagraphBlock] / [ReaderSliceParagraphBlock] /
 * [ReaderTranslationBlock] / [ReaderBookmarkMark] / [ReaderImageBlock]）
 * 再按同一批常量把切片画出来。两侧只要有任何一个数字对不上，这一页就会
 * 比一屏高出一截——用户必须页内滚动才能读完，页眉页脚还会跟着滚走。
 *
 * 所以：**这里出现的每个值都必须同时被分页侧和渲染侧消费**，任何一侧都
 * 不允许再写裸常量。新增段落级装饰（角标、时长、分隔线……）时，先在此
 * 登记度量，再把两侧一起接上。
 */
internal object ReaderLayout {

    // ── 行高 ────────────────────────────────────────────────────────────

    /** 正文行高倍数（与 [readerParagraphStyle] 的默认 lineMultiplier 一致）。 */
    const val BODY_LINE_MULTIPLIER = 1.8f

    /** 译文行高倍数。 */
    const val TRANS_LINE_MULTIPLIER = 1.5f

    /** 译文比正文小多少 sp。 */
    const val TRANS_FONT_DELTA = 2

    /**
     * 行高安全余量：只放大"每行占高"，不改变换行结果。最坏情况是页尾略空，
     * 不会让渲染高度反超预算。
     */
    const val LINE_SAFETY = 1.02f

    // ── 段落级垂直开销 ──────────────────────────────────────────────────

    /** 段落正文上下内边距：段首切片取上、段尾切片取下。 */
    val ParagraphPadding = 6.dp

    /** 书签标记行总高（图标 16dp + 上下 4dp）。 */
    val BookmarkRowHeight = 24.dp

    /** 插图块上下各留多少白（[ReaderImageBlock] 外层 Box 的 padding）。 */
    val ImageBlockVerticalPadding = 8.dp

    /** 插图块总高（含上下留白）。分页器按此固定高度给插图段记账。 */
    val ImageBlockHeight = 220.dp

    // ── 译文块 ──────────────────────────────────────────────────────────

    /** 译文块与正文之间的上间距。 */
    val TransTopSpacing = 4.dp

    /** 译文 Text 自身上下 padding。 */
    val TransInnerPadding = 2.dp

    /** 译文块与下一段之间的下间距。 */
    val TransBottomSpacing = 12.dp

    /** 译文块在 [com.eareyereading.ui.screens.reader.paginateBook] 中登记的附加高度。 */
    val TransBlockHeight = TransTopSpacing + TransInnerPadding * 2 + TransBottomSpacing

    // ── 页面框 ──────────────────────────────────────────────────────────

    /** 单页正文区的上下留白（[PagedReadingView] 里那一层 padding）。 */
    val PageVerticalPadding = 8.dp

    /** 页眉（书名 running header）+ 页脚（页码）实际占高：labelSmall 行高 16sp + 各自 2dp。 */
    val PageChromeHeight = 36.dp

    /**
     * 分页预算从视口高中扣掉的固定量：正文区上下留白 + 页眉页脚 + 16dp
     * 安全余量（约半行正文，吸收浮点累计与平台换行差异）。宁可页尾略空，
     * 也不让一页内容反超一屏。
     */
    val PageBudgetReserve = PageVerticalPadding * 2 + PageChromeHeight + 16.dp
}
