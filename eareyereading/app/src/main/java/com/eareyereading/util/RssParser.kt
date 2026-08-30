@file:Suppress("SwallowedException", "UnsafeCast")

package com.eareyereading.util

import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserException
import org.xmlpull.v1.XmlPullParserFactory
import java.io.StringReader
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.Charset
import java.text.SimpleDateFormat
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/**
 * RSS / Atom 订阅源解析器（容错实现）
 *
 * 支持 RSS 2.0 和 Atom 1.0。
 *
 * ## 为什么不用 `require()` / `nextText()`
 *
 * 线上崩溃：
 *
 * ```
 * org.xmlpull.v1.XmlPullParserException: expected: END_TAG {null}null
 *   (position:TEXT NPR Topics: News@4:28 in java.io.StringReader)
 *     at com.android.org.kxml2.io.KXmlParser.require(KXmlParser.java:2145)
 *     at com.eareyereading.util.RssParser.readText(RssParser.kt:180)
 * ```
 *
 * 旧实现的 `readText` 只调用了一次 `next()` 就 `require(END_TAG)`：
 *
 * ```kotlin
 * if (parser.next() == XmlPullParser.TEXT) {
 *     result = parser.text?.trim() ?: ""
 *     parser.require(XmlPullParser.END_TAG, null, parser.name)   // ← 永远抛
 * }
 * ```
 *
 * 但 [XmlPullParser.require] 是**纯断言，不会推进解析器**。`next()` 之后解析器还停在
 * TEXT 事件上，所以 `require(END_TAG)` 对**任何有文本内容**的 `<title>` / `<subtitle>`
 * 都必然失败。已用设备上的 KXml2 实测验证：
 *
 * - TEXT 事件上 `getName()` 返回 **null**，所以消息是 `expected: END_TAG {null}null`
 *   （如果传 `parser.name` 也补不出元素名）；
 * - 异常消息在 100 字符处截断，对应日志里被截断的 `NPR Topics: News`；
 * - 只有 `<title></title>` 这种空标题不会进 `if` 分支，因此不崩。
 *
 * 所以这里改为显式事件状态机：自己推进事件，遇到嵌套标签、CDATA、畸形 XML、
 * 未声明的 namespace 前缀时降级而不是抛异常。
 *
 * ## 其他实测到的平台坑
 *
 * - **接口里没有 `CDATA` 常量。** Android 的 `org.xmlpull.v1.XmlPullParser` 是
 *   XmlPull 1.1 API：`CDSECT`(=5) 存在，`CDATA` 不存在，写 `CDATA` 编译不过。
 *   不过 KXml2 实际把 `<![CDATA[...]]>` 以 **TEXT**(=4) 报告（实测确认），
 *   所以主要处理 TEXT，`CDSECT` 分支只是防御性保留。
 * - **CDATA 内容不做实体解码。** `<![CDATA[A &amp; B]]>` 的文本就是字面量
 *   `A &amp; B`，所以 [stripHtml] 之后还要 [decodeEntities]。
 * - **`isNamespaceAware = true` 会因未声明前缀直接失败。** 很多 feed 写
 *   `<content:encoded>` / `<dc:date>` 却不声明 `xmlns:xxx`，开启 namespace 处理后
 *   KXml2 抛 `undefined prefix: content` 让整份 feed 报废。这里关闭它，前缀作为
 *   元素名的一部分保留，用 [localName] 归一化后两种写法都能匹配。
 *
 * ## 已知局限
 *
 * 只有**格式层面**的错误能容忍（截断、字段未闭合、内容超长等）。一旦出现
 * **结构性**错误 —— 例如 `<description>` 未闭合就来了 `</item>` —— KXml2 自己抛
 * `expected: /description read: item`，解析器实例随之报废，无法跳过该段继续解析。
 * 此时 [safeNext] 只能终止解析并返回错误发生前已经拿到的内容。
 */
@Singleton
class RssParser @Inject constructor() {

