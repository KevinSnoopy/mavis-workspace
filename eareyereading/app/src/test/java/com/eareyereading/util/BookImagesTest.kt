package com.eareyereading.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * 插图标记协议（BookImages）+ 解析器插图提取（EpubParser / ArticleParser）单元测试。
 */
class BookImagesTest {

    @get:Rule
    val tmp = TemporaryFolder()

    // ── BookImages 标记协议 ─────────────────────────────────

    @Test
    fun `marker paragraph is recognized and ref extracted`() {
        assertTrue(BookImages.isImageMarker("[[IMG:3]]"))
        assertTrue(BookImages.isImageMarker("[[IMG:https://cdn.example.com/pic.jpg]]"))
        assertEquals("3", BookImages.markerRef("[[IMG:3]]"))
        assertEquals("https://cdn.example.com/pic.jpg", BookImages.markerRef("[[IMG:https://cdn.example.com/pic.jpg]]"))
    }

    @Test
    fun `plain text is not a marker`() {
        assertFalse(BookImages.isImageMarker("Normal paragraph text."))
        assertFalse(BookImages.isImageMarker("[[IMG:3]] trailing text"))
        assertFalse(BookImages.isImageMarker(""))
        assertNull(BookImages.markerRef("Normal paragraph text."))
    }

    @Test
    fun `isLoadableImageRef validates image references`() {
        // EPUB 本地图序号：始终有效
        assertTrue(BookImages.isLoadableImageRef("0"))
        assertTrue(BookImages.isLoadableImageRef("42"))
        // 正常 URL：有效
        assertTrue(BookImages.isLoadableImageRef("https://cdn.example.com/photo.jpg"))
        assertTrue(BookImages.isLoadableImageRef("http://example.com/img/abc.png?w=720"))
        // JS 占位残留 URL（旧导入数据）：无效
        assertFalse(BookImages.isLoadableImageRef("https://www.npr.org/2026/09/04/g-s1-141972/undefined"))
        assertFalse(BookImages.isLoadableImageRef("https://example.com/article/null"))
        assertFalse(BookImages.isLoadableImageRef("https://example.com/path/nan?x=1"))
        // 非 http(s) 非 URL：无效
        assertFalse(BookImages.isLoadableImageRef("not-a-url"))
        assertFalse(BookImages.isLoadableImageRef(""))
    }

    @Test
    fun `inline markers are stripped from text`() {
        assertEquals("Text around.", BookImages.stripImageMarkers("Text [[IMG:0]] around."))
        assertEquals("Clean text only.", BookImages.stripImageMarkers("Clean text only."))
        assertEquals("", BookImages.stripImageMarkers("[[IMG:https://x.example/a.png]]"))
    }

    @Test
    fun `mixed paragraph expands into separate marker and text paragraphs`() {
        val expanded = BookImages.expandInlineMarkers(listOf("before [[IMG:7]] after"))
        assertEquals(listOf("before", "[[IMG:7]]", "after"), expanded)
    }

    // ── <img> → 标记 的共享转换（ArticleParser / RssParser 共用）─────────

    @Test
    fun `replaceImgTagsWithMarkers keeps absolute url and pads blank lines`() {
        val out = BookImages.replaceImgTagsWithMarkers(
            "<p>Text.</p><img src=\"https://cdn.example.com/a.jpg\"/>",
            baseUrl = "https://www.example.com/story",
        )
        assertEquals("<p>Text.</p>\n\n[[IMG:https://cdn.example.com/a.jpg]]\n\n", out)
    }

    @Test
    fun `replaceImgTagsWithMarkers resolves relative src against base`() {
        val out = BookImages.replaceImgTagsWithMarkers(
            "<img src='/img/pic.png'>",
            baseUrl = "https://www.example.com/story/page.html",
        )
        assertEquals("\n\n[[IMG:https://www.example.com/img/pic.png]]\n\n", out)
    }

