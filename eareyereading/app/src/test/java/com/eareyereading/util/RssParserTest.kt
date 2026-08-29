package com.eareyereading.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [RssParser].
 *
 * 重点覆盖 2026-08-29 的线上崩溃：
 *
 * ```
 * org.xmlpull.v1.XmlPullParserException: expected: END_TAG {null}null
 *   (position:TEXT NPR Topics: News@4:28 in java.io.StringReader)
 *     at com.android.org.kxml2.io.KXmlParser.require(KXmlParser.java:2145)
 *     at com.eareyereading.util.RssParser.readText(RssParser.kt:180)
 * ```
 *
 * 根因：旧实现 `readText` 调用一次 `next()` 后就 `require(END_TAG)`：
 *
 * ```kotlin
 * if (parser.next() == XmlPullParser.TEXT) {
 *     result = parser.text?.trim() ?: ""
 *     parser.require(XmlPullParser.END_TAG, null, parser.name)   // ← 崩溃点
 * }
 * ```
 *
 * 而 `require()` 是**纯断言，不推进解析器**。`next()` 之后解析器仍停在 TEXT 事件上，
 * 所以只要 `<title>` / `<subtitle>` 有文本内容就必然抛异常 —— 不是「特殊内容」才崩。
 * 已用设备上的 KXml2 实测验证：TEXT 事件上 `getName()` 为 null，故消息为
 * `expected: END_TAG {null}null`；异常消息在 100 字符处截断。
 * 新实现是不依赖 `require()` / `nextText()` 的显式事件状态机。
 */
class RssParserTest {

    private val parser = RssParser()

    // ---------------- 崩溃复现 ----------------

    @Test
    fun `multi text node channel title no longer crashes`() {
        // 崩溃日志里的原始形态：标题前有一个空白 TEXT 节点
        val xml = """
            <?xml version="1.0" encoding="UTF-8"?>
            <rss version="2.0">
              <channel>
                <title>
                NPR Topics: News</title>
                <link>https://www.npr.org/rss/rss.php?id=1001</link>
                <description>NPR topics</description>
              </channel>
            </rss>
        """.trimIndent()

        val feed = parser.parseXml(xml)

        assertEquals("NPR Topics: News", feed.title)
        assertEquals("https://www.npr.org/rss/rss.php?id=1001", feed.link)
    }

    @Test
    fun `require does not advance the parser so the old readText always threw`() {
        // 记录崩溃机制本身：require() 是断言，不会推进解析器
        val xml = "<title>\nNPR Topics: News</title>"
        val p = org.xmlpull.v1.XmlPullParserFactory.newInstance().newPullParser()
        p.setInput(java.io.StringReader(xml))

        assertEquals(org.xmlpull.v1.XmlPullParser.START_TAG, p.next())
        assertEquals("title", p.name)
        assertEquals(org.xmlpull.v1.XmlPullParser.TEXT, p.next())
        assertEquals("NPR Topics: News", p.text.trim())

        // TEXT 事件上 getName() 是 null —— 这就是日志里 {null}null 的来源
        assertNull(p.name)

        val e = org.junit.Assert.assertThrows(org.xmlpull.v1.XmlPullParserException::class.java) {
            p.require(org.xmlpull.v1.XmlPullParser.END_TAG, null, p.name)
        }
        // 与线上日志逐字一致（消息在 100 字符处截断）
        assertTrue(
            "expected: ${e.message}",
            e.message!!.startsWith("expected: END_TAG {null}null (position:TEXT")
        )
    }

    @Test
    fun `cdata channel title is read`() {
        val xml = """
            <rss><channel><title><![CDATA[NPR Topics: News]]></title>
            <description><![CDATA[<p>Hello</p>]]></description></channel></rss>
        """.trimIndent()

        val feed = parser.parseXml(xml)

        assertEquals("NPR Topics: News", feed.title)
        assertEquals("Hello", feed.description)
    }

