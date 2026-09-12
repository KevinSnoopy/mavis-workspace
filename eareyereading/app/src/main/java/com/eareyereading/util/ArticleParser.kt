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
 * 文章抓取器：从 URL 抓取英文文章页并交给解析器提取正文 / 链接。
 *
 * ── 重构说明（13 条软件设计原则）──
 * 本类原本 453 行，把"HTTP 抓取"与"HTML 解析策略"（近 240 行正则与提取逻辑）
 * 混在一起，违反 SRP——解析策略的任何调整都要改动网络层文件。现已按职责拆分：
 *   - [ArticleHtmlExtractor]：HTML → 标题 + 正文段落（纯函数，无网络）
 *   - [ArticleLinkExtractor]：列表页 → 文章链接列表（纯函数，无网络）
 *   - 本类：URL 合法性校验、HTTP 请求、编码探测、限量读取
 *
 * [extractArticle] 仍作为实例方法对外保留（单元测试直接覆盖解析路径），
 * 内部委托给 [ArticleHtmlExtractor]，故所有既有调用点与测试无需改动。
 */
@Singleton
class ArticleParser @Inject constructor() {

    companion object {
        private val CHARSET_REGEX = Regex("charset=([^;\\s]+)")

        /** 页面正文读取上限（字符数），防止超大页面撑爆内存；超出部分直接截断。 */
        private const val MAX_HTML_CHARS = 5_000_000

        /** 可接受的响应 Content-Type（issue 10.5）：缺失时为 null 放行（部分源不返回该头），
         * 命中非 HTML/文本类型时拒收，避免把图片/JSON 等二进制流当正文解析。 */
        private val ALLOWED_CONTENT_TYPES = listOf(
            "text/html", "text/plain",
            "application/xhtml", "application/xml",
        )

        // 网络请求参数
        private const val CONNECT_TIMEOUT_MS = 15000
        private const val READ_TIMEOUT_MS = 15000
        private const val USER_AGENT = "Mozilla/5.0 (compatible; EareyeReader/1.0)"
        private const val ACCEPT_HEADER = "text/html,application/xhtml+xml"
        private const val DEFAULT_CHARSET = "UTF-8"

        @JvmStatic
        fun isHtmlContentType(contentType: String?): Boolean {
            val ct = contentType?.substringBefore(';')?.trim()?.lowercase() ?: return true
            // 头缺失/为空：部分源不返回 Content-Type，放行做尽力解析
            if (ct.isBlank()) return true
            return ALLOWED_CONTENT_TYPES.any { ct.startsWith(it) }
        }
    }

    /**
     * 从 URL 抓取文章（挂起函数，自动切换到 IO 调度器执行网络请求）
     * @param urlStr 文章 URL
     * @return Pair(标题, 正文段落列表)，失败返回 null
     */
    suspend fun parseFromUrl(urlStr: String): ArticleResult? = withContext(Dispatchers.IO) {
        // issue 10.2：只接受 http/https。file:/data:/jar: 等 scheme 的
        // openConnection() 返回非 HttpURLConnection，强转直接 ClassCastException
        if (!urlStr.startsWith("http://") && !urlStr.startsWith("https://")) {
            android.util.Log.w("ArticleParser", "Unsupported URL scheme, reject: $urlStr")
            return@withContext null
        }
        try {
            val conn = openHtmlConnection(urlStr)
            if (conn == null) return@withContext null
            try {
                val charset = detectCharset(conn) ?: DEFAULT_CHARSET
                val html = readHtmlCapped(BufferedReader(InputStreamReader(conn.inputStream, charset)))
                ArticleHtmlExtractor.extractArticle(html, urlStr)
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
        } catch (e: java.lang.ClassCastException) {
            // issue 10.2 兜底：非 http URL 的连接实现强转失败不再崩调用方
            android.util.Log.e("ArticleParser", "Unexpected connection type for URL: ${urlStr}", e)
            null
        }
    }

    /**
     * 从页面提取文章链接（适用于列表页 / 首页）
     */
    suspend fun parseArticleLinks(urlStr: String): ArticleLinkResult? = withContext(Dispatchers.IO) {
        // issue 10.2：与 parseFromUrl 同款 scheme 白名单
        if (!urlStr.startsWith("http://") && !urlStr.startsWith("https://")) {
            android.util.Log.w("ArticleParser", "Unsupported URL scheme, reject: $urlStr")
            return@withContext null
        }
        try {
            val conn = openHtmlConnection(urlStr)
            if (conn == null) return@withContext null
            try {
                val charset = detectCharset(conn) ?: DEFAULT_CHARSET
                val html = readHtmlCapped(BufferedReader(InputStreamReader(conn.inputStream, charset)))
                ArticleLinkExtractor.extractLinksFromHtml(html, urlStr)
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

    /**
     * 从 HTML 中提取文章结构（保留为实例方法：单元测试直接覆盖解析路径，
     * 无需走网络）。实现见 [ArticleHtmlExtractor.extractArticle]。
     */
    internal fun extractArticle(html: String, baseUrl: String? = null): ArticleResult =
        ArticleHtmlExtractor.extractArticle(html, baseUrl)

    /**
     * 建立 GET 连接并设置请求头/超时。
     *
     * @return 已配置的连接；Content-Type 非 HTML/文本时返回 null（并断开连接）
     */
    private fun openHtmlConnection(urlStr: String): HttpURLConnection? {
        val url = URL(urlStr)
        val conn = url.openConnection() as HttpURLConnection
        conn.apply {
            requestMethod = "GET"
            setRequestProperty("User-Agent", USER_AGENT)
            setRequestProperty("Accept", ACCEPT_HEADER)
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
        }
        // issue 10.5：非 HTML/文本 Content-Type 直接拒收，避免把图片/
        // JSON/任意二进制流当正文解析；缺失时不拦截（部分源不返回该头）。
        // 检查本身若抛异常同样要断开连接（与拆分前的 try/finally 语义一致）
        try {
            if (!isHtmlContentType(conn.contentType)) {
                android.util.Log.w("ArticleParser", "Unsupported Content-Type for URL: $urlStr -> ${conn.contentType}")
                conn.disconnect()
                return null
            }
        } catch (e: Exception) {
            conn.disconnect()
            throw e
        }
        return conn
    }

    /**
     * 检测网页编码
     */
    private fun detectCharset(conn: HttpURLConnection): String? {
        conn.getHeaderField("Content-Type")?.let { ct ->
            // charset="utf-8" 带引号是 RFC 合法写法，需去掉引号再交给 InputStreamReader
            CHARSET_REGEX.find(ct)?.let { return it.groupValues[1].trim('"', '\'') }
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
