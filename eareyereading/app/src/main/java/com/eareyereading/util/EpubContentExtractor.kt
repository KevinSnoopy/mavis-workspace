@file:Suppress("SwallowedException", "ReturnCount")

package com.eareyereading.util

import java.util.zip.ZipEntry
import java.util.zip.ZipFile

/**
 * EPUB 正文内容提取职责（从 EpubParser 拆出，SRP）：
 *  - 按 spine 顺序遍历章节 HTML
 *  - 跳过 non-content 页面（封面/目录/版权页等）
 *  - 解析图片标记 `[[IMG:src]]` → `[[IMG:序号]]`
 *  - 全书累计字符上限截断
 *
 * 章节切分与图片标记解析的正则/常量集中于此。
 */
internal object EpubContentExtractor {

    /** 全书累计字符上限：单文档有上限但 spine 条目数不限，
     * 恶意 OPF 可引用海量高压缩比条目 → 总提取量必须有顶。 */
    const val MAX_TOTAL_CHARS = 10_000_000

    /** 每本书提取的插图数量上限：超出后丢弃标记（防图册类 EPUB 落盘爆炸）。 */
    const val MAX_BOOK_IMAGES = 300

    /** 非正文页面黑名单：版权页/封面/目录/扉页/出版信息（issue 9.4）。 */
    private val nonContentHrefRegex = Regex(
        "/(cover|nav|toc|title[_-]?page|copyright|colophon|imprint)\\.x?html?$",
        RegexOption.IGNORE_CASE,
    )

    /** 封面图片扩展名（图片标记解析用）。 */
    private val imgExtRegex = Regex("\\.(jpe?g|png|webp|gif|bmp)$", RegexOption.IGNORE_CASE)

    /** `[[IMG:src]]` 标记正则。 */
    private val IMG_MARKER = Regex("(\\[\\[IMG:)([^\\]]*)(\\]\\])")

    /**
     * 按 spine 顺序提取正文段落与图片条目名。
     *
     * @param zip 已打开的 ZipFile
     * @param entryNames zip 条目列表
     * @param opfContent OPF 文本
     * @param opfDir OPF 所在目录
     * @return Triple(段落列表, 图片条目名列表, 是否截断, 截断前原文累计字符数, 图片总数)
     */
    fun extractContent(
        zip: ZipFile,
        entryNames: List<ZipEntry>,
        opfContent: String,
        opfDir: String,
    ): ExtractedContent {
        val paragraphs = mutableListOf<String>()
        val imageEntryNames = mutableListOf<String>()
        var totalChars = 0
        var originalTotalChars = 0
        var truncated = false
        var imageCount = 0

        val spineRefs = OpfParser.extractSpineRefs(opfContent)
        val manifestItems = OpfParser.extractManifestItems(opfContent)

        outer@ for (ref in spineRefs.take(EpubZipReader.MAX_SPINE_ITEMS)) {
            // linear="no" 表示出版方声明的辅助内容（封面/目录/版权页），
            // 不当正文混入（issue 9.4）
            if (!ref.linear) continue
            val rawHref = manifestItems[ref.idref] ?: continue
            // href 可能带片段（chapter1.xhtml#sec2），匹配文件时需去掉
            val href = rawHref.substringBefore('#')
            if (href.isBlank()) continue
            if (nonContentHrefRegex.containsMatchIn("/$href")) continue
            // 部分 OPF 的 href 是 URL 编码的（空格等），解码后再查条目；
            // 解码失败或无匹配时回退原始字符串
            val decoded = EpubZipReader.decodeHref(href)
            // 注意括号：elvis 右结合，不括起来 "null ?: continue" 会被
            // 吞进 if 的 else 分支，整个表达式退化为 ZipEntry? 可空类型
            val entry = (
                EpubZipReader.resolveEntry(zip, opfDir, decoded, entryNames)
                    ?: if (decoded != href) EpubZipReader.resolveEntry(zip, opfDir, href, entryNames) else null
                ) ?: continue

            val html = EpubZipReader.readEntryTextCapped(zip.getInputStream(entry), EpubZipReader.MAX_DOC_CHARS)
            // issue 9.8：extractParagraphsFromHtml 顺带统计 <img> 数量
            val (entryParagraphs, entryImages) = XhtmlParagraphExtractor.extractParagraphsFromHtml(html)
            imageCount += entryImages
            // 章节 HTML 所在目录：插图 src 相对它解析（与 spine 同款 href 语义）
            val chapterDir = entry.name.substringBeforeLast('/', "")
            // 先解析标记 src → 全局序号，再把"文本+行内标记"混排段
            // 规整成纯文本段/纯标记段（渲染层按标记段画插图）
            val chapterParas = entryParagraphs.flatMap { para ->
                if (para.contains("[[IMG:")) {
                    BookImages.expandInlineMarkers(
                        listOf(resolveImageMarkers(para, zip, chapterDir, entryNames, imageEntryNames)),
                    )
                } else {
                    listOf(para)
                }
            }
            for (para in chapterParas) {
                // 原文累计：含被截断的段落，表示扫描到的原文规模（issue 9.2 提示用）
                originalTotalChars += para.length
                if (totalChars + para.length > MAX_TOTAL_CHARS) {
                    truncated = true
                    break@outer
                }
                totalChars += para.length
                // 入库时顺手过滤空段：免最后再全量 filter 一遍
                // （图片标记段始终保留，即使很短）
                if (para.isNotBlank()) paragraphs.add(para)
            }
        }

        return ExtractedContent(
            paragraphs = paragraphs,
            imageEntryNames = imageEntryNames.toList(),
            wasTruncated = truncated,
            originalCharCount = if (truncated) originalTotalChars else 0,
            images = imageCount,
        )
    }

