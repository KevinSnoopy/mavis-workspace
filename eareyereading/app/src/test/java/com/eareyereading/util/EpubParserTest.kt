package com.eareyereading.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.RandomAccessFile
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
        assertEquals(listOf("The first chapter begins here.", "The second chapter continues the story."), result.paragraphs)
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
        assertFalse(result.paragraphs.any { it.contains("Front matter") })
        assertEquals(listOf("The real chapter content is here."), result.paragraphs)
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
        assertFalse(result.paragraphs.any { it.contains("Cover page") })
        assertEquals(listOf("Chapter one real content appears."), result.paragraphs)
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
        assertEquals(listOf("Encoded filename chapter content."), result.paragraphs)
    }

    // ── 元数据提取（issue 9.1）────────────────────────────

    @Test
    fun `metadata is extracted from OPF dc fields`() {
        val f = tmp.newFile("meta.epub")
        val opfWithMeta = """
            <?xml version="1.0"?>
            <package xmlns="http://www.idpf.org/2007/opf" version="2.0">
              <metadata>
                <dc:title>Journey to the West</dc:title>
                <dc:creator>Wu Cheng'en</dc:creator>
                <dc:language>zh-CN</dc:language>
              </metadata>
              <manifest>
                <item id="c1" href="ch1.xhtml" media-type="application/xhtml+xml"/>
              </manifest>
              <spine>
                <itemref idref="c1"/>
              </spine>
            </package>
        """.trimIndent()
        writeEpub(
            f,
            mapOf(
                "content.opf" to opfWithMeta,
                "ch1.xhtml" to chapter(listOf("Chapter one content.")),
            ),
        )
        val result = parser.parseBook(f.absolutePath)
        assertEquals("Journey to the West", result.title)
        assertEquals("Wu Cheng'en", result.author)
        assertEquals("zh-cn", result.language)
    }

    @Test
    fun `metadata falls back to empty when OPF has none`() {
        val f = tmp.newFile("nometa.epub")
        writeEpub(
            f,
            mapOf(
                "content.opf" to opf("c1::ch1.xhtml"),
                "ch1.xhtml" to chapter(listOf("Some paragraph content.")),
            ),
        )
        val result = parser.parseBook(f.absolutePath)
        assertEquals("", result.title)
        assertEquals("", result.author)
        assertEquals("", result.language)
    }

    // ── container.xml（issue 9.5）──────────────────────────

    @Test
    fun `opf is located via META-INF container xml`() {
        val f = tmp.newFile("container.epub")
        val container = """
            <?xml version="1.0"?>
            <container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
              <rootfiles>
                <rootfile full-path="OEBPS/book.opf" media-type="application/oebps-package+xml"/>
              </rootfiles>
            </container>
        """.trimIndent()
        // 放一个干扰性的 .opf（按字典序会在 book.opf 之前）验证 container.xml 优先
        writeEpub(
            f,
            mapOf(
                "META-INF/container.xml" to container,
                "aaa.opf" to opf("c1::ch1.xhtml"),
                "ch1.xhtml" to chapter(listOf("Wrong opf would be picked first.")),
                "OEBPS/book.opf" to opf("c1::real.xhtml"),
                "OEBPS/real.xhtml" to chapter(listOf("Real container xml opf content.")),
            ),
        )
        val result = parser.parseBook(f.absolutePath)
        assertTrue(result.paragraphs.any { it.contains("Real container xml opf content.") })
    }

    // ── 编码探测（issue 9.6）──────────────────────────────

    @Test
    fun `chapter with meta charset declaration is decoded`() {
        val f = tmp.newFile("charset.epub")
        // GBK 编码的"中文章节内容"（按 byte 写 zip，模拟非 UTF-8 章节）
        val gbkText = "中文章节的正文内容。"
        val html = "<html><head><meta charset=\"GBK\"></head><body><p>$gbkText</p></body></html>"
        ZipOutputStream(f.outputStream()).use { zip ->
            zip.putNextEntry(ZipEntry("content.opf"))
            zip.write(opf("c1::ch1.xhtml").toByteArray(Charsets.UTF_8))
            zip.closeEntry()
            zip.putNextEntry(ZipEntry("ch1.xhtml"))
            zip.write(html.toByteArray(java.nio.charset.Charset.forName("GBK")))
            zip.closeEntry()
        }
        val result = parser.parseBook(f.absolutePath)
        assertTrue("expected GBK text to be decoded, got ${result.paragraphs}", result.paragraphs.any { it.contains("中文章节") })
    }

    // ── 图册（issue 9.8）──────────────────────────────────

    @Test
    fun `image only epub throws ImageOnly`() {
        val f = tmp.newFile("images.epub")
        writeEpub(
            f,
            mapOf(
                "content.opf" to opf("c1::pics.xhtml"),
                "pics.xhtml" to "<html><body><p><img src=\"a.jpg\"/><img src=\"b.jpg\"/></p></body></html>",
            ),
        )
        val e = runCatching { parser.parseBook(f.absolutePath) }.exceptionOrNull()
        assertTrue("expected EpubParseException.ImageOnly but got $e", e is EpubParseException.ImageOnly)
    }

    // ── 截断标记（issue 9.2）──────────────────────────────

    @Test
    fun `truncated book flags wasTruncated`() {
        val f = tmp.newFile("big.epub")
        // 单文档读取上限 MAX_DOC_CHARS=2M，所以用多章叠加：
        // 每章 ~1.95MB，6 章累计 ~11.7MB > MAX_TOTAL_CHARS(10M)，第 6 章触发截断
        val huge = "word ".repeat(390_000)  // ~1.95MB
        val entries = mutableMapOf<String, String>()
        val refs = (1..7).map { "c$it::ch$it.xhtml" }
        entries["content.opf"] = opf(*refs.toTypedArray())
        for (i in 1..6) entries["ch$i.xhtml"] = chapter(listOf(huge))
        entries["ch7.xhtml"] = chapter(listOf("After the truncation boundary."))
        writeEpub(f, entries)
        val result = parser.parseBook(f.absolutePath)
        assertTrue("expected wasTruncated=true for oversized book", result.wasTruncated)
        assertFalse(result.paragraphs.any { it.contains("After the truncation boundary") })
    }

    // ── manifest 属性顺序（issue 10.9）─────────────────────

    @Test
    fun `manifest handles href before id and extra attributes`() {
        val f = tmp.newFile("manifest.epub")
        val opfOrdered = """
            <?xml version="1.0"?>
            <package xmlns="http://www.idpf.org/2007/opf" version="2.0">
              <manifest>
                <item href="chap1.xhtml" id="c1" media-type="application/xhtml+xml" properties="scripted"/>
                <item media-type="application/xhtml+xml" id="c2" href="chap2.xhtml"/>
              </manifest>
              <spine>
                <itemref idref="c1"/>
                <itemref idref="c2"/>
              </spine>
            </package>
        """.trimIndent()
        writeEpub(
            f,
            mapOf(
                "content.opf" to opfOrdered,
                "chap1.xhtml" to chapter(listOf("First ordered manifest chapter.")),
                "chap2.xhtml" to chapter(listOf("Second ordered manifest chapter.")),
            ),
        )
        val result = parser.parseBook(f.absolutePath)
        assertEquals(
            listOf("First ordered manifest chapter.", "Second ordered manifest chapter."),
            result.paragraphs,
        )
    }

    // ── 防御性上限（issue 10.3 / 10.4）──────────────────────

    @Test
    fun `oversized file beyond byte cap throws Corrupted`() {
        val f = tmp.newFile("huge.epub")
        // 稀疏文件扩展到上限+1 字节，无需真实写入 200MB 内容
        RandomAccessFile(f, "rw").use { raf -> raf.setLength(200L * 1024 * 1024 + 1) }
        val e = runCatching { parser.parseBook(f.absolutePath) }.exceptionOrNull()
        assertTrue("expected EpubParseException.Corrupted but got $e", e is EpubParseException.Corrupted)
    }

    @Test
    fun `zip with too many entries throws Corrupted`() {
        val f = tmp.newFile("bomb.epub")
        // 5001 个条目 > MAX_ZIP_ENTRIES(5000)，触发算法炸弹防护
        val entries = HashMap<String, String>()
        for (i in 0 until 5001) entries["entry$i.dat"] = "x"
        writeEpub(f, entries)
        val e = runCatching { parser.parseBook(f.absolutePath) }.exceptionOrNull()
        assertTrue("expected EpubParseException.Corrupted but got $e", e is EpubParseException.Corrupted)
    }
}
