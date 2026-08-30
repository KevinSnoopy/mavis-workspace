@file:Suppress("SwallowedException", "ReturnCount")

package com.eareyereading.util

import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import javax.inject.Inject
import javax.inject.Singleton

/**
 * EPUB 文件解析器
 * 解析 EPUB 文件并提取段落文本
 */
@Singleton
class EpubParser @Inject constructor() {

    companion object {
        /** 单个文档（OPF / 章节 HTML）读取上限，防压缩炸弹撑爆内存。 */
        private const val MAX_DOC_CHARS = 2_000_000

        /** 全书累计字符上限：单文档有上限但 spine 条目数不限，
         * 恶意 OPF 可引用海量高压缩比条目 → 总提取量必须有顶。 */
        private const val MAX_TOTAL_CHARS = 10_000_000

        /** spine 条目数上限，与字符上限双重保险。 */
        private const val MAX_SPINE_ITEMS = 20_000
    }

    /**
     * 解析 EPUB 文件，返回段落列表
     * 解析失败（文件不存在、IO 错误、格式错误）时返回空列表，由调用方决定如何处理。
     * 注：ZipException 是 IOException 子类，一个 catch 即可覆盖。
     */
    fun parseBook(filePath: String): List<String> {
        return try {
            parseEpub(File(filePath))
        } catch (e: java.io.IOException) {
            android.util.Log.e("EpubParser", "Error reading EPUB file: ${filePath}", e)
            emptyList()
        }
    }

    /**
     * 解析 EPUB 文件
     */
    private fun parseEpub(file: File): List<String> {
        if (!file.exists()) return emptyList()

        val paragraphs = mutableListOf<String>()
        ZipFile(file).use { zip ->
            // 找到 OPF 文件
            val opfEntry = zip.entries().asSequence()
                .filter { it.name.endsWith(".opf") }
                .firstOrNull() ?: return emptyList()

            // 解析 OPF 获取 spine 顺序
            val opfContent = readTextCapped(zip.getInputStream(opfEntry).bufferedReader(), MAX_DOC_CHARS)
            val spineIds = extractSpineIds(opfContent)
            val manifestItems = extractManifestItems(opfContent)
            // spine href 是相对 OPF 所在目录的路径
            val opfDir = opfEntry.name.substringBeforeLast('/', "")

            // 按 spine 顺序读取每个 HTML 文件
            var totalChars = 0
            outer@ for (id in spineIds.take(MAX_SPINE_ITEMS)) {
                val rawHref = manifestItems[id] ?: continue
                // href 可能带片段（chapter1.xhtml#sec2），匹配文件时需去掉
                val href = rawHref.substringBefore('#')
                if (href.isBlank()) continue
                // 部分 OPF 的 href 是 URL 编码的（空格等），解码后再查条目；
                // 解码失败或无匹配时回退原始字符串
                val decoded = decodeHref(href)
                val entry = resolveEntry(zip, opfDir, decoded)
                    ?: if (decoded != href) resolveEntry(zip, opfDir, href) else null
                    ?: continue

                val html = readTextCapped(zip.getInputStream(entry).bufferedReader(), MAX_DOC_CHARS)
                for (para in extractParagraphsFromHtml(html)) {
                    if (totalChars + para.length > MAX_TOTAL_CHARS) break@outer
                    paragraphs.add(para)
                    totalChars += para.length
                }
            }
        }

        return paragraphs.filter { it.isNotBlank() }
    }

    /**
     * 按 OPF 目录解析 spine href 对应的 zip 条目。
     * 精确路径优先，避免 `endsWith("1.xhtml")` 误匹配 `ch11.xhtml` 这类后缀重叠。
     */
    private fun resolveEntry(zip: ZipFile, opfDir: String, href: String): ZipEntry? {
        if (opfDir.isNotEmpty()) {
            zip.getEntry("$opfDir/$href")?.let { return it }
        }
        zip.getEntry(href)?.let { return it }
        // 兜底：仅按完整路径段后缀匹配（要求前置 '/'，杜绝子串误配）
        return zip.entries().asSequence().firstOrNull { it.name.endsWith("/$href") }
    }