    @Test
    fun `cdata item titles and descriptions are not dropped`() {
        // 旧实现的 TEXT 分支不处理 CDATA 事件，这类 item 会被整体丢弃
        val xml = """
            <rss><channel><title>F</title><item>
              <title><![CDATA[<em>Breaking</em> news]]></title>
              <link>https://example.com/1</link>
              <description><![CDATA[<p>Body &amp; more</p>]]></description>
              <pubDate>Sun, 31 Aug 2025 09:15:00 GMT</pubDate>
            </item></channel></rss>
        """.trimIndent()

        val feed = parser.parseXml(xml)

        assertEquals(1, feed.items.size)
        val item = feed.items.first()
        assertEquals("Breaking news", item.title)
        assertEquals("https://example.com/1", item.link)
        assertEquals("Body & more", item.description)
        assertTrue(item.pubTimestamp > 0)
    }

    @Test
    fun `nested element inside title is skipped without throwing`() {
        val xml = """
            <rss><channel><title>F</title><item>
              <title>First <b>bold</b> rest</title>
              <link>https://example.com/1</link>
            </item></channel></rss>
        """.trimIndent()

        val feed = parser.parseXml(xml)

        assertEquals(1, feed.items.size)
        assertEquals("First bold rest", feed.items.first().title)
    }

    @Test
    fun `structurally broken feed stops at the error without crashing`() {
        // KXml2 遇到 </item> 而 <description> 未闭合时直接抛
        //   XmlPullParserException: expected: /description read: item
        // 解析器实例随之报废，无法"跳过这一段继续解析"。所以这里能诚实保证的是：
        // 不崩、不泄漏到下一条数据、并保留错误发生前已经解析到的内容。
        val xml = """
            <rss><channel><title>F</title><item>
              <title>Good title
              <description>lost because title was never closed
            </item><item>
              <title>Second</title><link>https://example.com/2</link>
            </item></channel></rss>
        """.trimIndent()

        val feed = parser.parseXml(xml)

        assertEquals("F", feed.title)
        // 错误点之后的 item 拿不到（KXml2 的结构性错误会让解析终止）
        assertTrue(feed.items.isEmpty())
    }

    @Test
    fun `truncated feed returns what it managed to parse`() {
        val xml = """
            <rss><channel><title>T</title><item>
              <title>One</title><link>https://example.com/1</link>
            <item><title>Two</title>
        """.trimIndent()

        val feed = parser.parseXml(xml)

        assertEquals("T", feed.title)
        assertEquals(1, feed.items.size)
        assertEquals("One", feed.items.first().title)
    }

    @Test
    fun `empty and junk input returns an empty feed instead of throwing`() {
        val empty = parser.parseXml("")
        assertEquals("RSS Feed", empty.title)
        assertTrue(empty.items.isEmpty())

        val junk = parser.parseXml("this is not xml at all")
        assertTrue(junk.items.isEmpty())

        val brackets = parser.parseXml("<<<>>>")
        assertTrue(brackets.items.isEmpty())
    }

    // ---------------- 常规 RSS / Atom ----------------

    @Test
    fun `standard rss feed parses items with text link`() {
        val xml = """
            <rss version="2.0">
              <channel>
                <title>Feed Title</title>
                <link>https://example.com/</link>
                <description>Feed desc</description>
                <item>
                  <title>Article one</title>
                  <link>https://example.com/1</link>
                  <description>&lt;p&gt;Some body&lt;/p&gt;</description>
                  <pubDate>Mon, 01 Sep 2025 12:00:00 +0000</pubDate>
                </item>
                <item>
                  <title>Article two</title>
                  <link>https://example.com/2</link>
                </item>
              </channel>
            </rss>
        """.trimIndent()

        val feed = parser.parseXml(xml)

        assertEquals("Feed Title", feed.title)
        assertEquals("https://example.com/", feed.link)
        assertEquals(2, feed.items.size)
        assertEquals("Some body", feed.items[0].description)
        assertTrue(feed.items[0].pubTimestamp > 0)
        assertEquals("Article two", feed.items[1].title)
        assertNull(feed.items[1].description)
    }