    companion object {
        private const val MAX_ITEMS = 50
        private const val MAX_TITLE = 300
        private const val MAX_DESC = 500
        private const val MAX_RAW_FIELD = 32_000

        /** 响应体上限，防止恶意/失配的超大 feed 撑爆内存（10 MB 对任何正常 feed 都绰绰有余）。 */
        private const val MAX_BODY = 10_000_000

        /** 日期格式模式。SimpleDateFormat 非线程安全，parseDate 每次调用时按模式新建实例。 */
        private val DATE_PATTERNS = listOf(
            "EEE, dd MMM yyyy HH:mm:ss Z",
            "EEE, dd MMM yyyy HH:mm:ss",
            "yyyy-MM-dd'T'HH:mm:ssZ",
            "yyyy-MM-dd'T'HH:mm:ss.SSSZ",
            "yyyy-MM-dd'T'HH:mm:ss",
            "yyyy-MM-dd",
        )

        private val NAMED_ENTITIES = mapOf(
            "nbsp" to " ", "amp" to "&", "lt" to "<", "gt" to ">",
            "quot" to "\"", "apos" to "'", "hellip" to "…",
            "mdash" to "—", "ndash" to "–",
            "lsquo" to "\u2018", "rsquo" to "\u2019",
            "ldquo" to "\u201C", "rdquo" to "\u201D",
        )

        /** 需要采集文本内容的元素（本地名；namespaceAware 下 `parser.name` 已去前缀）。 */
        private val TEXT_TAGS = setOf(
            "title", "subtitle", "link", "guid",
            "description", "summary", "content", "encoded",
            "pubdate", "date", "published", "updated",
        )
    }

    data class RssFeed(
        val title: String,
        val description: String?,
        val link: String?,
        val items: List<RssArticle>,
    )

    data class RssArticle(
        val title: String,
        val link: String,
        val description: String?,
        val pubDate: String?,
        val pubTimestamp: Long,
    )

    /**
     * 解析 RSS/Atom 源 URL。
     * @return 解析后的 Feed，任何失败都返回 null（不抛异常）
     */
    fun parse(urlStr: String): RssFeed? {
        var conn: HttpURLConnection? = null
        return try {
            val url = URL(urlStr)
            conn = url.openConnection() as HttpURLConnection
            conn.apply {
                requestMethod = "GET"
                setRequestProperty("User-Agent", "EareyeReader/1.0")
                setRequestProperty("Accept", "application/rss+xml, application/atom+xml, application/xml, text/xml")
                connectTimeout = 12_000
                readTimeout = 12_000
            }
            if (conn.responseCode != HttpURLConnection.HTTP_OK) {
                android.util.Log.w("RssParser", "HTTP ${conn.responseCode} for RSS feed: $urlStr")
                return null
            }
            val bytes = readCapped(conn.inputStream, MAX_BODY)
            val charset = resolveCharset(conn.contentType, bytes)
            val xml = String(bytes, charset).removePrefix("\uFEFF")
            parseXml(xml)
        } catch (e: java.net.MalformedURLException) {
            android.util.Log.e("RssParser", "Invalid RSS URL: $urlStr", e)
            null
        } catch (e: java.net.SocketTimeoutException) {
            android.util.Log.e("RssParser", "Connection timeout for RSS feed: $urlStr", e)
            null
        } catch (e: java.io.IOException) {
            android.util.Log.e("RssParser", "IO error fetching RSS feed: $urlStr", e)
            null
        } catch (e: java.lang.RuntimeException) {
            android.util.Log.e("RssParser", "Unexpected error reading RSS feed: $urlStr", e)
            null
        } finally {
            conn?.disconnect()
        }
    }

