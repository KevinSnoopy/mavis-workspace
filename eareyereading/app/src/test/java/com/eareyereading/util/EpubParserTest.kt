package com.eareyereading.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * EpubParser 单元测试：
 * - 解析失败抛可区分的 EpubParseException（issue 9.3）
 * - spine linear="no" 与非正文 href 黑名单过滤（issue 9.4）
 */
class EpubParserTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val parser = EpubParser()

    private fun writeEpub(file: File, entries: Map<String, String>) {
        ZipOutputStream(file.outputStream()).use { zip ->
            entries.forEach { (name, content) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(content.toByteArray(Charsets.UTF_8))
                zip.closeEntry()
            }
        }
    }

    private fun opf(vararg itemrefs: String): String {
        val manifest = itemrefs.joinToString("\n    ") { ref ->
            val (id, href) = ref.split("::")
            "<item id=\"$id\" href=\"$href\" media-type=\"application/xhtml+xml\"/>"
        }
        val spine = itemrefs.joinToString("\n    ") { ref ->
            val id = ref.split("::")[0]
            "<itemref idref=\"$id\"/>"
        }
        return """
            <?xml version="1.0"?>
            <package xmlns="http://www.idpf.org/2007/opf" version="2.0">
              <manifest>
                $manifest
              </manifest>
              <spine>
                $spine
              </spine>
            </package>
        """.trimIndent()
    }

    private fun chapter(paragraphs: List<String>): String =
        "<html><body>" + paragraphs.joinToString("") { "<p>$it</p>" } + "</body></html>"

    // ── 解析失败分类（issue 9.3）────────────────────────────

    @Test
    fun `missing file throws Empty`() {
        val e = runCatching { parser.parseBook(File(tmp.root, "no-such.epub").absolutePath) }.exceptionOrNull()
        assertTrue("expected EpubParseException.Empty but got $e", e is EpubParseException.Empty)
    }

    @Test
    fun `zero byte file throws Empty`() {
        val f = tmp.newFile("empty.epub")
        val e = runCatching { parser.parseBook(f.absolutePath) }.exceptionOrNull()
        assertTrue("expected EpubParseException.Empty but got $e", e is EpubParseException.Empty)
    }

    @Test
    fun `garbage file throws Corrupted`() {
        val f = tmp.newFile("garbage.epub")
        f.writeBytes("this is definitely not a zip archive".toByteArray())
        val e = runCatching { parser.parseBook(f.absolutePath) }.exceptionOrNull()
        assertTrue("expected EpubParseException.Corrupted but got $e", e is EpubParseException.Corrupted)
    }

    @Test
    fun `zip without opf throws NoOpf`() {
        val f = tmp.newFile("noopf.epub")
        writeEpub(f, mapOf("chapter1.xhtml" to chapter(listOf("some random paragraph text"))))
        val e = runCatching { parser.parseBook(f.absolutePath) }.exceptionOrNull()
        assertTrue("expected EpubParseException.NoOpf but got $e", e is EpubParseException.NoOpf)
    }

    @Test
    fun `opf without readable content throws NoContent`() {
        val f = tmp.newFile("emptybook.epub")
        writeEpub(
            f,
            mapOf(
                "content.opf" to opf("ch1::chapter1.xhtml"),
                "chapter1.xhtml" to "<html><body></body></html>",
            ),
        )
        val e = runCatching { parser.parseBook(f.absolutePath) }.exceptionOrNull()
        assertTrue("expected EpubParseException.NoContent but got $e", e is EpubParseException.NoContent)
    }

    // ── spine 过滤（issue 9.4）──────────────────────────────

    @Test
    fun `parses normal chapters in spine order`() {
        val f = tmp.newFile("book.epub")
        writeEpub(
            f,
            mapOf(
                "content.opf" to opf("c1::a.xhtml", "c2::b.xhtml"),
                "a.xhtml" to chapter(listOf("The first chapter begins here.")),
                "b.xhtml" to chapter(listOf("The second chapter continues the story.")),
            ),
        )
        val result = parser.parseBook(f.absolutePath)
        assertEquals(listOf("The first chapter begins here.", "The second chapter continues the story."), result)
    }

    @Test
    fun `linear=no spine item is skipped`() {
        val f = tmp.newFile("linear.epub")
        val opfWithLinearNo = """
            <?xml version="1.0"?>
            <package xmlns="http://www.idpf.org/2007/opf" version="2.0">
              <manifest>
                <item id="fm" href="frontmatter.xhtml" media-type="application/xhtml+xml"/>
                <item id="c1" href="chapter1.xhtml" media-type="application/xhtml+xml"/>
              </manifest>
              <spine>
                <itemref idref="fm" linear="no"/>
                <itemref idref="c1" linear="yes"/>
              </spine>
            </package>
        """.trimIndent()
        writeEpub(
            f,
            mapOf(
                "content.opf" to opfWithLinearNo,
                "frontmatter.xhtml" to chapter(listOf("Front matter that should be skipped.")),
                "chapter1.xhtml" to chapter(listOf("The real chapter content is here.")),
            ),
        )
        val result = parser.parseBook(f.absolutePath)
        assertFalse(result.any { it.contains("Front matter") })
        assertEquals(listOf("The real chapter content is here."), result)
    }

    @Test
    fun `blacklisted non-content hrefs are skipped`() {
        val f = tmp.newFile("cover.epub")
        writeEpub(
            f,
            mapOf(
                "content.opf" to opf("cov::cover.xhtml", "c1::chapter1.xhtml"),
                "cover.xhtml" to chapter(listOf("Cover page standalone placeholder.")),
                "chapter1.xhtml" to chapter(listOf("Chapter one real content appears.")),
            ),
        )
        val result = parser.parseBook(f.absolutePath)
        assertFalse(result.any { it.contains("Cover page") })
        assertEquals(listOf("Chapter one real content appears."), result)
    }

    @Test
    fun `url encoded href resolves to entry`() {
        val f = tmp.newFile("encoded.epub")
        writeEpub(
            f,
            mapOf(
                "content.opf" to opf("c1::my%20chapter.xhtml"),
                "my chapter.xhtml" to chapter(listOf("Encoded filename chapter content.")),
            ),
        )
        val result = parser.parseBook(f.absolutePath)
        assertEquals(listOf("Encoded filename chapter content."), result)
    }
}
