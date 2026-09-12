package com.eareyereading.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [RssParser] 的内容清洗与过滤测试。
 *
 * 从 `RssParserTest.kt` 按测试主题拆出（SRP）：本类只覆盖「解析之后的清洗」
 * ——条目有效性过滤（无可用链接即丢弃）、条目数上限、content:encoded 的
 * 文本量约束与 `stripHtml` 的标签剥离。解析容错（崩溃复现 / CDATA /
 * 畸形 feed）保留在 [RssParserTest]。
 */
class RssContentFilterTest {

    private val parser = RssParser()

    @Test
    fun `item without a usable link is dropped`() {
        val xml = """
            <rss><channel><title>F</title>
              <item><title>no link</title></item>
              <item><title>only title</title><description>d</description></item>
              <item><title>ok</title><guid>https://example.com/guid</guid></item>
            </channel></rss>
        """.trimIndent()

        val feed = parser.parseXml(xml)

        assertEquals(1, feed.items.size)
        // guid 作为最后兜底
        assertEquals("https://example.com/guid", feed.items.first().link)
    }

    @Test
    fun `items are capped`() {
        val items = (1..60).joinToString("\n") { i ->
            "<item><title>T$i</title><link>https://example.com/$i</link></item>"
        }
        val xml = "<rss><channel><title>F</title>$items</channel></rss>"

        assertEquals(50, parser.parseXml(xml).items.size)
    }

    // ---------------- 文本处理 ----------------

    @Test
    fun `stripHtml removes tags and collapses whitespace`() {
        assertEquals(
            "Hello world",
            parser.stripHtml("<p>Hello\n\t  world</p>")
        )
    }

    @Test
    fun `decodeEntities handles numeric entities and preserves unknown ones`() {
        assertEquals("A & B < c > d \"q\" 'p'", parser.decodeEntities("A &amp; B &lt; c &gt; d &quot;q&quot; &apos;p&apos;"))
        assertEquals("中 \uD83D\uDE00 é", parser.decodeEntities("&#20013; &#x1F600; &#xE9;"))
        // 不在表内的实体原样保留，不产生乱码
        assertEquals("中 \uD83D\uDE00 &eacute;", parser.decodeEntities("&#20013; &#x1F600; &eacute;"))
        assertEquals("x &#UNKNOWN;", parser.decodeEntities("x &#UNKNOWN;"))
        assertEquals("plain", parser.decodeEntities("plain"))
    }

    @Test
    fun `parseDate handles rss rfc3339 and fallback`() {
        // 带时区信息的格式必须解析出精确时刻（此前只断言 > 0，
        // 而失败回退也返回 now，等于格式表整个坏掉测试照样绿）
        val expectedNoon = 1756728000000L // 2025-09-01T12:00:00Z
        assertEquals(expectedNoon, parser.parseDate("Mon, 01 Sep 2025 12:00:00 GMT"))
        assertEquals(expectedNoon, parser.parseDate("Mon, 01 Sep 2025 12:00:00 +0000"))
        assertEquals(expectedNoon, parser.parseDate("2025-09-01T12:00:00Z"))
        assertEquals(expectedNoon, parser.parseDate("2025-09-01T12:00:00+00:00"))
        assertEquals(expectedNoon + 123, parser.parseDate("2025-09-01T12:00:00.123Z"))
        // 数字时区偏移（+0800）也要精确：本地时间减 8 小时
        assertEquals(expectedNoon - 8 * 3_600_000L, parser.parseDate("2025-09-01T12:00:00+0800"))

        // 无时区信息的格式按 JVM 默认时区解释：与同口径的 Calendar 期望值对比，
        // 不能写死 epoch（CI 与开发机时区可能不同）
        val cal = java.util.Calendar.getInstance()
        cal.clear()
        cal.set(2025, java.util.Calendar.SEPTEMBER, 1, 12, 0, 0)
        assertEquals(cal.timeInMillis, parser.parseDate("2025-09-01T12:00:00"))
        cal.clear()
        cal.set(2025, java.util.Calendar.SEPTEMBER, 1, 0, 0, 0)
        assertEquals(cal.timeInMillis, parser.parseDate("2025-09-01"))

        val now = System.currentTimeMillis()
        assertTrue(parser.parseDate(null) in (now - 5000)..(now + 5000))
        assertTrue(parser.parseDate("not a date") in (now - 5000)..(now + 5000))
    }

    @Test
    fun `atom self-closing link does not corrupt subsequent fields`() {
        // Atom 里 <link .../> 自闭合：KXml2 不发 END_TAG。
        // 修复前解析器进入采集态出不来，<title> 文本被吞掉/错位
        val xml = """
            <feed><title>Feed</title><entry>
            <link rel="alternate" href="https://example.com/a"/>
            <title>Entry A</title>
            <updated>2025-09-01T12:00:00Z</updated>
            </entry></feed>
        """.trimIndent()

        val feed = parser.parseXml(xml)
        assertEquals(1, feed.items.size)
        val item = feed.items.first()
        assertEquals("Entry A", item.title)
        assertEquals("https://example.com/a", item.link)
        assertEquals(1756728000000L, item.pubTimestamp)
    }

    @Test
    fun `self-closing empty text tags are skipped`() {
        val xml = "<rss><channel><title>F</title><description/>" +
            "<item><title>One</title><link>https://example.com/1</link>" +
            "<guid/></item></channel></rss>"
        val feed = parser.parseXml(xml)
        assertEquals(1, feed.items.size)
        assertEquals("One", feed.items.first().title)
        assertEquals("https://example.com/1", feed.items.first().link)
    }

    @Test
    fun `resolveCharset prefers header then xml declaration then utf8`() {
        val body = "<?xml version=\"1.0\" encoding=\"ISO-8859-1\"?><rss/>"
            .toByteArray(Charsets.US_ASCII)
        // HTTP 头优先
        assertEquals(
            java.nio.charset.Charset.forName("GBK"),
            parser.resolveCharset("application/rss+xml; charset=GBK", body),
        )
        // 无头时取 XML 声明
        assertEquals(
            java.nio.charset.Charset.forName("ISO-8859-1"),
            parser.resolveCharset(null, body),
        )
        // 带引号的合法写法也能取到
        assertEquals(
            java.nio.charset.Charset.forName("GBK"),
            parser.resolveCharset("text/xml; charset=\"GBK\"", body),
        )
        // 非法 charset 名回退
        assertEquals(
            Charsets.UTF_8,
            parser.resolveCharset("text/xml; charset=NOT_A_CHARSET", "<rss/>".toByteArray()),
        )
        // 什么都没有时默认 UTF-8
        assertEquals(Charsets.UTF_8, parser.resolveCharset(null, "<rss/>".toByteArray()))
    }

    @Test
    fun `resolveCharset detects utf16 BOM`() {
        val be = byteArrayOf(0xFE.toByte(), 0xFF.toByte(), 0, '<'.code.toByte())
        val le = byteArrayOf(0xFF.toByte(), 0xFE.toByte(), '<'.code.toByte(), 0)
        assertEquals(Charsets.UTF_16BE, parser.resolveCharset(null, be))
        assertEquals(Charsets.UTF_16LE, parser.resolveCharset(null, le))
    }

    @Test
    fun `pubTimestamp without a date falls back to now`() {
        val xml = "<rss><channel><title>F</title><item>" +
            "<title>One</title><link>https://example.com/1</link></item></channel></rss>"

        val ts = parser.parseXml(xml).items.first().pubTimestamp
        val now = System.currentTimeMillis()
        assertTrue(ts in (now - 5000)..(now + 5000))
    }
}
