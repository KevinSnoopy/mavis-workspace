@file:Suppress("SwallowedException", "ReturnCount")

package com.eareyereading.util

import java.io.InputStream
import java.nio.charset.Charset
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipFile

/**
 * ZIP 条目低层读取职责（从 EpubParser 拆出，SRP）：
 *  - 有上限地读取条目文本（防压缩炸弹）
 *  - 探测章节/OPF 编码（BOM / meta charset / xml encoding）
 *  - 按 OPF 目录解析 spine href 对应的 zip 条目（精确优先 + 受限后缀兜底）
 *  - 定位 OPF 描述文件（container.xml 优先，first .opf 兜底）
 *  - URL 解码 spine href
 *
 * 所有正则预编译、常量集中于此，供 [EpubParser] / [EpubCoverExtractor] / [EpubContentExtractor] 复用。
 */
internal object EpubZipReader {

    /** 单个文档（OPF / 章节 HTML）读取上限，防压缩炸弹撑爆内存。 */
    const val MAX_DOC_CHARS = 2_000_000

    /** spine 条目数上限，与字符上限双重保险。 */
    const val MAX_SPINE_ITEMS = 20_000

    /** zip 条目总数上限（issue 10.3）。 */
    const val MAX_ZIP_ENTRIES = 5_000

    /** resolveEntry 无精确命中时的兜底后缀匹配次数上限（issue 10.3）。 */
    const val MAX_SUFFIX_PROBES = 200

    // ── 编码探测正则 ──
    private val META_CHARSET = Regex(
        "<meta[^>]+charset\\s*=\\s*[\"']?([a-zA-Z0-9_\\-]+)",
        RegexOption.IGNORE_CASE,
    )
    private val CHARSET_ATTR = Regex("""charset\s*=\s*["']?([a-zA-Z0-9_\-]+)["']?""", RegexOption.IGNORE_CASE)
    private val XML_ENCODING = Regex("""<\?xml[^>]*encoding\s*=\s*["']([^"']+)["']""", RegexOption.IGNORE_CASE)

    // ── container.xml 正则 ──
    private val CONTAINER_FULLPATH_1 = Regex(
        "<rootfile\\b[^>]*full-path\\s*=\\s*[\"']([^\"']+)[\"'][^>]*/?>",
        RegexOption.IGNORE_CASE,
    )
    private val CONTAINER_FULLPATH_2 = Regex(
        "<rootfile\\b[^>]*>[\\s\\S]*?full-path\\s*=\\s*[\"']([^\"']+)[\"'][^>]*>",
        RegexOption.IGNORE_CASE,
    )

    /**
     * 有上限地读取 zip 条目文本。
     * 先按字节读（上限按字符上限 × 3 放宽，覆盖 UTF-8 多字节），
     * 再探测编码解码（issue 9.6）。
     */
    fun readEntryTextCapped(input: InputStream, maxChars: Int): String {
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
     */
    fun detectCharset(bytes: ByteArray): Charset {
        if (bytes.size >= 3 &&
            bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte()
        ) return Charsets.UTF_8
        if (bytes.size >= 2 && bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte()) {
            return Charset.forName("UTF-16BE")
        }
        if (bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte()) {
            return Charset.forName("UTF-16LE")
        }
        val head = String(bytes.copyOfRange(0, minOf(bytes.size, 1024)), Charsets.ISO_8859_1)
        META_CHARSET.find(head)?.let { return parseCharset(it.groupValues[1]) }
        CHARSET_ATTR.find(head)?.let { return parseCharset(it.groupValues[1]) }
        XML_ENCODING.find(head)?.let { return parseCharset(it.groupValues[1]) }
        return Charsets.UTF_8
    }

    private fun parseCharset(name: String): Charset {
        return try {
            Charset.forName(name.trim().lowercase(Locale.ROOT))
        } catch (_: Exception) {
            Charsets.UTF_8
        }
    }

    /** URL 解码 spine href（`ch%201.xhtml` 类文件名）；失败回退原串。 */
    fun decodeHref(href: String): String {
        if (!href.contains('%')) return href
        return try {
            java.net.URLDecoder.decode(href, "UTF-8")
        } catch (_: Exception) {
            href
        }
    }

    /**
     * 按 OPF 目录解析 spine href 对应的 zip 条目。
     * 精确路径优先，避免 `endsWith("1.xhtml")` 误匹配 `ch11.xhtml` 这类后缀重叠。
     */
    fun resolveEntry(zip: ZipFile, opfDir: String, href: String, entryNames: List<ZipEntry>): ZipEntry? {
        if (opfDir.isNotEmpty()) {
            zip.getEntry("$opfDir/$href")?.let { return it }
        }
        zip.getEntry(href)?.let { return it }
        // 兜底：仅按完整路径段后缀匹配（要求前置 '/'，杜绝子串误配）。
        var probes = 0
        for (it in entryNames) {
            if (it.name.endsWith("/$href")) return it
            if (++probes >= MAX_SUFFIX_PROBES) break
        }
        return null
    }

    /**
     * 定位 OPF 描述文件（issue 9.5）：
     * 1. 优先读 `META-INF/container.xml`，按 `<rootfile full-path="...">` 找真实 OPF；
     * 2. 读不到/解析不到时回退到 `first .opf` 启发式并打 WARN。
     */
    fun findOpfEntry(entryNames: List<ZipEntry>, zip: ZipFile): ZipEntry? {
        val containerEntry = entryNames.firstOrNull {
            it.name.equals("META-INF/container.xml", ignoreCase = true)
        }
        if (containerEntry != null) {
            try {
                val text = readEntryTextCapped(zip.getInputStream(containerEntry), 4096)
                val fullPath = CONTAINER_FULLPATH_1.find(text)?.groupValues?.get(1)?.trim()
                    ?: CONTAINER_FULLPATH_2.find(text)?.groupValues?.get(1)?.trim()
                if (!fullPath.isNullOrBlank()) {
                    val normalized = fullPath.removePrefix("/")
                    entryNames.firstOrNull { it.name == normalized }?.let { return it }
                }
            } catch (e: Exception) {
                android.util.Log.w("EpubParser", "Failed to parse META-INF/container.xml", e)
            }
        }
        val fallback = entryNames.firstOrNull { it.name.endsWith(".opf") }
        if (containerEntry != null) {
            android.util.Log.w(
                "EpubParser",
                "container.xml present but full-path unresolved; falling back to first .opf heuristic",
            )
        }
        return fallback
    }
}
