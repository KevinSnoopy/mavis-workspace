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
        private val HTML_TAG = Regex("<[^>]+>")

        // issue 7.4：策略 5 命中 <body> 时页眉/页脚仍是噪音，先剥掉再取正文
        private val HEADER_FOOTER_TAG = Regex(
            "<(header|footer)\\b[^>]*>[\\s\\S]*?</\\1>",
            RegexOption.IGNORE_CASE,
        )
        // issue 7.5/10.10：块级标签边界 → 空行，保留原文段落结构（cleanText 分段用）
        private val BLOCK_END_TAG = Regex(
            "</(p|div|li|h[1-6]|blockquote|tr|section|article|figure|figcaption|header|footer|aside|nav)>|<br\\s*/?>",
            RegexOption.IGNORE_CASE,
        )

        // 性能：script/style/comment 的"整块删除"合并为单趟扫描（原 3 遍全文
        // 各一次全量拷贝，5MB 页面最坏 3×10MB 级临时分配）。三者模式均无
        // 分组/反向引用，直接拼 alternation 语义等价
        private val STRIP_ALL_BLOCK = Regex(
            "<script[^>]*>.*?</script>|<style[^>]*>.*?</style>|<!--.*?-->",
            RegexOption.DOT_MATCHES_ALL,
        )

        // 性能：三种噪音剔除（issue 7.2 配对块 / 按 class 的容器 / 残标签）合并为
        // 单趟扫描。回溯引用改用命名组——组合后匿名组号会漂移，\1 会指错组
        // 注意：figure 容器不整块删除（其内的 <img> 已被替换成 [[IMG:]] 标记，
        // 整块删除会把插图一起吞掉）；仅去掉 figure 开闭标签本身 + 图注文字。
        private val NOISE_ALL_BLOCK = Regex(
            "<(?<noiseTag>aside|nav|iframe|button|form|select|textarea|ins)\\b[^>]*>[\\s\\S]*?</\\k<noiseTag>" +
                "|<figcaption\\b[^>]*>[\\s\\S]*?</figcaption>" +
                "|<\\s*(?<classTag>div|section)\\b[^>]*class\\s*=\\s*[\"'][^\"']*\\b" +
                "(?:share|social|newsletter|subscribe|ad-|advert|promo|related|recommend|sidebar|breadcrumb|byline|author-bio|comments)" +
                "\\b[^\"']*[\"'][^>]*>[\\s\\S]*?</\\k<classTag>" +
                "|<(?:aside|nav|figcaption|iframe|button|form|input|select|textarea|ins)\\b[^>]*/?>",
        )

        // ── 插图提取：整段 <img> 标签（src 属性顺序无关）──
        private val IMG_TAG_FULL = Regex(
            "<img\\b[^>]*\\bsrc\\s*=\\s*[\"']([^\"']+)[\"'][^>]*>",
            RegexOption.IGNORE_CASE,
        )

        /** 明显的装饰/追踪类小图：命中即丢弃（1x1 像素、分享徽章等）。 */
        private val IMG_NOISE_SRC = Regex(
            "(1x1|pixel|spacer|blank\\.gif|tracking|analytics|beacon|badge|icon|logo|avatar|emoji|smiley|sprite)",
            RegexOption.IGNORE_CASE,
        )

        /** Coil 未含 svg 模块，svg 源直接跳过。 */
        private val IMG_SKIP_EXT = Regex("\\.svg(\\?|#|$)", RegexOption.IGNORE_CASE)

        // ── 每次调用重新编译的正则全部提升为常量（文章解析路径高频）──
        private val EXCLUDE_LINK_PATTERN = Regex(
            """(login|sign[-]?in|sign[-]?up|register|about|contact|privacy|terms|category|tag|author|profile|feed|rss|xml|sitemap|css|js|png|jpg|gif|svg|ico|pdf|zip)""",
            RegexOption.IGNORE_CASE,
        )
        private val ANCHOR_REGEX = Regex(
            """<a[^>]+href=["']([^"']+)["'][^>]*>([^<]+)</a>""",
            RegexOption.DOT_MATCHES_ALL,
        )
        private val CHARSET_REGEX = Regex("charset=([^;\\s]+)")

        private val OG_TITLE_CONTENT = Regex("""og:title["\s]+content=["']([^"']+)["']""")
        private val OG_TITLE_META = Regex("""<meta[^>]+property=["']og:title["'][^>]+content=["']([^"']+)["']""")
        private val TITLE_TAG = Regex("""<title[^>]*>([^<]+)</title>""")
        private val H1_TAG = Regex("""<h1[^>]*>([^<]+)</h1>""")

        private val ARTICLE_TAG = Regex("""<article[^>]*>(.*?)</article>""", RegexOption.DOT_MATCHES_ALL)
        private val MAIN_TAG = Regex("""<main[^>]*>(.*?)</main>""", RegexOption.DOT_MATCHES_ALL)
        private val JSON_LD_ARTICLE_BODY = Regex(
            """"articleBody"\s*:\s*"((?:\\.|[^"\\]){100,})"""",
            RegexOption.DOT_MATCHES_ALL,
        )
        private val CONTENT_DIV = Regex(
            """<div[^>]+class=["'][^"']*(?:content|article|body|text|story|entry)[^"']*["'][^>]*>(.*?)</div>""",
            RegexOption.DOT_MATCHES_ALL,
        )
        private val CONTENT_SECTION = Regex(
            """<section[^>]+class=["'][^"']*(?:content|article|body|text|story|entry)[^"']*["'][^>]*>(.*?)</section>""",
            RegexOption.DOT_MATCHES_ALL,
        )
        private val BODY_TAG = Regex("""<body[^>]*>(.*?)</body>""", RegexOption.DOT_MATCHES_ALL)

        private val PARAGRAPH_SPLIT = Regex("\n{2,}")
        private val WHITESPACE = Regex("\\s+")
        private val SENTENCE_SPLIT = Regex("(?<=[.!?])\\s+")
        private val SENTENCE_SPLIT_CAPITAL = Regex("(?<=[.!?])\\s+(?=[A-Z])")

        // 网络请求参数
        private const val CONNECT_TIMEOUT_MS = 15000
        private const val READ_TIMEOUT_MS = 15000
        private const val USER_AGENT = "Mozilla/5.0 (compatible; EareyeReader/1.0)"
        private const val ACCEPT_HEADER = "text/html,application/xhtml+xml"
        private const val DEFAULT_CHARSET = "UTF-8"

        /** 页面正文读取上限（字符数），防止超大页面撑爆内存；超出部分直接截断。 */
        private const val MAX_HTML_CHARS = 5_000_000

        /** 可接受的响应 Content-Type（issue 10.5）：缺失时为 null 放行（部分源不返回该头），
         * 命中非 HTML/文本类型时拒收，避免把图片/JSON 等二进制流当正文解析。 */
        private val ALLOWED_CONTENT_TYPES = listOf(
            "text/html", "text/plain",
            "application/xhtml", "application/xml",
        )

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
                // issue 10.5：非 HTML/文本 Content-Type 直接拒收，避免把图片/
                // JSON/任意二进制流当正文解析；缺失时不拦截（部分源不返回该头）
                if (!isHtmlContentType(conn.contentType)) {
                    android.util.Log.w("ArticleParser", "Unsupported Content-Type for URL: $urlStr -> ${conn.contentType}")
                    return@withContext null
                }
                val html = readHtmlCapped(BufferedReader(InputStreamReader(conn.inputStream, charset)))
                extractArticle(html, urlStr)
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
                // issue 10.5：非 HTML/文本响应拒收，会把 Feeds API 返回的 JSON 等当正文解析
                if (!isHtmlContentType(conn.contentType)) {
                    android.util.Log.w("ArticleParser", "Unsupported Content-Type for URL: $urlStr -> ${conn.contentType}")
                    return@withContext null
                }
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
        // 过滤规则：排除导航、登录、注册等非文章链接（正则已预编译为常量）
        val excludePattern = EXCLUDE_LINK_PATTERN
        val seen = mutableSetOf<String>()
        val links = mutableListOf<ArticleLink>()

        for (match in ANCHOR_REGEX.findAll(html)) {
            val href = match.groupValues[1].trim()
            val text = match.groupValues[2].trim()

            // 过滤：URL 必须有效、非排除项、文本有内容
            if (href.isBlank() || text.isBlank()) continue
            if (excludePattern.containsMatchIn(href) || excludePattern.containsMatchIn(text)) continue
            // issue 10.2：非 http scheme 的链接直接挡掉（javascript:/#/data: 等）
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
            // issue 10.2：补全后再校验一次——解析失败保留原值的分支可能是坏链
            if (!absoluteUrl.startsWith("http://") && !absoluteUrl.startsWith("https://")) continue
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
                        val hex4 = s.substring(i + 2, minOf(i + 6, s.length))
                        val code = hex4.toIntOrNull(16)
                        if (code != null && hex4.length == 4) {
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
     * 从 HTML 中提取文章内容（internal 供单元测试直接覆盖，无需走网络）
     * @param baseUrl 文章页 URL：插图 src 相对路径解析基准（测试直调时可空）
     */
    internal fun extractArticle(html: String, baseUrl: String? = null): ArticleResult {
        // 插图先行：<img> → [[IMG:绝对 URL]] 标记（必须在噪音剔除前替换，
        // 且 figure 容器不再整块删除，否则正文插图会被一并清掉）
        val withImages = replaceImgWithMarkers(html, baseUrl)
        // 清理脚本和样式 + issue 7.2 噪音元素（侧栏/导航/图注/iframe/按钮/
        // 表单/按 class 名的分享·订阅·广告·相关推荐容器）——策略命中容器前
        // 先剔除，否则 cleanText 的标签替换会把它们留进正文
        val text = withImages
            .replace(STRIP_ALL_BLOCK, "")
            .replace(NOISE_ALL_BLOCK, " ")

        // 提取标题
        val title = extractTitle(text)

        // 提取正文（多种策略）
        val content = BookImages.expandInlineMarkers(extractContent(text))

        return ArticleResult(title = title, paragraphs = content)
    }

    /** <img> → 段落级 [[IMG:绝对URL]] 标记；噪音图（追踪/徽章/svg/data URI）直接丢弃。 */
    private fun replaceImgWithMarkers(html: String, baseUrl: String?): String =
        html.replace(IMG_TAG_FULL) { m ->
            val src = m.groupValues[1].trim()
            when {
                src.isBlank() || src.startsWith("data:", ignoreCase = true) -> ""
                IMG_SKIP_EXT.containsMatchIn(src) -> ""
                IMG_NOISE_SRC.containsMatchIn(src) -> ""
                else -> {
                    val absolute = if (src.startsWith("http://") || src.startsWith("https://")) {
                        src
                    } else if (baseUrl != null) {
                        try {
                            java.net.URI(baseUrl).resolve(src).toString()
                        } catch (_: Exception) {
                            null
                        }
                    } else {
                        null
                    }
                    if (absolute != null &&
                        (absolute.startsWith("http://") || absolute.startsWith("https://"))
                    ) {
                        "\n\n[[IMG:$absolute]]\n\n"
                    } else {
                        ""
                    }
                }
            }
        }

    /**
     * 提取文章标题
     */
    private fun extractTitle(html: String): String {
        // og:title
        OG_TITLE_CONTENT.find(html)?.let { return it.groupValues[1].trim() }
        OG_TITLE_META.find(html)?.let { return it.groupValues[1].trim() }

        // <title> tag
        TITLE_TAG.find(html)?.let { return it.groupValues[1].trim() }

        // h1
        H1_TAG.find(html)?.let { return it.groupValues[1].trim() }

        return "Untitled Article"
    }

    /**
     * 提取正文内容（多种策略依次尝试）
     */
    private fun extractContent(html: String): List<String> {
        // 策略1: article tag
        ARTICLE_TAG.find(html)?.let {
            return cleanText(it.groupValues[1])
        }

        // 策略2: main tag
        MAIN_TAG.find(html)?.let {
            return cleanText(it.groupValues[1])
        }

        // 策略3: JSON-LD structured data
        // 匹配时跳过转义序列（\" 等），否则正文里第一个转义引号就会截断匹配
        JSON_LD_ARTICLE_BODY.find(html)?.let {
            val raw = unescapeJson(it.groupValues[1])
            return raw.split(SENTENCE_SPLIT).filter { s -> s.length > 20 }.map { it.trim() }
        }

        // 策略4: 找最大的文本块（content div）
        val candidates = listOf(CONTENT_DIV, CONTENT_SECTION)
        for (regex in candidates) {
            regex.findAll(html).maxByOrNull { match -> match.value.length }?.let { match ->
                val cleaned = cleanText(match.groupValues[1])
                if (cleaned.sumOf { it.length } > 200) return cleaned
            }
        }

        // 策略5: body 正文（issue 7.4：先剥页眉/页脚，否则整页文本都算正文）
        BODY_TAG.find(html)?.let {
            val cleaned = cleanText(it.groupValues[1].replace(HEADER_FOOTER_TAG, " "))
            if (cleaned.sumOf { s -> s.length } > 100) return cleaned
        }

        return cleanText(html)
    }

    /**
     * 清理 HTML 标签，提取纯文本并分段。
     *
     * issue 7.5 / 10.10：旧实现先把全文压成一行再"每 4 句硬切一段"，
     * 原文的段落边界（<p>/<div> 等）被丢弃，且切句正则要求句子首字母大写，
     * 小写开头的句子（引号/编号/缩写后）全被吞。改为保留块级标签边界：
     * 块级标签闭合 → 空行，再按空行自然分段；纯文本页才回退到"每 4 句合并"。
     */
    private fun cleanText(html: String): List<String> {
        // 块级标签边界 → 空行（先于通用标签替换，否则 </p> 会被替换成空格）
        val text = HtmlEntities.decode(
            html.replace(BLOCK_END_TAG, "\n\n").replace(HTML_TAG, " ")
        )
        // 段落级正则预编译：\s+ 原写在 map lambda 内，每个段落编译一次
        // 图片标记段不受长度/字母过滤约束（URL 就是它的内容）
        val paragraphs = text.split(PARAGRAPH_SPLIT)
            .map { it.replace(WHITESPACE, " ").trim() }
            .filter {
                (it.isNotBlank() && it.length > 20 && it.any { c -> c.isLetter() }) ||
                    BookImages.isImageMarker(it)
            }
        if (paragraphs.isNotEmpty()) return paragraphs

        // 回退：页面没有块级标签（纯句子流）时，保持旧的"每 4 句一段"合并，
        // 不返回空列表导致策略判定失败
        val normalized = text.replace(WHITESPACE, " ").trim()
        val sentences = normalized
            .split(SENTENCE_SPLIT_CAPITAL)
            .map { it.trim() }
            .filter { it.length > 30 && it.any { c -> c.isLetter() } }
        if (sentences.isEmpty()) return listOf(normalized)
        val merged = mutableListOf<String>()
        for (i in sentences.indices step 4) {
            merged.add(sentences.subList(i, minOf(i + 4, sentences.size)).joinToString(" "))
        }
        return merged
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
