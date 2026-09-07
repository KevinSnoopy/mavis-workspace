package com.eareyereading.tts

import com.k2fsa.sherpa.onnx.OfflineTts

/**
 * sherpa-onnx native 引擎实例持有者：[OfflineTts] 指针与模型元数据。
 *
 * 从 [EmbeddedTtsEngine] 抽出的单一职责类（SRP）：引擎主类不再直接持有
 * native 指针与元数据字段，通过本类统一读写。所有字段 `@Volatile`，
 * `synchronized` 访问由调用方在锁内完成（与 [EmbeddedTtsEngine.speakMutex]
 * 配合，保证 generate/release 互斥）。
 *
 * **为什么不可拆分到更小**：tts / currentModelName / currentModelIsKokoro /
 * sampleRate 四个字段是同一 native 实例的不可分割投影——构造时一起写、
 * release 时一起清，拆开会导致部分字段残留旧值与新实例不一致。
 * warmedUpModelId 语义上属于"引擎实例生命周期"（release 后置 null），
 * 一并管理。
 */
internal class TtsNativeEngine {
    /** sherpa-onnx OfflineTts native 实例；null = 未加载/已释放 */
    @Volatile
    var tts: OfflineTts? = null
        private set

    /** 当前已加载模型 id；空串 = 未加载 */
    @Volatile
    var currentModelName: String = ""
        private set

    /** 当前已加载模型是否为 Kokoro（决定 generate 时是否传音色 sid） */
    @Volatile
    var currentModelIsKokoro: Boolean = false
        private set

    /** 当前模型的采样率（Piper 22050 / Kokoro 24000） */
    @Volatile
    var sampleRate: Int = 22050
        private set

    /**
     * 已完成"首次推理预热"的模型 id（见 [EmbeddedTtsEngine.warmUp]）。
     * release()/换模型后置 null——新的 OfflineTts 实例要重新预热；
     * 任何一次真实合成成功也会置位（真实请求本身就完成了预热）。
     */
    @Volatile
    var warmedUpModelId: String? = null

    /** 是否已加载任意模型（快路径检查） */
    fun isLoaded(): Boolean = tts != null

    /** 已加载模型 id 是否与 [modelId] 一致（initialize 快路径） */
    fun isModelLoaded(modelId: String): Boolean = tts != null && currentModelName == modelId

    /**
     * 替换 native 实例：写入新 [OfflineTts] 及其元数据。
     * 调用方必须在 `synchronized` + speakMutex 内调用，
     * 保证与 generate()/release() 互斥。
     */
    fun assign(newTts: OfflineTts, modelId: String, isKokoro: Boolean, sampleRate: Int) {
        tts = newTts
        currentModelName = modelId
        currentModelIsKokoro = isKokoro
        this.sampleRate = sampleRate
    }

    /**
     * 释放并清空 native 实例。
     * 调用方必须在 speakMutex 内调用（与 generate 互斥，防 use-after-free）。
     */
    fun release() {
        tts?.let { try { it.release() } catch (_: Exception) {} }
        clear()
    }

    /** 清空元数据（不释放 native 实例；用于并发抢先加载时丢弃多余实例） */
    fun clear() {
        tts = null
        currentModelIsKokoro = false
        warmedUpModelId = null
    }

    /** 标记当前模型已完成预热（幂等，@Volatile 写） */
    fun markWarmedUp() {
        warmedUpModelId = currentModelName
    }

    /** 当前模型是否已完成首次推理预热 */
    fun isWarmedUp(): Boolean = warmedUpModelId != null && warmedUpModelId == currentModelName
}
