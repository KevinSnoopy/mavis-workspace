package com.eareyereading.util

import java.util.Locale
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
    val isWord: Boolean = true,
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

    private companion object {
        /** 分词正则预编译：extractWords 被 5 个公开方法共用，
         *  旧实现在函数体内每次构造 Regex（每次 Pattern.compile）。 */
        private val WORD_REGEX = Regex("[a-zA-Z]+")
    }

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
     * 计算词频（整段文本）。
     * 单次 lowercase：旧实现 filter 与 groupingBy 各转一次，每词多一次分配。
     */
    fun calculateWordFrequencies(text: String): Map<String, Int> =
        countWords(WORD_REGEX.findAll(text))

    /**
     * 计算词频（按段落）：导入路径已持有段落列表，
     * 无需再 joinToString 拼整本全文（大书省一份 MB 级拷贝）。
     */
    fun calculateWordFrequencies(paragraphs: List<String>): Map<String, Int> =
        countWords(paragraphs.asSequence().flatMap { WORD_REGEX.findAll(it) })

    private fun countWords(matches: Sequence<MatchResult>): Map<String, Int> {
        val counts = HashMap<String, Int>()
        for (match in matches) {
            val word = match.value.lowercase(Locale.ROOT)
            if (word.length > 2 && word !in stopWords) {
                counts.merge(word, 1, Int::plus)
            }
        }
        return counts
    }

    /**
     * 提取所有单词
     */
    fun extractWords(text: String): List<String> =
        WORD_REGEX.findAll(text).map { it.value }.toList()

    /**
     * 提取句子中的关键词（用于生词提示）
     */
    fun extractKeyWords(sentence: String): List<String> {
        return extractWords(sentence).filter {
            it.length >= 5 && it.lowercase(Locale.ROOT) !in stopWords
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
        val allMatches = WORD_REGEX.findAll(text).toList()
        val hideWords = wordsToHide ?: if (ratio > 0) {
            // 先去重再采样：否则重复出现的词会占掉多个隐藏名额，
            // 实际隐藏比例明显低于请求的 ratio
            allMatches.map { it.value.lowercase(Locale.ROOT) }
                .filter { it.length > 3 && it !in stopWords }
                .distinct()
                .shuffled()
                .take((allMatches.size * ratio).toInt().coerceAtLeast(1))
                .toSet()
        } else emptySet()

        // 直接用正则匹配区间还原位置：旧实现的 indexOf 回退路径在词 miss 时
        // 会扫到文本末尾（连续 miss 最坏 O(n²)）且丢掉词间标点
        val result = ArrayList<ClozeWord>(allMatches.size * 2 + 1)
        var pos = 0
        for (match in allMatches) {
            val start = match.range.first
            if (start > pos) {
                result.add(ClozeWord(text.substring(pos, start), isHidden = false, isWord = false))
            }
            val lower = match.value.lowercase(Locale.ROOT)
            result.add(ClozeWord(match.value, isHidden = lower in hideWords, isWord = true))
            pos = match.range.last + 1
        }
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
        val allMatches = WORD_REGEX.findAll(text).toList()
        val result = ArrayList<FuzzyWord>(allMatches.size * 2 + 1)
        var pos = 0

        for (match in allMatches) {
            val start = match.range.first
            if (start > pos) {
                result.add(FuzzyWord(text.substring(pos, start), isBlurred = true, isWord = false))
            }

            // 随机决定是否模糊。用 "< (1 - visibleRatio)" 而不是 "> visibleRatio"：
            // 后者在 visibleRatio=0 时 nextFloat()==0.0 会漏出可见词（边界概率事件）
            val isBlurred = kotlin.random.Random.nextFloat() < (1f - visibleRatio)
            result.add(FuzzyWord(match.value, isBlurred = isBlurred, isWord = true))
            pos = match.range.last + 1
        }

        if (pos < text.length) {
            result.add(FuzzyWord(text.substring(pos), isBlurred = true, isWord = false))
        }

        return result
    }

    /**
     * 计算阅读等级（基于词频分析简化版）
     */
    fun estimateReadingLevel(text: String): String {
        var count = 0
        var totalLength = 0
        for (match in WORD_REGEX.findAll(text)) {
            count++
            totalLength += match.value.length
        }
        // 空输入：除零得 NaN，所有比较为 false 会落进 else 误报 "Advanced"
        if (count == 0) return "Easy"
        val avgLength = totalLength.toDouble() / count
        return when {
            avgLength < 4.5 -> "Easy"
            avgLength < 5.5 -> "Intermediate"
            avgLength < 6.5 -> "Upper-Intermediate"
            else -> "Advanced"
        }
    }
}
