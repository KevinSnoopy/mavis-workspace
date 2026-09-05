package com.eareyereading.util

import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Collins COBUILD 词频分级
 * 基于 Collins COBUILD 词典的词频分级体系：
 * - 星级越高 = 词汇越生僻
 *
 * 同时映射到国内考试等级：
 * - 核心词汇 = CET4 程度
 * - 进阶词汇 = CET6 / TEM4 程度
 * - 高阶词汇 = TEM8 / IELTS 程度
 * - 学术词汇 = TOEFL / GRE 程度
 *
 * 数据来源：Collins COBUILD Active Study Dictionary 词频数据
 */
@Singleton
class CollinsClassifier @Inject constructor() {

    enum class WordLevel(
        val displayName: String,
        val level: Int,       // 1-5 Collins星级
        val description: String
    ) {
        // Collins 1星：最常用词汇（约2500词）
        CORE("核心词汇", 1, "最常用基础词汇，CET4 程度"),
        // Collins 2星：常用词汇（约3000词）
        INTERMEDIATE("进阶词汇", 2, "日常生活常用，CET6/TEM4 程度"),
        // Collins 3星：中等常用（约3500词）
        UPPER_INTERMEDIATE("提高词汇", 3, "较正式场合使用，IELTS 程度"),
        // Collins 4星：较不常用（约4000词）
        ADVANCED("高阶词汇", 4, "学术/专业语境，TOEFL 程度"),
        // Collins 5星：生僻词汇（约5000+词）
        RARE("学术词汇", 5, "GRE/学术术语，最高级别"),
        // 标点/数字/无法识别
        UNKNOWN("未分级", 0, "数字、标点或未知词");
    }

    companion object {
        /**
         * 数据列表里混有少量大写条目（如 "January"、"Greek"、"kWh"），而 classify
         * 对输入统一小写化，这些条目原本永远无法命中。构造时统一归一化为小写。
         * 词表本体见 CollinsStar1Words.kt ~ CollinsStar5Words.kt（纯数据文件）。
         */
        private val collinsOneLower: Set<String> by lazy { CollinsStar1Words.mapTo(HashSet()) { it.lowercase(Locale.ROOT) } }
        private val collinsTwoLower: Set<String> by lazy { CollinsStar2Words.mapTo(HashSet()) { it.lowercase(Locale.ROOT) } }
        private val collinsThreeLower: Set<String> by lazy { CollinsStar3Words.mapTo(HashSet()) { it.lowercase(Locale.ROOT) } }
        private val collinsFourLower: Set<String> by lazy { CollinsStar4Words.mapTo(HashSet()) { it.lowercase(Locale.ROOT) } }
        private val collinsFiveLower: Set<String> by lazy { CollinsStar5Words.mapTo(HashSet()) { it.lowercase(Locale.ROOT) } }

        /** classifyText 对每个词调用 classify，正则提升为常量避免重复编译。 */
        private val ALPHA_ONLY = Regex("^[a-z]+$")

        /** classifyText 的分词正则同样预编译（旧实现每次调用 Pattern.compile）。 */
        private val WORD_REGEX = Regex("[a-zA-Z]+")
    }

    /**
     * 判断单词所属等级
     * 优先级：按 Collins 星级顺序检查，未命中则用长度启发
     */
    fun classify(word: String): WordLevel {
        // Locale.ROOT：避免土耳其语等 locale 下 lowercase 的 I→ı 变体破坏分级
        val w = word.lowercase(Locale.ROOT).trim()
        // issue 2.12：collinsOne 本身收录 "a"/"i"——单字母一刀切 UNKNOWN
        // 会让英文最高频的 "I" 永远无分级
        if (w.length < 2) {
            return if (w in collinsOneLower) WordLevel.CORE else WordLevel.UNKNOWN
        }
        if (!ALPHA_ONLY.matches(w)) return WordLevel.UNKNOWN

        return if (w in collinsOneLower) WordLevel.CORE
        else if (w in collinsTwoLower) WordLevel.INTERMEDIATE
        else if (w in collinsThreeLower) WordLevel.UPPER_INTERMEDIATE
        else if (w in collinsFourLower) WordLevel.ADVANCED
        else if (w in collinsFiveLower) WordLevel.RARE
        else when {
            // 长度启发：长词越可能是高阶词汇
            w.length >= 12 -> WordLevel.RARE
            w.length >= 10 -> WordLevel.ADVANCED
            w.length >= 8  -> WordLevel.UPPER_INTERMEDIATE
            w.length >= 6  -> WordLevel.INTERMEDIATE
            else           -> WordLevel.CORE
        }
    }

    /**
     * 批量分类一串文本中的所有词汇
     */
    fun classifyText(text: String): Map<String, WordLevel> {
        val words = WORD_REGEX.findAll(text).mapTo(HashSet()) { it.value }
        return words.associateWith { classify(it) }
    }
}
