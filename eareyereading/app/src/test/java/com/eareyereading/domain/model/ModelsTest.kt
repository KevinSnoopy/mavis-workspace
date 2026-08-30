package com.eareyereading.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for the pure-Kotlin domain models.
 *
 * These tests validate data-class semantics and enum contract behavior
 * relied on by the reader UI (mode lookups, theme switching, progress).
 */
class ModelsTest {

    // ---------------- Book ----------------

    @Test
    fun `Book data class supports equality and copy`() {
        val a = Book(
            id = 1L,
            title = "Hamlet",
            author = "Shakespeare",
            filePath = "/tmp/hamlet.txt",
            totalWords = 30_000,
        )
        val b = a.copy()
        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())

        val c = a.copy(isArchived = true)
        assertNotEquals(a, c)
        assertTrue(c.isArchived)
        assertFalse(a.isArchived)
    }

    @Test
    fun `Book default values match app contract`() {
        val b = Book(title = "Untitled", author = "Unknown", filePath = "/tmp/x.txt")
        assertEquals(0L, b.id)
        assertEquals(0, b.totalWords)
        assertEquals(0f, b.readProgress, 0f)
        assertEquals(0, b.lastReadPosition)
        assertEquals("en", b.language)
        assertFalse(b.isArchived)
        assertEquals("", b.content)
        assertEquals(emptyList<String>(), b.paragraphs)
    }

    @Test
    fun `Book readProgress can express 0 and 1 boundaries`() {
        val start = Book(title = "x", author = "y", filePath = "/p", readProgress = 0f)
        val end = start.copy(readProgress = 1f)
        assertEquals(0f, start.readProgress, 0f)
        assertEquals(1f, end.readProgress, 0f)
    }

    // ---------------- ReadingState ----------------

    @Test
    fun `ReadingState defaults are reader-friendly`() {
        val s = ReadingState(bookId = 42L)
        assertEquals(42L, s.bookId)
        assertEquals(0, s.currentPosition)
        assertEquals(0, s.currentParagraph)
        assertEquals(18, s.fontSize)
        assertEquals(300, s.rsvpSpeed)
        assertEquals(ReadingMode.NORMAL, s.readingMode)
        assertEquals(ReadingTheme.LIGHT, s.theme)
    }

    // ---------------- ReadingMode ----------------

    @Test
    fun `ReadingMode exposes stable value and displayName`() {
        // The serialized `value` is persisted in DataStore/Room — do not change lightly.
        // 全部 9 个枚举都要钉住：旧测试只覆盖 4 个，其余 5 个改名/删除不会有任何告警，
        // 而 ReadingRepositoryImpl.toDomain 会把未知串静默回退成 NORMAL
        assertEquals("normal", ReadingMode.NORMAL.value)
        assertEquals("rsvp", ReadingMode.RSVP.value)
        assertEquals("speed", ReadingMode.SPEED.value)
        assertEquals("cloze", ReadingMode.CLOZE.value)
        assertEquals("fuzzy", ReadingMode.FUZZY.value)
        assertEquals("dictation", ReadingMode.DICTATION.value)
        assertEquals("split", ReadingMode.SPLIT.value)
        assertEquals("back_translation", ReadingMode.BACK_TRANSLATION.value)
        assertEquals("pos_analysis", ReadingMode.POS_ANALYSIS.value)

        assertEquals("普通阅读", ReadingMode.NORMAL.displayName)
        assertEquals("仿生阅读", ReadingMode.RSVP.displayName)
        assertEquals("挖空练习", ReadingMode.CLOZE.displayName)
        assertEquals("模糊听读", ReadingMode.FUZZY.displayName)
    }

    @Test
    fun `ReadingMode values are unique and lowercase`() {
        // value 是唯一性 + 小写契约：重复会让 toDomain 匹配到第一个同名项
        val values = ReadingMode.entries.map { it.value }
        assertEquals(values.size, values.toSet().size)
        assertTrue(values.all { it == it.lowercase() })
    }

    @Test
    fun `ReadingMode valueOf round-trips`() {
        ReadingMode.values().forEach { mode ->
            assertEquals(mode, ReadingMode.valueOf(mode.name))
        }
    }

    // ---------------- ReadingTheme ----------------

    @Test
    fun `ReadingTheme exposes stable value and displayName`() {
        assertEquals("light", ReadingTheme.LIGHT.value)
        assertEquals("dark", ReadingTheme.DARK.value)
        assertEquals("sepia", ReadingTheme.SEPIA.value)
        assertEquals("明亮", ReadingTheme.LIGHT.displayName)
        assertEquals("暗黑", ReadingTheme.DARK.displayName)
        assertEquals("护眼", ReadingTheme.SEPIA.displayName)
    }

    // ---------------- Vocabulary ----------------

    @Test
    fun `Vocabulary default level is unknown`() {
        val v = Vocabulary(word = "ephemeral")
        assertEquals(0, v.level)
        assertFalse(v.isLearned)
        assertEquals(0, v.reviewCount)
    }

    @Test
    fun `Vocabulary allows Collins star assignment`() {
        // 1=CORE ... 5=RARE — used by the reader UI to color-code words.
        val core = Vocabulary(word = "time", level = 1)
        val rare = Vocabulary(word = "sesquipedalian", level = 5)
        assertEquals(1, core.level)
        assertEquals(5, rare.level)
    }
}
