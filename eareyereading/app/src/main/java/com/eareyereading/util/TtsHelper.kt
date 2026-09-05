package com.eareyereading.util

import android.content.Context
import com.eareyereading.tts.EmbeddedTtsEngine
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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
 * 公共 API（保留原签名以兼容 ReaderViewModel / ReaderScreen 调用方）：
 *   - initialize / initializeEmbeddedForced
 *   - speak / speakSentences
 *   - stop / pause / isSpeaking
 *   - shutdown / isInSentenceChain
 *   - getEmbeddedEngine
 *   - onEmbeddedReleased
 *   - setSpeed / getSpeed / ttsModeState
 */
@Singleton
class TtsHelper @Inject constructor(
    @ApplicationContext private val context: Context,
    private val embeddedTts: EmbeddedTtsEngine,
) {
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

    /** 模式流——只剩 EMBEDDED，但保留字段让外部观察代码不被破坏 */
    private val _ttsModeState = MutableStateFlow(TtsMode.EMBEDDED)
    val ttsModeState: StateFlow<TtsMode> = _ttsModeState.asStateFlow()

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
    }

    fun getSpeed(): Float = currentSpeed

    /**
     * 初始化内置 TTS 引擎。
     * - 模型未下载：返回 false，引导用户去设置页下载
     * - 模型已下载：构造 OfflineTts 实例，置 isInitialized=true
     */
    suspend fun initialize(language: String = "en"): Boolean =
        initializeEmbeddedForced(language)

    /** 兼容旧 API，等价于 initialize */
    suspend fun initializeWith(language: String = "en", enginePackage: String?): Boolean =
        initializeEmbeddedForced(language)

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
        val ok = embeddedTts.initialize(modelInfo)
        if (ok) {
            isInitialized = true
            currentLocale = Locale.US
            // 不要再次 updateTtsMode 切到 EMBEDDED（已经是了）。但调用方可能初始化前 mode
            // 为 SYSTEM（旧登录状态），所以强制同步一下状态
            _ttsModeState.value = TtsMode.EMBEDDED
            android.util.Log.i(TAG, "initializeEmbeddedForced: ready (${modelInfo.id})")
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

        android.util.Log.d(TAG, "speak(): embedded, len=${text.length}, '${text.take(50)}'")

        // 取消仍挂在 speakMutex 上的上一次朗读，避免旧文本在新朗读之后才播出
        embeddedSpeakJob?.cancel()
        embeddedSpeakJob = scope.launch {
            embeddedTts.speak(text, speed = currentSpeed)
            // 自然播完归还音频焦点（被 stop() 取消路径已自行归还，幂等）
            embeddedTts.abandonAudioFocus()
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
                embeddedTts.speakSentencesStreaming(sentences, speed = currentSpeed) { index ->
                    // 引擎回调来自 IO 线程（单生产者保序）；scope 是 Main 调度器，
                    // launch 入队 FIFO，回调顺序与句子顺序一致
                    scope.launch { onSentenceDone(index) }
                }
            } finally {
                isInSentenceChain = false
                // 链结束（自然读完或被 stop() 取消）统一归还音频焦点
                embeddedTts.abandonAudioFocus()
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
    fun isSpeaking(): Boolean = embeddedTts.isPlaying()

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

    /**
     * 内置引擎被外部 release() 后调用：复位初始化状态。
     * 由于已无系统模式，复位后等下次用户触发朗读走"未就绪"路径 → 引导下载。
     */
    suspend fun onEmbeddedReleased() {
        isInitialized = false
        android.util.Log.w(TAG, "onEmbeddedReleased: embedded engine released, reset isInitialized")
    }

    /**
     * 当前使用的 TTS 模式（保留枚举——外部 UI 仍可能引用 SYSTEM 但总是隐式被忽略）。
     */
    @Suppress("unused")
    val ttsMode: TtsMode = TtsMode.EMBEDDED

    /** 兼容旧枚举调用方。仅 EMBEDDED 一种值。 */
    enum class TtsMode(val displayName: String) {
        @Suppress("unused") SYSTEM("系统 TTS"),     // 已下线：保留常量但不再可达
        EMBEDDED("内置 TTS"),
    }

    /**
     * 占位兼容 — 旧 API 有 `lastFailureReason` 字段，外部可能读它；返回 null
     * 因为已无系统 TTS 失败类型。
     */
    var lastFailureReason: InitFailureReason? = null
        private set

    /**
     * 失败原因枚举（占位）：保留枚举项让老调用方编译过；运行时不再 set。
     */
    enum class InitFailureReason(@Suppress("unused") val userMessage: String) {
        @Suppress("unused") NO_ENGINE(""),
        @Suppress("unused") PHANTOM_DEFAULT(""),
        @Suppress("unused") ALL_DISABLED(""),
        @Suppress("unused") TIMEOUT(""),
        @Suppress("unused") ENGINE_ERROR(""),
        @Suppress("unused") LANGUAGE_UNSUPPORTED(""),
    }

    companion object {
        private const val TAG = "TtsHelper"
    }
}