    @Test
    fun `replaceImgTagsWithMarkers drops noise svg and data uri images`() {
        val html = "<img src=\"https://t.example/pixel.gif\"/>" +
            "<img src=\"https://cdn.example.com/logo.svg\"/>" +
            "<img src=\"data:image/png;base64,iVBOR\"/>" +
            "<img src=\"https://cdn.example.com/real.jpg\"/>"
        val out = BookImages.replaceImgTagsWithMarkers(html, baseUrl = "https://www.example.com/")
        assertEquals("\n\n[[IMG:https://cdn.example.com/real.jpg]]\n\n", out)
    }

    @Test
    fun `replaceImgTagsWithMarkers without base drops relative src`() {
        val out = BookImages.replaceImgTagsWithMarkers("<img src=\"img/rel.jpg\"/>", baseUrl = null)
        assertEquals("", out)
    }

    @Test
    fun `replaceImgTagsWithMarkers drops JS lazy-load placeholder src`() {
        // Modern sites emit <img src="undefined"> or src="#" server-side; the real
        // URL is written by client JS into data-src. These must not become markers.
        val html = "<img src=\"undefined\"/>" +
            "<img src=\"null\"/>" +
            "<img src=\"#\"/>" +
            "<img src=\"about:blank\"/>" +
            "<img src=\"javascript:void(0)\"/>" +
            "<img src=\"https://cdn.example.com/real.jpg\"/>"
        val out = BookImages.replaceImgTagsWithMarkers(html, baseUrl = "https://www.npr.org/2026/09/04/story")
        assertEquals("\n\n[[IMG:https://cdn.example.com/real.jpg]]\n\n", out)
    }

    // ── EpubParser：<img> → 标记 + zip 条目登记 ─────────────

    private fun writeEpub(file: File, entries: Map<String, String>) {
        ZipOutputStream(file.outputStream()).use { zip ->
            entries.forEach { (name, content) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(content.toByteArray(Charsets.UTF_8))
                zip.closeEntry()
            }
        }
    }

    private fun opfWithImages(): String = """
        <?xml version="1.0"?>
        <package xmlns="http://www.idpf.org/2007/opf" version="2.0">
          <manifest>
            <item id="c1" href="chapter1.xhtml" media-type="application/xhtml+xml"/>
          </manifest>
          <spine>
            <itemref idref="c1"/>
          </spine>
        </package>
    """.trimIndent()

    @Test
    fun `epub img tag becomes marker paragraph with resolved index`() {
        val f = tmp.newFile("images.epub")
        writeEpub(
            f,
            mapOf(
                "content.opf" to opfWithImages(),
                "chapter1.xhtml" to (
                    "<html><body>" +
                        "<p>First paragraph of the chapter text.</p>" +
                        "<p><img src=\"images/pic.jpg\" alt=\"illustration\"/></p>" +
                        "<p>Second paragraph after the picture.</p>" +
                        "</body></html>"
                    ),
                "images/pic.jpg" to "fake-jpeg-bytes",
            ),
        )
        val result = EpubParser().parseBook(f.absolutePath)
        // 段落流里出现独立标记段
        assertTrue("expected marker paragraph, got ${result.paragraphs}", result.paragraphs.contains("[[IMG:0]]"))
        // 标记前后段落保序
        val markerIdx = result.paragraphs.indexOf("[[IMG:0]]")
        assertTrue(result.paragraphs.subList(0, markerIdx).any { it.contains("First paragraph") })
        assertTrue(result.paragraphs.subList(markerIdx + 1, result.paragraphs.size).any { it.contains("Second paragraph") })
        // zip 条目名按序登记
        assertEquals(listOf("images/pic.jpg"), result.imageEntryNames)
    }

    @Test
    fun `epub missing image entry drops the marker`() {
        val f = tmp.newFile("missingimg.epub")
        writeEpub(
            f,
            mapOf(
                "content.opf" to opfWithImages(),
                "chapter1.xhtml" to (
                    "<html><body>" +
                        "<p>Only one real paragraph in this chapter.</p>" +
                        "<p><img src=\"images/nope.jpg\"/></p>" +
                        "</body></html>"
                    ),
            ),
        )
        val result = EpubParser().parseBook(f.absolutePath)
        assertFalse("marker for missing entry should be dropped", result.paragraphs.any { it.contains("[[IMG:") })
        assertTrue(result.imageEntryNames.isEmpty())
    }

