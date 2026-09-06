package com.eareyereading.tts

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.AudioTrack
import android.util.Log
import com.eareyereading.util.NotificationService
import com.k2fsa.sherpa.onnx.OfflineTts
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * 内嵌的离线 TTS 引擎，基于 sherpa-onnx（K2-FSA 项目）。
 *
 * **为什么需要这个**：在某些 MIUI/HyperOS 等深度定制的国产 ROM 上，系统的 TextToSpeech
 * 服务会拒绝让第三方 app bind 到用户安装的 TTS 引擎（例如 Google TTS APK）。这是 OS 层的
 * 权限限制，应用层无法绕过。
 *
 * sherpa-onnx 是一个**完全自包含**的离线 TTS 库，把神经网络模型直接打包进 app，无需
 * 系统 TTS 服务，从根本上绕过了这个限制。
 *
 * **模型选择**（2026-09-04 起双模型，设置页可切换）：
 *   - Piper en_US-lessac-medium（默认，约 66MB）：英文男声，韵律自然，轻量。
 *   - Kokoro int8 中英双语（约 205MB，解压后）：103 种音色（美式/英式女声、
 *     中文女声/男声），情感表现力显著优于 Piper，且原生支持中英混读——
 *     中文书朗读不再被预处理过滤成静音。音色通过 generate(sid) 切换。
 *
 * 模型文件从 CDN 下载到 app 的私有目录（Piper 约 66MB / Kokoro 约 147MB 压缩包）。
 *
 * **职责边界**（拆分见同包扩展文件）：本类只保留生命周期与并发语义——
 * 朗读队列在 [doSpeakQueueLocked]（EmbeddedTtsEngineSpeak.kt）、预热/预合成在
 * EmbeddedTtsEngineWarmup.kt、下载链在 TtsModelDownloader.kt、native 配置构造在
 * TtsEngineConfigFactory、首声埋点在 TtsFirstAudioTrace、分块在 TtsBlockChunker。
 */
