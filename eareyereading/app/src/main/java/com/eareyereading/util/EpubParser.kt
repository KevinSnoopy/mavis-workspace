@file:Suppress("SwallowedException", "ReturnCount")

package com.eareyereading.util

import android.content.ContentResolver
import android.net.Uri
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

    /** 章节全是图片、没有任何可读文本段落（图册类 EPUB，issue 9.8）。 */
    class ImageOnly : EpubParseException("此书为图册，不支持导入")
}

/**
 * EPUB 解析结果（issue 9.1 / 9.2 / 9.8）：
 * 此前 [EpubParser.parseBook] 只返回段落列表，OPF 里的元数据
 * （标题/作者/语言）从不提取，导入书籍卡片标题 = 文件名、作者 = Unknown、
 * 语言 = en（永远），TTS 用英文声读中文/日文书。
 */
data class ParsedBook(
    /** OPF `<dc:title>`；缺失时为空串。 */
    val title: String = "",
    /** OPF `<dc:creator>`；缺失时为空串。 */
    val author: String = "",
    /** OPF `<dc:language>`（小写，截断到 8 字符）；缺失时为空串。 */
    val language: String = "",
    /** OPF `<dc:identifier>`（书籍唯一标识，跨导入去重用，issue 9.7）；缺失时为空串。 */
    val identifier: String = "",
    /** 按 spine 顺序提取的正文段落。 */
    val paragraphs: List<String> = emptyList(),
    /** 是否因 [EpubParser.MAX_TOTAL_CHARS] 上限被截断（issue 9.2）。 */
    val wasTruncated: Boolean = false,
    /** 截断前扫描到的原文累计字符数（仅截断时>0，issue 9.2）。 */
    val originalCharCount: Int = 0,
    /** 解析过程中见到的 `<img>` 数量（图册检测用，issue 9.8）。 */
    val images: Int = 0,
)

