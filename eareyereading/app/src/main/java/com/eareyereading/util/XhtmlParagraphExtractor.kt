@file:Suppress("SwallowedException", "ReturnCount")

package com.eareyereading.util

/**
 * XHTML 段落提取职责（从 EpubParser 拆出，SRP）：
 *  - 移除 script/style
 *  - `<img>` → `[[IMG:src]]` 标记
 *  - 按 `<p>` / 块级标签分割段落
 *  - 去标签、解码 HTML 实体、压缩空白
 *
 * 所有正则预编译集中于此。
 */
internal object XhtmlParagraphExtractor {

    private val SCRIPT_BLOCK = Regex("<script[^>]*>[\\s\\S]*?</script>")
    private val STYLE_BLOCK = Regex("<style[^>]*>[\\s\\S]*?</style>")
    private val IMG_TAG = Regex("<img\\b", RegexOption.IGNORE_CASE)
    /** 整个 <img> 标签及其 src 属性（属性顺序无关）。 */
    private val IMG_WITH_SRC = Regex(
        "<img\\b[^>]*\\bsrc\\s*=\\s*[\"']([^\"']+)[\"'][^>]*>",
        RegexOption.IGNORE_CASE,
    )
    private val P_TAG = Regex("<p[^>]*>(.*?)</p>", RegexOption.DOT_MATCHES_ALL)
    private val BR_OR_BLOCK_SPLIT = Regex("<br\\s*/?>|</(?:div|section|article|p)>")
    private val ANY_TAG = Regex("<[^>]+>")
    private val WHITESPACE = Regex("\\s+")

    /**
     * 从 HTML 中提取段落文本。
     * `<img>` 在去标签前替换为 `[[IMG:src]]` 标记（src 由调用方解析成 zip 条目），
     * 标记随段落流转、导入后由渲染层画成插图。
     * @return Pair(段落列表, 该章节 <img> 数量)（issue 9.8）
     */
    fun extractParagraphsFromHtml(html: String): Pair<List<String>, Int> {
        // 移除脚本和样式
        var text = html.replace(SCRIPT_BLOCK, "")
        text = text.replace(STYLE_BLOCK, "")
        val imageCount = IMG_TAG.findAll(html).count()
        // <img> → 标记：必须在 ANY_TAG 清理前替换，否则 src 信息丢失
        text = text.replace(IMG_WITH_SRC) { m -> "\n\n[[IMG:${m.groupValues[1].trim()}]]\n\n" }

        // 优先按 <p> 标签分割段落（EPUB 最常见的段落标签）
        val pParagraphs = P_TAG.findAll(text).map { it.groupValues[1] }.toList()

        val rawParagraphs = if (pParagraphs.isNotEmpty()) {
            pParagraphs
        } else {
            // 回退：按 <br> 或块级标签分割
            text.split(BR_OR_BLOCK_SPLIT)
        }

        val paragraphs = rawParagraphs
            .map { para ->
                // 移除剩余 HTML 标签
                var cleaned = para.replace(ANY_TAG, " ")
                // 解码 HTML 实体（与 ArticleParser 共用同一实现，行为不分叉）
                cleaned = HtmlEntities.decode(cleaned)
                // 压缩空白并 trim
                cleaned.replace(WHITESPACE, " ").trim()
            }
            .filter { it.length > 3 }   // 阈值从 10 降到 3：章标题"Chapter 1"等短段不再被吞（issue 10.8）
        return paragraphs to imageCount
    }
}
