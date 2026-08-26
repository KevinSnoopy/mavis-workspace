package com.eareyereading.util

import javax.inject.Inject
import javax.inject.Singleton

/**
 * 简单词性标注器（基于规则 + 高频词表）
 * 提供 noun/verb/adjective/adverb/preposition/conjunction/determiner/pronoun 等标签
 */
@Singleton
class PosTagger @Inject constructor() {

    // 高频词词性表（取自 COBUILD 高频词表）
    private val wordPos = mapOf(
        // 冠词
        "the" to PosTag.DETERMINER, "a" to PosTag.DETERMINER, "an" to PosTag.DETERMINER,
        // 代词
        "i" to PosTag.PRONOUN, "you" to PosTag.PRONOUN, "he" to PosTag.PRONOUN,
        "she" to PosTag.PRONOUN, "it" to PosTag.PRONOUN, "we" to PosTag.PRONOUN,
        "they" to PosTag.PRONOUN, "me" to PosTag.PRONOUN, "him" to PosTag.PRONOUN,
        "her" to PosTag.PRONOUN, "us" to PosTag.PRONOUN, "them" to PosTag.PRONOUN,
        "my" to PosTag.PRONOUN, "your" to PosTag.PRONOUN, "his" to PosTag.PRONOUN,
        "its" to PosTag.PRONOUN, "our" to PosTag.PRONOUN, "their" to PosTag.PRONOUN,
        "this" to PosTag.PRONOUN, "that" to PosTag.PRONOUN, "these" to PosTag.PRONOUN,
        "those" to PosTag.PRONOUN, "what" to PosTag.PRONOUN, "which" to PosTag.PRONOUN,
        "who" to PosTag.PRONOUN, "whom" to PosTag.PRONOUN, "whose" to PosTag.PRONOUN,
        "someone" to PosTag.PRONOUN, "anyone" to PosTag.PRONOUN, "everyone" to PosTag.PRONOUN,
        "noone" to PosTag.PRONOUN, "something" to PosTag.PRONOUN, "anything" to PosTag.PRONOUN,
        "everything" to PosTag.PRONOUN, "nothing" to PosTag.PRONOUN,
        // be 动词
        "is" to PosTag.VERB, "am" to PosTag.VERB, "are" to PosTag.VERB,
        "was" to PosTag.VERB, "were" to PosTag.VERB, "be" to PosTag.VERB,
        "been" to PosTag.VERB, "being" to PosTag.VERB,
        // 助动词
        "do" to PosTag.VERB, "does" to PosTag.VERB, "did" to PosTag.VERB,
        "have" to PosTag.VERB, "has" to PosTag.VERB, "had" to PosTag.VERB,
        "will" to PosTag.VERB, "would" to PosTag.VERB, "can" to PosTag.VERB,
        "could" to PosTag.VERB, "should" to PosTag.VERB, "may" to PosTag.VERB,
        "might" to PosTag.VERB, "must" to PosTag.VERB, "shall" to PosTag.VERB,
        // 介词
        "in" to PosTag.PREPOSITION, "on" to PosTag.PREPOSITION, "at" to PosTag.PREPOSITION,
        "by" to PosTag.PREPOSITION, "for" to PosTag.PREPOSITION, "with" to PosTag.PREPOSITION,
        "about" to PosTag.PREPOSITION, "against" to PosTag.PREPOSITION, "between" to PosTag.PREPOSITION,
        "into" to PosTag.PREPOSITION, "through" to PosTag.PREPOSITION, "during" to PosTag.PREPOSITION,
        "before" to PosTag.PREPOSITION, "after" to PosTag.PREPOSITION, "above" to PosTag.PREPOSITION,
        "below" to PosTag.PREPOSITION, "to" to PosTag.PREPOSITION, "of" to PosTag.PREPOSITION,
        "off" to PosTag.PREPOSITION, "over" to PosTag.PREPOSITION, "under" to PosTag.PREPOSITION,
        "again" to PosTag.PREPOSITION, "further" to PosTag.PREPOSITION, "then" to PosTag.PREPOSITION,
        "once" to PosTag.PREPOSITION, "from" to PosTag.PREPOSITION, "up" to PosTag.PREPOSITION,
        "down" to PosTag.PREPOSITION, "out" to PosTag.PREPOSITION, "as" to PosTag.PREPOSITION,
        // 连词
        "and" to PosTag.CONJUNCTION, "but" to PosTag.CONJUNCTION, "or" to PosTag.CONJUNCTION,
        "nor" to PosTag.CONJUNCTION, "so" to PosTag.CONJUNCTION, "yet" to PosTag.CONJUNCTION,
        "because" to PosTag.CONJUNCTION, "although" to PosTag.CONJUNCTION, "if" to PosTag.CONJUNCTION,
        "when" to PosTag.CONJUNCTION, "where" to PosTag.CONJUNCTION, "why" to PosTag.CONJUNCTION,
        "how" to PosTag.CONJUNCTION, "while" to PosTag.CONJUNCTION, "whether" to PosTag.CONJUNCTION,
        // 副词
        "not" to PosTag.ADVERB, "no" to PosTag.ADVERB, "very" to PosTag.ADVERB,
        "also" to PosTag.ADVERB, "just" to PosTag.ADVERB, "only" to PosTag.ADVERB,
        "now" to PosTag.ADVERB, "here" to PosTag.ADVERB, "there" to PosTag.ADVERB,
        "then" to PosTag.ADVERB, "too" to PosTag.ADVERB, "more" to PosTag.ADVERB,
        "most" to PosTag.ADVERB, "such" to PosTag.ADVERB, "even" to PosTag.ADVERB,
        "back" to PosTag.ADVERB, "still" to PosTag.ADVERB, "well" to PosTag.ADVERB,
        "much" to PosTag.ADVERB, "often" to PosTag.ADVERB, "always" to PosTag.ADVERB,
        "never" to PosTag.ADVERB, "ever" to PosTag.ADVERB, "really" to PosTag.ADVERB,
        "already" to PosTag.ADVERB, "almost" to PosTag.ADVERB, "enough" to PosTag.ADVERB,
        // 数词
        "one" to PosTag.NUMERAL, "two" to PosTag.NUMERAL, "three" to PosTag.NUMERAL,
        "four" to PosTag.NUMERAL, "five" to PosTag.NUMERAL, "six" to PosTag.NUMERAL,
        "seven" to PosTag.NUMERAL, "eight" to PosTag.NUMERAL, "nine" to PosTag.NUMERAL,
        "ten" to PosTag.NUMERAL, "first" to PosTag.NUMERAL, "second" to PosTag.NUMERAL,
        "both" to PosTag.NUMERAL, "each" to PosTag.NUMERAL, "few" to PosTag.NUMERAL,
        "many" to PosTag.NUMERAL, "several" to PosTag.NUMERAL, "most" to PosTag.NUMERAL,
    )

