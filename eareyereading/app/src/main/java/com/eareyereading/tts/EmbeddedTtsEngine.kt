package com.eareyereading.tts

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.AudioTrack
import android.util.Log
import com.eareyereading.util.NotificationService
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsKokoroModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsVitsModelConfig
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
import kotlinx.coroutines.launch
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
 */
@Singleton
class EmbeddedTtsEngine @Inject constructor(
    @ApplicationContext internal val context: Context,
    private val notificationService: NotificationService,
) {
    @Volatile
    private var tts: OfflineTts? = null
    @Volatile
    private var currentModelName: String = ""
    /** 当前已加载模型是否为 Kokoro（决定 generate 时是否传音色 sid） */
    @Volatile
    private var currentModelIsKokoro: Boolean = false
    @Volatile
    private var sampleRate: Int = 22050

    /**
     * 已完成"首次推理预热"的模型 id（见 [warmUp]）。
     * release()/换模型后置 null——新的 OfflineTts 实例要重新预热；
     * 任何一次真实合成成功也会置位（真实请求本身就完成了预热）。
     */
    @Volatile
    private var warmedUpModelId: String? = null

    /**
     * 短文本（单词）预合成 PCM 缓存：key = "清洗后文本|sid|speed"。
     *
     * 为什么需要：Kokoro 每次 generate 调用有 ~2 秒**固定开销**（与文本长度
     * 无关——真机实测：7 字符单词合成 1.2 秒音频耗时 2.9 秒；80 字符长块
     * 6.5 秒音频耗时 4.2 秒，反推固定成本 ~2s + RTF≈0.34）。固定开销在
     * native 推理层，预热消不掉、每次都付——单词/短句现场合成必然卡。
     * 单词弹窗打开时后台预合成进缓存，点喇叭时零推理延迟直接播
     * （2026-09-05 "读一个单词都卡"修复）。
     *
     * android.util.LruCache 的 get/put 方法级 synchronized，线程安全。
     */
    private val pcmCache = android.util.LruCache<String, FloatArray>(PCM_CACHE_ENTRIES)

    /** 缓存键：与 [doSpeakQueueLocked] 的消费端保持一致（清洗后文本 + 音色 + 语速）。 */
    private fun cacheKey(text: String, sid: Int, speed: Float): String =
        "${text.trim().lowercase()}|$sid|$speed"

    // 是否正在播放
    private val isPlaying = AtomicBoolean(false)

    // speak() 调用串行化锁：
    // sherpa-onnx OfflineTts 的 native 指针不能并发使用，
    // 否则两个协程同时调 generate() 会触发 JNI 段错误 (SIGSEGV)。
    // TtsHelper.speak() 在 scope.launch 里多次调用本方法，必须串行。
    private val speakMutex = Mutex()

    // 当前播放轨道槽：流式播放器建轨时注册，stop() 经此接管释放（见 StreamingTrackPlayer.kt）
    private val trackSlot = AudioTrackSlot()

    // ── 音频焦点：此前完全不申请，朗读会压在音乐/播客上（或被电话打断后不恢复）。
    // 焦点丢失时先发 externalStop 再停引擎（保序），循环播放驱动才能同步收闸
    private val audioManager: AudioManager? by lazy {
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
    private val playbackAudioAttributes: AudioAttributes = AudioAttributes.Builder()
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
    private val audioFocus = TtsAudioFocusController(
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
        private const val NUM_THREADS = 2
        /** Kokoro（82M 参数）合成开销大，官方演示对带 voices 的模型用 4 线程 */
        private const val NUM_THREADS_KOKORO = 4

        /**
         * 单块合成文本最大字符数。
         *
         * 2026-09-05：从 80 提高到 400。Kokoro 不支持 maxNumSentences 并行
         *（native 日志：max_num_sentences != 1 is ignored for Kokoro），
         * 真正的加速是减少 generate 调用次数——每次 generate 有 ~2s 固定开销。
         * 把大块文本一次传给 native，native 端按句点切分逐句回调出声（流式），
         * 首句合成完就回调，不用等整块合成完。400 字符覆盖典型段落，
         * 在 sherpa-onnx 安全范围内（~500 以内稳定）。
         */
        private const val MAX_CHUNK_CHARS = 400

        /**
         * 推理预热文本（见 [warmUp]）：长度必须接近真实首块负载
         * （~90 字符 ≈ 4-6 秒音频）。用 "Ok." 这类短句预热时，ONNX Runtime
         * 的 arena 内存池只长到小句规模，真实首句推理仍要触发大额 arena
         * 扩张与物理页缺页，冷启动成本大部分重现（2026-09-05 真机实测：
         * 短句预热后首句出声仍 ~8s）。长句预热把内存池/页表一次性长到
         * 峰值形状，真实首句直接复用。合成出的音频直接丢弃，
         * 不建 AudioTrack、不申请音频焦点。
         */
        private const val WARMUP_TEXT =
            "The morning sun rises slowly over the quiet hills, and the birds begin to sing in the trees."

        /**
         * 预合成 PCM 缓存条目数（单词场景）：单条约 100-300KB
         * （1-3 秒 24kHz Float PCM），24 条峰值 ~7MB。
         */
        private const val PCM_CACHE_ENTRIES = 24

        /**
         * 预合成仅面向短文本（单词/短语）：超长文本的每次 generate 固定开销
         * 占比小，缓存价值低且浪费内存。
         */
        private const val MAX_PREWARM_CHARS = 40

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
     */
    suspend fun initialize(modelInfo: ModelInfo = getCurrentModelInfo()): Boolean =
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
                val dir = File(context.filesDir, MODELS_DIR_NAME)
                val modelDir = File(dir, modelInfo.id)

                // 通过文件名在已下载文件中查找路径，避免依赖 files 数组下标顺序
                fun findFile(name: String): String? =
                    modelInfo.files.firstOrNull { it.relativePath.endsWith("/$name") }
                        ?.let { File(dir, it.relativePath).absolutePath }

                // 主模型文件名各家不同（model.onnx / en_US-lessac-medium.onnx），
                // 按 .onnx 后缀找而不是写死文件名
                val modelPath = modelInfo.files
                    .firstOrNull { it.relativePath.endsWith(".onnx") }
                    ?.let { File(dir, it.relativePath).absolutePath }
                    ?: throw IllegalStateException("缺少 .onnx 模型文件")
                val tokensPath = findFile("tokens.txt")
                    ?: throw IllegalStateException("缺少 tokens.txt")
                // Piper 不用 lexicon（G2P 走 espeak-ng）；其余模型可选
                val lexiconPath = if (modelInfo.usesEspeakNg) null else findFile("lexicon.txt")
                // MeloTTS 的 jieba 词典目录名为 dict
                val dictDirPath = modelInfo.files
                    .firstOrNull { it.relativePath.endsWith("/dict") }
                    ?.let { File(dir, it.relativePath).absolutePath }
                // Piper 的 dataDir 必须指向 espeak-ng-data 目录（G2P 数据），
                // 其余模型保持模型目录本身（MeloTTS 另用 dict 子目录）
                val dataDirPath = if (modelInfo.usesEspeakNg) {
                    File(modelDir, "espeak-ng-data").absolutePath
                } else {
                    modelDir.absolutePath
                }

                // 配置构造：Kokoro 与 VITS 走不同的 ModelConfig 分支。
                // Kokoro 官方 Android 演示配置（sherpa-onnx v1.13.7）：
                //   model/voices/tokens/dataDir(espeak-ng-data) +
                //   lexicon = "lexicon-us-en.txt,lexicon-zh.txt"（逗号分隔）+
                //   ruleFsts = "phone-zh.fst,date-zh.fst,number-zh.fst"
                //   （中文电话号/日期/数字归一化）。jieba dict/ 由 native 端
                //   按模型目录自动加载，无需显式配置。
                val modelConfig: OfflineTtsModelConfig
                var ruleFsts = ""
                if (modelInfo.isKokoro) {
                    val voicesPath = findFile("voices.bin")
                        ?: throw IllegalStateException("缺少 voices.bin")
                    val lexicons = listOfNotNull(
                        findFile("lexicon-us-en.txt"),
                        findFile("lexicon-zh.txt"),
                    ).joinToString(",")
                    ruleFsts = listOfNotNull(
                        findFile("phone-zh.fst"),
                        findFile("date-zh.fst"),
                        findFile("number-zh.fst"),
                    ).joinToString(",")
                    val kokoroConfig = OfflineTtsKokoroModelConfig(
                        model = modelPath,
                        voices = voicesPath,
                        tokens = tokensPath,
                        dataDir = dataDirPath,
                        lexicon = lexicons,
                    )
                    // 官方 getOfflineTtsConfig 对带 voices 的模型（Kokoro/Kitten）
                    // 推荐 4 线程：82M 参数模型合成开销远大于 Piper
                    modelConfig = OfflineTtsModelConfig(
                        kokoro = kokoroConfig,
                        numThreads = NUM_THREADS_KOKORO,
                    )
                } else {
                    // VITS 模型配置（使用构造参数，避免依赖 var 字段默认值）
                    val vitsConfig = OfflineTtsVitsModelConfig(
                        model = modelPath,
                        tokens = tokensPath,
                        lexicon = lexiconPath ?: "",
                        dataDir = dataDirPath,
                        dictDir = dictDirPath ?: "",
                    )
                    modelConfig = OfflineTtsModelConfig(
                        vits = vitsConfig,
                        numThreads = NUM_THREADS,
                    )
                }
                val config = OfflineTtsConfig(model = modelConfig, ruleFsts = ruleFsts)
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
     * 后台预热：跑一次与真实首块等长的合成并丢弃音频，提前消化
     * ONNX Runtime **首次** generate 的一次性开销（图优化、线程池爬升、
     * arena 内存池扩张与物理页缺页）。
     *
     * **为什么需要**（2026-09-05 真机日志实测，Kokoro int8 / 4 线程）：
     * 首次 generateWithCallback 从入队到攒满 0.8s 预缓冲花了 **10.4 秒**，
     * 而稳态第二块仅 4.2 秒合成 6.5 秒音频（RTF≈0.65）——即首块里约
     * 7-8 秒是纯冷启动开销，全部落在"用户点击朗读后的首声延迟"上。
     * 把这笔开销挪到进书/初始化后的空闲时间，用户点击时引擎已热，
     * 首声延迟从 10s 级降到稳态首块合成时间（约 1-3 秒）。
     */

    /**
     * 预热取消标志：用户点朗读时设 true，warmUp 的 generate 回调返回 0 中止合成，
     * 释放 speakMutex 让用户请求立即开始。warmUp 是优化，绝不能阻塞用户 10 秒。
     */
    @Volatile
    private var warmUpCancelled = false

    /**
     * 后台预热：跑一次与真实首块等长的合成并丢弃音频，提前消化
     * ONNX Runtime **首次** generate 的一次性开销（图优化、线程池爬升、
     * arena 内存池扩张与物理页缺页）。
     *
     * **锁语义**：tryLock 拿不到（正在朗读）直接放弃。拿到锁后开始合成，
     * 但合成期间用户点朗读时，speakViaQueue 会设 [warmUpCancelled] = true，
     * generate 回调返回 0 中止合成、释放锁，用户请求立即开始——
     * 不让预热阻塞用户 10 秒（2026-09-05 真机实测：warmUp 10s 未完成时
     * 用户点朗读，speak 挂锁等 10s 才出声）。
     */
    suspend fun warmUp() = withContext(Dispatchers.IO) {
        val modelId = currentModelName
        if (modelId.isEmpty() || modelId == warmedUpModelId) return@withContext
        if (!speakMutex.tryLock()) return@withContext
        try {
            // 双检：等锁/调度期间引擎可能已被 release() 或换了模型
            val engine = synchronized(this@EmbeddedTtsEngine) { tts } ?: return@withContext
            if (currentModelName != modelId) return@withContext
            val sid = if (currentModelIsKokoro) getSelectedSid() else 0
            val startMs = System.currentTimeMillis()
            warmUpCancelled = false
            try {
                // 与真实朗读同一代码路径（generateWithCallback + sid），
                // 确保 ONNX 会话/内存池/线程池全部被预热。
                // 回调检查 warmUpCancelled：用户点朗读时返回 0 中止合成
                engine.generateWithCallback(WARMUP_TEXT, sid = sid, speed = 1.0f) {
                    if (warmUpCancelled) 0 else 1
                }
                warmedUpModelId = modelId
                Log.i(
                    TAG,
                    "warmUp: model=$modelId done in ${System.currentTimeMillis() - startMs}ms",
                )
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                // 预热失败静默：真实朗读路径有自己的重试/熔断处理，
                // 下次 initialize 后还会再尝试
                Log.w(TAG, "warmUp generate failed (harmless, first real speak will retry)", e)
            }
        } finally {
            speakMutex.unlock()
        }
    }

    /**
     * 预合成一段短文本（≤[MAX_PREWARM_CHARS]，单词/短语）并把 PCM 存入
     * [pcmCache]；朗读路径命中缓存时跳过 generate 直接播（见
     * [doSpeakQueueLocked]），避开 Kokoro 每次 generate ~2s 的固定开销。
     *
     * 典型用法：单词释义弹窗打开时调用——用户看释义的几秒内合成完成，
     * 点喇叭时缓存命中立即出声；若用户在预合成完成前点喇叭，speak 挂在
     * mutex 上等预合成结束，缓存随即命中，总延迟仍严格小于现场合成。
     *
     * 锁语义与 [warmUp] 一致：tryLock 拿不到（正文朗读进行中）直接放弃——
     * 预合成是体验优化，绝不能反过来阻塞用户的正文朗读。
     * 引擎未加载/文本超长/已在缓存：零成本 no-op。
     */
    suspend fun prewarmSynthesis(text: String, speed: Float = 1.0f) = withContext(Dispatchers.IO) {
        val trimmed = text.trim()
        if (trimmed.isEmpty() || trimmed.length > MAX_PREWARM_CHARS) return@withContext
        if (synchronized(this@EmbeddedTtsEngine) { tts } == null) return@withContext
        val isKokoro = currentModelIsKokoro
        val sid = if (isKokoro) getSelectedSid() else 0
        // 与朗读路径同一套清洗：缓存键与合成输入都必须和 doSpeakQueueLocked 对齐
        val cleaned = if (isKokoro) preprocessForTtsLight(trimmed) else preprocessForTts(trimmed)
        if (cleaned.isBlank()) return@withContext
        val key = cacheKey(cleaned, sid, speed)
        if (pcmCache.get(key) != null) return@withContext
        if (!speakMutex.tryLock()) return@withContext
        try {
            // 双检：等锁期间可能已被另一条路径合成并缓存 / 引擎被 release
            val engine = synchronized(this@EmbeddedTtsEngine) { tts } ?: return@withContext
            if (pcmCache.get(key) != null) return@withContext
            val audio = engine.generateWithCallback(cleaned, sid = sid, speed = speed) { _ -> 1 }
            if (audio.samples.isNotEmpty()) {
                pcmCache.put(key, audio.samples)
                // 顺带完成引擎级预热置位（本次 generate 已消化首次推理开销）
                warmedUpModelId = currentModelName
                Log.i(
                    TAG,
                    "prewarmSynthesis cached: '${cleaned.take(30)}', samples=${audio.samples.size}",
                )
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            // 预合成失败静默：点喇叭时走正常合成路径兜底
            Log.w(TAG, "prewarmSynthesis failed for '${trimmed.take(30)}'", e)
        } finally {
            speakMutex.unlock()
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

    private suspend fun doSpeakQueueLocked(
        rawSentences: List<String>,
        speed: Float,
        onSentenceDone: ((Int) -> Unit)?,
    ): Boolean {
        val currentTts = tts ?: run {
            Log.w(TAG, "speak() called but tts not initialized")
            return false
        }
        if (rawSentences.isEmpty()) return true

        val speakJob = kotlin.coroutines.coroutineContext[Job]
        isPlaying.set(true)
        // 流式播放器：整条链共用一条 AudioTrack，边合成边写边播
        val player = StreamingTrackPlayer(
            sampleRate = currentTts.sampleRate(),
            audioManager = audioManager,
            audioAttributes = playbackAudioAttributes,
            trackSlot = trackSlot,
            requestAudioFocus = audioFocus::requestIfNeeded,
        )
        var circuitBroken = false
        try {
            kotlinx.coroutines.coroutineScope {
                // 文本预处理（模型相关）：
                // - Piper：把 OOV 字符替换成可发音等价物（数字→英文单词、CJK→占位符），
                //   否则裸文本进 generate() 会触发 native 段错误 (SIGSEGV)。
                // - Kokoro：双语模型自带中英 G2P（espeak-ng + 中文词典 + ruleFst 数字
                //   归一化），只做空白归一化——Piper 专用的替换反而会破坏中文文本
                val isKokoro = currentModelIsKokoro
                // Kokoro 音色：用户在设置页选择的 sid（Piper 单说话人恒为 0）。
                // 每条链入队时读取一次偏好：朗读中途切音色，下一条链生效
                val sid = if (isKokoro) getSelectedSid() else 0
                Log.i(TAG, "Embedded TTS speak queue: sentences=${rawSentences.size}, sid=$sid, streaming")
                // 句完成水位队列 + 单监视协程：句 i 的全部帧被硬件消费完（水位到达）
                // 才回调 onSentenceDone(i)，既不超前（音频没播完就推进）也不滞后；
                // 单消费者保证回调顺序与句子顺序一致
                val pendingWatermarks = java.util.concurrent.ConcurrentLinkedQueue<Pair<Long, Int>>()
                val generationDone = java.util.concurrent.atomic.AtomicBoolean(false)
                if (onSentenceDone != null) {
                    launch {
                        while (true) {
                            kotlinx.coroutines.delay(20)
                            if (player.isTrackTakenOver()) {
                                // 轨道已被 stop() 接管释放：未播完的句不再回调
                                //（父协程随即被取消，监视协程一并退出）
                                pendingWatermarks.clear()
                                return@launch
                            }
                            val head = player.currentHead()
                            if (head >= 0L) {
                                while (true) {
                                    val next = pendingWatermarks.peek() ?: break
                                    if (head < next.first) break
                                    pendingWatermarks.poll()
                                    onSentenceDone(next.second)
                                }
                            }
                            if (generationDone.get() && pendingWatermarks.isEmpty()) return@launch
                        }
                    }
                }
                // 熔断器：模型损坏时每句都会抛异常，旧实现逐句"跳过"后照常返回成功，
                // 上层会"静音朗读"完整本书并推进进度。连续失败 3 句直接中止并报失败
                var consecutiveFailures = 0
                try {
                    // 把相邻句子合并成大块（≤MAX_CHUNK_CHARS）一次 generate：
                    // Kokoro 每次 generate 有 ~2s 固定开销，逐句串行 N 句 = N×2s。
                    // 合并后大块一次 generate，native 端按句点切分逐句回调出声（流式），
                    // 首句合成完就回调，不用等整块合成完。固定开销从 N 次降到 ceil(N/k) 次。
                    val blocks = mutableListOf<String>()
                    val blockSentenceIndexMap = mutableListOf<Int>()
                    val currentBuf = StringBuilder()
                    var currentLastIdx = -1
                    for ((sIdx, raw) in rawSentences.withIndex()) {
                        if (raw.isBlank()) continue
                        val cleaned = if (isKokoro) preprocessForTtsLight(raw) else preprocessForTts(raw)
                        if (cleaned.isBlank()) continue
                        if (currentBuf.length + cleaned.length + 1 > MAX_CHUNK_CHARS && currentBuf.isNotEmpty()) {
                            blocks.add(currentBuf.toString().trim())
                            blockSentenceIndexMap.add(currentLastIdx)
                            currentBuf.clear()
                            currentLastIdx = -1
                        }
                        if (currentBuf.isNotEmpty()) currentBuf.append(' ')
                        currentBuf.append(cleaned)
                        currentLastIdx = sIdx
                    }
                    if (currentBuf.isNotEmpty()) {
                        blocks.add(currentBuf.toString().trim())
                        blockSentenceIndexMap.add(currentLastIdx)
                    }
                    for ((idx, block) in blocks.withIndex()) {
                        if (block.isBlank()) continue
                        // 每块之前检查协程是否已被取消（stop() 调用）
                        kotlinx.coroutines.yield()
                        if (speakJob?.isActive == false) {
                            throw kotlinx.coroutines.CancellationException("stop() requested")
                        }
                        val framesBeforeBlock = player.framesOffered
                        // 预合成缓存命中（单词弹窗 selectWord 时后台预合成）：
                        // 跳过 generate 直接播缓存 PCM——Kokoro 每次 generate 有
                        // ~2s 固定开销（与文本长度无关），单词现场合成必然卡
                        val cachedPcm = pcmCache.get(cacheKey(block, sid, speed))
                        if (cachedPcm != null) {
                            if (speakJob?.isActive != false) {
                                player.offer(cachedPcm)
                                Log.i(
                                    TAG,
                                    "Embedded TTS block from cache: len=${block.length}, " +
                                        "samples=${cachedPcm.size}",
                                )
                            }
                        } else {
                        val audio = try {
                            currentTts.generateWithCallback(block, sid = sid, speed = speed) { samples ->
                                // 返回 1 继续合成；协程已取消时返回 0 让 native 立即中止
                                if (speakJob?.isActive == false) 0 else player.offer(samples)
                            }
                        } catch (e: kotlinx.coroutines.CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            // 单块 generate 崩溃（如 native G2P bug）：跳过该块，继续
                            Log.e(TAG, "block generate failed, skipping: '${block.take(60)}'", e)
                            null
                        }
                        // 兜底与日志均以 framesOffered（含预缓冲 pending 里的帧）为基准：
                        // 预缓冲期间 framesWritten 恒为 0，若用它判断"一帧未写"会把
                        // 首块音频在兜底路径重复 offer 一遍（声音重叠）
                        // 链已被 stop() 取消时必须禁用兜底：回调被取消检查挡住
                        //（返回 0 中止合成）并不代表"JNI 回调静默失效"，此时整段
                        // 补写会让一条已停止的链在数秒后突然出声——真机表现为
                        // "点了停止，几秒后突然又开始读"，且与用户随后启动的新链
                        // 叠音（2026-09-05 顶栏两播报按钮"冲突"的机理）
                        if (audio != null && player.framesOffered == framesBeforeBlock &&
                            audio.samples.isNotEmpty() && speakJob?.isActive != false
                        ) {
                            // 兜底：JNI 回调静默失效（一帧未写）时整段补写，保证有声
                            player.offer(audio.samples)
                        }
                        }
                        if (player.framesOffered > framesBeforeBlock) {
                            // 真实合成成功 = 本模型的首次推理开销已被消化，
                            // 与 warmUp() 的置位语义一致（幂等，@Volatile 写）
                            warmedUpModelId = currentModelName
                            Log.i(
                                TAG,
                                "Embedded TTS block queued: idx=$idx, len=${block.length}, " +
                                    "samples=${player.framesOffered - framesBeforeBlock}, " +
                                    "totalFrames=${player.framesOffered}",
                            )
                        }
                        // 句完成水位：用 block 边界作为水位。onSentenceDone 回调
                        // block 内最后一个句子的索引（block 可能含多个原句）。
                        // 精确的句级高亮需要 native 回调报告句边界，当前按 block 粒度。
                        if (onSentenceDone != null && player.framesOffered > framesBeforeBlock) {
                            consecutiveFailures = 0
                            // block 对应的原句索引范围：mergeIntoBlocks 返回每个 block
                            // 包含的句子索引，用最后一个索引作为水位回调点
                            val lastSentenceIdxInBlock = blockSentenceIndexMap[idx]
                            if (lastSentenceIdxInBlock >= 0) {
                                pendingWatermarks.add(player.framesOffered to lastSentenceIdxInBlock)
                            }
                        } else if (player.framesOffered == framesBeforeBlock) {
                            consecutiveFailures++
                            if (consecutiveFailures >= 3) {
                                Log.e(
                                    TAG,
                                    "3 consecutive block failures — aborting speak (model likely broken)",
                                )
                                _state.value = EngineState.FAILED("语音合成连续失败，模型可能已损坏")
                                circuitBroken = true
                                return@coroutineScope
                            }
                        }
                    }
                    // 整条链音频总量可能不足预缓冲阈值（如单句短文本）：此时全部帧还在
                    // pending 队列、轨道从未开播——冲刷出去并开播，否则最后一段静音丢失。
                    // 必须在 awaitWatermark 之前：flush 后 pending 帧才计入 framesWritten，
                    // 排水水位才是完整帧数。
                    // 先做协作式取消检查（与循环内每句前的检查同级）：stop() 之后
                    // 不能再建新轨道开播残留音频
                    if (speakJob?.isActive == false) {
                        throw kotlinx.coroutines.CancellationException("stop() requested")
                    }
                    player.flushPendingAndPlay()
                    // 全部生成完毕：等最后水位排空（音频真正播完）本链才算结束。
                    // onSentenceDone 为 null 时监视协程不存在，这里是唯一的排水口
                    player.awaitWatermark(player.framesWritten)
                } finally {
                    generationDone.set(true)
                }
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            // 播放等待的 delay() 会在协程取消时抛出 CancellationException；
            // 不能吞掉，否则 withContext 不会正确传播取消信号。
            isPlaying.set(false)
            player.releaseIfCurrent()
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "speak failed", e)
            isPlaying.set(false)
            player.releaseIfCurrent()
            return false
        }
        isPlaying.set(false)
        player.releaseIfCurrent()
        return !circuitBroken
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
     * 引擎是否已完成首次推理预热（[warmUp] 成功或任一真实合成成功后为 true）。
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