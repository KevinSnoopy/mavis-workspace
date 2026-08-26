@file:Suppress("SwallowedException", "ReturnCount")

package com.eareyereading.util

import java.io.File
import java.util.zip.ZipFile
import javax.inject.Inject
import javax.inject.Singleton

/**
 * EPUB 文件解析器
 * 解析 EPUB 文件并提取段落文本
 */
@Singleton
class EpubParser @Inject constructor() {

    /**
     * 解析 EPUB 文件，返回段落列表
     */
    fun parseBook(filePath: String): List<String> {
        return try {
            parseEpub(File(filePath))
        } catch (e: java.io.IOException) {
            android.util.Log.e("EpubParser", "Error reading EPUB file: ${filePath}", e)
            getSampleParagraphs()
        } catch (e: java.util.zip.ZipException) {
            android.util.Log.e("EpubParser", "Invalid EPUB file: ${filePath}", e)
            getSampleParagraphs()
        }
    }

    /**
     * 解析 EPUB 文件
     */
    private fun parseEpub(file: File): List<String> {
        if (!file.exists()) return getSampleParagraphs()

        val paragraphs = mutableListOf<String>()
        ZipFile(file).use { zip ->
            // 找到 OPF 文件
            val opfEntry = zip.entries().asSequence()
                .filter { it.name.endsWith(".opf") }
                .firstOrNull() ?: return getSampleParagraphs()

            // 解析 OPF 获取 spine 顺序
            val opfContent = zip.getInputStream(opfEntry).bufferedReader().readText()
            val spineIds = extractSpineIds(opfContent)
            val manifestItems = extractManifestItems(opfContent)

            // 按 spine 顺序读取每个 HTML 文件
            for (id in spineIds) {
                val href = manifestItems[id] ?: continue
                val entry = zip.entries().asSequence()
                    .filter { it.name.endsWith(href) || it.name.endsWith("$href.xhtml") || it.name.endsWith("$href.html") }
                    .firstOrNull() ?: continue

                val html = zip.getInputStream(entry).bufferedReader().readText()
                paragraphs.addAll(extractParagraphsFromHtml(html))
            }
        }

        return paragraphs.filter { it.isNotBlank() }
    }

    private fun extractSpineIds(opfContent: String): List<String> {
        val regex = Regex("<itemref\\s+idref=\"([^\"]+)\"")
        return regex.findAll(opfContent).map { it.groupValues[1] }.toList()
    }

    private fun extractManifestItems(opfContent: String): Map<String, String> {
        val result = mutableMapOf<String, String>()
        val regex = Regex("<item\\s+[^>]*id=\"([^\"]+)\"[^>]*href=\"([^\"]+)\"[^>]*/?>")
        regex.findAll(opfContent).forEach { match ->
            result[match.groupValues[1]] = match.groupValues[2]
        }
        // 也匹配 href 在 id 之前的情况
        val regex2 = Regex("<item\\s+[^>]*href=\"([^\"]+)\"[^>]*id=\"([^\"]+)\"[^>]*/?>")
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

        // 移除所有 HTML 标签
        text = text.replace(Regex("<[^>]+>"), " ")

        // 解码 HTML 实体
        text = decodeHtmlEntities(text)

        // 清理空白字符
        text = text.replace(Regex("\\s+"), " ").trim()

        // 按段落分割（两个以上换行符）
        return text.split(Regex("\\n\\s*\\n"))
            .map { it.trim() }
            .filter { it.length > 10 }
    }

    private fun decodeHtmlEntities(text: String): String {
        return text
            .replace("&nbsp;", " ")
            .replace("&amp;", "&")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&apos;", "'")
            .replace("&#39;", "'")
            .replace(Regex("&#(\\d+);")) { it.groupValues[1].toInt().toChar().toString() }
    }

    /**
     * 示例段落（当文件不存在时使用）
     */
    private fun getSampleParagraphs(): List<String> = listOf(
        "The quick brown fox jumps over the lazy dog. This pangram contains every letter of the English alphabet at least once.",
        "Reading is a gateway to knowledge and imagination. Through books, we can travel to distant lands, meet fascinating characters, and explore ideas that expand our understanding of the world.",
        "Effective reading requires both speed and comprehension. Skilled readers develop the ability to process text quickly while retaining key information and meaning.",
        "Building vocabulary is essential for reading fluency. Each new word learned opens additional pathways for understanding and communication.",
        "Practice makes progress. Consistent daily reading builds the neural connections that make fluent reading feel natural and effortless.",
    )
}