@Singleton
class EmbeddedTtsEngine @Inject constructor(
    @ApplicationContext internal val context: Context,
    private val notificationService: NotificationService,
) {
    @Volatile
    internal var tts: OfflineTts? = null

    @Volatile
    private var currentModelName: String = ""

    /** 当前已加载模型是否为 Kokoro（决定 generate 时是否传音色 sid） */
    @Volatile
    internal var currentModelIsKokoro: Boolean = false

    @Volatile
    private var sampleRate: Int = 22050

    /**
     * 已完成"首次推理预热"的模型 id（见 EmbeddedTtsEngineWarmup.kt 的 warmUp）。
     * release()/换模型后置 null——新的 OfflineTts 实例要重新预热；
     * 任何一次真实合成成功也会置位（真实请求本身就完成了预热）。
     */
    @Volatile
    internal var warmedUpModelId: String? = null

    /**
     * 预热取消标志：用户点朗读时设 true，warmUp 的 generate 回调返回 0 中止合成，
     * 释放 speakMutex 让用户请求立即开始（见 EmbeddedTtsEngineWarmup.kt）。
     */
    @Volatile
    internal var warmUpCancelled = false

    /** 首声四段埋点（click→firstOffer→trackPlayed→headMoved） */
    internal val firstAudioTrace = TtsFirstAudioTrace()

    /** 短文本（单词）预合成 PCM 缓存（见 EmbeddedTtsEngineWarmup.kt） */
    internal val pcmCache = TtsPcmCache()

    // 是否正在播放
    internal val isPlaying = AtomicBoolean(false)

    // speak() 调用串行化锁：
    // sherpa-onnx OfflineTts 的 native 指针不能并发使用，
    // 否则两个协程同时调 generate() 会触发 JNI 段错误 (SIGSEGV)。
    // TtsHelper.speak() 在 scope.launch 里多次调用本方法，必须串行。
    internal val speakMutex = Mutex()

    // 当前播放轨道槽：流式播放器建轨时注册，stop() 经此接管释放（见 StreamingTrackPlayer.kt）
    internal val trackSlot = AudioTrackSlot()

    // ── 音频焦点：此前完全不申请，朗读会压在音乐/播客上（或被电话打断后不恢复）。
    // 焦点丢失时先发 externalStop 再停引擎（保序），循环播放驱动才能同步收闸
    internal val audioManager: AudioManager? by lazy {
        context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
    }

    /**
     * 播放轨道与焦点请求共用的音频属性（两处必须严格一致）。
     *
     * CONTENT_TYPE_MUSIC 而非 SPEECH（2026-09-05 真机诊断定案）：MIUI/HyperOS
     * 对 SPEECH 内容类型走语音通道特殊策略（与小爱同学/语音识别通道互斥），
     * 实测 USAGE_MEDIA+CONTENT_TYPE_SPEECH 组合下 AudioTrack 写入成功、
     * start 成功、状态 PLAYING，但 mixer 恒不消费（playbackHeadPosition=0），
     * 扬声器完全无声；pcmPeak/musicVol 诊断排除数据与音量因素后锁定于此。
     */
    internal val playbackAudioAttributes: AudioAttributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_MEDIA)
        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
        .build()

    /**
     * 外部停止信号：音频焦点丢失等系统事件触发。
     * 引擎的 stop() 只能取消"正在出声的那一句"，循环播放是由上层
     * （ReaderViewModel 的 autoRead/speed/rsvp Job）驱动的——它们以
     * uiState 播放标志为闸，焦点丢失后不收闸就会播下一段。
     * UI 层 collect 此流后应调用 stopAllPlayback() 收闸。
     */
    private val _externalStop = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val externalStop: SharedFlow<Unit> = _externalStop.asSharedFlow()

    /** 焦点控制器：请求/归还集中在 TtsAudioFocusController，丢失回调先发信号再停引擎 */
    internal val audioFocus = TtsAudioFocusController(
        audioManager = audioManager,
        audioAttributes = playbackAudioAttributes,
        onFocusLost = {
            _externalStop.tryEmit(Unit)
            stop()
        },
    )

    /**
     * 主动归还音频焦点（供调用方在一次朗读会话结束时调用）。
     * 句子链/单段朗读自然播完不会走 stop()，若不归还，
     * 被 duck 的背景音乐/播客会一直保持压低状态直到进程结束。
     * 幂等：stop()/release() 已归还过时重复调用无副作用
     */
    fun abandonAudioFocus() {
        audioFocus.abandonIfHeld()
    }

    /**
     * 在 UI 点击朗读的瞬间调用，标记本条朗读链首声计时的起点（委托
     * [TtsFirstAudioTrace.begin]）。必须在 speakViaQueue 拿锁之前调用——
     * 首声延迟含等锁时间。幂等重置：连点会重置起点。
     */
    fun beginFirstAudioTrace() {
        firstAudioTrace.begin()
    }

    // 当前正在跑的 speak() 协程的 Job 集合。stop() 全部取消，连带释放 mutex。

    // 单值字段不够：speak A 持锁播放、speak B 挂在锁上等待时，
    // stop() 只会取消后注册的 B，A 的句循环跨过下一句继续出声——停止看似无效
    private val speakJobLock = Any()
    private val activeSpeakJobs = mutableSetOf<Job>()

    // 引擎状态（用于 UI 显示；下载链扩展写 DOWNLOADING/FAILED 等中间态）
    internal val _state = MutableStateFlow<EngineState>(EngineState.NOT_INITIALIZED)
    val state: StateFlow<EngineState> = _state.asStateFlow()

    /**
     * 当前下载 / 解压 / 初始化 阶段。
     * UI 根据 type 显示不同文案（"下载中 65%" / "解压中 (2/3) tokens.txt" / "正在初始化…"），
     * 用 sealed class 而不是 Float 让"是否在解压"对用户透明——避免他们看到进度条停滞
     * 在 95% 误以为卡死。
     */
    sealed class Progress {
        object Idle : Progress()
        data class Downloading(val bytesSoFar: Long, val totalBytes: Long) : Progress() {
            val fraction: Float
                get() = if (totalBytes > 0) (bytesSoFar.toFloat() / totalBytes.toFloat()).coerceIn(0f, 1f) else 0f
        }
        data class Extracting(
            val bytesDone: Long,
            val bytesTotal: Long,
            val currentEntryName: String?,
            // 解压已耗时（ms），供 UI 计算 ETA
            val elapsedMs: Long = 0L,
        ) : Progress() {
            val fraction: Float
                get() = if (bytesTotal > 0) (bytesDone.toFloat() / bytesTotal.toFloat()).coerceIn(0f, 1f) else 0f
        }
        object Initializing : Progress()
        object Completed : Progress()
        data class Failed(val reason: String) : Progress()
    }

    internal val _downloadProgress = MutableStateFlow<Progress>(Progress.Idle)
    val downloadProgress: StateFlow<Progress> = _downloadProgress.asStateFlow()

    /**
     * 引擎状态机
     */
    sealed class EngineState {
        data object NOT_INITIALIZED : EngineState()        // 未初始化
        data object MODEL_NOT_FOUND : EngineState()         // 模型文件不存在，需要下载
        data object DOWNLOADING : EngineState()             // 正在下载模型
        data class DOWNLOAD_FAILED(val reason: String) : EngineState()
        data object INITIALIZING : EngineState()            // 正在加载模型
        data class READY(val modelName: String) : EngineState()  // 已就绪
        data class FAILED(val reason: String) : EngineState()
    }

    companion object {
        private const val TAG = "EmbeddedTtsEngine"

        /** 用户当前选中的模型 ID（用 SharedPreferences 持久化） */
        private const val PREFS_NAME = "embedded_tts_prefs"
        private const val KEY_SELECTED_MODEL = "selected_model"

        /** 用户选中的音色 sid，按模型分别持久化（Piper 无多音色，仅 Kokoro 使用） */
        private const val KEY_SELECTED_VOICE_PREFIX = "selected_voice_"
    }

    private val prefs by lazy {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    fun getSelectedModelId(): String {
        return prefs.getString(KEY_SELECTED_MODEL, DEFAULT_MODEL_ID) ?: DEFAULT_MODEL_ID
    }

    fun setSelectedModelId(id: String) {
        prefs.edit().putString(KEY_SELECTED_MODEL, id).apply()
    }

    /** 当前模型下用户选中的音色 sid（仅 Kokoro 有意义；越界/未设置回退 0） */
    fun getSelectedSid(model: ModelInfo = getCurrentModelInfo()): Int {
        val saved = prefs.getInt(KEY_SELECTED_VOICE_PREFIX + model.id, 0)
        return if (saved in 0 until KOKORO_VOICES.size) saved else 0
    }

    fun setSelectedSid(modelId: String, sid: Int) {
        prefs.edit().putInt(KEY_SELECTED_VOICE_PREFIX + modelId, sid).apply()
    }

    /** 当前选中模型的音色信息（非 Kokoro 模型返回 null） */
    fun getSelectedVoice(): VoiceInfo? {
        val model = getCurrentModelInfo()
        return if (model.isKokoro) KOKORO_VOICES.getOrNull(getSelectedSid(model)) else null
    }

    /**
     * 引擎当前加载的是否为 Kokoro 模型（音色试听前的检查）：
     * READY 但加载的是 Piper 时（用户已选中未下载的 Kokoro），试听会
     * 落在英文声上——调用方应先 initialize(selected) 换引擎再试听。
     */
    val isKokoroActive: Boolean
        get() = currentModelIsKokoro && tts != null

    fun getCurrentModelInfo(): ModelInfo {
        // firstOrNull 全程兜底：持久化的模型 id 可能已被新版本移除，
        // first{} 会直接抛 NoSuchElementException（且本方法会在 Compose 组合期被调用）
        //
        // 优先级必须是"用户选择 > 已加载模型"：切换模型时 setSelectedModelId 先落盘、
        // currentModelName 还是旧模型——若旧模型优先，setEmbeddedModel 拿到的仍是旧
        // ModelInfo（initialize 快路径直接复用旧实例），设置页单选还会被
        // refreshEmbeddedStatus 翻回旧模型——引擎永远切不过去（2026-09-05 修复）
        return AVAILABLE_MODELS.firstOrNull { it.id == getSelectedModelId() }
            ?: AVAILABLE_MODELS.firstOrNull { it.id == currentModelName }
            ?: AVAILABLE_MODELS.first()
    }

    /**
     * 按书籍语言解析理想模型（不保证已下载）。
     *
     * 双模型时代（2026-09-04 起）：用户在设置页显式选择的模型优先——
     * 英文主线默认 Piper，中文书/多音色需求由用户切到 Kokoro。
     * `language` 参数保留兼容旧调用方，不再参与路由（用户意图 > 语言启发式）。
     */
    fun resolveModelForLanguage(@Suppress("UNUSED_PARAMETER") language: String?): ModelInfo {
        return getCurrentModelInfo()
    }

    /**
     * 初始化时实际可加载的模型：用户选中的模型已下载则用它；否则返回 null
     * 由调用方引导用户下载。language 参数保留（兼容旧调用方），不用于路由。
     */
    fun modelForInitialize(@Suppress("UNUSED_PARAMETER") language: String?): ModelInfo? {
        val ideal = getCurrentModelInfo()
        return if (isModelDownloaded(ideal)) ideal else null
    }

    /**
     * 检查模型是否已下载（且每个文件有 .complete 标记，确保完整）。
     */
    fun isModelDownloaded(modelInfo: ModelInfo = getCurrentModelInfo()): Boolean {
        val dir = File(context.filesDir, MODELS_DIR_NAME)
        return modelInfo.files.all { file ->
            val f = File(dir, file.relativePath)
            val contentOk = if (f.isDirectory) f.exists() else f.exists() && f.length() > 0
            contentOk && File(dir, file.relativePath + COMPLETE_SUFFIX).exists()
        }
    }

    /**
     * 获取已下载的模型占用空间（字节）。
     */
    fun getDownloadedSize(): Long {
        val dir = File(context.filesDir, MODELS_DIR_NAME)
        if (!dir.exists()) return 0L
        return dir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
    }

    /**
     * 删除已下载的模型（释放空间）。
     */
    fun deleteModel(modelInfo: ModelInfo = getCurrentModelInfo()) {
        val dir = File(context.filesDir, MODELS_DIR_NAME)
        modelInfo.files.forEach { file ->
            // deleteRecursively：Piper 的 espeak-ng-data 是目录，
            // File.delete() 对非空目录静默失败会留下 ~5MB 残留
            File(dir, file.relativePath).let { if (it.exists()) it.deleteRecursively() }
            File(dir, file.relativePath + COMPLETE_SUFFIX).let { if (it.exists()) it.delete() }
        }
        // 状态流同步复位：此前删完模型流里仍是 READY(旧模型)，
        // 设置页状态与实际不符
        if (_state.value is EngineState.READY || _state.value is EngineState.FAILED) {
            _state.value = EngineState.MODEL_NOT_FOUND
        }
        // 进度流也复位：删除后 Completed 残留会让 collect 保持
        // embeddedModelDownloaded=true（downloadedOverride），与磁盘实际不符
        _downloadProgress.value = Progress.Idle
    }

    /**
     * 当前下载失败原因（剥离展示前缀的裸原因），供 UI 展示层组合文案。
     * 引擎内部逐文件失败原因历史上带"下载失败："前缀，统一在这里剥离——
     * 展示层各自 cast + removePrefix 的隐式约定已被两个调用点复制两遍，
     * 第三个调用点漏一半就会复活"下载失败：下载失败：…"双重前缀
     */
    fun downloadFailureReasonOrNull(): String? =
        (_state.value as? EngineState.DOWNLOAD_FAILED)?.reason?.removePrefix("下载失败：")

    /** 下载互斥：设置页与阅读页弹窗是两个独立入口，各自的 UI 守卫挡不住跨入口并发。
     * 两个下载协程交错写同一批文件/解压同一个 tarball 会产出损坏模型 */
    internal val downloadMutex = Mutex()

    /**
     * 重置残留的下载进度状态。
     *
     * 场景：上次下载协程因 ViewModel 销毁被取消，但 `_downloadProgress` 停在
     * Downloading/Extracting/Initializing 中间态（CancellationException 路径
     * 在 downloadMutex.withLock 内，写 Idle 后才向上传播，但若取消发生在
     * withLock 等待期间则不写）。新 ViewModel 的 collect 立即收到残留中间态，
     * isInProgress=true → UI 卡在"正在下载..."，下载按钮不可点。
     *
     * 仅在 downloadMutex 空闲时重置：若下载真的在进行，不破坏进度。
     * 调用方应在 ViewModel init 时调用。
     */
    fun resetStaleDownloadProgress() {
        if (downloadMutex.isLocked) return
        val current = _downloadProgress.value
        if (current is Progress.Downloading ||
            current is Progress.Extracting ||
            current is Progress.Initializing
        ) {
            Log.w(TAG, "resetStaleDownloadProgress: clearing stale $current")
            _downloadProgress.value = Progress.Idle
        }
    }

    /**
     * 下载模型文件（带进度回调、多镜像回退、断点续传）。
     *
     * @param modelInfo 要下载的模型
     * @param onProgress 0.0 - 1.0 的进度回调
     */
    suspend fun downloadModel(
        modelInfo: ModelInfo = getCurrentModelInfo(),
        onProgress: (Float) -> Unit = {},
    ): Boolean = downloadMutex.withLock { downloadModelLocked(modelInfo, onProgress) }

    /**
     * 初始化 OfflineTts 实例（同步方法，调用前确保模型已下载）。
     *
     * 并发语义（本方法保留的核心）：native 实例的构造在锁外、替换进锁；
     * 状态写入与 release() 同锁，避免 READY∧tts=null 的说谎状态。
     */
    suspend fun initialize(
        modelInfo: ModelInfo = getCurrentModelInfo(),
        /**
         * 书籍语言（"en"/"zh"/null）。Kokoro 按此决定 G2P 配置：
         * 英文时省略中文 lexicon/ruleFST/jieba，G2P 从 ~8s 降到 <1s
         *（2026-09-06 实测：中文资源是 G2P 68% 耗时的根因）。
         * null = 全配（向后兼容，中英混读场景）。
         */
        language: String? = null,
    ): Boolean =
        withContext(Dispatchers.IO) {
            // 快路径也进锁：与 release()/deleteModel 竞态时，可能在 tts 被置空的
            // 同时返回 true，之后每次 speak 静默失败
            val sameModelLoaded = speakMutex.withLock {
                tts != null && currentModelName == modelInfo.id
            }
            if (sameModelLoaded) {
                // 已加载同模型：把状态流也摆正（此前可能停留在
                // FAILED/DOWNLOAD_FAILED，与布尔返回值互相矛盾）
                _state.value = EngineState.READY(modelInfo.id)
                // 若正处于下载→初始化流程中（progress=Initializing），推进到 Completed
                // 让 UI 收到 100% "已启用"；否则不碰 progress（避免干扰独立 initialize 调用）
                if (_downloadProgress.value is Progress.Initializing) {
                    _downloadProgress.value = Progress.Completed
                }
                return@withContext true
            }
            _state.value = EngineState.INITIALIZING
            try {
                if (!isModelDownloaded(modelInfo)) {
                    _state.value = EngineState.MODEL_NOT_FOUND
                    // 下载→初始化流程中模型文件缺失（解压后校验失败等）：
                    // 推进到 Failed 让 UI 退出"初始化中"，否则 UI 卡在 99%
                    if (_downloadProgress.value is Progress.Initializing) {
                        _downloadProgress.value = Progress.Failed("模型文件缺失")
                    }
                    return@withContext false
                }
                val modelsRoot = File(context.filesDir, MODELS_DIR_NAME)
                val config = TtsEngineConfigFactory.create(modelInfo, modelsRoot)
                val newTts = OfflineTts(config = config)
                // 关键：替换/释放旧 native 实例必须与 generate() 互斥。
                // 只加 synchronized(this) 时，另一个协程可能正持有 speakMutex
                // 在 generate() 里使用旧实例 → release() 直接 JNI use-after-free
                // （正是注释里说的 SIGSEGV 类别）。构造在锁外完成，仅替换进锁。
                var assigned = false
                try {
                    speakMutex.withLock {
                        // 注意锁对象必须是引擎实例本身：withContext 的 lambda 里裸 this
                        // 是 CoroutineScope，与 release() 的 synchronized(this)（成员
                        // 函数内 = 引擎实例）不是同一把锁，互斥会失效
                        synchronized(this@EmbeddedTtsEngine) {
                            // 锁内双检：快路径检查后两个协程可能同时在锁外构造
                            // OfflineTts（各 ~66MB native 内存）。后进锁者若发现
                            // 同模型已被抢先加载，直接复用——否则会把刚加载好的
                            // 实例 release 掉再换自己的（双份峰值 + 白加载一次）
                            if (tts != null && currentModelName == modelInfo.id) {
                                Log.i(TAG, "initialize: model=${modelInfo.id} already loaded by concurrent call, reuse")
                            } else {
                                // 替换前 shutdown 旧的
                                tts?.let { try { it.release() } catch (_: Exception) {} }
                                tts = newTts
                                assigned = true
                                currentModelName = modelInfo.id
                                currentModelIsKokoro = modelInfo.isKokoro
                                sampleRate = newTts.sampleRate()
                            }
                            // 状态写入也进锁：出锁再写会与 release()（同锁内置
                            // tts=null + NOT_INITIALIZED）交错出 READY∧tts=null 的
                            // 说谎状态——之后所有 speak 静默失败而 UI 显示就绪
                            _state.value = EngineState.READY(modelInfo.id)
                        }
                    }
                } finally {
                    // 构造成功但从未赋值（等锁时被取消/异常/被并发抢先）：显式释放，
                    // 上百 MB 的 native 模型不该只等 GC finalizer
                    if (!assigned) {
                        try { newTts.release() } catch (_: Exception) {}
                    }
                }
                Log.i(TAG, "Initialized sherpa-onnx OfflineTts: model=${modelInfo.id}, sampleRate=$sampleRate")
                // 下载→初始化流程：推进到 Completed 让 UI 收到 100% "已启用"
                if (_downloadProgress.value is Progress.Initializing) {
                    _downloadProgress.value = Progress.Completed
                }
                true
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "initialize failed", e)
                // 新实例构造失败但旧引擎还在时，状态回到"旧模型就绪"，
                // 而不是 FAILED（引擎实际仍可用，UI 显示"未就绪"会说谎）
                val fallback = if (tts != null) currentModelName else null
                _state.value = if (fallback != null) {
                    EngineState.READY(fallback)
                } else {
                    EngineState.FAILED(e.message ?: "初始化失败")
                }
                // 下载→初始化流程中失败：推进到 Failed 让 UI 退出"初始化中"
                if (_downloadProgress.value is Progress.Initializing) {
                    _downloadProgress.value = Progress.Failed(e.message ?: "初始化失败")
                }
                false
            }
        }

    /**
     * 朗读一段文字（阻塞至音频播放完毕，由调用方在协程中调用）。
     *
     * 流式播放：文本按句入队，sherpa-onnx 每合成完一小段（内部按句）就回调，
     * 采样边合成边写入 MODE_STREAM 的 AudioTrack 立即出声——Kokoro 这类大模型
     * "整段合成完才开始播"的首句静默等待（手机 CPU 上可达十几秒）被压缩到
     * 首小段的合成时间。
     *
     * @param text 要朗读的文本
     * @param speed 语速倍率，1.0 = 正常
     * @param onDone 完成回调（音频播完时触发；失败/取消不触发）
     * @return true 表示播放完成
     */
    suspend fun speak(
        text: String,
        speed: Float = 1.0f,
        onDone: () -> Unit = {},
    ): Boolean {
        val ok = speakViaQueue(splitSentences(text), speed, onSentenceDone = null)
        if (ok) onDone()
        return ok
    }

    /**
     * 句链流式朗读（TtsHelper.speakSentences 的底层）。
     *
     * 整条链共用一条 MODE_STREAM AudioTrack：句 i 的音频还在播放时句 i+1 已在
     * 合成并按序排队写入——消除旧实现（每句单独 speak，合成期间完全静默）在
     * Kokoro 这类大模型下句句之间"整句合成时长"的 gap。
     *
     * @param sentences 原始句子列表（引擎内部按当前模型做预处理再切分）
     * @param speed 语速倍率，1.0 = 正常
     * @param onSentenceDone 第 i 句音频播完时回调（IO 线程触发，单生产者保序；
     *        调用方自行切回主线程）
     * @return true 表示全部播完
     */
    suspend fun speakSentencesStreaming(
        sentences: List<String>,
        speed: Float = 1.0f,
        onSentenceDone: (Int) -> Unit,
    ): Boolean = speakViaQueue(sentences, speed, onSentenceDone)

    /**
     * speak / speakSentencesStreaming 的公共入口：注册 Job（stop() 全量取消，
     * 含正在播的与挂在锁上等锁的）+ speakMutex 串行化——native OfflineTts
     * 指针不能并发使用，两个协程同时 generate() 会触发 JNI 段错误 SIGSEGV。
     */
    private suspend fun speakViaQueue(
        rawSentences: List<String>,
        speed: Float,
        onSentenceDone: ((Int) -> Unit)?,
    ): Boolean = withContext(Dispatchers.IO) {
        // issue 2.11：入口即检查取消。TtsHelper.speak 先 cancel 旧 job 再 launch
        // 新 job——cancel() 非阻塞，旧协程可能还挂在 speakMutex 上等锁，新协程
        // 已在队列里；入口 ensureActive 让被取消的旧协程在抢锁前就放弃，避免
        // 两个 speak 协程几乎同时进入 doSpeakQueueLocked 造成音频叠播/错序。
        coroutineContext[Job]?.ensureActive()
        // 用户点朗读时取消正在进行的 warmUp：warmUp 持有 speakMutex 合成 ~10s，
        // 不取消的话 speak 挂锁等 10s 才出声。设标志让 warmUp 的 generate 回调
        // 返回 0 中止合成、释放锁，用户请求立即开始
        warmUpCancelled = true
        val myJob = coroutineContext[Job]
        synchronized(speakJobLock) {
            myJob?.let { activeSpeakJobs.add(it) }
        }
        try {
            speakMutex.withLock {
                doSpeakQueueLocked(rawSentences, speed, onSentenceDone)
            }
        } finally {
            synchronized(speakJobLock) {
                myJob?.let { activeSpeakJobs.remove(it) }
            }
        }
    }

    /**
     * 停止当前播放。
     */
    fun stop() {
        // 取消全部 speak 协程：让 doSpeakQueueLocked 立刻退出（协程取消时
        // kotlinx coroutines Mutex.withLock 会在 finally 释放锁）。
        // 之前只停 AudioTrack 会导致旧 speak 继续在 mutex 里跑完整段，
        // 用户的"停止"按钮实际无效——新的 speak 必须等旧协程跑完才能进。
        // 必须取消"所有"调用者：正在出声的与挂在锁上等待的，
        // 只取消一个时另一个会跨过下一句继续播
        synchronized(speakJobLock) {
            activeSpeakJobs.forEach { it.cancel() }
            activeSpeakJobs.clear()
        }
        synchronized(trackSlot.lock) {
            try {
                trackSlot.track?.let {
                    if (it.state == AudioTrack.STATE_INITIALIZED) {
                        it.pause()
                        it.flush()
                    }
                    it.release()
                }
            } catch (_: Exception) {}
            trackSlot.track = null
        }
        isPlaying.set(false)
        // 停止即归还音频焦点，让被压低的音乐/播客恢复
        audioFocus.abandonIfHeld()
    }

    /**
     * 释放所有资源。
     *
     * suspend：native 实例的释放必须与 generate() 互斥（与 initialize() 同理）——
     * stop() 只是协作式取消，正在 JNI 里的 generate() 无法被打断；
     * 不持 speakMutex 就 release 会释放仍在被使用的指针（use-after-free）
     */
    suspend fun release() {
        stop()
        speakMutex.withLock {
            synchronized(this) {
                tts?.let { try { it.release() } catch (_: Exception) {} }
                tts = null
                currentModelIsKokoro = false
                // native 实例已销毁，下次 initialize 分配的新实例需重新预热
                warmedUpModelId = null
            }
        }
        // 状态流复位：旧实现释放后流里仍是 READY，设置页状态说谎
        _state.value = EngineState.NOT_INITIALIZED
        audioFocus.abandonIfHeld()
    }

    /**
     * 是否正在播放。
     */
    fun isPlaying(): Boolean = isPlaying.get()

    /**
     * 引擎是否已完成首次推理预热（warmUp 成功或任一真实合成成功后为 true）。
     * UI 用于在"引擎未热"的等待窗口给用户即时反馈：speak 挂锁等启动预热
     * 完成的数秒内无声是预期行为，无提示时用户会误判"没声音/卡死"
     * （2026-09-05 实测：点喇叭后 8 秒无声，实为 warmUp 收尾期排队）。
     */
    fun isWarmedUp(): Boolean = warmedUpModelId != null && warmedUpModelId == currentModelName

    // ── 下载通知（委托 NotificationService 集中管理：进度 ongoing、完成可划掉）──

    /** 显示/更新下载进度通知。progress 0..1，null 表示不确定。 */
    fun showDownloadNotification(progress: Float?, contentText: String) {
        notificationService.showTtsDownloadProgress(progress, contentText)
    }

    /** 下载成功后的收尾通知：替换掉 ongoing 的进度通知，保证可划掉并结束常驻状态。 */
    internal fun showDownloadCompleteNotification(contentText: String) {
        notificationService.showTtsDownloadComplete(contentText)
    }

    /** 取消下载通知。 */
    fun cancelDownloadNotification() {
        notificationService.cancelTtsDownloadNotification()
    }
}
