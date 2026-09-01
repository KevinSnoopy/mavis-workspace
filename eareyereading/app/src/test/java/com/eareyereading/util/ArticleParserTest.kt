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
}