    /**
     * 解析已获取的 XML 文本。包可见以便单元测试直接覆盖解析逻辑，无需走网络。
     */
    internal fun parseXml(xml: String): RssFeed {
        if (xml.isBlank()) return RssFeed("RSS Feed", null, null, emptyList())

        val parser = newParser()
        parser.setInput(StringReader(xml))

        var feedTitle = ""
        var feedDescription: String? = null
        var feedLink: String? = null

        var inItem = false
        var inEntry = false
        val item = ItemState()

        // 正在采集文本的元素本地名；null 表示当前不在任何文本元素内
        var collecting: String? = null
        var textBuf = StringBuilder()
        // 采集过程中打开的嵌套元素数量
        var nestedDepth = 0

        val articles = mutableListOf<RssArticle>()

        // 把采集到的原文写入当前上下文的对应字段
        fun applyField(tag: String, raw: String) {
            val inArticle = inItem || inEntry
            when (tag) {
                "title" -> {
                    val text = stripHtml(raw).take(MAX_TITLE)
                    if (inArticle) item.title = text
                    else if (feedTitle.isEmpty()) feedTitle = text
                }
                "subtitle" -> {
                    if (!inArticle && feedDescription == null) feedDescription = stripHtml(raw)
                }
                "link" -> {
                    if (raw.isNotBlank()) {
                        val url = raw.trim()
                        if (inArticle) item.link = url
                        else if (feedLink == null) feedLink = url
                    }
                }
                "guid" -> {
                    if (inArticle && item.link == null && raw.isNotBlank()) item.link = raw.trim()
                }
                "pubdate", "date", "published", "updated" -> {
                    if (inArticle && raw.isNotBlank()) item.pubDate = raw.trim()
                }
                else -> {
                    // description / summary / content / encoded
                    val cleaned = stripHtml(raw)
                    if (inArticle) appendDesc(item.description, cleaned)
                    else if (feedDescription == null) feedDescription = cleaned
                }
            }
        }

        var event = safeNext(parser)
        while (event != XmlPullParser.END_DOCUMENT) {
            when (event) {
                XmlPullParser.START_TAG -> {
                    if (collecting != null) {
                        // 嵌套标签：只记深度，继续采集其文本（RSS 的 <description> /
                        // <content:encoded> 里几乎总是嵌着 <p>/<em> 这类标签）
                        nestedDepth++
                    } else {
                        val name = localName(parser.name).lowercase()
                        when (name) {
                            "item", "entry" -> {
                                // 先落盘上一条：应对缺少 </item> 的畸形 feed
                                flushItem(articles, item)
                                item.reset()
                                inItem = name == "item"
                                inEntry = name == "entry"
                            }
                            "link" -> {
                                // Atom: <link rel="alternate" href="..."/>；RSS: <link>url</link>
                                val href = parser.getAttributeValue(null, "href")?.trim()
                                val rel = parser.getAttributeValue(null, "rel")?.lowercase()
                                if (!href.isNullOrEmpty() && (rel == null || rel == "alternate")) {
                                    if (inItem || inEntry) item.link = href
                                    else if (feedLink == null) feedLink = href
                                }
                                // 自闭合 <link .../>（KXml2 不会发 END_TAG）不进入采集，
                                // 否则后续所有标签都会被当作嵌套文本，字段全部错位
                                if (!parser.isEmptyElementTag) {
                                    collecting = "link"
                                    textBuf.setLength(0)
                                    nestedDepth = 0
                                }
                            }
                            else -> if (name in TEXT_TAGS && !parser.isEmptyElementTag) {
                                collecting = name
                                textBuf.setLength(0)
                                nestedDepth = 0
                            }
                        }
                    }
                }

                // KXml2 实际把 CDATA 以 TEXT 报告；CDSECT 分支保留作为防御
                XmlPullParser.TEXT, XmlPullParser.CDSECT -> {
                    val chunk = parser.text ?: ""
                    if (collecting != null && chunk.isNotEmpty() && textBuf.length < MAX_RAW_FIELD) {
                        textBuf.append(chunk, 0, minOf(chunk.length, MAX_RAW_FIELD - textBuf.length))
                    }
                }

                XmlPullParser.END_TAG -> {
                    val name = localName(parser.name).lowercase()
                    val closingArticle = name == "item" || name == "entry"

                    if (collecting != null) {
                        val closingNested = nestedDepth > 0
                        when {
                            // 文章边界到达：把已采集的内容落进字段，再结束采集
                            closingArticle -> applyField(collecting, textBuf.toString())
                            // </p> 之类：只是嵌套标签闭合，继续采集
                            closingNested -> nestedDepth--
                            name == collecting -> applyField(collecting, textBuf.toString())
                            else ->
                                // 畸形 XML：文本元素未闭合就遇到了别的结束标签，丢弃该字段
                                android.util.Log.w(
                                    "RssParser",
                                    "Unclosed <$collecting> before </$name> at line ${parser.lineNumber}; field dropped"
                                )
                        }
                        if (!closingNested || closingArticle) {
                            collecting = null
                            textBuf.setLength(0)
                            nestedDepth = 0
                        }
                    }

                    if (closingArticle) {
                        inItem = false
                        inEntry = false
                        flushItem(articles, item)
                    }
                }
            }
            event = safeNext(parser)
        }

        // 最后再落盘一次：feed 在最后一个 </item> 之前就被截断也能拿到那条
        flushItem(articles, item)

        return RssFeed(
            title = feedTitle.ifBlank { "RSS Feed" },
            description = feedDescription,
            link = feedLink,
            items = articles.take(MAX_ITEMS),
        )
    }