    @Test
    fun `epub inline img splits paragraph into text and marker`() {
        val f = tmp.newFile("inlineimg.epub")
        writeEpub(
            f,
            mapOf(
                "content.opf" to opfWithImages(),
                "chapter1.xhtml" to (
                    "<html><body>" +
                        "<p>Words before the illustration <img src=\"pic.png\"/> and words after it.</p>" +
                        "</body></html>"
                    ),
                "pic.png" to "fake-png-bytes",
            ),
        )
        val result = EpubParser().parseBook(f.absolutePath)
        assertTrue(result.paragraphs.contains("[[IMG:0]]"))
        assertTrue(result.paragraphs.any { it.contains("Words before the illustration") })
        assertTrue(result.paragraphs.any { it.contains("and words after it") })
        // 标记不能残留在文本段里
        assertFalse(result.paragraphs.any { it.contains("[[IMG:") && !BookImages.isImageMarker(it) })
    }

    // ── ArticleParser：<img> → URL 标记 ─────────────────────

    @Test
    fun `article img becomes absolute url marker`() {
        val html = """
            <html><body><article>
              <p>This is the real article body content that matters a lot here.</p>
              <figure><img src="https://cdn.example.com/photo.jpg"/><figcaption>Caption noise</figcaption></figure>
              <p>Second paragraph follows the illustration in this article.</p>
            </article></body></html>
        """.trimIndent()
        val result = ArticleParser().extractArticle(html, "https://www.example.com/story")
        assertTrue(
            "expected image marker paragraph, got ${result.paragraphs}",
            result.paragraphs.any { BookImages.isImageMarker(it) },
        )
        assertTrue(result.paragraphs.any { BookImages.markerRef(it) == "https://cdn.example.com/photo.jpg" })
        // 图注（figcaption）仍是噪音，不进正文
        assertFalse(result.paragraphs.any { it.contains("Caption noise") })
    }

    @Test
    fun `article relative img src is resolved against base url`() {
        val html = """
            <html><body><article>
              <p>This is the real article body content that matters a lot here.</p>
              <img src="/assets/img/hero.png"/>
            </article></body></html>
        """.trimIndent()
        val result = ArticleParser().extractArticle(html, "https://www.example.com/story/page.html")
        val ref = result.paragraphs.firstNotNullOfOrNull { BookImages.markerRef(it) }
        assertEquals("https://www.example.com/assets/img/hero.png", ref)
    }

    @Test
    fun `article noise images are dropped`() {
        val html = """
            <html><body><article>
              <p>This is the real article body content that matters a lot here.</p>
              <img src="https://tracker.example.com/pixel.gif"/>
              <img src="https://cdn.example.com/spacer-1x1.png"/>
              <img src="https://cdn.example.com/icon-home.svg"/>
              <img src="data:image/png;base64,iVBORw0KGgo="/>
            </article></body></html>
        """.trimIndent()
        val result = ArticleParser().extractArticle(html, "https://www.example.com/story")
        assertFalse("noise images must not become markers", result.paragraphs.any { BookImages.isImageMarker(it) })
    }

    @Test
    fun `article without base url keeps absolute markers only`() {
        val html = """
            <html><body><article>
              <p>This is the real article body content that matters a lot here.</p>
              <img src="https://cdn.example.com/absolute.jpg"/>
              <img src="relative-only.jpg"/>
            </article></body></html>
        """.trimIndent()
        // baseUrl 缺省（单测直调场景）：只有绝对 URL 能成标记
        val result = ArticleParser().extractArticle(html)
        val refs = result.paragraphs.mapNotNull { BookImages.markerRef(it) }
        assertEquals(listOf("https://cdn.example.com/absolute.jpg"), refs)
    }
}
