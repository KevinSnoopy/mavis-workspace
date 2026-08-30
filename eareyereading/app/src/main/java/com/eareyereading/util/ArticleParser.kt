@file:Suppress("TooGenericExceptionCaught", "ReturnCount")

package com.eareyereading.util

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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

        // 网络请求参数
        private const val CONNECT_TIMEOUT_MS = 15000
        private const val READ_TIMEOUT_MS = 15000
        private const val USER_AGENT = "Mozilla/5.0 (compatible; EareyeReader/1.0)"
        private const val ACCEPT_HEADER = "text/html,application/xhtml+xml"
        private const val DEFAULT_CHARSET = "UTF-8"

        /** 页面正文读取上限（字符数），防止超大页面撑爆内存；超出部分直接截断。 */
        private const val MAX_HTML_CHARS = 5_000_000
    }

    /**
     * 从 URL 抓取文章（挂起函数，自动切换到 IO 调度器执行网络请求）
     * @param urlStr 文章 URL
     * @return Pair(标题, 正文段落列表)，失败返回 null
     */
    suspend fun parseFromUrl(urlStr: String): ArticleResult? = withContext(Dispatchers.IO) {
        try {
            val url = URL(urlStr)
            val conn = url.openConnection() as HttpURLConnection
            conn.apply {
                requestMethod = "GET"
                setRequestProperty("User-Agent", USER_AGENT)
                setRequestProperty("Accept", ACCEPT_HEADER)
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
            }

            try {
                val charset = detectCharset(conn) ?: DEFAULT_CHARSET
                val html = readHtmlCapped(BufferedReader(InputStreamReader(conn.inputStream, charset)))
                extractArticle(html)
            } finally {
                conn.disconnect()
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
     * 从页面提取文章链接（适用于列表页 / 首页）
     */
    suspend fun parseArticleLinks(urlStr: String): ArticleLinkResult? = withContext(Dispatchers.IO) {
        try {
            val url = URL(urlStr)
            val conn = url.openConnection() as HttpURLConnection
            conn.apply {
                requestMethod = "GET"
                setRequestProperty("User-Agent", USER_AGENT)
                setRequestProperty("Accept", ACCEPT_HEADER)
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
            }
            try {
                val charset = detectCharset(conn) ?: DEFAULT_CHARSET
                val html = readHtmlCapped(BufferedReader(InputStreamReader(conn.inputStream, charset)))
                extractLinksFromHtml(html, urlStr)
            } finally {
                conn.disconnect()
            }
        } catch (e: java.net.MalformedURLException) {
            android.util.Log.e("ArticleParser", "Invalid URL: ${urlStr}", e)
            null
        } catch (e: java.net.SocketTimeoutException) {
            android.util.Log.e("ArticleParser", "Timeout for URL: ${urlStr}", e)
            null
        } catch (e: java.io.IOException) {
            android.util.Log.e("ArticleParser", "IO error: ${urlStr}", e)
            null
        } catch (e: Exception) {
            android.util.Log.e("ArticleParser", "Unexpected error: ${urlStr}", e)
            null
        }
    }

    private fun extractLinksFromHtml(html: String, baseUrl: String): ArticleLinkResult? {
        val title = extractTitle(html)
        // 过滤规则：排除导航、登录、注册等非文章链接
        val excludePattern = Regex("""(login|sign[-]?in|sign[-]?up|register|about|contact|privacy|terms|category|tag|author|profile|feed|rss|xml|sitemap|css|js|png|jpg|gif|svg|ico|pdf|zip)""", RegexOption.IGNORE_CASE)

        val anchorRegex = Regex("""<a[^>]+href=["']([^"']+)["'][^>]*>([^<]+)</a>""", RegexOption.DOT_MATCHES_ALL)
        val seen = mutableSetOf<String>()
        val links = mutableListOf<ArticleLink>()

        for (match in anchorRegex.findAll(html)) {
            val href = match.groupValues[1].trim()
            val text = match.groupValues[2].trim()

            // 过滤：URL 必须有效、非排除项、文本有内容
            if (href.isBlank() || text.isBlank()) continue
            if (excludePattern.containsMatchIn(href) || excludePattern.containsMatchIn(text)) continue
            if (href.startsWith("#") || href.startsWith("javascript:")) continue
            if (seen.contains(href)) continue

            // 补全相对 URL：以列表页 URL 为 base 解析；解析失败时保留原值
            val absoluteUrl = if (href.startsWith("http")) {
                href
            } else {
                try {
                    java.net.URI(baseUrl).resolve(href).toString()
                } catch (_: Exception) {
                    href
                }
            }
            seen.add(href)

            links.add(ArticleLink(
                title = text.take(120),
                url = absoluteUrl,
            ))

            if (links.size >= 30) break
        }

        return if (links.isEmpty()) null
        else ArticleLinkResult(title = title, links = links)
    }

    /**
     * 检测网页编码
     */
    private fun detectCharset(conn: HttpURLConnection): String? {
        conn.getHeaderField("Content-Type")?.let { ct ->
            // charset="utf-8" 带引号是 RFC 合法写法，需去掉引号再交给 InputStreamReader
            Regex("charset=([^;\\s]+)").find(ct)?.let { return it.groupValues[1].trim('"', '\'') }
        }
        return null
    }

    /** 有上限地读取页面：超过 [MAX_HTML_CHARS] 截断，防止超大页面 OOM。 */
    private fun readHtmlCapped(reader: BufferedReader): String {
        reader.use { r ->
            val sb = StringBuilder()
            val buf = CharArray(8192)
            while (sb.length < MAX_HTML_CHARS) {
                val n = r.read(buf)
                if (n < 0) break
                sb.append(buf, 0, minOf(n, MAX_HTML_CHARS - sb.length))
            }
            return sb.toString()
        }
    }

    /** 还原 JSON 字符串里最常见的转义序列（articleBody 场景）。 */
    private fun unescapeJson(s: String): String {
        val sb = StringBuilder(s.length)
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '\\' && i + 1 < s.length) {
                when (val next = s[i + 1]) {
                    '"', '\\', '/' -> { sb.append(next); i += 2 }
                    'n' -> { sb.append('\n'); i += 2 }
                    't' -> { sb.append('\t'); i += 2 }
                    'r' -> { sb.append('\r'); i += 2 }
                    'u' -> {
                        val hex = s.getOrNull(i + 2)?.toString() ?: ""
                        val hex4 = s.substring(i + 2, minOf(i + 6, s.length))
                        val code = hex4.toIntOrNull(16)
                        if (hex.isNotEmpty() && code != null && hex4.length == 4) {
                            sb.append(code.toChar()); i += 6
                        } else {
                            sb.append(c); i += 1
                        }
                    }
                    else -> { sb.append(c); i += 1 }
                }
            } else {
                sb.append(c); i += 1
            }
        }
        return sb.toString()
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
        // 匹配时跳过转义序列（\" 等），否则正文里第一个转义引号就会截断匹配
        Regex(""""articleBody"\s*:\s*"((?:\\.|[^"\\]){100,})"""", RegexOption.DOT_MATCHES_ALL).find(html)?.let {
            val raw = unescapeJson(it.groupValues[1])
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
        val text = HtmlEntities.decode(html.replace(HTML_TAG, " "))
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

data class ArticleLinkResult(
    val title: String,
    val links: List<ArticleLink>,
)

data class ArticleLink(
    val title: String,
    val url: String,
)
