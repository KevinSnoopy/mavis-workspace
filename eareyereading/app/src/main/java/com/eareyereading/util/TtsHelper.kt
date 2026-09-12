package com.eareyereading.util

import android.content.Context
import com.eareyereading.domain.repository.SettingsRepository
import com.eareyereading.tts.EmbeddedTtsEngine
import com.eareyereading.tts.TencentTtsEngine
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/**
 * TTS 协调器。
 *
 * 自 2026-08-30 起，**只走内置 sherpa-onnx 模式**——系统 TextToSpeech
 * 完全下线。理由：
 *   1) 国内 ROM（MIUI/HyperOS）系统 TTS 服务拒绝 bind 给第三方 app（OS 层限制）
 *   2) 即便绑定成功，系统 TTS 自带的中文 OEM 引擎会把英文内容里的数字 /
 *      缩写成普通话风格（"2026" 读成"二零二六"），与英文阅读产品定位冲突
 *   3) 内置模型（Piper 英文 / Kokoro 中英多音色，2026-09 起双模型可选）
 *      带完整词典，朗读稳定性高于系统 TTS
 *
 * 公共 API（ReaderViewModel / ReaderScreen / 设置页消费）：
 *   - initialize / initializeEmbeddedForced
 *   - speak / speakSentences
 *   - stop / pause / isSpeaking
 *   - shutdown / isInSentenceChain
 *   - getEmbeddedEngine / getTencentEngine / getEngineType
 *   - onEmbeddedReleased
 *   - setSpeed / getSpeed
 */
