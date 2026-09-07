@file:Suppress("SwallowedException", "ReturnCount")

package com.eareyereading.util

import java.io.File
import java.io.InputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipFile

/**
 * EPUB 封面图片提取职责（从 EpubParser 拆出，SRP）：
 *  - OPF manifest 中 properties="cover-image" 的条目（EPUB 3 标准）
 *  - `<meta name="cover" content="id">` 指向的 manifest 条目（EPUB 2 常见）
 *  - 兜底：manifest 中 id/href 含 "cover" 的图片条目
 *
 * 封面相关正则与常量集中于此。
 */
internal object EpubCoverExtractor {

    /** 封面图片字节上限：超过则放弃真实封面，回退生成式封面（防炸弹）。 */
    const val MAX_COVER_BYTES = 5L * 1024 * 1024

    private val COVER_IMAGE_ITEM = Regex(
        "<item\\b[^>]*properties\\s*=\\s*[\"'][^\"']*cover-image[^\"']*[\"'][^>]*>",
        RegexOption.IGNORE_CASE,
    )
    private val COVER_META_TAG = Regex(
        "<meta\\b[^>]*name\\s*=\\s*[\"']cover[\"'][^>]*>",
        RegexOption.IGNORE_CASE,
    )
    private val CONTENT_ATTR = Regex("content\\s*=\\s*[\"']([^\"']+)[\"']", RegexOption.IGNORE_CASE)
    private val HREF_ATTR = Regex("\\bhref\\s*=\\s*[\"']([^\"']+)[\"']", RegexOption.IGNORE_CASE)

    /** 封面图片扩展名（兜底匹配 manifest 条目用）。 */
    private val imgExtRegex = Regex("\\.(jpe?g|png|webp|gif|bmp)$", RegexOption.IGNORE_CASE)

    /**
     * 提取 EPUB 内嵌封面图片字节（导入时调用，落盘为书籍封面文件）。
     * 找不到、读取失败或超过 5MB 上限时返回 null，调用方回退生成式封面。
     */
    fun extractCoverImage(filePath: String): ByteArray? {
        val file = File(filePath)
        if (!file.exists() || file.length() == 0L) return null
        return try {
            ZipFile(file).use { zip ->
                val entryNames = zip.entries().toList()
                if (entryNames.size > EpubZipReader.MAX_ZIP_ENTRIES) return null
                val opfEntry = EpubZipReader.findOpfEntry(entryNames, zip) ?: return null
                val opfContent = EpubZipReader.readEntryTextCapped(zip.getInputStream(opfEntry), EpubZipReader.MAX_DOC_CHARS)
                val manifestItems = OpfParser.extractManifestItems(opfContent)
                val opfDir = opfEntry.name.substringBeforeLast('/', "")

                fun hrefToEntry(href: String): ZipEntry? {
                    val clean = href.substringBefore('#')
                    if (clean.isBlank()) return null
                    val decoded = EpubZipReader.decodeHref(clean)
                    return EpubZipReader.resolveEntry(zip, opfDir, decoded, entryNames)
                        ?: if (decoded != clean) EpubZipReader.resolveEntry(zip, opfDir, clean, entryNames) else null
                }

                // 1) EPUB 3：properties="cover-image"（先抓整个 item 标签再取 href，属性顺序无关）
                val coverHref = COVER_IMAGE_ITEM.findAll(opfContent)
                    .mapNotNull { tag ->
                        HREF_ATTR.find(tag.value)?.groupValues?.get(1)
                    }
                    .firstOrNull()

                // 2) EPUB 2：<meta name="cover" content="cover-id" />
                val metaCoverId = COVER_META_TAG.findAll(opfContent)
                    .mapNotNull { tag ->
                        CONTENT_ATTR.find(tag.value)?.groupValues?.get(1)
                    }
                    .firstOrNull()

                // 3) 兜底：id/href 含 "cover" 的图片条目
                val targetEntry = coverHref?.let { hrefToEntry(it) }
                    ?: metaCoverId?.let { manifestItems[it] }?.let { hrefToEntry(it) }
                    ?: manifestItems.entries.firstOrNull { (id, href) ->
                        (id.contains("cover", ignoreCase = true) ||
                            href.contains("cover", ignoreCase = true)) &&
                            imgExtRegex.containsMatchIn(href)
                    }?.value?.let { hrefToEntry(it) }

                targetEntry?.let { entry -> readCoverCapped(zip.getInputStream(entry)) }
            }
        } catch (e: Exception) {
            // 封面提取失败不影响导入主流程：静默回退生成式封面
            android.util.Log.w("EpubParser", "extract cover failed: $filePath", e)
            null
        }
    }

    /** 带上限读取封面字节：zip 条目 size 可能未知（-1），不能信头部声明。 */
    private fun readCoverCapped(input: InputStream): ByteArray? {
        input.use { ins ->
            val out = java.io.ByteArrayOutputStream()
            val buf = ByteArray(8192)
            var total = 0L
            while (true) {
                val n = ins.read(buf)
                if (n < 0) break
                total += n
                if (total > MAX_COVER_BYTES) return null
                out.write(buf, 0, n)
            }
            return if (total in 1..MAX_COVER_BYTES) out.toByteArray() else null
        }
    }
}
