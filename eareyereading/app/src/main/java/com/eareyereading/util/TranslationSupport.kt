package com.eareyereading.util

import android.os.SystemClock
import java.util.LinkedHashMap
import java.util.Collections

/**
 * 翻译结果内存 LRU 缓存。
 *
 * 从 [TranslationHelper] 抽出的单一职责类（SRP）。同一段落/句子/单词的重复翻译
 *（翻译开关重开、分栏/回译模式重进、同句再次双击等）直接命中内存，
 * 不再消耗 ML Kit 推理。失败结果不落缓存（下次仍会重试）。
 *
 * accessOrder LinkedHashMap + 条数上限驱逐，synchronizedMap 保证并发安全。
 */
internal class TranslationMemoryCache {
    private val cache: MutableMap<String, String> = Collections.synchronizedMap(
        object : LinkedHashMap<String, String>(64, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>): Boolean =
                size > MAX_ENTRIES
        },
    )

    fun get(key: String): String? = cache[key]

    fun put(key: String, value: String) { cache[key] = value }

    fun key(text: String, sourceLang: String, targetLang: String): String =
        "$sourceLang>$targetLang|$text"

    companion object {
        // 内存 LRU 缓存上限：段落级译文体量较大，512 条足够覆盖
        // 整本中小型书籍 + 常用句子/单词，超出按访问顺序驱逐
        private const val MAX_ENTRIES = 512
    }
}

/**
 * LLM 翻译熔断器：连续失败达阈值后进入冷却期，期间不再尝试 LLM。
 *
 * 从 [TranslationHelper] 抽出的单一职责类（SRP）。离线/端点故障时
 * 避免每段翻译都先等满 10s 连接超时才回退机翻。
 */
internal class LlmCircuitBreaker {
    @Volatile
    private var consecutiveFailures = 0

    @Volatile
    private var cooldownUntil = 0L

    /** 熔断是否处于开启状态（冷却期内）。 */
    fun isOpen(): Boolean = SystemClock.elapsedRealtime() < cooldownUntil

    /** 记录一次成功：重置失败计数。 */
    fun recordSuccess() {
        consecutiveFailures = 0
    }

    /** 记录一次失败：累计计数，达阈值后进入冷却期。 */
    fun recordFailure() {
        consecutiveFailures++
        if (consecutiveFailures >= FAILURE_THRESHOLD) {
            cooldownUntil = SystemClock.elapsedRealtime() + COOLDOWN_MS
            consecutiveFailures = 0
            android.util.Log.w(
                TAG,
                "LLM failed $FAILURE_THRESHOLD times in a row, cooldown ${COOLDOWN_MS}ms",
            )
        }
    }

    companion object {
        private const val TAG = "TranslationHelper"
        private const val FAILURE_THRESHOLD = 3
        private const val COOLDOWN_MS = 60_000L
    }
}