    /**
     * 把段落里的 `[[IMG:src]]` 标记解析成 `[[IMG:序号]]`：
     * src 按 chapterDir 相对解析到 zip 条目；命中则登记进 imageEntryNames
     * 并置换为该条目的全局序号；未命中/超上限的标记直接删除。
     */
    private fun resolveImageMarkers(
        para: String,
        zip: ZipFile,
        chapterDir: String,
        entryNames: List<ZipEntry>,
        imageEntryNames: MutableList<String>,
    ): String {
        val imgExt = imgExtRegex
        return IMG_MARKER.replace(para) { m ->
            val src = m.groupValues[2].trim()
            if (src.isEmpty()) return@replace ""
            val decoded = EpubZipReader.decodeHref(src.substringBefore('#'))
            // 括号原因同 spine 解析处：防 elvis 右结合把 return 吞进 else 分支
            val resolved = (
                EpubZipReader.resolveEntry(zip, chapterDir, decoded, entryNames)
                    ?: if (decoded != src) EpubZipReader.resolveEntry(zip, chapterDir, src.substringBefore('#'), entryNames) else null
                ) ?: return@replace ""                        // 图片缺条目：删标记
            if (imageEntryNames.size >= MAX_BOOK_IMAGES) {  // 超上限：删标记
                return@replace ""
            }
            if (!imgExt.containsMatchIn(resolved.name)) {   // 非图片扩展名：删标记
                return@replace ""
            }
            // 同一 zip 条目复用既有序号（EPUB 复用图片很常见）
            val idx = imageEntryNames.indexOf(resolved.name)
                .takeIf { it >= 0 }
                ?: imageEntryNames.size.also { imageEntryNames.add(resolved.name) }
            "[[IMG:$idx]]"
        }
    }

    /** 内容提取结果。 */
    data class ExtractedContent(
        val paragraphs: List<String>,
        val imageEntryNames: List<String>,
        val wasTruncated: Boolean,
        val originalCharCount: Int,
        val images: Int,
    )
}
