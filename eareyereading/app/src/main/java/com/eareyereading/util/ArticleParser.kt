@file:Suppress("TooGenericExceptionCaught", "ReturnCount")

package com.eareyereading.util

import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 文章解析器
 * 支持从 URL 抓取英文文章，自动提取正文内容
 */
@Singleton
class ArticleParser @Inject constructor() {

    companion object {
        // HTML 标签清理正则
        private val SCRIPT_TAG = Regex("<script[^>]*>.*?</script>", RegexOption.DOT_MATCHES_ALL)
        private val STYLE_TAG = Regex("<style[^>]*>.*?</style>", RegexOption.DOT_MATCHES_ALL)
        private val COMMENT_TAG = Regex("<!--.*?-->", RegexOption.DOT_MATCHES_ALL)
        private val HTML_TAG = Regex("<[^>]+>")
    }

    /**
     * 从 URL 抓取文章
     * @param urlStr 文章 URL
     * @return Pair(标题, 正文段落列表)，失败返回 null
     */
    fun parseFromUrl(urlStr: String): ArticleResult? {
        val url = URL(urlStr)
        val conn = url.openConnection() as HttpURLConnection
        conn.apply {
            requestMethod = "GET"
            setRequestProperty("User-Agent", "Mozilla/5.0 (compatible; EareyeReader/1.0)")
            setRequestProperty("Accept", "text/html,application/xhtml+xml")
            connectTimeout = 15000
            readTimeout = 15000
        }

        return try {
            conn.use {
                val charset = detectCharset(conn) ?: "UTF-8"
                val html = BufferedReader(InputStreamReader(conn.inputStream, charset)).readText()
                extractArticle(html)
            }
        } catch (e: java.net.MalformedURLException) {
            android.util.Log.e("ArticleParser", "Invalid URL: ${urlStr}", e)
            null
        } catch (e: java.net.SocketTimeoutException) {
            android.util.Log.e("ArticleParser", "Connection timeout for URL: ${urlStr}", e)
            null
        } catch (e: java.io.IOException) {
            android.util.Log.e("ArticleParser", "IO error fetching article: ${urlStr}", e)
            null
        }
    }

    /**
     * 检测网页编码
     */
    private fun detectCharset(conn: HttpURLConnection): String? {
        conn.getHeaderField("Content-Type")?.let { ct ->
            Regex("charset=([^;\\s]+)").find(ct)?.let { return it.groupValues[1] }
        }
        return null
    }

    /**
     * 从 HTML 中提取文章内容
     */
    private fun extractArticle(html: String): ArticleResult {
        // 清理脚本和样式
        var text = html
            .replace(SCRIPT_TAG, "")
            .replace(STYLE_TAG, "")
            .replace(COMMENT_TAG, "")

        // 提取标题
        val title = extractTitle(text)

        // 提取正文（多种策略）
        val content = extractContent(text)

        return ArticleResult(title = title, paragraphs = content)
    }

    /**
     * 提取文章标题
     */
    private fun extractTitle(html: String): String {
        // og:title
        Regex("""og:title["\s]+content=["']([^"']+)["']""").find(html)?.let { return it.groupValues[1].trim() }
        Regex("""<meta[^>]+property=["']og:title["'][^>]+content=["']([^"']+)["']""").find(html)?.let { return it.groupValues[1].trim() }

        // <title> tag
        Regex("""<title[^>]*>([^<]+)</title>""").find(html)?.let { return it.groupValues[1].trim() }

        // h1
        Regex("""<h1[^>]*>([^<]+)</h1>""").find(html)?.let { return it.groupValues[1].trim() }

        return "Untitled Article"
    }

    /**
     * 提取正文内容（多种策略依次尝试）
     */
    private fun extractContent(html: String): List<String> {
        // 策略1: article tag
        Regex("""<article[^>]*>(.*?)</article>""", RegexOption.DOT_MATCHES_ALL).find(html)?.let {
            return cleanText(it.groupValues[1])
        }

        // 策略2: main tag
        Regex("""<main[^>]*>(.*?)</main>""", RegexOption.DOT_MATCHES_ALL).find(html)?.let {
            return cleanText(it.groupValues[1])
        }

        // 策略3: JSON-LD structured data
        Regex(""""articleBody"\s*:\s*"([^"]{100,})"""", RegexOption.DOT_MATCHES_ALL).find(html)?.let {
            val raw = it.groupValues[1].replace("\\\"", "\"")
            return raw.split(Regex("(?<=[.!?])\\s+")).filter { s -> s.length > 20 }.map { it.trim() }
        }

        // 策略4: 找最大的文本块（content div）
        val candidates = listOf(
            Regex("""<div[^>]+class=["'][^"']*(?:content|article|body|text|story|entry)[^"']*["'][^>]*>(.*?)</div>""", RegexOption.DOT_MATCHES_ALL),
            Regex("""<section[^>]+class=["'][^"']*(?:content|article|body|text|story|entry)[^"']*["'][^>]*>(.*?)</section>""", RegexOption.DOT_MATCHES_ALL),
        )
        for (regex in candidates) {
            regex.findAll(html).maxByOrNull { match -> match.value.length }?.let { match ->
                val cleaned = cleanText(match.groupValues[1])
                if (cleaned.sumOf { it.length } > 200) return cleaned
            }
        }

        // 策略5: body 正文
        Regex("""<body[^>]*>(.*?)</body>""", RegexOption.DOT_MATCHES_ALL).find(html)?.let {
            val cleaned = cleanText(it.groupValues[1])
            if (cleaned.sumOf { s -> s.length } > 100) return cleaned
        }

        return cleanText(html)
    }

    /**
     * 清理 HTML 标签，提取纯文本并分段
     */
    private fun cleanText(html: String): List<String> {
        val text = html
            .replace(HTML_TAG, " ")       // 移除 HTML 标签
            .replace("&nbsp;", " ")        // 替换不换行空格
            .replace("&amp;", "&")         // 替换 &
            .replace("&lt;", "<")           // 替换 <
            .replace("&gt;", ">")           // 替换 >
            .replace("&quot;", "\"")        // 替换 "
            .replace("&#39;", "'")          // 替换 '
            .replace("&mdash;", "—")        // 替换破折号
            .replace("&ndash;", "–")
            .replace("&hellip;", "…")
            .replace("&hellip", "…")
            .replace("\\s+".toRegex(), " ") // 压缩空格
            .trim()

        // 按句子分段
        val sentences = text.split(Regex("(?<=[.!?])\\s+(?=[A-Z])"))
            .map { it.trim() }
            .filter { it.length > 30 && it.any { c -> c.isLetter() } }

        // 合并成段落（每3-5句一段）
        val paragraphs = mutableListOf<String>()
        for (i in sentences.indices step 4) {
            val end = minOf(i + 4, sentences.size)
            val para = sentences.subList(i, end).joinToString(" ")
            paragraphs.add(para)
        }

        return paragraphs.ifEmpty { listOf(text) }
    }
}

data class ArticleResult(
    val title: String,
    val paragraphs: List<String>,
)