    // 规则后缀
    private val suffixes = listOf(
        // 动词后缀
        Pair("ing", PosTag.VERB),
        Pair("ized", PosTag.VERB), Pair("ised", PosTag.VERB),
        Pair("ify", PosTag.VERB), Pair("ate", PosTag.VERB),
        Pair("en", PosTag.VERB),
        // 名词后缀
        Pair("tion", PosTag.NOUN), Pair("sion", PosTag.NOUN),
        Pair("ment", PosTag.NOUN), Pair("ness", PosTag.NOUN),
        Pair("ity", PosTag.NOUN), Pair("ance", PosTag.NOUN), Pair("ence", PosTag.NOUN),
        Pair("er", PosTag.NOUN), Pair("or", PosTag.NOUN), Pair("ist", PosTag.NOUN),
        Pair("ism", PosTag.NOUN), Pair("dom", PosTag.NOUN), Pair("ship", PosTag.NOUN),
        Pair("hood", PosTag.NOUN), Pair("th", PosTag.NOUN),
        // 形容词后缀
        Pair("ful", PosTag.ADJECTIVE),
        Pair("less", PosTag.ADJECTIVE),
        Pair("ous", PosTag.ADJECTIVE), Pair("ious", PosTag.ADJECTIVE),
        Pair("ive", PosTag.ADJECTIVE), Pair("ative", PosTag.ADJECTIVE),
        Pair("able", PosTag.ADJECTIVE), Pair("ible", PosTag.ADJECTIVE),
        Pair("al", PosTag.ADJECTIVE),
        Pair("ical", PosTag.ADJECTIVE),
        Pair("ish", PosTag.ADJECTIVE),
        Pair("ed", PosTag.ADJECTIVE),
        Pair("ent", PosTag.ADJECTIVE), Pair("ant", PosTag.ADJECTIVE),
        // 副词后缀
        Pair("ly", PosTag.ADVERB),
    )

    /**
     * 标注句子中每个英文单词的词性
     */
    fun tagSentence(sentence: String): List<Pair<String, PosTag>> {
        val words = Regex("([a-zA-Z]+)").findAll(sentence)
        return words.map { match ->
            val word = match.value.lowercase()
            val tag = wordPos[word] ?: classifyBySuffix(word)
            word to tag
        }.toList()
    }

    private fun classifyBySuffix(word: String): PosTag {
        // 规则后缀判断
        for ((suffix, tag) in suffixes) {
            if (word.length > suffix.length + 2 && word.endsWith(suffix)) {
                return tag
            }
        }
        // 默认为名词
        return PosTag.NOUN
    }
}

enum class PosTag {
    NOUN, VERB, ADJECTIVE, ADVERB, DETERMINER, PRONOUN,
    PREPOSITION, CONJUNCTION, NUMERAL, OTHER,
}

enum class PosColor(val label: String, val hex: Long) {
    NOUN("#5B7FFF"),       // 蓝色 - 名词
    VERB("#E91E63"),      // 粉色 - 动词
    ADJECTIVE("#FF9800"), // 橙色 - 形容词
    ADVERB("#9C27B0"),    // 紫色 - 副词
    OTHER("#9E9E9E"),     // 灰色 - 其他
}