@Singleton
class TtsHelper @Inject constructor(
    @ApplicationContext private val context: Context,
    private val embeddedTts: EmbeddedTtsEngine,
    private val tencentTts: TencentTtsEngine,
    private val settingsRepository: SettingsRepository,
) {
    /**
     * 当前 TTS 引擎类型："embedded"（离线）或 "tencent"（在线腾讯云 TTS）。
     * 从 SettingsRepository 读取，设置页切换时更新。
     */
    @Volatile
    private var engineType: String = "embedded"
    /**
     * 内部协程作用域：用 var 而非 val — shutdown() 后会换新 scope。
     * 否则旧 scope.cancel 后所有 launch 都落进已取消 scope，embeddedTTS 永久静默失效。
     */
    @Volatile
    private var scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    /** 当前嵌入式引擎是否已就绪 */
    @Volatile
    private var isInitialized = false

    /** 当前朗读的语速（字 / 秒） */
    @Volatile
    var currentSpeed: Float = 1.0f
        private set

    // 此处原有 `ttsModeState: StateFlow<TtsMode>`，用于让 UI 观察"系统 TTS / 内置 TTS"
    // 模式切换。系统 TTS 下线后该流恒为 EMBEDDED，已无任何外部订阅者，
    // 随之移除（连同 TtsMode 枚举）。

    /** 当前内置引擎单句朗读协程，用于 stop() 取消过期朗读 */
    @Volatile
    private var embeddedSpeakJob: Job? = null

    /** 句子链协程；走 stop() 取消路径 */
    @Volatile
    private var sentenceChainJob: Job? = null

    /** 标记是否正在句子链朗读中（用于 speak() 打断判断） */
    @Volatile
    private var isInSentenceChain = false

    /** 语言 — 默认 en；书切换时由 initializeEmbeddedForced 更新 */
    @Volatile
    private var currentLocale: Locale = Locale.US

    /** 朗读速度调节 */
    fun setSpeed(speed: Float) {
        currentSpeed = speed
        tencentTts.setSpeed(speed)
    }

    fun getSpeed(): Float = currentSpeed

    /**
     * 初始化 TTS 引擎（按当前引擎类型路由）。
     * - tencent：在线引擎，网络可达性在首次合成时检验
     * - embedded：模型未下载返回 false，引导用户去设置页下载
     */
    suspend fun initialize(language: String = "en"): Boolean {
        refreshEngineType()
        if (engineType == "tencent") {
            val ok = tencentTts.initialize(language)
            if (ok) isInitialized = true
            return ok
        }
        return initializeEmbeddedForced(language)
    }

    /** 从 SettingsRepository 刷新引擎类型 + 腾讯云凭证/音色（设置页切换后调用） */
    suspend fun refreshEngineType() {
        engineType = settingsRepository.getTtsEngineType().first()
        val tencentId = settingsRepository.getTencentSecretId().first()
        val tencentKey = settingsRepository.getTencentSecretKey().first()
        tencentTts.setCredentials(tencentId, tencentKey)
        val voiceId = settingsRepository.getTencentVoiceId().first()
        tencentTts.setVoiceId(voiceId)
        tencentTts.setSpeed(currentSpeed)
    }

    /**
     * 显式初始化内置 TTS（用户从设置页下载完模型后调用）。
     * 不尝试系统 TTS 路径——已下线。
     */
    suspend fun initializeEmbeddedForced(language: String? = null): Boolean {
        val modelInfo = embeddedTts.modelForInitialize(language)
        if (modelInfo == null) {
            android.util.Log.w(TAG, "initializeEmbeddedForced: no model downloaded")
            return false
        }
        val ok = embeddedTts.initialize(modelInfo, language = language)
        if (ok) {
            isInitialized = true
            currentLocale = Locale.US
            android.util.Log.i(TAG, "initializeEmbeddedForced: ready (${modelInfo.id})")
            // 首次推理预热：后台消化 ONNX Runtime 首次 generate 的冷启动开销
            // （真机实测 Kokoro int8 首块 ~10s vs 稳态 RTF≈0.65），把这笔时间
            // 从"用户点击朗读后的首声延迟"挪到初始化后的空闲期。warmUp 内部
            // tryLock + 已预热检查：快路径重复调用、朗读进行中都是零成本 no-op
            scope.launch {
                try {
                    embeddedTts.warmUp()
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (_: Exception) {
                    // 预热是尽力而为：失败不阻塞初始化结果，下次初始化再试
                }
            }
        }
        return ok
    }

    /**
     * 朗读一段文字
     * 注意：句子链进行中时此方法会打断并停止朗读
     */
    fun speak(text: String, onComplete: (() -> Unit)? = null) {
        // 先停止自动朗读链
        stop()

        if (!isInitialized) {
            android.util.Log.w(TAG, "speak() called but TTS not initialized")
            onComplete?.invoke()
            return
        }

        android.util.Log.d(TAG, "speak(): engine=$engineType, len=${text.length}, '${text.take(50)}'")

        // 取消仍挂在 speakMutex 上的上一次朗读，避免旧文本在新朗读之后才播出
        embeddedSpeakJob?.cancel()
        embeddedSpeakJob = scope.launch {
            if (engineType == "tencent") {
                tencentTts.speak(text, speed = currentSpeed)
                tencentTts.abandonAudioFocus()
            } else {
                embeddedTts.speak(text, speed = currentSpeed)
                // 自然播完归还音频焦点（被 stop() 取消路径已自行归还，幂等）
                embeddedTts.abandonAudioFocus()
            }
            withContext(Dispatchers.Main) {
                onComplete?.invoke()
            }
        }
    }

    /**
     * 逐句朗读 — 每个句子完成时触发 onSentenceDone。
     * 句子链进行中时不可被打断（直到全部读完或显式 stop()）。
     *
     * 底层走引擎的流式句链（speakSentencesStreaming）：整链共用一条
     * MODE_STREAM AudioTrack，句 i 还在出声时句 i+1 已在合成——旧实现
     * 逐句"整句合成完才开始播"，Kokoro 这类大模型句句之间都有
     * 整句合成时长的静默 gap。
     */
    fun speakSentences(
        sentences: List<String>,
        onSentenceDone: (Int) -> Unit,
        onAllDone: () -> Unit,
    ) {
        if (!isInitialized || sentences.isEmpty()) {
            onAllDone()
            return
        }

        isInSentenceChain = true

        // 取消旧链但保新链；单条 onDone 仍按原顺序走
        sentenceChainJob?.cancel()
        sentenceChainJob = scope.launch {
            try {
                if (engineType == "tencent") {
                    tencentTts.speakSentencesStreaming(sentences, speed = currentSpeed) { index ->
                        scope.launch { onSentenceDone(index) }
                    }
                } else {
                    embeddedTts.speakSentencesStreaming(sentences, speed = currentSpeed) { index ->
                        // 引擎回调来自 IO 线程（单生产者保序）；scope 是 Main 调度器，
                        // launch 入队 FIFO，回调顺序与句子顺序一致
                        scope.launch { onSentenceDone(index) }
                    }
                }
            } finally {
                isInSentenceChain = false
                // 链结束（自然读完或被 stop() 取消）统一归还音频焦点
                if (engineType == "tencent") tencentTts.abandonAudioFocus() else embeddedTts.abandonAudioFocus()
                // scope 在 Dispatchers.Main 上，直接回调即可
                onAllDone()
            }
        }
    }

    fun stop() {
        isInSentenceChain = false
        sentenceChainJob?.cancel()
        sentenceChainJob = null
        embeddedSpeakJob?.cancel()
        embeddedSpeakJob = null
        embeddedTts.stop()
        tencentTts.stop()
    }

    fun pause() {
        stop()
    }

    /**
     * 是否正在自动朗读句子链
     */
    fun isInSentenceChain(): Boolean = isInSentenceChain

    /**
     * 是否正在播放（包括单句朗读）
     */
    fun isSpeaking(): Boolean = embeddedTts.isPlaying() || tencentTts.isPlaying()

    /**
     * 切换内置 TTS 模型以匹配新书语言（跨语言换书时调用）。
     * 2026-09 起双模型：模型选择以用户在设置页的显式选择为准（用户意图 >
     * 语言启发式），引擎内 getCurrentModelInfo 已按选择路由，此方法保留为
     * 兼容调用方的 no-op。
     */
    suspend fun switchEmbeddedModelIfNeeded(language: String?) {
        android.util.Log.d(TAG, "switchEmbeddedModelIfNeeded($language): no-op (user-selected model wins)")
    }

    /**
     * 单 TTS 引擎跨书复用时，同步设置当前语言。
     * 由于已下线 TextToSpeech，setLanguage 实际只更新内部 Locale 字段。
     */
    fun setLanguage(language: String) {
        currentLocale = parseLocale(language)
        android.util.Log.i(TAG, "setLanguage($language): currentLocale=$currentLocale")
    }

    private fun parseLocale(language: String): Locale = when (language.lowercase()) {
        "zh", "zh-cn", "zh-hans" -> Locale.SIMPLIFIED_CHINESE
        "en", "en-us" -> Locale.US
        "en-gb" -> Locale.UK
        else -> Locale.US
    }

    fun shutdown() {
        isInSentenceChain = false
        sentenceChainJob?.cancel()
        sentenceChainJob = null
        embeddedSpeakJob?.cancel()
        embeddedSpeakJob = null
        try { embeddedTts.stop() } catch (_: Exception) {}
        try { tencentTts.stop() } catch (_: Exception) {}
        // cancel 内部协程 scope + 换新 scope，避免后续 launch 落进已取消 scope
        val oldScope = scope
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        oldScope.cancel()
        isInitialized = false
    }

    /**
     * 暴露 embedded TTS 给上层（用于模型下载管理 UI）
     */
    fun getEmbeddedEngine(): EmbeddedTtsEngine = embeddedTts

    /** 暴露腾讯云 TTS 引擎给上层 */
    fun getTencentEngine(): TencentTtsEngine = tencentTts

    /** 当前引擎类型（"embedded" / "tencent"） */
    fun getEngineType(): String = engineType

    /**
     * 内置引擎被外部 release() 后调用：复位初始化状态。
     * 由于已无系统模式，复位后等下次用户触发朗读走"未就绪"路径 → 引导下载。
     */
    suspend fun onEmbeddedReleased() {
        isInitialized = false
        android.util.Log.w(TAG, "onEmbeddedReleased: embedded engine released, reset isInitialized")
    }

    // ── 重构说明（YAGNI）──
    // 此处原有 `ttsMode` 属性、`TtsMode` 枚举（含已不可达的 SYSTEM 常量）、
    // 恒为 null 的 `lastFailureReason` 字段，以及 6 个不再被赋值的
    // `InitFailureReason` 枚举项。它们都是 2026-08-30 系统 TTS 下线时留下的
    // 兼容层：既没有生产者也没有读取者，全部挂着 @Suppress("unused")。
    // 保留"兼容"代码而外部并无消费者，只会让后续维护者误以为还存在
    // 系统引擎分支需要处理，故一并移除。

    companion object {
        private const val TAG = "TtsHelper"
    }
}