    /** 有上限地读取文本，超限截断。 */
    private fun readTextCapped(reader: java.io.BufferedReader, maxChars: Int): String {
        reader.use { r ->
            val sb = StringBuilder()
            val buf = CharArray(8192)
            while (sb.length < maxChars) {
                val n = r.read(buf)
                if (n < 0) break
                sb.append(buf, 0, minOf(n, maxChars - sb.length))
            }
            return sb.toString()
        }
    }

    /** URL 解码 spine href（`ch%201.xhtml` 类文件名）；失败回退原串。 */
    private fun decodeHref(href: String): String {
        if (!href.contains('%')) return href
        return try {
            java.net.URLDecoder.decode(href, "UTF-8")
        } catch (_: Exception) {
            href
        }
    }

    /**
     * 提取 spine idref。属性顺序不敏感（`<itemref linear="yes" idref="ch1"/>` 也合法），
     * 同时容忍单/双引号与等号两侧空白。
     */
    private fun extractSpineIds(opfContent: String): List<String> {
        val regex = Regex("<itemref\\b[^>]*?idref\\s*=\\s*[\"']([^\"']+)[\"']", RegexOption.IGNORE_CASE)
        return regex.findAll(opfContent).map { it.groupValues[1] }.toList()
    }

    private fun extractManifestItems(opfContent: String): Map<String, String> {
        val result = mutableMapOf<String, String>()
        val regex = Regex(
            "<item\\b[^>]*?id\\s*=\\s*[\"']([^\"']+)[\"'][^>]*?href\\s*=\\s*[\"']([^\"']+)[\"'][^>]*/?>",
            RegexOption.IGNORE_CASE,
        )
        regex.findAll(opfContent).forEach { match ->
            result[match.groupValues[1]] = match.groupValues[2]
        }
        // 也匹配 href 在 id 之前的情况
        val regex2 = Regex(
            "<item\\b[^>]*?href\\s*=\\s*[\"']([^\"']+)[\"'][^>]*?id\\s*=\\s*[\"']([^\"']+)[\"'][^>]*/?>",
            RegexOption.IGNORE_CASE,
        )
        regex2.findAll(opfContent).forEach { match ->
            result[match.groupValues[2]] = match.groupValues[1]
        }
        return result
    }

    /**
     * 从 HTML 中提取段落文本
     */
    private fun extractParagraphsFromHtml(html: String): List<String> {
        // 移除脚本和样式
        var text = html.replace(Regex("<script[^>]*>[\\s\\S]*?</script>"), "")
        text = text.replace(Regex("<style[^>]*>[\\s\\S]*?</style>"), "")

        // 优先按 <p> 标签分割段落（EPUB 最常见的段落标签）
        val pTagRegex = Regex("<p[^>]*>(.*?)</p>", RegexOption.DOT_MATCHES_ALL)
        val pParagraphs = pTagRegex.findAll(text).map { it.groupValues[1] }.toList()

        val rawParagraphs = if (pParagraphs.isNotEmpty()) {
            pParagraphs
        } else {
            // 回退：按 <br> 或块级标签分割
            text.split(Regex("<br\\s*/?>|</(?:div|section|article|p)>"))
        }

        return rawParagraphs
            .map { para ->
                // 移除剩余 HTML 标签
                var cleaned = para.replace(Regex("<[^>]+>"), " ")
                // 解码 HTML 实体（与 ArticleParser 共用同一实现，行为不分叉）
                cleaned = HtmlEntities.decode(cleaned)
                // 压缩空白并 trim
                cleaned.replace(Regex("\\s+"), " ").trim()
            }
            .filter { it.length > 10 }
    }
}
