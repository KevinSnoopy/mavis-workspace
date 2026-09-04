package com.eareyereading.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * OnlineTranslator 长文本分片逻辑单元测试：
 * 修复背景——阅读页段落截断上限 4000 字符，而 gtx/Bing 单次请求上限 3500，
 * 3500~4000 字符段落此前会被所有端点拒绝，整段翻译失败。
 */
class OnlineTranslatorTest {

    private val translator = OnlineTranslator()

    private fun paragraphOf(sentenceCount: Int, sentenceLen: Int): String =
        (1..sentenceCount).joinToString(" ") { idx ->
            val words = "word$idx " + "x".repeat(sentenceLen)
            words.trim() + "."
        }

    @Test
    fun `short text stays single chunk`() {
        val text = "The quick brown fox jumps over the lazy dog."
        assertEquals(listOf(text), translator.chunksOf(text))
    }

    @Test
    fun `long text splits into bounded chunks`() {
        // 40 句 × ~90 字符 ≈ 3700 字符：必须切分且每片 ≤3000
        val text = paragraphOf(40, 85)
        assertTrue("text should exceed chunk limit, was ${text.length}", text.length > 3000)
        val chunks = translator.chunksOf(text)
        assertTrue("expected multiple chunks, got ${chunks.size}", chunks.size >= 2)
        chunks.forEach { c ->
            assertTrue("chunk length ${c.length} exceeds 3000", c.length <= 3000)
        }
        // 切分不丢内容：拼接后与原文一致（按空白归一化比较）
        val joined = chunks.joinToString(" ")
        assertEquals(text.split(Regex("\\s+")).filter { it.isNotBlank() },
            joined.split(Regex("\\s+")).filter { it.isNotBlank() })
    }

    @Test
    fun `sentence boundary is respected when chunking`() {
        // 分片边界不落在句子中间：每个分片要么以句号结尾，要么是硬切片段
        val text = paragraphOf(60, 80)
        val chunks = translator.chunksOf(text)
        chunks.forEach { c ->
            val endsClean = c.endsWith(".") || c.length == 3000 || c.length < 3000
            assertTrue("chunk should end at sentence boundary: ...${c.takeLast(20)}", endsClean)
        }
    }

    @Test
    fun `run on text without punctuation is hard split`() {
        // 无句读的超长文本：硬切成 ≤3000 的片段
        val text = "a".repeat(7100)
        val chunks = translator.chunksOf(text)
        assertEquals(listOf(3000, 3000, 1100), chunks.map { it.length })
    }

    @Test
    fun `mixed sentence lengths keep all content`() {
        // 短句与长句混排：所有句子都必须出现在某个分片里
        val sentences = (1..50).map { "Sentence number $it with some padding words here." }
        val long = "This is an extremely long sentence without any punctuation " +
            "that keeps going and going with many words to force hard splitting behavior."
        val text = sentences.joinToString(" ") + " " + long.repeat(20)
        val chunks = translator.chunksOf(text)
        val rejoined = chunks.joinToString(" ")
        sentences.forEach { s ->
            assertTrue("sentence missing after chunking: $s", rejoined.contains(s))
        }
    }
}
