package com.eareyereading.util

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Unit tests for [CollinsClassifier].
 *
 * 除基础分级外，重点钉住两个既有产品决策（U-16/U-27 的延期结论）：
 * - 数据表存在跨星级重复词（约 2500 个），classify 按 1→5 星顺序检查，
 *   重复词取更低星级（更保守）的解释 —— 去重语义若改变需产品决策，
 *   测试先钉住现状防止无声回归
 */
class CollinsClassifierTest {

    private val classifier = CollinsClassifier()

    @Test
    fun `core words classify as CORE`() {
        assertEquals(CollinsClassifier.WordLevel.CORE, classifier.classify("the"))
        assertEquals(CollinsClassifier.WordLevel.CORE, classifier.classify("you"))
    }

    @Test
    fun `classification is case-insensitive`() {
        assertEquals(classifier.classify("abandon"), classifier.classify("Abandon"))
        assertEquals(classifier.classify("abandon"), classifier.classify("ABANDON"))
    }

    @Test
    fun `short empty and non-alpha inputs are UNKNOWN`() {
        // issue 2.12：collinsOne 收录 "a"/"i"——单字母词不再一刀切 UNKNOWN，
        // 否则英文最高频的 "I" 永远无分级
        assertEquals(CollinsClassifier.WordLevel.CORE, classifier.classify("a"))
        assertEquals(CollinsClassifier.WordLevel.CORE, classifier.classify("i"))
        assertEquals(CollinsClassifier.WordLevel.CORE, classifier.classify("I"))
        assertEquals(CollinsClassifier.WordLevel.UNKNOWN, classifier.classify(""))
        assertEquals(CollinsClassifier.WordLevel.UNKNOWN, classifier.classify("123"))
        assertEquals(CollinsClassifier.WordLevel.UNKNOWN, classifier.classify("don't"))
        assertEquals(CollinsClassifier.WordLevel.UNKNOWN, classifier.classify("x"))
    }

    @Test
    fun `words duplicated across tiers keep the lowest-tier interpretation`() {
        // "abandon" 同时存在于三星与四星表；"administration" 存在于二/三/四/五星表。
        // 检查顺序 1→5 星决定重复词落在最低星级（更保守的分级）
        assertEquals(CollinsClassifier.WordLevel.UPPER_INTERMEDIATE, classifier.classify("abandon"))
        assertEquals(CollinsClassifier.WordLevel.INTERMEDIATE, classifier.classify("administration"))
    }

    @Test
    fun `classifyText aggregates per word`() {
        val result = classifier.classifyText("the abandon")
        assertEquals(CollinsClassifier.WordLevel.CORE, result["the"])
        assertEquals(CollinsClassifier.WordLevel.UPPER_INTERMEDIATE, result["abandon"])
    }
}