    /** 一个尚未落盘的 <item> / <entry>。 */
    private class ItemState {
        var title: String = ""
        var link: String? = null
        val description = StringBuilder()
        var pubDate: String? = null

        fun reset() {
            title = ""
            link = null
            description.setLength(0)
            pubDate = null
        }
    }

    /** 落盘一条文章；缺 title 或 link 时丢弃。 */
    private fun flushItem(articles: MutableList<RssArticle>, item: ItemState) {
        val title = item.title.trim()
        val link = item.link?.trim().orEmpty()
        if (title.isEmpty() || link.isEmpty()) return
        articles.add(
            RssArticle(
                title = title,
                link = link,
                description = item.description.toString().trim().take(MAX_DESC).ifEmpty { null },
                pubDate = item.pubDate,
                pubTimestamp = parseDate(item.pubDate),
            )
        )
        item.reset()
    }

    private fun appendDesc(sb: StringBuilder, cleaned: String) {
        val room = MAX_DESC - sb.length
        if (room <= 0) return
        if (cleaned.length <= room) sb.append(cleaned) else sb.append(cleaned, 0, room)
    }

    /** 推进解析器；遇到畸形 XML 时停止（保留已解析到的内容）而不是抛异常。 */
    private fun safeNext(parser: XmlPullParser): Int {
        return try {
            parser.next()
        } catch (e: XmlPullParserException) {
            android.util.Log.w(
                "RssParser",
                "Malformed feed XML at line ${parser.lineNumber}: ${e.message}"
            )
            XmlPullParser.END_DOCUMENT
        }
    }

    private fun newParser(): XmlPullParser {
        val factory = XmlPullParserFactory.newInstance()
        // 故意不开启 namespace 处理。开启后 KXml2 遇到未声明的前缀（很多 feed 的
        // <content:encoded> / <dc:date> 都没写 xmlns:xxx）会直接抛
        // "undefined prefix" 让整个 feed 解析失败；关闭后前缀作为元素名的一部分保留，
        // 再用 localName() 归一化，两种写法都能匹配。
        factory.isNamespaceAware = false
        return factory.newPullParser()
    }

    /** 取元素名的本地部分：`content:encoded` / `dc:date` / `link` -> `encoded` / `date` / `link`。 */
    private fun localName(name: String): String {
        val i = name.lastIndexOf(':')
        return if (i >= 0) name.substring(i + 1) else name
    }

    /** 从 `application/rss+xml; charset=UTF-8` 这类 Content-Type 里取 charset。 */
    internal fun charsetFromContentType(contentType: String?): String? {
        if (contentType == null) return null
        return Regex(";\\s*charset\\s*=\\s*[\"']?([^\"';\\s]+)", RegexOption.IGNORE_CASE)
            .find(contentType)?.groupValues?.get(1)
    }

