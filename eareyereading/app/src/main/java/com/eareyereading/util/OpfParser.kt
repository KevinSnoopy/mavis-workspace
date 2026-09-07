@file:Suppress("SwallowedException", "ReturnCount")

package com.eareyereading.util

import java.util.Locale

/**
 * OPF 描述文件解析职责（从 EpubParser 拆出，SRP）：
 *  - 元数据提取（title / author / language / identifier）
 *  - spine 引用提取（idref + linear）
 *  - manifest 条目提取（id → href）
 *
 * 所有正则预编译集中于此。
 */
internal object OpfParser {

    /** OPF 元数据载体（issue 9.1/9.7）。 */
    data class OpfMetadata(
        val title: String,
        val author: String,
        val language: String,
        val identifier: String,
    )

    /** spine 引用：idref + 是否正文（linear，缺省 yes）。 */
    data class SpineRef(val idref: String, val linear: Boolean)

    private val ITEMREF_TAG = Regex("<itemref\\b[^>]*>", RegexOption.IGNORE_CASE)
    private val IDREF_ATTR = Regex("idref\\s*=\\s*[\"']([^\"']+)[\"']", RegexOption.IGNORE_CASE)
    private val LINEAR_ATTR = Regex("linear\\s*=\\s*[\"']([^\"']+)[\"']", RegexOption.IGNORE_CASE)
    private val ITEM_TAG = Regex("<item\\b[^>]*?>", RegexOption.IGNORE_CASE)
    private val ID_ATTR = Regex("\\bid\\s*=\\s*[\"']([^\"']+)[\"']", RegexOption.IGNORE_CASE)
    private val HREF_ATTR = Regex("\\bhref\\s*=\\s*[\"']([^\"']+)[\"']", RegexOption.IGNORE_CASE)

    /**
     * 提取 OPF 元数据（issue 9.1）：`<dc:title>` / `<dc:creator>` / `<dc:language>`。
     * 部分 OPF 用 dcterms: 前缀或 dc:title 内嵌 span 标签，一并容错。
     */
    fun extractMetadata(opfContent: String): OpfMetadata {
        fun dcValue(tag: String): String {
            val regex = Regex(
                "<(?:dc|dcterms):$tag\\b[^>]*>([\\s\\S]*?)</(?:dc|dcterms):$tag>",
                RegexOption.IGNORE_CASE,
            )
            return regex.find(opfContent)?.groupValues?.get(1)
                ?.replace(Regex("<[^>]+>"), "")
                ?.replace(Regex("\\s+"), " ")
                ?.trim()
                .orEmpty()
        }
        val title = dcValue("title")
        val author = dcValue("creator")
        val language = dcValue("language").lowercase(Locale.ROOT).take(8)
        val identifier = dcValue("identifier")
        return OpfMetadata(title, author, language, identifier)
    }

    /**
     * 提取 spine 引用。先匹配整个 `<itemref>` 标签再在标签内取属性，
     * 属性顺序不敏感，同时容忍单/双引号与等号两侧空白。
     * linear="no" 的条目（封面/目录/版权页）不再被当正文读入（issue 9.4）。
     */
    fun extractSpineRefs(opfContent: String): List<SpineRef> {
        return ITEMREF_TAG.findAll(opfContent).mapNotNull { tag ->
            val id = IDREF_ATTR.find(tag.value)?.groupValues?.get(1) ?: return@mapNotNull null
            val linear = LINEAR_ATTR.find(tag.value)?.groupValues?.get(1)?.lowercase() ?: "yes"
            SpineRef(idref = id, linear = linear != "no")
        }.toList()
    }

    /**
     * 提取 manifest：id → href。
     * issue 10.9：单遍扫描整个 `<item ...>` 标签，再在标签内独立取 id 与 href，
     * 属性顺序完全无关；同 id 先到者胜（LinkedHashMap 语义）。
     */
    fun extractManifestItems(opfContent: String): Map<String, String> {
        val result = LinkedHashMap<String, String>()
        for (tag in ITEM_TAG.findAll(opfContent)) {
            val id = ID_ATTR.find(tag.value)?.groupValues?.get(1) ?: continue
            val href = HREF_ATTR.find(tag.value)?.groupValues?.get(1) ?: continue
            if (id !in result) result[id] = href
        }
        return result
    }
}
