package com.eareyereading.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [WordAnalyzer].
 *
 * Covers the core vocabulary processing features of the EareyeReading app:
 *  - Word frequency counting
 *  - Stop-word filtering
 *  - RSVP bolding ratios
 *  - Cloze generation (blank-out / hide words)
 *  - Fuzzy text generation (auditory training)
 *  - Reading level estimation
 */
class WordAnalyzerTest {

    private val analyzer = WordAnalyzer()

    // ---------------- extractWords ----------------

    @Test
    fun `extractWords keeps only latin alphabetic tokens`() {
        val words = analyzer.extractWords("Hello, world! 123 abc_def abc.def")
        // "abc_def" and "abc.def" are not matched by `[a-zA-Z]+` because
        // `_` and `.` are non-letter; the regex matches "abc", "def", "abc", "def" separately.
        assertEquals(listOf("Hello", "world", "abc", "def", "abc", "def"), words)
    }

    @Test
    fun `extractWords returns empty list for empty text`() {
        assertEquals(emptyList<String>(), analyzer.extractWords(""))
    }

    @Test
    fun `extractWords ignores pure punctuation`() {
        assertEquals(emptyList<String>(), analyzer.extractWords("!!! ... ??? ;; ;;"))
    }

    // ---------------- calculateWordFrequencies ----------------

    @Test
    fun `calculateWordFrequencies counts case-insensitively`() {
        val freqs = analyzer.calculateWordFrequencies("Cat cat CAT dog Dog DOG")
        assertEquals(3, freqs["cat"])
        assertEquals(3, freqs["dog"])
        assertEquals(2, freqs.size)
    }

    @Test
    fun `calculateWordFrequencies filters stop words and short tokens`() {
        // "the" is a stop word, "a"/"I" are too short (length<=2)
        val freqs = analyzer.calculateWordFrequencies("the a I reading reading is fun")
        assertFalse("stop word 'the' must be filtered", freqs.containsKey("the"))
        assertFalse("stop word 'is' must be filtered", freqs.containsKey("is"))
        assertFalse("length<=2 token 'a' must be filtered", freqs.containsKey("a"))
        assertEquals(2, freqs["reading"])
        assertEquals(1, freqs["fun"])
    }

    @Test
    fun `calculateWordFrequencies returns empty map for empty input`() {
        assertEquals(emptyMap<String, Int>(), analyzer.calculateWordFrequencies(""))
    }

    // ---------------- extractKeyWords ----------------

    @Test
    fun `extractKeyWords keeps only long non-stop words`() {
        val keys = analyzer.extractKeyWords("The quick brown fox jumps over lazy dog")
        // >= 5 chars and not in stop words
        assertTrue("quick" in keys)
        assertTrue("brown" in keys)
        assertTrue("jumps" in keys)
        assertFalse("the" in keys)
        assertFalse("over" in keys)
        assertFalse("dog" in keys)
    }

    // ---------------- processRsvpWord ----------------

    @Test
    fun `processRsvpWord returns empty pair for empty input`() {
        val (bold, normal) = analyzer.processRsvpWord("")
        assertEquals("", bold)
        assertEquals("", normal)
    }

    @Test
    fun `processRsvpWord splits word at the configured ratio`() {
        // strength=5 -> ratio 0.7 -> "reading" (7) * 0.7 = 4 (integer truncation)
        val (bold, normal) = analyzer.processRsvpWord("reading", strength = 5)
        assertEquals("read", bold)
        assertEquals("ing", normal)
    }

    @Test
    fun `processRsvpWord strength 1 uses ratio 0_3`() {
        // "reading" (7) * 0.3 = 2
        val (bold, normal) = analyzer.processRsvpWord("reading", strength = 1)
        assertEquals("re", bold)
        assertEquals("ading", normal)
    }

    @Test
    fun `processRsvpWord strength out of range falls back to ratio 0_7`() {
        val (bold, normal) = analyzer.processRsvpWord("reading", strength = 99)
        // ratio 0.7 -> 4
        assertEquals("read", bold)
        assertEquals("ing", normal)
    }

    @Test
    fun `processRsvpWord guarantees at least one bold character`() {
        // "I" has length 1; maxOf(1, 1*ratio.toInt()) = 1
        val (bold, normal) = analyzer.processRsvpWord("I", strength = 1)
        assertEquals("I", bold)
        assertEquals("", normal)
    }

    // ---------------- generateClozeText ----------------

    @Test
    fun `generateClozeText marks explicit hide words as hidden`() {
        val text = "Reading is fun and helpful"
        val tokens = analyzer.generateClozeText(text, wordsToHide = setOf("reading"))
        val readingToken = tokens.first { it.isWord && it.text.equals("Reading", ignoreCase = true) }
        assertTrue("explicit word should be hidden", readingToken.isHidden)
    }

    @Test
    fun `generateClozeText keeps punctuation tokens unhidden`() {
        val tokens = analyzer.generateClozeText("Hello, world!")
        val punctuation = tokens.filter { !it.isWord }
        assertTrue(punctuation.isNotEmpty())
        assertTrue(punctuation.all { !it.isHidden })
    }

