package com.eareyereading.util

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ArticleParser 单元测试：
 * - Content-Type 白名单校验（issue 10.5）
 */
class ArticleParserTest {

    // ── Content-Type 白名单（issue 10.5）──────────────────

    @Test
    fun `html and text content types are accepted`() {
        assertTrue(ArticleParser.isHtmlContentType("text/html"))
        assertTrue(ArticleParser.isHtmlContentType("text/html; charset=utf-8"))
        assertTrue(ArticleParser.isHtmlContentType("application/xhtml+xml"))
        assertTrue(ArticleParser.isHtmlContentType("application/xml"))
        assertTrue(ArticleParser.isHtmlContentType("text/plain"))
    }

    @Test
    fun `non html content types are rejected`() {
        assertFalse(ArticleParser.isHtmlContentType("application/json"))
        assertFalse(ArticleParser.isHtmlContentType("application/octet-stream"))
        assertFalse(ArticleParser.isHtmlContentType("image/png"))
        assertFalse(ArticleParser.isHtmlContentType("application/pdf"))
    }

    @Test
    fun `missing content type passes through best effort`() {
        // 部分源不返回 Content-Type 头，缺失时放行而不拒收
        assertTrue(ArticleParser.isHtmlContentType(null))
        assertTrue(ArticleParser.isHtmlContentType(""))
        assertTrue(ArticleParser.isHtmlContentType("   "))
    }

    // ── 自然段落分段（issue 7.5 / 10.10）────────────────

    @Test
    fun `clean text keeps paragraph boundaries from block tags`() {
        val html = """
            <html><body>
              <p>First paragraph has enough words to be kept intact.</p>
              <p>Second paragraph stands alone right after the first one.</p>
            </body></html>
        """.trimIndent()
        val result = ArticleParser().extractArticle(html)
        // 不再"每 4 句硬切"：两个 <p> 各自成段，段落边界不丢失
        assertTrue(
            "expected 2 paragraphs preserving boundaries, got ${result.paragraphs}",
            result.paragraphs.size >= 2,
        )
        assertTrue(result.paragraphs.any { it.contains("First paragraph") })
        assertTrue(result.paragraphs.any { it.contains("Second paragraph") })
        // 两段必须独立存在，而不是被合并成一段
        assertFalse(result.paragraphs.any { it.contains("First paragraph") && it.contains("Second paragraph") })
    }

    @Test
    fun `noise elements are stripped from article body`() {
        val html = """
            <html><body>
              <article>
                <p>This is the real article body content that matters.</p>
                <aside><p>Sidebar recommendation should be removed.</p></aside>
                <nav><p>Navigation links should also be removed.</p></nav>
                <figure><figcaption>Image caption noise</figcaption></figure>
              </article>
            </body></html>
        """.trimIndent()
        val result = ArticleParser().extractArticle(html)
        assertTrue(result.paragraphs.any { it.contains("real article body") })
        assertFalse(result.paragraphs.any { it.contains("Sidebar") })
        assertFalse(result.paragraphs.any { it.contains("Navigation links") })
        assertFalse(result.paragraphs.any { it.contains("Image caption") })
    }
}