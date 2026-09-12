@file:Suppress("SwallowedException")

package com.eareyereading.util

import java.util.zip.ZipEntry
import java.util.zip.ZipFile

/**
 * EPUB 目录文件解析（toc.ncx / EPUB3 nav.xhtml）。
 *
 * 输出"zip 条目名 → 章标题"的有序映射，供 [EpubContentExtractor] 在 spine
 * 遍历记录章起始段下标时取标题。只解析**顶级**章节（ncx 顶级 navPoint /
 * nav 顶级 li）：章内小节对本应用的段落级跳转没有意义，平铺反而产生重复标题。
 *
 * 所有正则预编译集中于此；解析失败静默返回空映射（目录缺失不阻断导入）。
 */
internal object EpubTocParser {

    /** manifest 里 EPUB2 目录文件的 media-type。 */
    private const val NCX_MEDIA_TYPE = "application/x-dtbncx+xml"

    /** navMap 块（不嵌套，非贪婪一次截取）。 */
    private val NAV_MAP_BLOCK = Regex("<navMap\\b[^>]*>([\\s\\S]*?)</navMap>", RegexOption.IGNORE_CASE)

    /** navPoint 开/闭标签 token（配对用深度计数取顶级块）。 */
    private val NAV_POINT_TOKEN = Regex("</?navPoint\\b[^>]*>", RegexOption.IGNORE_CASE)

    private val NAV_LABEL_TEXT = Regex(
        "<navLabel\\b[^>]*>[\\s\\S]*?<text\\b[^>]*>([\\s\\S]*?)</text>",
        RegexOption.IGNORE_CASE,
    )
    private val NAV_CONTENT_SRC = Regex(
        "<content\\b[^>]*\\bsrc\\s*=\\s*[\"']([^\"']+)[\"']",
        RegexOption.IGNORE_CASE,
    )

    /** EPUB3 nav 块（不嵌套）。 */
    private val NAV_BLOCK = Regex("<nav\\b[^>]*>([\\s\\S]*?)</nav>", RegexOption.IGNORE_CASE)
    private val NAV_TYPE_TOC = Regex("epub:type\\s*=\\s*[\"'][^\"']*\\btoc\\b", RegexOption.IGNORE_CASE)

    /** li 开/闭标签 token（顶级 li 深度计数）。 */
    private val LI_TOKEN = Regex("</?li\\b[^>]*>", RegexOption.IGNORE_CASE)
    private val A_TAG = Regex(
        "<a\\b[^>]*\\bhref\\s*=\\s*[\"']([^\"']+)[\"'][^>]*>([\\s\\S]*?)</a>",
        RegexOption.IGNORE_CASE,
    )

    private val NCX_ITEM = Regex("<item\\b[^>]*>", RegexOption.IGNORE_CASE)
    private val ITEM_MEDIA_TYPE = Regex("media-type\\s*=\\s*[\"']([^\"']+)[\"']", RegexOption.IGNORE_CASE)
    private val ITEM_PROPERTIES = Regex("properties\\s*=\\s*[\"']([^\"']+)[\"']", RegexOption.IGNORE_CASE)
    private val ANY_TAG = Regex("<[^>]+>")
    private val WHITESPACE = Regex("\\s+")

    /**
     * 解析目录文件，返回 `LinkedHashMap<zip 条目名, 标题>`（目录出现顺序）。
     * ncx 优先（EPUB2 规范产物、更稳定），缺失时用 EPUB3 nav.xhtml。
     * href 解析不到 zip 条目 / 标题为空的条目跳过；同条目重复出现取首个标题。
     */
    fun parseToc(
        zip: ZipFile,
        entryNames: List<ZipEntry>,
        opfContent: String,
        opfDir: String,
    ): Map<String, String> {
        val links = parseNcx(zip, entryNames, opfContent, opfDir)
            .ifEmpty { parseNav(zip, entryNames, opfContent, opfDir) }
        val result = LinkedHashMap<String, String>()
        for ((href, title) in links) {
            val cleanTitle = title.trim()
            if (cleanTitle.isEmpty()) continue
            val decoded = EpubZipReader.decodeHref(href.substringBefore('#')).trim()
            if (decoded.isEmpty()) continue
            val entry = EpubZipReader.resolveEntry(zip, opfDir, decoded, entryNames) ?: continue
            if (entry.name !in result) result[entry.name] = cleanTitle
        }
        return result
    }

    // ── EPUB2: toc.ncx ─────────────────────────────────────

