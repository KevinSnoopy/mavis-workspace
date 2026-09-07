package com.eareyereading.tts

/**
 * 短文本（单词）预合成 PCM 缓存：key = "清洗后文本|sid|speed"。
 *
 * 从 [EmbeddedTtsEngine] 抽出的单一职责类（SRP）。
 *
 * 为什么需要：Kokoro 每次 generate 调用有 ~2 秒**固定开销**（与文本长度
 * 无关——真机实测：7 字符单词合成 1.2 秒音频耗时 2.9 秒；80 字符长块
 * 6.5 秒音频耗时 4.2 秒，反推固定成本 ~2s + RTF≈0.34）。固定开销在
 * native 推理层，预热消不掉、每次都付——单词/短句现场合成必然卡。
 * 单词弹窗打开时后台预合成进缓存，点喇叭时零推理延迟直接播
 *（2026-09-05 "读一个单词都卡"修复）。
 *
 * android.util.LruCache 的 get/put 方法级 synchronized，线程安全。
 */
internal class PcmCache {
    private val cache = android.util.LruCache<String, FloatArray>(MAX_ENTRIES)

    /** 缓存键：与合成消费端保持一致（清洗后文本 + 音色 + 语速）。 */
    fun key(text: String, sid: Int, speed: Float): String =
        "${text.trim().lowercase()}|$sid|$speed"

    fun get(key: String): FloatArray? = cache.get(key)

    fun put(key: String, samples: FloatArray) = cache.put(key, samples)

    companion object {
        /**
         * 预合成 PCM 缓存条目数（单词场景）：单条约 100-300KB
         *（1-3 秒 24kHz Float PCM），24 条峰值 ~7MB。
         */
        private const val MAX_ENTRIES = 24
    }
}