    @Test
    fun `atom feed uses rel alternate link and ignores rel self`() {
        val xml = """
            <feed xmlns="http://www.w3.org/2005/Atom">
              <title>Atom Feed</title>
              <subtitle>The subtitle</subtitle>
              <link rel="self" href="https://example.com/feed.xml"/>
              <link rel="alternate" href="https://example.com/"/>
              <entry>
                <title>Entry one</title>
                <link rel="self" href="https://example.com/entry/1.xml"/>
                <link rel="alternate" href="https://example.com/entry/1"/>
                <summary>Summary text</summary>
                <published>2025-09-01T12:00:00+00:00</published>
              </entry>
            </feed>
        """.trimIndent()

        val feed = parser.parseXml(xml)

        assertEquals("Atom Feed", feed.title)
        assertEquals("https://example.com/", feed.link)
        assertEquals(1, feed.items.size)
        val item = feed.items.first()
        assertEquals("Entry one", item.title)
        assertEquals("https://example.com/entry/1", item.link)
        assertEquals("Summary text", item.description)
        assertTrue(item.pubTimestamp > 0)
    }

    @Test
    fun `dc date namespace is recognised as the publish date`() {
        val xml = """
            <rss xmlns:dc="http://purl.org/dc/elements/1.1/" version="2.0">
              <channel><title>F</title>
                <item>
                  <title>One</title>
                  <link>https://example.com/1</link>
                  <dc:date>2025-09-02T08:30:00Z</dc:date>
                </item>
              </channel>
            </rss>
        """.trimIndent()

        val feed = parser.parseXml(xml)

        assertEquals(1, feed.items.size)
        assertEquals("2025-09-02T08:30:00Z", feed.items.first().pubDate)
        assertEquals(parser.parseDate("2025-09-02T08:30:00Z"), feed.items.first().pubTimestamp)
    }

    @Test
    fun `undeclared namespace prefix does not kill the feed`() {
        // 很多 feed 写 <content:encoded> / <dc:date> 却不声明 xmlns:xxx。
        // 开启 namespace 处理后 KXml2 会直接抛 "undefined prefix" 让整份 feed 报废，
        // 这里必须容错（否则 WordPress 类 feed 一条文章都拿不到）。
        val undeclared = """
            <rss version="2.0"><channel><title>F</title><item>
              <title>One</title><link>https://example.com/1</link>
              <content:encoded><p>Body</p></content:encoded>
              <dc:date>2025-09-03T09:00:00Z</dc:date>
            </item></channel></rss>
        """.trimIndent()

        val feed = parser.parseXml(undeclared)

        assertEquals("F", feed.title)
        assertEquals(1, feed.items.size)
        val item = feed.items.first()
        assertEquals("Body", item.description)
        assertEquals("2025-09-03T09:00:00Z", item.pubDate)

        // 声明了 xmlns 的规范写法同样可用
        val declared = undeclared
            .replaceFirst("<rss version=\"2.0\">",
                "<rss xmlns:content=\"http://purl.org/rss/1.0/modules/content/\"" +
                    " xmlns:dc=\"http://purl.org/dc/elements/1.1/\" version=\"2.0\">")
        val declaredFeed = parser.parseXml(declared)
        assertEquals(1, declaredFeed.items.size)
        assertEquals("Body", declaredFeed.items.first().description)
    }

    @Test
    fun `content encoded html is stripped and bounded`() {
        val longHtml = "<p>" + "word ".repeat(5000) + "</p>"
        val xml = """
            <rss><channel><title>F</title><item>
              <title>One</title><link>https://example.com/1</link>
              <content:encoded>${longHtml}</content:encoded>
            </item></channel></rss>
        """.trimIndent()

        val feed = parser.parseXml(xml)

        assertEquals(1, feed.items.size)
        val desc = feed.items.first().description
        assertNotNull(desc)
        assertTrue("description should be bounded, was ${desc!!.length}", desc.length <= 500)
        assertFalse(desc.contains("<p>"))
    }

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
        assertTrue(parser.parseDate("Mon, 01 Sep 2025 12:00:00 GMT") > 0)
        assertTrue(parser.parseDate("Mon, 01 Sep 2025 12:00:00 +0000") > 0)
        assertTrue(parser.parseDate("2025-09-01T12:00:00Z") > 0)
        assertTrue(parser.parseDate("2025-09-01T12:00:00+00:00") > 0)
        assertTrue(parser.parseDate("2025-09-01T12:00:00.123Z") > 0)
        assertTrue(parser.parseDate("2025-09-01") > 0)

        val now = System.currentTimeMillis()
        assertTrue(parser.parseDate(null) in (now - 5000)..(now + 5000))
        assertTrue(parser.parseDate("not a date") in (now - 5000)..(now + 5000))
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
