@file:Suppress("SwallowedException", "ReturnCount")

package com.eareyereading.util

import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import javax.inject.Inject
import javax.inject.Singleton

/** EPUB 解析失败的具体原因（issue 9.3）：
 * 此前所有 IOException 一律吞掉返回空列表，导入 UI 只能笼统提示"文件读取错误"，
 * 无法区分损坏/加密/空文件/缺 OPF/无可读章节。
 * 继承 IOException，调用方现有 catch 语句无需改动。 */
sealed class EpubParseException(message: String) : java.io.IOException(message) {
    /** zip 结构损坏或根本不是 EPUB。 */
    class Corrupted : EpubParseException("文件损坏或非 EPUB 格式")

    /** 加密 EPUB（java.util.zip 在读条目时抛 "encrypted ZIP entry not supported"）。 */
    class Encrypted : EpubParseException("文件已加密（需解密）")

    /** 0 字节 / 不存在的文件。 */
    class Empty : EpubParseException("文件为空或不存在")

    /** zip 有效但找不到 OPF 描述文件。 */
    class NoOpf : EpubParseException("EPUB 缺少 OPF 描述文件")

    /** OPF/spine 有效但提取不到任何正文段落。 */
    class NoContent : EpubParseException("EPUB 不含可读章节内容")
}

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

        /** EPUB 文件总字节上限（issue 10.4）：防超大连载/恶意文件占满存储或撑爆内存。 */
        private const val MAX_EPUB_BYTES = 200L * 1024 * 1024

        /** zip 条目总数上限（issue 10.3）：恶意 EPUB 可在 OPF 塞海量条目把
         * resolveEntry 兜底分支打成 O(spine × entries) 算法炸弹。 */
        private const val MAX_ZIP_ENTRIES = 5_000

        /** resolveEntry 无精确命中时的兜底后缀匹配次数上限（issue 10.3）。 */
        private const val MAX_SUFFIX_PROBES = 200
    }

    /** 非正文页面黑名单：版权页/封面/目录/扉页/出版信息（issue 9.4）。 */
    private val nonContentHrefRegex = Regex(
        "/(cover|nav|toc|title[_-]?page|copyright|colophon|imprint)\\.x?html?$",
        RegexOption.IGNORE_CASE,
    )

    /**
     * 解析 EPUB 文件，返回段落列表。
     * 解析失败抛 [EpubParseException]（IOException 子类），
     * 调用方可按异常类型给出可区分的失败提示。
     */
    fun parseBook(filePath: String): List<String> {
        val file = File(filePath)
        if (!file.exists() || file.length() == 0L) throw EpubParseException.Empty()
        // 字节级防护（issue 10.4）：文件本身超限直接拒绝，避免进一步解压 OOM/占盘
        if (file.length() > MAX_EPUB_BYTES) {
            android.util.Log.w("EpubParser", "EPUB exceeds ${MAX_EPUB_BYTES / (1024 * 1024)}MB: ${file.length()}")
            throw EpubParseException.Corrupted()
        }
        return try {
            parseEpub(file)
        } catch (e: EpubParseException) {
            throw e
        } catch (e: java.io.IOException) {
            // ZipException 是 IOException 子类：加密条目在读流时才暴露，
            // 靠 message 特征分流出"已加密"，其余按损坏归类
            if (e.message?.contains("encrypt", ignoreCase = true) == true) {
                throw EpubParseException.Encrypted()
            }
            android.util.Log.e("EpubParser", "Error reading EPUB file: ${filePath}", e)
            throw EpubParseException.Corrupted()
        }
    }

    /**
     * 解析 EPUB 文件
     */
    private fun parseEpub(file: File): List<String> {
        val paragraphs = mutableListOf<String>()
        ZipFile(file).use { zip ->
            // 一次性构建条目索引：防恶意 EPUB 在 OPF 里塞海量 itemref，让
            // resolveEntry 兜底分支退化成 O(spine × entries) 算法炸弹（issue 10.3）
            val entryNames = zip.entries().toList()
            if (entryNames.size > MAX_ZIP_ENTRIES) {
                android.util.Log.w("EpubParser", "EPUB has ${entryNames.size} entries, exceeding $MAX_ZIP_ENTRIES")
                throw EpubParseException.Corrupted()
            }

            // 找到 OPF 文件
            val opfEntry = entryNames.asSequence()
                .filter { it.name.endsWith(".opf") }
                .firstOrNull() ?: throw EpubParseException.NoOpf()

            // 解析 OPF 获取 spine 顺序
            val opfContent = readTextCapped(zip.getInputStream(opfEntry).bufferedReader(), MAX_DOC_CHARS)
            val spineRefs = extractSpineRefs(opfContent)
            val manifestItems = extractManifestItems(opfContent)
            // spine href 是相对 OPF 所在目录的路径
            val opfDir = opfEntry.name.substringBeforeLast('/', "")

            // 按 spine 顺序读取每个 HTML 文件
            var totalChars = 0
            outer@ for (ref in spineRefs.take(MAX_SPINE_ITEMS)) {
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
                val decoded = decodeHref(href)
                val entry = resolveEntry(zip, opfDir, decoded, entryNames)
                    ?: if (decoded != href) resolveEntry(zip, opfDir, href, entryNames) else null
                    ?: continue

                val html = readTextCapped(zip.getInputStream(entry).bufferedReader(), MAX_DOC_CHARS)
                for (para in extractParagraphsFromHtml(html)) {
                    if (totalChars + para.length > MAX_TOTAL_CHARS) break@outer
                    paragraphs.add(para)
                    totalChars += para.length
                }
            }
        }

        val result = paragraphs.filter { it.isNotBlank() }
        if (result.isEmpty()) throw EpubParseException.NoContent()
        return result
    }

    /**
     * 按 OPF 目录解析 spine href 对应的 zip 条目。
     * 精确路径优先，避免 `endsWith("1.xhtml")` 误匹配 `ch11.xhtml` 这类后缀重叠。
     */
    private fun resolveEntry(zip: ZipFile, opfDir: String, href: String, entryNames: List<ZipEntry>): ZipEntry? {
        if (opfDir.isNotEmpty()) {
            zip.getEntry("$opfDir/$href")?.let { return it }
        }
        zip.getEntry(href)?.let { return it }
        // 兜底：仅按完整路径段后缀匹配（要求前置 '/'，杜绝子串误配）。
        // 命中条目数有限（≤MAX_ZIP_ENTRIES），每轮扫描至多探测 MAX_SUFFIX_PROBES
        // 次，防止恶意 href 让兜底分支反复整表扫描（issue 10.3）。
        var probes = 0
        for (it in entryNames) {
            if (it.name.endsWith("/$href")) return it
            if (++probes >= MAX_SUFFIX_PROBES) break
        }
        return null
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

    /** spine 引用：idref + 是否正文（linear，缺省 yes）。 */
    private data class SpineRef(val idref: String, val linear: Boolean)

    /**
     * 提取 spine 引用。先匹配整个 `<itemref>` 标签再在标签内取属性，
     * 属性顺序不敏感（`<itemref linear="no" idref="ch1"/>` 也合法），
     * 同时容忍单/双引号与等号两侧空白。
     * linear="no" 的条目（封面/目录/版权页）不再被当正文读入（issue 9.4）。
     */
    private fun extractSpineRefs(opfContent: String): List<SpineRef> {
        val tagRegex = Regex("<itemref\\b[^>]*>", RegexOption.IGNORE_CASE)
        val idRegex = Regex("idref\\s*=\\s*[\"']([^\"']+)[\"']", RegexOption.IGNORE_CASE)
        val linearRegex = Regex("linear\\s*=\\s*[\"']([^\"']+)[\"']", RegexOption.IGNORE_CASE)
        return tagRegex.findAll(opfContent).mapNotNull { tag ->
            val id = idRegex.find(tag.value)?.groupValues?.get(1) ?: return@mapNotNull null
            val linear = linearRegex.find(tag.value)?.groupValues?.get(1)?.lowercase() ?: "yes"
            SpineRef(idref = id, linear = linear != "no")
        }.toList()
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
            .filter { it.length > 3 }   // 阈值从 10 降到 3：章标题"Chapter 1"等短段不再被吞（issue 10.8）
    }
}