/** OPF 元数据载体（issue 9.1/9.7）。 */
private data class OpfMetadata(
    val title: String,
    val author: String,
    val language: String,
    val identifier: String,
)

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
     * 解析 EPUB 文件。
     * 解析失败抛 [EpubParseException]（IOException 子类），
     * 调用方可按异常类型给出可区分的失败提示。
     * 成功时返回 [ParsedBook]（含 OPF 元数据与段落，issue 9.1/9.2/9.8）。
     */
    fun parseBook(filePath: String): ParsedBook {
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
     * 统一读取代理（issue 9.9）：文件优先、content:// URI 兜底。
     * 存量书在换设备/清数据后本地拷贝可能失效（filePath 指向的文件不存在），
     * 此时回退用 sourceUri 经 ContentResolver 重新打开流读取，避免"书源丢失打不开"。
     * 文件存在时等价于 [parseBook] 的既有行为，不破坏存量书解析。
     * 解析失败抛 [EpubParseException]（IOException 子类），调用方可按类型区分。
     */
    fun parseBook(
        filePath: String,
        sourceUri: String?,
        resolver: ContentResolver,
    ): ParsedBook {
        val file = File(filePath)
        if (file.exists() && file.length() > 0L) {
            return parseBook(filePath)
        }
        if (!sourceUri.isNullOrBlank()) {
            val input = try {
                resolver.openInputStream(Uri.parse(sourceUri))
            } catch (e: java.io.IOException) {
                android.util.Log.w("EpubParser", "openInputStream failed for $sourceUri", e)
                throw EpubParseException.Empty()
            } catch (e: SecurityException) {
                android.util.Log.w("EpubParser", "No read permission for $sourceUri", e)
                throw EpubParseException.Empty()
            }
            if (input == null) {
                android.util.Log.w("EpubParser", "openInputStream returned null for $sourceUri")
                throw EpubParseException.Empty()
            }
            return parseFromInputStream(input, sourceUri)
        }
        throw EpubParseException.Empty()
    }

    /**
     * 从任意 InputStream 解析 EPUB（issue 9.9 URI 兜底用）。
     * ZipFile 需要随机访问，content:// 流不能直接喂给 ZipFile：
     * 先按字节上限拷到临时文件，再复用既有 [parseEpub] 逻辑，解析完即删。
     */
    private fun parseFromInputStream(input: java.io.InputStream, label: String): ParsedBook {
        val tempFile = try {
            File.createTempFile("epub_resolve_", ".epub")
        } catch (e: java.io.IOException) {
            throw EpubParseException.Corrupted()
        }
        try {
            var total = 0L
            tempFile.outputStream().use { output ->
                val buffer = ByteArray(8192)
                while (true) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    total += n
                    // 字节级防护（issue 10.4）：流来源同样受全局体积上限约束
                    if (total > MAX_EPUB_BYTES) {
                        android.util.Log.w(
                            "EpubParser",
                            "EPUB exceeds ${MAX_EPUB_BYTES / (1024 * 1024)}MB from $label",
                        )
                        throw EpubParseException.Corrupted()
                    }
                    output.write(buffer, 0, n)
                }
            }
            input.close()
            if (total == 0L) throw EpubParseException.Empty()
            return parseEpub(tempFile)
        } finally {
            tempFile.delete()
        }
    }

    /**
     * 解析 EPUB 文件
     */
    private fun parseEpub(file: File): ParsedBook {
        val paragraphs = mutableListOf<String>()
        var totalChars = 0
        var originalTotalChars = 0
        var truncated = false
        var imageCount = 0
        // OPF 元数据在 zip 块内解析，ParsedBook 在块外组装，须提升作用域
        var metadata = OpfMetadata("", "", "", "")
        ZipFile(file).use { zip ->
            // 一次性构建条目索引：防恶意 EPUB 在 OPF 里塞海量 itemref，让
            // resolveEntry 兜底分支退化成 O(spine × entries) 算法炸弹（issue 10.3）
            val entryNames = zip.entries().toList()
            if (entryNames.size > MAX_ZIP_ENTRIES) {
                android.util.Log.w("EpubParser", "EPUB has ${entryNames.size} entries, exceeding $MAX_ZIP_ENTRIES")
                throw EpubParseException.Corrupted()
            }

            // issue 9.5：优先读 META-INF/container.xml 里声明的 OPF 路径，
            // 只有读不到/解析不到时才回退到"第一个 .opf"启发式（并打 WARN）
            val opfEntry = findOpfEntry(entryNames, zip) ?: throw EpubParseException.NoOpf()

            // 解析 OPF 获取 spine 顺序 + 元数据
            val opfContent = readEntryTextCapped(zip.getInputStream(opfEntry), MAX_DOC_CHARS)
            metadata = extractMetadata(opfContent)
            val spineRefs = extractSpineRefs(opfContent)
            // issue 10.9：manifest 双正则合并成单遍 LinkedHashMap（先到者胜，
            // 属性顺序不敏感），不再可能出现"id 与 href 错位配对"
            val manifestItems = extractManifestItems(opfContent)
            // spine href 是相对 OPF 所在目录的路径
            val opfDir = opfEntry.name.substringBeforeLast('/', "")

            // 按 spine 顺序读取每个 HTML 文件
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

                val html = readEntryTextCapped(zip.getInputStream(entry), MAX_DOC_CHARS)
                // issue 9.8：统计章节内 <img> 数量（图册检测）
                imageCount += Regex("<img\\b", RegexOption.IGNORE_CASE).findAll(html).count()
                val (entryParagraphs, _) = extractParagraphsFromHtml(html)
                for (para in entryParagraphs) {
                    // 原文累计：含被截断的段落，表示扫描到的原文规模（issue 9.2 提示用）
                    originalTotalChars += para.length
                    if (totalChars + para.length > MAX_TOTAL_CHARS) {
                        truncated = true
                        break@outer
                    }
                    paragraphs.add(para)
                    totalChars += para.length
                }
            }
        }

        val result = paragraphs.filter { it.isNotBlank() }
        // issue 9.8：没有可读段落但全是图片 → 图册，给明确错误而非笼统 NoContent
        if (result.isEmpty() && imageCount > 0) throw EpubParseException.ImageOnly()
        if (result.isEmpty()) throw EpubParseException.NoContent()
        return ParsedBook(
            title = metadata.title,
            author = metadata.author,
            language = metadata.language,
            identifier = metadata.identifier,
            paragraphs = result,
            wasTruncated = truncated,
            originalCharCount = if (truncated) originalTotalChars else 0,
            images = imageCount,
        )
    }

    /**
     * 定位 OPF 描述文件（issue 9.5）：
     * 1. 优先读 `META-INF/container.xml`，按 `<rootfile full-path="...">` 找真实 OPF；
     * 2. 读不到/解析不到时回退到 `first .opf` 启发式并打 WARN。
     */
    private fun findOpfEntry(entryNames: List<ZipEntry>, zip: ZipFile): ZipEntry? {
        val containerEntry = entryNames.firstOrNull {
            it.name.equals("META-INF/container.xml", ignoreCase = true)
        }
        if (containerEntry != null) {
            try {
                val text = readEntryTextCapped(zip.getInputStream(containerEntry), 4096)
                // 双正则处理属性顺序：full-path 可能出现在 rootfile 标签任意位置
                val fullPath = Regex(
                    "<rootfile\\b[^>]*full-path\\s*=\\s*[\"']([^\"']+)[\"'][^>]*/?>",
                    RegexOption.IGNORE_CASE,
                ).find(text)?.groupValues?.get(1)?.trim()
                    ?: Regex(
                        "<rootfile\\b[^>]*>[\\s\\S]*?full-path\\s*=\\s*[\"']([^\"']+)[\"'][^>]*>",
                        RegexOption.IGNORE_CASE,
                    ).find(text)?.groupValues?.get(1)?.trim()
                if (!fullPath.isNullOrBlank()) {
                    // container 里的路径是 zip 根下的绝对路径（不含前导 '/'）
                    val normalized = fullPath.removePrefix("/")
                    entryNames.firstOrNull { it.name == normalized }?.let { return it }
                }
            } catch (e: Exception) {
                android.util.Log.w("EpubParser", "Failed to parse META-INF/container.xml", e)
            }
        }
        // 回退：first .opf 启发式（只在该目录确实没有 container.xml 或其中路径解析失败时）
        val fallback = entryNames.firstOrNull { it.name.endsWith(".opf") }
        if (containerEntry != null) {
            android.util.Log.w(
                "EpubParser",
                "container.xml present but full-path unresolved; falling back to first .opf heuristic",
            )
        }
        return fallback
    }

    /**
     * 提取 OPF 元数据（issue 9.1）：`<dc:title>` / `<dc:creator>` / `<dc:language>`。
     * 部分 OPF 用 dcterms: 前缀或 dc:title 内嵌 span 标签，一并容错。
     */
    private fun extractMetadata(opfContent: String): OpfMetadata {
        fun dcValue(tag: String): String {
            val regex = Regex(
                "<(?:dc|dcterms):$tag\\b[^>]*>([\\s\\S]*?)</(?:dc|dcterms):$tag>",
                RegexOption.IGNORE_CASE,
            )
            return regex.find(opfContent)?.groupValues?.get(1)
                ?.replace(Regex("<[^>]+>"), "") // 某些 OPF 的 dc:title 内嵌 <span> 等
                ?.replace(Regex("\\s+"), " ")
                ?.trim()
                .orEmpty()
        }
        val title = dcValue("title")
        val author = dcValue("creator")
        val language = dcValue("language").lowercase(java.util.Locale.ROOT).take(8)
        // issue 9.7：OPF 的 <dc:identifier> 是书籍唯一标识（如 urn:uuid:... / ISBN）。
        // 多个 OPF 可能声明多个 identifier，取第一个非空即可
        val identifier = dcValue("identifier")
        return OpfMetadata(title, author, language, identifier)
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

    /**
     * 有上限地读取 zip 条目文本。
     * 先按字节读（上限按字符上限 × 3 放宽，覆盖 UTF-8 多字节），
     * 再探测编码解码（issue 9.6）——旧实现用 bufferedReader() 默认 UTF-8，
     * GBK/Big5/Shift-JIS 编码的章节会整章乱码或正文全丢。
     */
    private fun readEntryTextCapped(input: java.io.InputStream, maxChars: Int): String {
        input.use { ins ->
            val out = java.io.ByteArrayOutputStream()
            val buf = ByteArray(8192)
            var total = 0
            val maxBytes = maxChars * 3
            while (total < maxBytes) {
                val n = ins.read(buf)
                if (n < 0) break
                total += n
                out.write(buf, 0, n)
            }
            val bytes = out.toByteArray()
            // 截断可能在多字节字符中间：解码后按字符上限再收敛
            return String(bytes, detectCharset(bytes)).take(maxChars)
        }
    }

    /**
     * 探测章节/OPF 编码（issue 9.6）：
     * BOM 优先，其次 `<meta charset=...>` / `<?xml encoding=...?>` 声明，
     * 都没有时默认 UTF-8（EPUB 规范强制 UTF-8/UTF-16）。
     * 头部探测用 ISO-8859-1 单字节解码，保证不破坏原始字节序。
     */
    private fun detectCharset(bytes: ByteArray): java.nio.charset.Charset {
        if (bytes.size >= 3 &&
            bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte()
        ) return Charsets.UTF_8
        if (bytes.size >= 2 && bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte()) {
            return java.nio.charset.Charset.forName("UTF-16BE")
        }
        if (bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte()) {
            return java.nio.charset.Charset.forName("UTF-16LE")
        }
        val head = String(bytes.copyOfRange(0, minOf(bytes.size, 1024)), Charsets.ISO_8859_1)
        // <meta charset="gbk">（HTML5 简写）
        Regex("<meta[^>]+charset\\s*=\\s*[\"']?([a-zA-Z0-9_\\-]+)", RegexOption.IGNORE_CASE)
            .find(head)?.let { return parseCharset(it.groupValues[1]) }
        // <meta http-equiv="Content-Type" content="text/html; charset=gbk">
        Regex("""charset\s*=\s*["']?([a-zA-Z0-9_\-]+)["']?""", RegexOption.IGNORE_CASE)
            .find(head)?.let { return parseCharset(it.groupValues[1]) }
        // <?xml version="1.0" encoding="utf-8"?>
        Regex("""<\?xml[^>]*encoding\s*=\s*["']([^"']+)["']""", RegexOption.IGNORE_CASE)
            .find(head)?.let { return parseCharset(it.groupValues[1]) }
        return Charsets.UTF_8
    }

    private fun parseCharset(name: String): java.nio.charset.Charset {
        return try {
            java.nio.charset.Charset.forName(name.trim().lowercase(java.util.Locale.ROOT))
        } catch (_: Exception) {
            Charsets.UTF_8
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

    /**
     * 提取 manifest：id → href。
     * issue 10.9：旧实现两条正则分别抓 "id 在前 href 在后" / "href 在前 id 在后"，
     * 结果合并进可变 Map——同 id 被两条正则各写一次时，后者覆盖前者，
     * 且属性间距异常（href 与 id 之间再夹别的属性）时正则会漏配错配。
     * 改为单遍：先匹配整个 `<item ...>` 标签，再在标签内独立取 id 与 href，
     * 属性顺序完全无关；同 id 先到者胜（LinkedHashMap 语义）。
     */
    private fun extractManifestItems(opfContent: String): Map<String, String> {
        val result = LinkedHashMap<String, String>()
        val tagRegex = Regex("<item\\b[^>]*?>", RegexOption.IGNORE_CASE)
        val idRegex = Regex("\\bid\\s*=\\s*[\"']([^\"']+)[\"']", RegexOption.IGNORE_CASE)
        val hrefRegex = Regex("\\bhref\\s*=\\s*[\"']([^\"']+)[\"']", RegexOption.IGNORE_CASE)
        for (tag in tagRegex.findAll(opfContent)) {
            val id = idRegex.find(tag.value)?.groupValues?.get(1) ?: continue
            val href = hrefRegex.find(tag.value)?.groupValues?.get(1) ?: continue
            if (id !in result) result[id] = href
        }
        return result
    }

    /**
     * 从 HTML 中提取段落文本。
     * @return Pair(段落列表, 该章节 <img> 数量)（issue 9.8）
     */
    private fun extractParagraphsFromHtml(html: String): Pair<List<String>, Int> {
        // 移除脚本和样式
        var text = html.replace(Regex("<script[^>]*>[\\s\\S]*?</script>"), "")
        text = text.replace(Regex("<style[^>]*>[\\s\\S]*?</style>"), "")
        val imageCount = Regex("<img\\b", RegexOption.IGNORE_CASE).findAll(html).count()

        // 优先按 <p> 标签分割段落（EPUB 最常见的段落标签）
        val pTagRegex = Regex("<p[^>]*>(.*?)</p>", RegexOption.DOT_MATCHES_ALL)
        val pParagraphs = pTagRegex.findAll(text).map { it.groupValues[1] }.toList()

        val rawParagraphs = if (pParagraphs.isNotEmpty()) {
            pParagraphs
        } else {
            // 回退：按 <br> 或块级标签分割
            text.split(Regex("<br\\s*/?>|</(?:div|section|article|p)>"))
        }

        val paragraphs = rawParagraphs
            .map { para ->
                // 移除剩余 HTML 标签
                var cleaned = para.replace(Regex("<[^>]+>"), " ")
                // 解码 HTML 实体（与 ArticleParser 共用同一实现，行为不分叉）
                cleaned = HtmlEntities.decode(cleaned)
                // 压缩空白并 trim
                cleaned.replace(Regex("\\s+"), " ").trim()
            }
            .filter { it.length > 3 }   // 阈值从 10 降到 3：章标题"Chapter 1"等短段不再被吞（issue 10.8）
        return paragraphs to imageCount
    }
}
