package com.eareyereading.util

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import com.eareyereading.tts.EmbeddedTtsEngine
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.util.*
import java.util.concurrent.CopyOnWriteArrayList
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume

@Singleton
class TtsHelper @Inject constructor(
    @ApplicationContext private val context: Context,
    private val embeddedTts: EmbeddedTtsEngine,
) {
    @Volatile
    private var tts: TextToSpeech? = null
    @Volatile
    private var isInitialized = false
    @Volatile
    private var currentLocale = Locale.US
    @Volatile
    private var pendingLanguage: String? = null
    // 标记 TTS 引擎的 InitListener 回调是否仍在等待（尚未触发）
    @Volatile
    private var initPending = false
    // 每次创建新 TextToSpeech 实例时递增，用于在回调中识别过期的旧实例回调
    @Volatile
    private var ttsGeneration = 0
    // 初始化失败的原因，用于向用户展示具体的错误信息
    @Volatile
    var lastFailureReason: InitFailureReason? = null
        private set
    // 当前 TTS 模式（系统 TextToSpeech 还是内置 sherpa-onnx）
    @Volatile
    var ttsMode: TtsMode = TtsMode.SYSTEM
        private set
    // 反应式状态流：供 Settings/Reader 等 UI 观察模式变化
    private val _ttsModeState = MutableStateFlow(ttsMode)
    val ttsModeState: StateFlow<TtsMode> = _ttsModeState.asStateFlow()
    // 使用 CopyOnWriteArrayList 保证多协程/回调线程并发访问安全
    private val pendingContinuations = CopyOnWriteArrayList<kotlin.coroutines.Continuation<Boolean>>()
    // 协程作用域（用于 embedded TTS 的异步朗读）。
    // 用 var 而非 val：shutdown() 会 cancel 掉旧 scope，若不换新，
    // 之后所有 launch 都落进已取消的 scope，embedded TTS 将永久静默失效。
    @Volatile
    private var scope = newScope()

    private fun newScope(): CoroutineScope =
        CoroutineScope(SupervisorJob() + Dispatchers.Main)

    // 当前 embedded 单句朗读的协程，用于在 stop()/新 speak() 时取消停留在
    // speakMutex 上的过期朗读，避免旧文本在新朗读之后才播出（乱序）
    @Volatile
    private var embeddedSpeakJob: kotlinx.coroutines.Job? = null

    // SYSTEM 句子链的终止回调：tts.stop() 后引擎只回 onStop 不回 onDone/onError，
    // 链上回调会被孤立；stop() 时从这里补偿调用
    @Volatile
    private var activeChainOnAllDone: (() -> Unit)? = null

    // 标记是否正在自动朗读句子链，防止 speak() 打断
    @Volatile
    private var isInSentenceChain = false

    companion object {
        private const val TAG = "TtsHelper"
        // TTS 引擎初始化超时（毫秒）：如果回调迟迟不返回，避免协程永久挂起
        private const val INIT_TIMEOUT_MS = 15_000L
    }

    /**
     * 切换 TTS 模式，同时更新 @Volatile 字段和 StateFlow。
     * 所有内部 ttsMode 赋值都必须走这个方法，保证两个视图一致。
     */
    private suspend fun updateTtsMode(mode: TtsMode) {
        ttsMode = mode
        _ttsModeState.value = mode
        if (mode == TtsMode.SYSTEM) {
            // 切回系统 TTS 时释放已加载的 sherpa-onnx 模型（上百 MB native 内存），
            // 反向切换（→EMBEDDED）的释放已在 initializeEmbeddedForced 处理
            try { embeddedTts.release() } catch (_: Exception) {}
        }
    }

    /**
     * TTS 初始化失败的原因。
     *
     * 用于向用户展示具体的失败原因和引导步骤，兼容国产手机缺少 Google TTS 的场景。
     */
    enum class InitFailureReason(val userMessage: String) {
        /** 设备上没有安装任何 TTS 引擎（包括 Google TTS 和国产厂商引擎） */
        NO_ENGINE("设备上未检测到任何文字转语音引擎。请前往系统设置启用 TTS 引擎，或安装 Google 文字转语音。"),
        /** 系统设置里选了某个引擎包名，但该包根本没安装 — 典型的 MIUI/华为"幽灵默认"场景 */
        PHANTOM_DEFAULT("系统设置中的 TTS 引擎不可用（仅供系统内部使用）。请安装 Google 文字转语音或带 TTS 的第三方应用（如讯飞、Google 翻译）。"),
        /** 设备有 TTS 引擎但都被禁用 */
        ALL_DISABLED("检测到的 TTS 引擎都未启用。请在系统设置 → 文本转语音(TTS)输出中启用一个引擎。"),
        /** 15 秒内 TTS 引擎回调未触发 — 引擎可能未启动或卡死 */
        TIMEOUT("TTS 引擎初始化超时（15 秒无响应）。请尝试在系统设置中切换其他 TTS 引擎。"),
        /** 引擎回调触发但 status != SUCCESS（一般是 -1 或 -2） */
        ENGINE_ERROR("TTS 引擎返回错误状态。请尝试在系统设置中切换其他 TTS 引擎。"),
        /** 引擎初始化成功但当前语言不可用 */
        LANGUAGE_UNSUPPORTED("当前 TTS 引擎不支持所选语言。"),
    }

    /**
     * 当前使用的 TTS 模式。
     */
    enum class TtsMode(val displayName: String) {
        /** 系统 TextToSpeech（默认尝试） */
        SYSTEM("系统 TTS"),
        /** 内置 sherpa-onnx（兜底，不依赖系统服务） */
        EMBEDDED("内置 TTS"),
    }

    /**
     * 初始化 TTS 引擎，使用系统默认引擎。
     *
     * 带超时保护：如果 15 秒内回调不触发（设备无 TTS 引擎或引擎异常），
     * 返回 false 而不是让协程永久挂起。
     *
     * **自动回退**：当系统默认引擎（如 Google TTS）在国产手机上不存在时，
     * 会自动尝试设备上检测到的 OEM 引擎（小米小爱 / 华为 / OPPO / vivo / 百度小度 / 讯飞等）。
     * 这对国产手机用户是关键 — 在没有 Google TTS 的情况下也能用上自带的 TTS 引擎。
     *
     * 超时或失败后会清理旧的 TextToSpeech 实例，使下次调用可以创建新实例重试，
     * 避免陷入「旧实例不回调 → 新调用加入等待队列 → 永远超时」的死循环。
     */
    suspend fun initialize(language: String = "en"): Boolean =
        initializeInternal(language, null, allowFallback = true)

    /**
     * 使用指定的引擎包名初始化 TTS 引擎。
     *
     * 用于用户从 TTS 引擎列表中选择某个具体引擎后重试。
     * 传 null 等价于 initialize()，使用系统默认引擎。
     *
     * **不会**自动回退到其他引擎（用户已明确选了某一个）。
     */
    suspend fun initializeWith(language: String = "en", enginePackage: String?): Boolean =
        initializeInternal(language, enginePackage, allowFallback = false)

    /**
     * 自动回退到 OEM 引擎的实现：先尝试指定引擎，失败后尝试检测到的 OEM 引擎。
     */
    private suspend fun initializeInternal(
        language: String,
        enginePackage: String?,
        allowFallback: Boolean,
    ): Boolean {
        val firstAttempt = initializeCore(language, enginePackage)
        if (firstAttempt) {
            updateTtsMode(TtsMode.SYSTEM)
            return true
        }
        if (!allowFallback || enginePackage != null) return false

        // 默认引擎失败 — 综合扫描（getEngines API + Intent 扫描 + 已知包名）。
        // 扫描涉及 binder/PackageManager 查询且可能遍历多个引擎，放 IO 线程，
        // 避免初始化走 Main 调度器时卡 UI
        val discovered = withContext(Dispatchers.IO) {
            TtsEngineHelper.discoverAllTtsEngines(context)
        }
        android.util.Log.w(
            TAG,
            "Default TTS engine failed. Comprehensive scan found ${discovered.size} engines: " +
                discovered.map { "${it.packageName}(enabled=${it.isEnabled}, label=${it.displayName})" }
        )
        for (engine in discovered) {
            if (engine.packageName == enginePackage) continue
            android.util.Log.w(
                TAG,
                "Auto-trying discovered engine: ${engine.packageName}"
            )
            if (initializeCore(language, engine.packageName)) {
                updateTtsMode(TtsMode.SYSTEM)
                return true
            }
        }

        // 兜底：当扫描找不到任何引擎时，主动尝试显式 bind TTS_DEFAULT_SYNTH
        // 这对刚装上 Google TTS 但系统 TTS service 还没刷新列表的场景特别重要
        // — 系统设置已经指向 com.google.android.tts，但 getEngines() 还没返回它
        val systemDefault = TtsEngineHelper.getSystemDefaultEnginePackage(context)
        if (systemDefault != null && systemDefault != enginePackage) {
            val isAlreadyTried = discovered.any { it.packageName == systemDefault }
            if (!isAlreadyTried) {
                android.util.Log.w(
                    TAG,
                    "Scan returned nothing. Explicitly binding TTS_DEFAULT_SYNTH=$systemDefault"
                )
                if (initializeCore(language, systemDefault)) {
                    updateTtsMode(TtsMode.SYSTEM)
                    return true
                }
            }
        }

        // 终极兜底：内置 TTS（sherpa-onnx）
        // 在 MIUI/HyperOS 等深度定制的国产 ROM 上，系统的 TTS service 会拒绝
        // 让第三方 app bind 到任何 TTS 引擎。这是 OS 层限制，应用层无法绕过。
        // 此时唯一可用的方案就是内置的 sherpa-onnx TTS。
        android.util.Log.w(TAG, "All system TTS engines failed. Falling back to embedded TTS (sherpa-onnx)")
        return initializeEmbedded()
    }

    /**
     * 初始化内置 TTS 引擎（sherpa-onnx）。
     *
     * 如果模型已下载，立即初始化；如果没下载，返回 false（调用方应引导用户下载）。
     */
    private suspend fun initializeEmbedded(): Boolean {
        val modelInfo = embeddedTts.getCurrentModelInfo()
        if (!embeddedTts.isModelDownloaded(modelInfo)) {
            android.util.Log.w(
                TAG,
                "Embedded TTS model not downloaded: ${modelInfo.id} (${modelInfo.sizeBytes / 1_000_000}MB)"
            )
            lastFailureReason = InitFailureReason.NO_ENGINE
            return false
        }
        val ok = embeddedTts.initialize(modelInfo)
        if (ok) {
            updateTtsMode(TtsMode.EMBEDDED)
            isInitialized = true
            currentLocale = Locale.US
            android.util.Log.i(TAG, "Switched to embedded TTS mode: ${modelInfo.id}")
        }
        return ok
    }

    /**
     * 显式初始化内置 TTS（无论系统 TTS 状态如何）。
     * 用于用户从设置中选择"使用内置 TTS"或模型下载完成后。
     */
    suspend fun initializeEmbeddedForced(): Boolean {
        val modelInfo = embeddedTts.getCurrentModelInfo()
        if (!embeddedTts.isModelDownloaded(modelInfo)) return false
        val ok = embeddedTts.initialize(modelInfo)
        if (ok) {
            updateTtsMode(TtsMode.EMBEDDED)
            isInitialized = true
            currentLocale = Locale.US
            // 关闭系统 TTS（如果存在）
            try { tts?.stop(); tts?.shutdown(); tts = null } catch (_: Exception) {}
        }
        return ok
    }

    private suspend fun initializeCore(language: String, enginePackage: String?): Boolean =
        try {
            withTimeout(INIT_TIMEOUT_MS) {
                doInitialize(language, enginePackage)
            }
        } catch (e: TimeoutCancellationException) {
            android.util.Log.w(TAG, "TTS init timed out (engine=$enginePackage)", e)
            // 在 invokeOnCancellation 中已经设置了 lastFailureReason
            // 清理可能残留的实例
            try { tts?.shutdown() } catch (_: Exception) {}
            tts = null
            isInitialized = false
            initPending = false
            false
        } catch (e: Exception) {
            android.util.Log.e(TAG, "TTS init failed (engine=$enginePackage)", e)
            lastFailureReason = InitFailureReason.ENGINE_ERROR
            try { tts?.shutdown() } catch (_: Exception) {}
            tts = null
            isInitialized = false
            initPending = false
            false
        }

    private suspend fun doInitialize(language: String, enginePackage: String?): Boolean =
        suspendCancellableCoroutine { cont ->
                // 所有路径都注册取消回调，确保超时后从等待队列移除并清理旧实例
                cont.invokeOnCancellation {
                    pendingContinuations.remove(cont)
                    // 如果没有其他等待者且回调仍未触发，清理旧实例以便下次重试
                    if (pendingContinuations.isEmpty() && initPending && !isInitialized) {
                        initPending = false
                        lastFailureReason = classifyFailureReason(enginePackage)
                        try { tts?.shutdown() } catch (_: Exception) {}
                        tts = null
                    }
                }

                // 已初始化完成，直接返回
                if (isInitialized && tts != null) {
                    cont.resume(true)
                    return@suspendCancellableCoroutine
                }

                // TTS 实例存在且回调仍在等待中 — 加入等待队列
                if (tts != null && initPending) {
                    pendingContinuations.add(cont)
                    return@suspendCancellableCoroutine
                }

                // 之前的初始化已失败/超时（回调已触发但失败，或从未触发）— 清理旧实例，创建新的
                if (tts != null) {
                    try { tts?.shutdown() } catch (_: Exception) {}
                    tts = null
                    isInitialized = false
                }

                pendingContinuations.add(cont)
                pendingLanguage = language
                initPending = true
                val gen = ++ttsGeneration

                val instance = try {
                    if (enginePackage != null) {
                        TextToSpeech(context, { status ->
                            handleInitCallback(status, gen, language, enginePackage)
                        }, enginePackage)
                    } else {
                        TextToSpeech(context) { status ->
                            handleInitCallback(status, gen, language, null)
                        }
                    }
                } catch (e: Exception) {
                    android.util.Log.e(TAG, "Failed to create TextToSpeech", e)
                    initPending = false
                    lastFailureReason = InitFailureReason.ENGINE_ERROR
                    val pending = pendingContinuations.toList()
                    pendingContinuations.clear()
                    cont.resume(false)
                    pending.forEach { c ->
                        if (c !== cont) {
                            try { c.resume(false) } catch (_: IllegalStateException) {}
                        }
                    }
                    return@suspendCancellableCoroutine
                }
                tts = instance
            }

    /**
     * 统一的初始化回调处理，便于在多个构造函数之间复用。
     */
    private fun handleInitCallback(status: Int, gen: Int, language: String, enginePackage: String?) {
        // 忽略旧实例的延迟回调（已被 shutdown 取代）
        if (ttsGeneration != gen) return

        isInitialized = status == TextToSpeech.SUCCESS
        initPending = false
        android.util.Log.i(
            TAG,
            "TTS engine[$enginePackage] init status=$status ($status / " +
                "SUCCESS=${TextToSpeech.SUCCESS})"
        )

        val reason: InitFailureReason? = if (isInitialized) {
            val lang = pendingLanguage ?: language
            currentLocale = when (lang) {
                "zh" -> Locale.SIMPLIFIED_CHINESE
                "ja" -> Locale.JAPANESE
                "fr" -> Locale.FRENCH
                "de" -> Locale.GERMAN
                "es" -> Locale("es", "ES")
                else -> Locale.US
            }
            val result = tts?.setLanguage(currentLocale)
            if (result == TextToSpeech.LANG_MISSING_DATA ||
                result == TextToSpeech.LANG_NOT_SUPPORTED
            ) {
                android.util.Log.w(TAG, "TTS does not support locale $currentLocale, status=$result")
                // 尝试回退到英语：只有连英语也不支持才算语言不可用。
                // 原实现在 setLanguage(Locale.US) 成功后仍然销毁引擎，
                // 导致 isInitialized=true 但 tts=null，之后所有朗读永久静默。
                val usResult = tts?.setLanguage(Locale.US)
                if (usResult == TextToSpeech.LANG_MISSING_DATA ||
                    usResult == TextToSpeech.LANG_NOT_SUPPORTED
                ) {
                    InitFailureReason.LANGUAGE_UNSUPPORTED
                } else {
                    currentLocale = Locale.US
                    tts?.setSpeechRate(1.0f)
                    null
                }
            } else {
                tts?.setSpeechRate(1.0f)
                null
            }
        } else {
            // 回调触发但 status != SUCCESS（一般是 ERROR 或 ERROR_SYNTHESIS）
            InitFailureReason.ENGINE_ERROR
        }

        if (reason != null) {
            lastFailureReason = reason
            // 初始化失败 — 清理实例，使下次调用可以重新创建
            try { tts?.shutdown() } catch (_: Exception) {}
            tts = null
        } else {
            lastFailureReason = null
        }

        // 唤醒所有等待的协程
        val pending = pendingContinuations.toList()
        pendingContinuations.clear()
        pending.forEach { c ->
            try { c.resume(isInitialized) } catch (_: IllegalStateException) {
                // 协程已被取消/已完成，忽略
            }
        }
    }

    /**
     * 在超时或异常时分析失败原因。
     *
     * 优先级：
     * 1. PHANTOM_DEFAULT — 系统设置里选了不存在的包（MIUI/华为的典型问题）
     * 2. NO_ENGINE — 完全没有引擎
     * 3. ALL_DISABLED — 有引擎但都禁用
     * 4. TIMEOUT — 引擎超时
     */
    private fun classifyFailureReason(enginePackage: String?): InitFailureReason {
        return when {
            // 仅当使用系统默认引擎（enginePackage=null）失败时才检测 phantom，
            // 因为用户主动选的引擎如果失败不应该归类为 phantom
            enginePackage == null && TtsEngineHelper.isPhantomDefaultState(context) ->
                InitFailureReason.PHANTOM_DEFAULT
            !TtsEngineHelper.hasAnyEngine(context) ->
                InitFailureReason.NO_ENGINE
            enginePackage == null &&
                TtsEngineHelper.listAvailableEngines(context).none { it.isEnabled } ->
                InitFailureReason.ALL_DISABLED
            else ->
                InitFailureReason.TIMEOUT
        }
    }

    fun setLanguage(language: String) {
        currentLocale = when (language) {
            "zh" -> Locale.SIMPLIFIED_CHINESE
            "ja" -> Locale.JAPANESE
            "fr" -> Locale.FRENCH
            "de" -> Locale.GERMAN
            "es" -> Locale("es", "ES")
            else -> Locale.US
        }
        // 内置 sherpa-onnx 自动根据文本判断语言（中英混合支持）
        // 这里只对系统 TTS 设置 locale
        if (ttsMode == TtsMode.SYSTEM) {
            tts?.language = currentLocale
        }
    }

    fun setSpeed(speed: Float) {
        currentSpeed = speed
        when (ttsMode) {
            TtsMode.SYSTEM -> tts?.setSpeechRate(speed)
            // 内置 TTS 的语速通过 speak(text, speed) 传递，setSpeed 不直接生效
            else -> { /* no-op */ }
        }
    }

    /**
     * 当前播放语速倍率（用于 embedded TTS 的 speak 调用）。
     */
    @Volatile
    var currentSpeed: Float = 1.0f
        private set

    /**
     * 朗读一段文字
     * 注意：自动朗读句子链进行中时，此方法会打断并停止朗读
     */
    fun speak(text: String, onComplete: (() -> Unit)? = null) {
        // 先停止自动朗读链
        stop()

        if (!isInitialized) {
            android.util.Log.w(TAG, "speak() called but TTS not initialized")
            onComplete?.invoke()
            return
        }

        android.util.Log.d(TAG, "speak() mode=$ttsMode, text length=${text.length}, text='${text.take(50)}'")

        when (ttsMode) {
            TtsMode.SYSTEM -> {
                val listener = object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) {
                        android.util.Log.d(TAG, "onStart utterance=$utteranceId")
                    }
                    override fun onDone(utteranceId: String?) {
                        android.util.Log.d(TAG, "onDone utterance=$utteranceId")
                        onComplete?.invoke()
                    }
                    override fun onError(utteranceId: String?) {
                        android.util.Log.e(TAG, "onError utterance=$utteranceId")
                        onComplete?.invoke()
                    }
                }
                tts?.setOnUtteranceProgressListener(listener)
                tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "utterance_${System.currentTimeMillis()}")
            }
            TtsMode.EMBEDDED -> {
                // speak() 是 suspend 且在 IO 线程阻塞到播放完成；完成后切回主线程回调。
                // 先取消仍挂在 speakMutex 上的上一次朗读，避免旧文本在新朗读之后才播出
                embeddedSpeakJob?.cancel()
                embeddedSpeakJob = scope.launch {
                    embeddedTts.speak(text, speed = currentSpeed)
                    withContext(Dispatchers.Main) {
                        onComplete?.invoke()
                    }
                }
            }
        }
    }

    /**
     * 逐句朗读 — 每个句子完成时触发 onSentenceDone
     * 朗读链进行中时不可被打断
     */
    fun speakSentences(sentences: List<String>, onSentenceDone: (Int) -> Unit, onAllDone: () -> Unit) {
        if (!isInitialized || sentences.isEmpty()) {
            onAllDone()
            return
        }

        isInSentenceChain = true

        when (ttsMode) {
            TtsMode.SYSTEM -> {
                // 记录链的终止回调：tts.stop() 后引擎只回调 onStop，
                // onDone/onError 不再触发，链会永久悬挂；stop() 从这里补偿调用
                activeChainOnAllDone = onAllDone
                var index = 0
                fun speakNext() {
                    if (index >= sentences.size) {
                        isInSentenceChain = false
                        activeChainOnAllDone = null
                        onAllDone()
                        return
                    }
                    val sentence = sentences[index]
                    val listener = object : UtteranceProgressListener() {
                        override fun onStart(utteranceId: String?) {}
                        override fun onDone(utteranceId: String?) {
                            onSentenceDone(index)
                            index++
                            speakNext()
                        }
                        override fun onError(utteranceId: String?) {
                            android.util.Log.w("TtsHelper", "TTS error on sentence $index, skipping")
                            index++
                            speakNext()
                        }
                    }
                    tts?.setOnUtteranceProgressListener(listener)
                    tts?.speak(sentence, TextToSpeech.QUEUE_FLUSH, null, "sentence_$index")
                }
                speakNext()
            }
            TtsMode.EMBEDDED -> {
                // Embedded 模式：逐句异步朗读。
                // try/finally 保证被 stop() 取消（CancellationException 直接穿透 for 循环）
                // 时 onAllDone 仍然触发，否则阅读页会永久卡在"朗读中"状态
                scope.launch {
                    try {
                        for ((index, sentence) in sentences.withIndex()) {
                            if (!isInSentenceChain) {
                                // 被 stop() 打断
                                break
                            }
                            embeddedTts.speak(sentence, speed = currentSpeed)
                            withContext(Dispatchers.Main) {
                                onSentenceDone(index)
                            }
                        }
                    } finally {
                        isInSentenceChain = false
                        // scope 在 Dispatchers.Main 上，直接回调即可
                        onAllDone()
                    }
                }
            }
        }
    }

    fun stop() {
        isInSentenceChain = false
        when (ttsMode) {
            TtsMode.SYSTEM -> {
                tts?.stop()
                // tts.stop() 不会触发 onDone/onError（只有 onStop），
                // 句子链回调被孤立；补偿调用当前链的终止回调
                activeChainOnAllDone?.let { cb ->
                    activeChainOnAllDone = null
                    cb()
                }
            }
            TtsMode.EMBEDDED -> {
                // 取消停在 speakMutex 上的过期朗读 + 当前朗读
                embeddedSpeakJob?.cancel()
                embeddedSpeakJob = null
                embeddedTts.stop()
            }
        }
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
    fun isSpeaking(): Boolean = when (ttsMode) {
        TtsMode.SYSTEM -> tts?.isSpeaking == true
        TtsMode.EMBEDDED -> embeddedTts.isPlaying()
    }

    fun shutdown() {
        isInSentenceChain = false
        activeChainOnAllDone = null
        embeddedSpeakJob?.cancel()
        embeddedSpeakJob = null
        try { tts?.stop(); tts?.shutdown() } catch (_: Exception) {}
        tts = null
        isInitialized = false
        initPending = false
        ttsGeneration++
        // 唤醒所有等待初始化的协程（返回 false），否则它们要挂到 15s 超时，
        // 超时后的清理回调还可能误伤新建的实例
        val pending = pendingContinuations.toList()
        pendingContinuations.clear()
        pending.forEach { c ->
            try { c.resume(false) } catch (_: IllegalStateException) {
                // 协程已被取消/已完成，忽略
            }
        }
        try { embeddedTts.stop() } catch (_: Exception) {}
        // P0 修复: cancel 内部协程 scope,避免 shutdown 后仍在飞的协程持有
        // Activity/Context 引用造成内存泄漏(单例生命周期 = 进程生命周期,通常不致命,
        // 但 hot reload / 单元测试 / 进程存活但 TTS 实例重建场景会泄漏 Activity 引用)
        // 注意：先换新 scope 再 cancel 旧的 — scope 是 val 时，shutdown 后
        // 所有后续 launch 都落进已取消的 scope，embedded TTS 会永久静默失效
        val oldScope = scope
        scope = newScope()
        oldScope.cancel()
    }

    /**
     * 暴露 embedded TTS 给上层，用于模型下载管理 UI。
     */
    fun getEmbeddedEngine(): EmbeddedTtsEngine = embeddedTts
}