    private fun parseNcx(
        zip: ZipFile,
        entryNames: List<ZipEntry>,
        opfContent: String,
        opfDir: String,
    ): List<Pair<String, String>> {
        val ncxHref = findManifestHref(
            opfContent,
            predicate = { mediaType, properties -> mediaType == NCX_MEDIA_TYPE },
        ) ?: return emptyList()
        val decoded = EpubZipReader.decodeHref(ncxHref)
        val ncxEntry = EpubZipReader.resolveEntry(zip, opfDir, decoded, entryNames)
            ?: (if (decoded != ncxHref) EpubZipReader.resolveEntry(zip, opfDir, ncxHref, entryNames) else null)
            ?: return emptyList()
        val ncxText = try {
            EpubZipReader.readEntryTextCapped(zip.getInputStream(ncxEntry), EpubZipReader.MAX_DOC_CHARS)
        } catch (_: Exception) {
            return emptyList()
        }
        val navMap = NAV_MAP_BLOCK.find(ncxText)?.groupValues?.get(1) ?: return emptyList()
        return topLevelBlocks(navMap, NAV_POINT_TOKEN).mapNotNull { block ->
            val href = NAV_CONTENT_SRC.find(block)?.groupValues?.get(1) ?: return@mapNotNull null
            val title = NAV_LABEL_TEXT.find(block)
                ?.groupValues?.get(1)
                ?.let { flatten(it) }
                ?: return@mapNotNull null
            href to title
        }
    }

    // ── EPUB3: nav.xhtml ───────────────────────────────────

    private fun parseNav(
        zip: ZipFile,
        entryNames: List<ZipEntry>,
        opfContent: String,
        opfDir: String,
    ): List<Pair<String, String>> {
        val navHref = findManifestHref(
            opfContent,
            predicate = { _, properties -> properties.split(Regex("\\s+")).any { it.equals("nav", true) } },
        ) ?: return emptyList()
        val decoded = EpubZipReader.decodeHref(navHref)
        val navEntry = EpubZipReader.resolveEntry(zip, opfDir, decoded, entryNames)
            ?: (if (decoded != navHref) EpubZipReader.resolveEntry(zip, opfDir, navHref, entryNames) else null)
            ?: return emptyList()
        val navText = try {
            EpubZipReader.readEntryTextCapped(zip.getInputStream(navEntry), EpubZipReader.MAX_DOC_CHARS)
        } catch (_: Exception) {
            return emptyList()
        }
        // 优先 epub:type="toc" 的 nav 块，回退第一个 nav
        val blocks = NAV_BLOCK.findAll(navText).toList()
        val navInner = blocks.firstOrNull { NAV_TYPE_TOC.containsMatchIn(it.value) }?.groupValues?.get(1)
            ?: blocks.firstOrNull()?.groupValues?.get(1)
            ?: return emptyList()
        return topLevelBlocks(navInner, LI_TOKEN).mapNotNull { block ->
            val a = A_TAG.find(block) ?: return@mapNotNull null
            a.groupValues[1] to flatten(a.groupValues[2])
        }
    }

    // ── 公共工具 ───────────────────────────────────────────

    /** manifest 里第一个满足条件的 item href。 */
    private fun findManifestHref(
        opfContent: String,
        predicate: (mediaType: String, properties: String) -> Boolean,
    ): String? {
        for (tag in NCX_ITEM.findAll(opfContent)) {
            val mediaType = ITEM_MEDIA_TYPE.find(tag.value)?.groupValues?.get(1).orEmpty()
            val properties = ITEM_PROPERTIES.find(tag.value)?.groupValues?.get(1).orEmpty()
            if (!predicate(mediaType, properties)) continue
            val href = OpfParser.extractManifestItems(tag.value).values.firstOrNull()
            if (!href.isNullOrBlank()) return href
        }
        return null
    }

    /**
     * 按开/闭 token 深度计数提取**顶级**完整块（含标签本身）。
     * 嵌套子块留给父块整体返回，调用方只在父块里取首个标题/链接，
     * 天然实现"只取章级目录"。
     */
    private fun topLevelBlocks(text: String, token: Regex): List<String> {
        val blocks = mutableListOf<String>()
        var depth = 0
        var start = -1
        for (m in token.findAll(text)) {
            if (m.value.startsWith("</")) {
                depth--
                if (depth == 0 && start >= 0) {
                    blocks.add(text.substring(start, m.range.last + 1))
                    start = -1
                }
            } else {
                if (depth == 0) start = m.range.first
                depth++
            }
        }
        return blocks
    }

    /** 去标签 + 解实体 + 压缩空白。 */
    private fun flatten(html: String): String =
        html.replace(ANY_TAG, " ")
            .let { HtmlEntities.decode(it) }
            .replace(WHITESPACE, " ")
            .trim()
}