    @Test
    fun `generateClozeText hides nothing when ratio is zero and no explicit list`() {
        val text = "Reading is fun and helpful"
        val tokens = analyzer.generateClozeText(text, wordsToHide = null, ratio = 0f)
        val wordTokens = tokens.filter { it.isWord }
        assertTrue(
            "all words should be visible when ratio=0 and no hide set",
            wordTokens.none { it.isHidden }
        )
    }

    @Test
    fun `generateClozeText preserves text round-trip when joined`() {
        val text = "Reading is fun, and helpful."
        val tokens = analyzer.generateClozeText(text, wordsToHide = emptySet())
        val joined = tokens.joinToString("") { it.text }
        assertEquals(text, joined)
    }

    @Test
    fun `generateClozeText skips very short words when auto-selecting hide set`() {
        // Ratio 1.0 should hide every eligible (>3 chars, non-stop) word.
        // "I" / "is" / "a" are too short or stop words and must remain visible.
        val tokens = analyzer.generateClozeText("I am reading books", ratio = 1.0f)
        val iToken = tokens.first { it.isWord && it.text.equals("I", ignoreCase = true) }
        val isToken = tokens.first { it.isWord && it.text.equals("am", ignoreCase = true) }
        assertFalse("'I' should not be hidden", iToken.isHidden)
        assertFalse("'am' should not be hidden (length<=3)", isToken.isHidden)
        // 正向断言：符合条件的词在 ratio=1.0 下必须被隐藏。
        // 此前只有负向断言——自动选择管线整个坏掉（隐藏集为空）测试也照样通过
        assertTrue("'reading' must be hidden at ratio=1.0",
            tokens.first { it.text == "reading" }.isHidden)
        assertTrue("'books' must be hidden at ratio=1.0",
            tokens.first { it.text == "books" }.isHidden)
    }

    @Test
    fun `generateClozeText auto-select hides repeated words at full ratio`() {
        // 重复词不再吃掉多个隐藏名额：去重后采样，所有去重后的合格词都应被隐藏
        val tokens = analyzer.generateClozeText("apple apple apple banana", ratio = 1.0f)
        assertTrue(tokens.filter { it.text == "apple" }.all { it.isHidden })
        assertTrue(tokens.first { it.text == "banana" }.isHidden)
    }

    // ---------------- generateFuzzyText ----------------

    @Test
    fun `generateFuzzyText marks punctuation as blurred`() {
        val tokens = analyzer.generateFuzzyText("Hello, world!")
        val punctuation = tokens.filter { !it.isWord }
        assertTrue(punctuation.isNotEmpty())
        assertTrue(punctuation.all { it.isBlurred })
    }

    @Test
    fun `generateFuzzyText with visibleRatio 1 keeps no words blurred`() {
        // visibleRatio = 1.0 means every word passes the random check
        val text = "alpha beta gamma delta epsilon"
        repeat(20) {
            val tokens = analyzer.generateFuzzyText(text, visibleRatio = 1.0f)
            val wordTokens = tokens.filter { it.isWord }
            assertTrue(
                "all words should remain visible with visibleRatio=1",
                wordTokens.none { it.isBlurred }
            )
        }
    }

    @Test
    fun `generateFuzzyText with visibleRatio 0 blurs every word`() {
        val text = "alpha beta gamma"
        repeat(20) {
            val tokens = analyzer.generateFuzzyText(text, visibleRatio = 0.0f)
            val wordTokens = tokens.filter { it.isWord }
            assertTrue(
                "all words should be blurred with visibleRatio=0",
                wordTokens.all { it.isBlurred }
            )
        }
    }

    @Test
    fun `generateFuzzyText preserves text round-trip`() {
        val text = "Reading, listening and speaking."
        val tokens = analyzer.generateFuzzyText(text, visibleRatio = 1.0f)
        val joined = tokens.joinToString("") { it.text }
        assertEquals(text, joined)
    }

    // ---------------- estimateReadingLevel ----------------

    @Test
    fun `estimateReadingLevel returns Easy for short words`() {
        val level = analyzer.estimateReadingLevel("I am a cat and a dog")
        assertEquals("Easy", level)
    }

    @Test
    fun `estimateReadingLevel returns Advanced for long academic words`() {
        val text = "pseudopseudohypoparathyroidism " // 27-letter single word -> avg length >> 6.5
        val level = analyzer.estimateReadingLevel(text)
        assertEquals("Advanced", level)
    }

    @Test
    fun `estimateReadingLevel returns Easy for empty text`() {
        // 契约：空输入没有词长可算（average() 为 NaN），必须显式返回 "Easy"。
        // 旧实现 NaN 落进 else 分支误报 "Advanced"，且旧测试接受任何结果
        assertEquals("Easy", analyzer.estimateReadingLevel(""))
        assertEquals("Easy", analyzer.estimateReadingLevel("123 456 !!!"))
    }

    @Test
    fun `estimateReadingLevel covers intermediate bands`() {
        // avg length 5.0 -> Intermediate；avg length 6.0 -> Upper-Intermediate
        assertEquals("Intermediate", analyzer.estimateReadingLevel("aaaaa bbbbb ccccc"))
        assertEquals("Upper-Intermediate", analyzer.estimateReadingLevel("aaaaaa bbbbbb cccccc"))
    }
}
