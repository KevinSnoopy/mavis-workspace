package com.eareyereading.util
@file:Suppress("WildcardImport", "FunctionNaming", "UnusedParameter", "MatchingDeclarationName", "EmptyFunctionBlock", "UnusedPrivateProperty")

import javax.inject.Inject
import javax.inject.Singleton

/** 挖空单词 */
data class ClozeWord(
    val text: String,
    val isHidden: Boolean,
    val isWord: Boolean = true,
)

/** 模糊单词 */
data class FuzzyWord(
    val text: String,
    val isBlurred: Boolean,
)

/**
 * 词汇分析工具
 * - 词频统计
 * - RSVP 仿生阅读（部分字母加粗）
 * - 挖空生成（隐藏单词）
 * - 模糊文本
 */
@Singleton
class WordAnalyzer @Inject constructor() {

    // 常见停用词列表
    private val stopWords = setOf(
        "the", "a", "an", "and", "or", "but", "in", "on", "at", "to", "for",
        "of", "with", "by", "from", "as", "is", "was", "are", "were", "been",
        "be", "have", "has", "had", "do", "does", "did", "will", "would",
        "could", "should", "may", "might", "must", "shall", "can", "need",
        "it", "its", "this", "that", "these", "those", "i", "you", "he",
        "she", "we", "they", "what", "which", "who", "whom", "whose",
        "when", "where", "why", "how", "all", "each", "every", "both",
        "few", "more", "most", "other", "some", "such", "no", "nor", "not",
        "only", "own", "same", "so", "than", "too", "very", "just",
        "also", "now", "here", "there", "then", "once", "if", "because",
        "about", "into", "through", "during", "before", "after", "above",
        "below", "between", "under", "again", "further", "while",
        "s", "t", "d", "ll", "m", "re", "ve", "isn", "aren", "wasn", "weren",
        "don", "doesn", "didn", "won", "wouldn", "couldn", "shouldn",
        "hasn", "haven", "hadn"
    )

    /**
     * 计算词频
     */
    fun calculateWordFrequencies(text: String): Map<String, Int> {
        val words = extractWords(text)
        return words
            .filter { it.length > 2 && it !in stopWords }
            .groupingBy { it.lowercase() }
            .eachCount()
    }

    /**
     * 提取所有单词
     */
    fun extractWords(text: String): List<String> {
        return Regex("[a-zA-Z]+").findAll(text).map { it.value }.toList()
    }

    /**
     * 提取句子中的关键词（用于生词提示）
     */
    fun extractKeyWords(sentence: String): List<String> {
        val words = extractWords(sentence)
        return words.filter {
            it.length >= 5 && it.lowercase() !in stopWords
        }
    }

    /**
     * 为 RSVP 阅读模式处理单词
     * 对单词的前几个字母加粗（RSIT 理论）
     * strength: 1-5，对应加粗占比 0.3/0.4/0.5/0.6/0.7
     * 返回: Pair(加粗部分, 普通部分)
     */
    fun processRsvpWord(word: String, strength: Int = 3): Pair<String, String> {
        if (word.isEmpty()) return Pair("", "")
        val ratio = when (strength) {
            1 -> 0.3f
            2 -> 0.4f
            3 -> 0.5f
            4 -> 0.6f
            else -> 0.7f
        }
        val boldCount = maxOf(1, (word.length * ratio).toInt())
        val bold = word.substring(0, boldCount)
        val normal = word.substring(boldCount)
        return Pair(bold, normal)
    }

    /**
     * 生成挖空文本（隐藏指定单词）
     * @param text 原文
     * @param wordsToHide 要隐藏的单词列表
     * @param ratio 隐藏比例 (0.0 ~ 1.0)
     */
    fun generateClozeText(text: String, wordsToHide: Set<String>? = null, ratio: Float = 0.15f): List<ClozeWord> {
        val words = extractWords(text)
        val result = mutableListOf<ClozeWord>()
        var pos = 0

        val hideWords = wordsToHide ?: if (ratio > 0) {
            words.filter { it.length > 3 && it.lowercase() !in stopWords }
                .shuffled()
                .take((words.size * ratio).toInt().coerceAtLeast(1))
                .map { it.lowercase() }
                .toSet()
        } else emptySet()

        for (word in words) {
            val start = text.indexOf(word, pos)
            if (start == -1) {
                result.add(ClozeWord(word, isHidden = false, isWord = true))
                pos += word.length
                continue
            }

            // 添加单词前的标点符号
            if (start > pos) {
                result.add(ClozeWord(text.substring(pos, start), isHidden = false, isWord = false))
            }

            val lower = word.lowercase()
            val isHidden = lower in hideWords
            result.add(ClozeWord(word, isHidden = isHidden, isWord = true))
            pos = start + word.length
        }

        // 补充剩余文本
        if (pos < text.length) {
            result.add(ClozeWord(text.substring(pos), isHidden = false, isWord = false))
        }

        return result
    }

    /**
     * 生成模糊文本（用于听力训练）
     * @param text 原文
     * @param visibleRatio 可见比例 (0.0 ~ 1.0)
     */
    fun generateFuzzyText(text: String, visibleRatio: Float = 0.3f): List<FuzzyWord> {
        val words = extractWords(text)
        val result = mutableListOf<FuzzyWord>()
        var pos = 0

        for (word in words) {
            val start = text.indexOf(word, pos)
            if (start == -1) {
                result.add(FuzzyWord(word, isBlurred = true))
                pos += word.length
                continue
            }

            if (start > pos) {
                result.add(FuzzyWord(text.substring(pos, start), isBlurred = true))
            }

            // 随机决定是否模糊
            val isBlurred = kotlin.random.Random.nextFloat() > visibleRatio
            result.add(FuzzyWord(word, isBlurred = isBlurred))
            pos = start + word.length
        }

        if (pos < text.length) {
            result.add(FuzzyWord(text.substring(pos), isBlurred = true))
        }

        return result
    }

    /**
     * 计算阅读等级（基于词频分析简化版）
     */
    fun estimateReadingLevel(text: String): String {
        val words = extractWords(text)
        val avgLength = words.map { it.length }.average()
        return when {
            avgLength < 4.5 -> "Easy"
            avgLength < 5.5 -> "Intermediate"
            avgLength < 6.5 -> "Upper-Intermediate"
            else -> "Advanced"
        }
    }
}
