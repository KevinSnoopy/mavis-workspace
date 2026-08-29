package com.eareyereading.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [PosTagger].
 *
 * Exercises dictionary lookups, suffix-based heuristics and the documented
 * "unknown word falls back to noun" behavior.
 */
class PosTaggerTest {

    private val tagger = PosTagger()

    @Test
    fun `tagSentence recognises determiners from dictionary`() {
        val tags = tagger.tagSentence("the cat")
        assertEquals(2, tags.size)
        assertEquals("the" to PosTag.DETERMINER, tags[0])
        assertEquals("cat" to PosTag.NOUN, tags[1])
    }

    @Test
    fun `tagSentence recognises pronouns from dictionary`() {
        val tags = tagger.tagSentence("I you he she it we they")
        assertTrue(tags.all { it.second == PosTag.PRONOUN })
    }

    @Test
    fun `tagSentence recognises auxiliary verbs`() {
        val tags = tagger.tagSentence("is am are was were be been")
        assertTrue(tags.all { it.second == PosTag.VERB })
    }

    @Test
    fun `tagSentence uses suffix heuristics for unknown words`() {
        // "running" ends with "ing" -> VERB
        val runTags = tagger.tagSentence("running")
        assertEquals("running" to PosTag.VERB, runTags.single())

        // "happiness" ends with "ness" -> NOUN
        val happyTags = tagger.tagSentence("happiness")
        assertEquals("happiness" to PosTag.NOUN, happyTags.single())

        // "quickly" ends with "ly" -> ADVERB
        val quickTags = tagger.tagSentence("quickly")
        assertEquals("quickly" to PosTag.ADVERB, quickTags.single())

        // "beautiful" ends with "ful" -> ADJECTIVE
        val adjTags = tagger.tagSentence("beautiful")
        assertEquals("beautiful" to PosTag.ADJECTIVE, adjTags.single())
    }

    @Test
    fun `tagSentence defaults to NOUN for short unknown words`() {
        // Word length must exceed suffix.length + 2, otherwise falls back to NOUN.
        val tags = tagger.tagSentence("zz") // length 2 < 2 + suffix
        assertEquals("zz" to PosTag.NOUN, tags.single())
    }

    @Test
    fun `tagSentence lowercases inputs`() {
        val tags = tagger.tagSentence("THE Cat")
        // "THE" lowercased -> "the" -> DETERMINER (from dict)
        assertEquals("the" to PosTag.DETERMINER, tags[0])
        assertEquals("cat" to PosTag.NOUN, tags[1])
    }

    @Test
    fun `tagSentence ignores non-alphabetic tokens`() {
        val tags = tagger.tagSentence("Hello, 123 world!")
        assertEquals(listOf("hello" to PosTag.NOUN, "world" to PosTag.NOUN), tags)
    }

    @Test
    fun `tagSentence returns empty list for empty input`() {
        assertEquals(emptyList<Pair<String, PosTag>>(), tagger.tagSentence(""))
    }
}
