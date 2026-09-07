@file:Suppress("SwallowedException", "UnsafeCast")

package com.eareyereading.util

import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.Charset
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
 * 但 [org.xmlpull.v1.XmlPullParser.require] 是**纯断言，不会推进解析器**。`next()` 之后解析器还停在
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
 *   元素名的一部分保留，用 localName 归一化后两种写法都能匹配。
 *
 * ## 已知局限
 *
 * 只有**格式层面**的错误能容忍（截断、字段未闭合、内容超长等）。一旦出现
 * **结构性**错误 —— 例如 `<description>` 未闭合就来了 `</item>` —— KXml2 自己抛
 * `expected: /description read: item`，解析器实例随之报废，无法跳过该段继续解析。
 * 此时 safeNext 只能终止解析并返回错误发生前已经拿到的内容。
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
        /**
         * `<content:encoded>` / Atom `<content>` 的完整正文（stripHtml 后）。
         * issue 7.1：此前整段被塞进 description 并截断到 500 字符，
         * "加入书库"只能重新抓 link（SPA 页拿不到正文）。
         * 不带 MAX_DESC 限制（采集层 MAX_RAW_FIELD = 32K 已兜底）。
         */
        val content: String? = null,
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
    internal fun parseXml(xml: String): RssFeed = parseRssXml(xml)

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

    internal fun stripHtml(html: String): String = com.eareyereading.util.stripHtml(html)

    internal fun stripHtmlKeepParagraphs(html: String): String =
        com.eareyereading.util.stripHtmlKeepParagraphs(html)

    internal fun decodeEntities(s: String): String = com.eareyereading.util.decodeEntities(s)

    internal fun parseDate(dateStr: String?): Long = parseRssDate(dateStr)

    internal fun charsetFromContentType(contentType: String?): String? =
        com.eareyereading.util.charsetFromContentType(contentType)

    internal fun resolveCharset(contentType: String?, bytes: ByteArray): Charset =
        com.eareyereading.util.resolveCharset(contentType, bytes)
}