    /**
     * HTTP Content-Type 优先，其次 BOM / XML 声明里的 encoding，最后回退 UTF-8。
     * 包可见以便单元测试直接覆盖 charset 决策，无需走网络。
     */
    internal fun resolveCharset(contentType: String?, bytes: ByteArray): Charset {
        val headerCharset = charsetFromContentType(contentType)
        // UTF-16 BOM：声明嗅探按 US-ASCII 解码，NUL 交错的 UTF-16 声明永远匹配不到，
        // 只能靠 BOM 识别，否则会按 UTF-8 解出乱码
        if (bytes.size >= 2) {
            if (bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte()) return Charset.forName("UTF-16BE")
            if (bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte()) return Charset.forName("UTF-16LE")
        }
        val head = String(
            bytes.copyOfRange(0, minOf(bytes.size, 512)),
            Charset.forName("US-ASCII")
        )
        val xmlEncoding = Regex("""encoding\s*=\s*["']([^"']+)["']""", RegexOption.IGNORE_CASE)
            .find(head)?.groupValues?.get(1)
        return listOfNotNull(headerCharset, xmlEncoding, "UTF-8").firstNotNullOfOrNull { name ->
            try {
                Charset.forName(name.trim())
            } catch (_: Exception) {
                null
            }
        } ?: Charset.forName("UTF-8")
    }

    /** 读取响应体，超过 [max] 字节立即中止（抛 IOException，由 parse 统一降级为 null）。 */
    private fun readCapped(input: java.io.InputStream, max: Int): ByteArray {
        input.use { stream ->
            val out = java.io.ByteArrayOutputStream()
            val buf = ByteArray(8192)
            var total = 0
            while (true) {
                val n = stream.read(buf)
                if (n < 0) break
                total += n
                if (total > max) throw java.io.IOException("RSS body exceeds $max bytes")
                out.write(buf, 0, n)
            }
            return out.toByteArray()
        }
    }

    internal fun stripHtml(html: String): String {
        return Regex("\\s+").replace(decodeEntities(html.replace(Regex("<[^>]+>"), " ")), " ").trim()
    }

    /** 解码 HTML 实体（主要服务于 CDATA 里的原始 HTML，以及 `<p>`/`<em>` 之外的转义字符）。 */
    internal fun decodeEntities(s: String): String {
        val idx = s.indexOf('&')
        if (idx < 0) return s
        return s.replace(Regex("(&#[xX]?[0-9a-fA-F]+;|&[a-zA-Z][a-zA-Z0-9]*;)")) { m ->
            decodeEntity(m.value)
        }
    }

    private fun decodeEntity(raw: String): String {
        val inner = raw.substring(1, raw.length - 1) // 去掉 '&' 和 ';'
        return try {
            when {
                inner.startsWith("#x", ignoreCase = true) ->
                    Character.toChars(inner.substring(2).toInt(16)).concatToString()
                inner.startsWith('#') ->
                    Character.toChars(inner.substring(1).toInt(10)).concatToString()
                else -> NAMED_ENTITIES[inner.lowercase()] ?: raw
            }
        } catch (_: Exception) {
            raw
        }
    }

    internal fun parseDate(dateStr: String?): Long {
        if (dateStr.isNullOrBlank()) return System.currentTimeMillis()
        // SimpleDateFormat.parse 容忍尾部未消费的文本：`... HH:mm:ss Z` 遇到
        // 文本 "GMT" 时区段解析失败后，会把 "12:00:00" 按设备本地时区解释并
        // 静默忽略 " GMT" 尾巴，RSS 最常见的 GMT 时间整体偏移数小时。
        // 先把文本时区归一成数字偏移再解析。
        val normalized = dateStr.trim()
            .replace(Regex("([+-]\\d{2}):(\\d{2})"), "$1$2") // +00:00 -> +0000（RFC3339 偏移归一）
            .replace("Z", "+0000")
            .replace(Regex("\\s+(GMT|UTC|UT)$"), " +0000")
        // SimpleDateFormat 非线程安全：每次调用新建实例，避免并发刷新时互相污染
        for (pattern in DATE_PATTERNS) {
            try {
                return SimpleDateFormat(pattern, Locale.ENGLISH).parse(normalized)?.time ?: 0L
            } catch (_: java.text.ParseException) {
                // 尝试下一个格式
            }
        }
        return System.currentTimeMillis()
    }
}
