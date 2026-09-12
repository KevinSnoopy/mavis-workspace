package com.eareyereading.util

import com.eareyereading.domain.model.TocEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * 目录提取单元测试：
 * - EpubParser 端到端：toc.ncx（EPUB2）/ nav.xhtml（EPUB3）/ 嵌套只取顶级 / h1 兜底
 * - TocHeadingScanner：Gutenberg 章标题扫描与误伤防护
 * - TocCodec：JSON 往返与脏数据容错
 */
class TocExtractionTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val parser = EpubParser()

    // ── EPUB 构造设施（与 EpubParserTest 同款最小化做法） ──

    private fun writeEpub(file: File, entries: Map<String, String>) {
        ZipOutputStream(file.outputStream()).use { zip ->
            entries.forEach { (name, content) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(content.toByteArray(Charsets.UTF_8))
                zip.closeEntry()
            }
        }
    }

    private fun chapter(vararg paragraphs: String): String =
        "<html><body>" + paragraphs.joinToString("") { "<p>$it</p>" } + "</body></html>"

    private fun opf(spineItems: List<Pair<String, String>>, extraManifest: String = ""): String {
        val manifest = spineItems.joinToString("\n        ") { (id, href) ->
            "<item id=\"$id\" href=\"$href\" media-type=\"application/xhtml+xml\"/>"
        }
        val spine = spineItems.joinToString("\n        ") { (id, _) -> "<itemref idref=\"$id\"/>" }
        return """
            <?xml version="1.0"?>
            <package xmlns="http://www.idpf.org/2007/opf" version="2.0">
              <manifest>
                $manifest
                $extraManifest
              </manifest>
              <spine>
                $spine
              </spine>
            </package>
        """.trimIndent()
    }

    private fun ncx(entries: List<Triple<String, String, String>>): String {
        val points = entries.joinToString("\n    ") { (id, title, src) ->
            """<navPoint id="$id" playOrder="1"><navLabel><text>$title</text></navLabel><content src="$src"/></navPoint>"""
        }
        return """
            <?xml version="1.0" encoding="UTF-8"?>
            <ncx xmlns="http://www.daisy.org/z3986/2005/ncx/" version="2005-1">
              <navMap>
            $points
              </navMap>
            </ncx>
        """.trimIndent()
    }

    // ── EPUB2: toc.ncx ─────────────────────────────────────

    @Test
    fun `epub2 ncx produces chapter titles and paragraph indexes`() {
        val file = tmp.newFile("book.epub")
        writeEpub(
            file,
            mapOf(
                "mimetype" to "application/epub+zip",
                "content.opf" to opf(
                    listOf("c1" to "chapter1.xhtml", "c2" to "chapter2.xhtml"),
                    """<item id="ncx" href="toc.ncx" media-type="application/x-dtbncx+xml"/>""",
                ),
                "toc.ncx" to ncx(
                    listOf(
                        Triple("np1", "Chapter One", "chapter1.xhtml"),
                        Triple("np2", "Chapter Two", "chapter2.xhtml"),
                    ),
                ),
                "chapter1.xhtml" to chapter("First alpha passage.", "Second alpha passage.", "Third alpha."),
                "chapter2.xhtml" to chapter("Beta passage here.", "Gamma passage here."),
            ),
        )

        val parsed = parser.parseBook(file.absolutePath)

        assertEquals(
            listOf(
                TocEntry("Chapter One", 0),
                TocEntry("Chapter Two", 3),
            ),
            parsed.chapters,
        )
    }

    @Test
    fun `nested navPoints only produce top level entries`() {
        val file = tmp.newFile("book.epub")
        val nestedNcx = """
            <?xml version="1.0" encoding="UTF-8"?>
            <ncx xmlns="http://www.daisy.org/z3986/2005/ncx/" version="2005-1">
              <navMap>
                <navPoint id="np1"><navLabel><text>Part One</text></navLabel><content src="chapter1.xhtml"/>
                  <navPoint id="np1-1"><navLabel><text>Section 1</text></navLabel><content src="chapter1.xhtml#s1"/></navPoint>
                </navPoint>
                <navPoint id="np2"><navLabel><text>Part Two</text></navLabel><content src="chapter2.xhtml"/></navPoint>
              </navMap>
            </ncx>
        """.trimIndent()
        writeEpub(
            file,
            mapOf(
                "content.opf" to opf(
                    listOf("c1" to "chapter1.xhtml", "c2" to "chapter2.xhtml"),
                    """<item id="ncx" href="toc.ncx" media-type="application/x-dtbncx+xml"/>""",
                ),
                "toc.ncx" to nestedNcx,
                "chapter1.xhtml" to chapter("Alpha passage text.", "Beta passage text."),
                "chapter2.xhtml" to chapter("Gamma passage text."),
            ),
        )

        val parsed = parser.parseBook(file.absolutePath)

        assertEquals(
            listOf(TocEntry("Part One", 0), TocEntry("Part Two", 2)),
            parsed.chapters,
        )
    }

    // ── EPUB3: nav.xhtml ───────────────────────────────────

    @Test
    fun `epub3 nav produces top level chapter entries`() {
        val file = tmp.newFile("book.epub")
        val nav = """
            <html xmlns="http://www.w3.org/1999/xhtml" xmlns:epub="http://www.idpf.org/2007/ops">
              <body><nav epub:type="toc"><ol>
                <li><a href="c1.xhtml">Alpha</a></li>
                <li><a href="c2.xhtml">Beta</a><ol><li><a href="c2.xhtml#s1">Beta.1</a></li></ol></li>
              </ol></nav></body>
            </html>
        """.trimIndent()
        val opf3 = opf(listOf("c1" to "c1.xhtml", "c2" to "c2.xhtml"))
            .replace("""version="2.0"""", """version="3.0"""")
            .replace(
                "</manifest>",
                """<item id="nav" href="nav.xhtml" media-type="application/xhtml+xml" properties="nav"/>""" +
                    "\n  </manifest>",
            )
        writeEpub(
            file,
            mapOf(
                "content.opf" to opf3,
                "nav.xhtml" to nav,
                "c1.xhtml" to chapter("One two three four.", "Five six seven eight."),
                "c2.xhtml" to chapter("Nine ten eleven twelve."),
            ),
        )

        val parsed = parser.parseBook(file.absolutePath)

        assertEquals(
            listOf(TocEntry("Alpha", 0), TocEntry("Beta", 2)),
            parsed.chapters,
        )
    }

    // ── 标题兜底 ───────────────────────────────────────────

    @Test
    fun `falls back to first heading when toc file missing`() {
        val file = tmp.newFile("book.epub")
        writeEpub(
            file,
            mapOf(
                "content.opf" to opf(listOf("c1" to "chapter1.xhtml", "c2" to "chapter2.xhtml")),
                "chapter1.xhtml" to "<html><body><h1>The First</h1><p>Alpha passage text.</p></body></html>",
                "chapter2.xhtml" to "<html><body><h2>Secondary Heading</h2><p>Beta passage text.</p></body></html>",
            ),
        )

        val parsed = parser.parseBook(file.absolutePath)

        assertEquals(
            listOf(TocEntry("The First", 0), TocEntry("Secondary Heading", 1)),
            parsed.chapters,
        )
    }

    @Test
    fun `heading extraction decodes entities and strips tags`() {
        assertNull(XhtmlParagraphExtractor.extractFirstHeading("<html><body><p>no heading</p></body></html>"))
        assertEquals(
            "Chapter & Test",
            XhtmlParagraphExtractor.extractFirstHeading("<h1>Chapter <em>&amp;</em> Test</h1>"),
        )
    }

    // ── TocHeadingScanner ──────────────────────────────────

    @Test
    fun `scanner detects gutenberg chapter headings with indexes`() {
        val paragraphs = listOf(
            "The Project Gutenberg eBook of Test",   // 书头
            "CHAPTER I",                              // 章 1 标题 @1
            "It is a truth universally acknowledged that a single man must be in want of a wife.",
            "CHAPTER II",                             // 章 2 标题 @3
            "However little known the feelings or views of such a man may be on his first entering.",
            "CHAPTER 3. Loomings.",                   // 阿拉伯数字 + 副题 @5
            "Call me Ishmael. Some years ago never mind how long precisely having little or no money.",
            "CHAPTER IV",                             // 章 4 标题 @7
            "The pale Usher umbrella in his hands.",
        )

        assertEquals(
            listOf(
                TocEntry("CHAPTER I", 1),
                TocEntry("CHAPTER II", 3),
                TocEntry("CHAPTER 3. Loomings.", 5),
                TocEntry("CHAPTER IV", 7),
            ),
            TocHeadingScanner.scan(paragraphs),
        )
    }

    @Test
    fun `scanner returns empty when hits below threshold`() {
        val paragraphs = listOf(
            "Normal opening paragraph mentioning chapter ten in prose flowing text.",
            "CHAPTER I",
            "Body text of the only chapter that exists here with enough length.",
        )
        assertTrue(TocHeadingScanner.scan(paragraphs).isEmpty())
    }

    @Test
    fun `scanner ignores long paragraphs and image markers`() {
        val longLine = "chapter " + "x".repeat(100)
        val paragraphs = listOf(
            "CHAPTER I",
            longLine,                       // 超长，非标题段
            "[[IMG:0]]",                    // 插图标记段
            "CHAPTER II",
            "Body text one with sufficient length for paragraph extraction filter.",
            "CHAPTER III",
            "Body text two with sufficient length for paragraph extraction filter.",
        )

        assertEquals(
            listOf(TocEntry("CHAPTER I", 0), TocEntry("CHAPTER II", 3), TocEntry("CHAPTER III", 5)),
            TocHeadingScanner.scan(paragraphs),
        )
    }

    // ── TocCodec ───────────────────────────────────────────

    @Test
    fun `codec roundtrips entries`() {
        val entries = listOf(TocEntry("Chapter One", 0), TocEntry("Chapter & Two", 12))
        assertEquals(entries, TocCodec.decode(TocCodec.encode(entries)))
    }

    @Test
    fun `codec handles edge cases`() {
        assertNull(TocCodec.encode(emptyList()))
        assertTrue(TocCodec.decode(null).isEmpty())
        assertTrue(TocCodec.decode("").isEmpty())
        // 脏 JSON 降级为空列表，不抛异常
        assertTrue(TocCodec.decode("{{{not json").isEmpty())
        // 非法条目逐条跳过：空标题 / 负下标 / 非对象元素
        assertTrue(
            TocCodec.decode("""[{"t":"","p":3},{"t":"Ok","p":-1},"junk",{"t":"Keep","p":7}]""")
                .containsExactly(TocEntry("Keep", 7)),
        )
    }

    private fun List<TocEntry>.containsExactly(vararg expected: TocEntry): Boolean =
        this.size == expected.size && this.zip(expected.toList()).all { (a, b) -> a == b }
}
