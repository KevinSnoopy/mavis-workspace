package com.eareyereading.util

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [LlmTranslator] 纯函数单元测试（提示词构造 / 请求体 / 响应解析 / 长文分片）。
 * HTTP 部分依赖网络与真实端点，不在 JVM 单测覆盖。
 */
class LlmTranslatorTest {

    private val translator = LlmTranslator()
    private val config = LlmTranslator.Config(
        baseUrl = "https://open.bigmodel.cn/api/paas/v4",
        apiKey = "test-key",
        model = "glm-4-flash",
    )

    // ── systemPrompt ─────────────────────────────

    @Test
    fun `system prompt targets simplified chinese and forbids preamble`() {
        val prompt = translator.systemPrompt("zh")
        assertTrue(prompt.contains("简体中文"))
        assertTrue(prompt.contains("只输出译文本身"))
        assertTrue(prompt.contains("翻译腔"))
    }

    @Test
    fun `system prompt keeps non zh target language code`() {
        val prompt = translator.systemPrompt("ja")
        assertTrue(prompt.contains("ja"))
    }

    // ── buildRequestBody ─────────────────────────

    @Test
    fun `request body is openai compatible chat completions`() {
        val body = JSONObject(
            translator.buildRequestBody("Hello world.", "en", "zh", config),
        )
        assertEquals("glm-4-flash", body.getString("model"))
        assertEquals(false, body.getBoolean("stream"))
        assertEquals(0.1, body.getDouble("temperature"), 1e-9)
        val messages = body.getJSONArray("messages")
        assertEquals(2, messages.length())
        assertEquals("system", messages.getJSONObject(0).getString("role"))
        assertEquals("user", messages.getJSONObject(1).getString("role"))
        // user 消息只含原文：指令全在 system，避免模型复读指令污染译文
        assertEquals("Hello world.", messages.getJSONObject(1).getString("content"))
    }

    // ── parseResponse ────────────────────────────

    @Test
    fun `parses standard choices content`() {
        val raw = """
            {"choices":[{"message":{"role":"assistant","content":"老人坐在港口边，望着归航的船只，直到太阳融进海里。"}}]}
        """.trimIndent()
        assertEquals("老人坐在港口边，望着归航的船只，直到太阳融进海里。", translator.parseResponse(raw))
    }

    @Test
    fun `strips model preamble like 翻译如下`() {
        val raw = """
            {"choices":[{"message":{"content":"翻译如下：\n他缓缓走向那扇门。"}}]}
        """.trimIndent()
        assertEquals("他缓缓走向那扇门。", translator.parseResponse(raw))
    }

    @Test
    fun `strips 以下是xxx prefix`() {
        val raw = """
            {"choices":[{"message":{"content":"以下是译文：夜色渐深。"}}]}
        """.trimIndent()
        assertEquals("夜色渐深。", translator.parseResponse(raw))
    }

    @Test
    fun `error body returns null`() {
        val raw = """{"error":{"code":"401","message":"invalid api key"}}"""
        assertNull(translator.parseResponse(raw))
    }

    @Test
    fun `blank content returns null`() {
        val raw = """{"choices":[{"message":{"content":"  "}}]}"""
        assertNull(translator.parseResponse(raw))
    }

    @Test
    fun `missing choices returns null instead of throwing`() {
        assertNull(translator.parseResponse("""{"id":"x"}"""))
    }

    // ── chunksOf ─────────────────────────────────

    @Test
    fun `short text is a single chunk`() {
        assertEquals(listOf("One sentence only."), translator.chunksOf("One sentence only."))
    }

    @Test
    fun `long text splits at sentence boundary under limit`() {
        val sentence = "This is a fairly long sentence that keeps repeating itself. "
        val text = sentence.repeat(300)  // ~17k 字符
        val chunks = translator.chunksOf(text)
        assertTrue(chunks.size > 1)
        chunks.forEach { chunk ->
            assertTrue("chunk ${chunk.length} exceeds limit", chunk.length <= LlmTranslator.CHUNK_MAX_CHARS)
        }
        // 切片按原顺序拼回后内容无损（句间空白标准化为单空格）
        assertEquals(text.trim(), chunks.joinToString(" ").trim())
    }

    @Test
    fun `no punctuation text is hard split under limit`() {
        val text = "a".repeat(LlmTranslator.CHUNK_MAX_CHARS + 500)
        val chunks = translator.chunksOf(text)
        assertTrue(chunks.size >= 2)
        chunks.forEach { assertTrue(it.length <= LlmTranslator.CHUNK_MAX_CHARS) }
        assertEquals(text, chunks.joinToString(""))
    }

    // ── Config 健壮性 ────────────────────────────

    @Test
    fun `blank config fields reject translate before network`() {
        // 无网络环境下 translate 必须因配置缺失/请求失败返回 null 而不是抛异常
        val bad = config.copy(apiKey = "")
        kotlinx.coroutines.runBlocking {
            assertNull(translator.translate("Hello.", "en", "zh", bad))
        }
    }

    @Test
    fun `same language short circuits`() {
        kotlinx.coroutines.runBlocking {
            assertNotNull(translator.translate("Hello.", "en", "en", config))
        }
    }
}
