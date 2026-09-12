package com.eareyereading.util

import com.eareyereading.domain.model.TocEntry

/**
 * 纯文本（.txt）章节标题扫描：按段落流识别章标题段，生成目录。
 *
 * 主要面向 Project Gutenberg 纯文本名著（CHAPTER I / CHAPTER 1. Loomings.），
 * 同时兼容普通 `Chapter 12` 类标题。仅当整段就是一个章标题（标题后最多跟
 * 章节副题）且命中数达到阈值才认定该书有目录——普通文章正文里连续出现
 * 独立成段的 "chapter <数字>" 几乎不可能，阈值足以防误伤。
 */
internal object TocHeadingScanner {

    /** 命中数达到该值才认定有章节结构（防普通文章误伤）。 */
    const val MIN_HEADING_HITS = 3

    /** 标题段长度上限：超长段落不可能是章标题。 */
    private const val MAX_HEADING_LENGTH = 80

    private val CHAPTER_HEADING = Regex(
        """^\s*chapter\s+([ivxlcdm]+|\d+)\s*[.:\-—–]?\s*(.{0,60})\s*$""",
        RegexOption.IGNORE_CASE,
    )

    /**
     * 扫描段落流中的章标题段。标题段自身是段落流一员，其下标即跳转目标，
     * 与 books.content 的拆分口径（split("\n\n")）一致。
     * 命中数不足 [MIN_HEADING_HITS] 时返回空列表（视为无目录）。
     */
    fun scan(paragraphs: List<String>): List<TocEntry> {
        val entries = mutableListOf<TocEntry>()
        for ((index, para) in paragraphs.withIndex()) {
            if (para.length > MAX_HEADING_LENGTH) continue
            if (BookImages.isImageMarker(para)) continue
            if (CHAPTER_HEADING.containsMatchIn(para)) {
                entries.add(TocEntry(title = para.trim(), paragraphIndex = index))
            }
        }
        return if (entries.size >= MIN_HEADING_HITS) entries else emptyList()
    }
}
