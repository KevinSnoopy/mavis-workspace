package com.eareyereading.tts

import android.content.Context
import android.media.AudioTrack
import android.util.Log
import com.eareyereading.util.NotificationService
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

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
 * ── 重构说明（13 条软件设计原则）──
 * 本类原本 1215 行，混合了状态管理、偏好持久化、文件管理、埋点、缓存、
 * 合成播放、JNI 配置构造、音频基础设施、Job 生命周期等多个职责，违反 SRP。
 * 第一轮按职责拆分到同包独立文件：
 *   - [EmbeddedTtsStateHolder]：EngineState / Progress sealed class 与状态流持有
 *   - [EmbeddedTtsPrefs]：模型选择与音色偏好的 SharedPreferences 持久化
 *   - [EmbeddedTtsModelFiles]：模型文件的磁盘存在性/空间/删除
 *   - [FirstAudioTrace]：首声四段埋点字段与日志
 *   - [PcmCache]：短文本预合成 PCM 的 LRU 缓存
 *   - [TtsBlockMerger]：句子合并成 generate 块的纯逻辑
 *   - [TtsNativeEngine]：sherpa-onnx native 实例指针与模型元数据持有
 *   - [TtsModelConfigBuilder]：sherpa-onnx OfflineTts 配置构造（VITS/Kokoro 分支）
 *   - [SpeakJobRegistry]：活跃 speak 协程注册与全量取消
 *   - [TtsAudioInfrastructure]：AudioManager / AudioAttributes / 轨道槽 / 外部停止信号
 *   - [TtsModelDownloader]（同包扩展函数）：下载与解压链
 *
 * 第二轮继续下沉两个仍然过大的职责块（本类 874 行 → 约 380 行）：
 *   - `TtsSpeakOrchestrator.kt`：朗读执行链（预处理→分块→合成/缓存→写播放器→
 *     句完成水位→熔断），即 [runSpeakQueue] / [executeSpeakQueueLocked]
 *   - `EmbeddedTtsEngineLifecycle.kt`：初始化、预热、预合成缓存三条 native 生命周期路径
 *
 * 本类现在只保留**对外门面**：字段持有、委托方法、状态机定义与生命周期收尾
 * （stop/release）。所有对外 API 的签名与业务行为完全不变。
 */
@Singleton
class EmbeddedTtsEngine @Inject constructor(
    @ApplicationContext internal val context: Context,
    private val notificationService: NotificationService,
) {
    // ── 拆分出的职责持有者（SRP）──
    internal val stateHolder = EmbeddedTtsStateHolder()
    private val prefs = EmbeddedTtsPrefs(context)
    internal val modelFiles = EmbeddedTtsModelFiles(context)
    internal val firstAudioTrace = FirstAudioTrace()
    internal val pcmCache = PcmCache()
    internal val nativeEngine = TtsNativeEngine()
    internal val configBuilder = TtsModelConfigBuilder(modelFiles)
    internal val audioInfra = TtsAudioInfrastructure(context)
    internal val speakJobRegistry = SpeakJobRegistry()

    /** 在 UI 点击朗读的瞬间调用，标记本条朗读链首声计时的起点。 */
    fun beginFirstAudioTrace() = firstAudioTrace.begin()

    // 是否正在播放
    internal val isPlaying = AtomicBoolean(false)

    // speak() 调用串行化锁：
    // sherpa-onnx OfflineTts 的 native 指针不能并发使用，
    // 否则两个协程同时调 generate() 会触发 JNI 段错误 (SIGSEGV)。
    // TtsHelper.speak() 在 scope.launch 里多次调用本方法，必须串行。
    internal val speakMutex = Mutex()

    /** 焦点控制器：请求/归还集中在 TtsAudioFocusController，丢失回调先发信号再停引擎 */
    internal val audioFocus = TtsAudioFocusController(
        audioManager = audioInfra.audioManager,
        audioAttributes = audioInfra.playbackAudioAttributes,
        onFocusLost = {
            audioInfra.emitExternalStop()
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

    // ── 状态流暴露（委托给 stateHolder，保留原 API 签名以兼容外部引用）──
    val state: kotlinx.coroutines.flow.StateFlow<EngineState> get() = stateHolder.state
    val downloadProgress: kotlinx.coroutines.flow.StateFlow<Progress> get() = stateHolder.downloadProgress
    internal val _state get() = stateHolder._state
    internal val _downloadProgress get() = stateHolder._downloadProgress

    /** 外部停止信号（委托给 audioInfra，保留原 API 签名以兼容外部引用） */
    val externalStop: SharedFlow<Unit> get() = audioInfra.externalStop

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

    /**
     * 当前下载 / 解压 / 初始化 阶段。
     * UI 根据 type 显示不同文案（"下载中 65%" / "解压中 (2/3) tokens.txt" / "正在初始化…"），
     * 用 sealed class 而不是 Float 让"是否在解压"对用户透明——避免他们看到进度条停滞
     * 在 95% 误以为卡死。
     */
    sealed class Progress {
        data object Idle : Progress()
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
        data object Initializing : Progress()
        data object Completed : Progress()
        data class Failed(val reason: String) : Progress()
    }

    /**
     * 当前选中模型是否为 Kokoro（音色试听前的检查）：
     * READY 但加载的是 Piper 时（用户已选中未下载的 Kokoro），试听会
     * 落在英文声上——调用方应先 initialize(selected) 换引擎再试听。
     */
    val isKokoroActive: Boolean
        get() = nativeEngine.currentModelIsKokoro && nativeEngine.isLoaded()

    // ── 偏好读写（委托给 prefs）──
    fun getSelectedModelId(): String = prefs.getSelectedModelId()
    fun setSelectedModelId(id: String) = prefs.setSelectedModelId(id)
    fun getSelectedSid(model: ModelInfo = getCurrentModelInfo()): Int = prefs.getSelectedSid(model)
    fun setSelectedSid(modelId: String, sid: Int) = prefs.setSelectedSid(modelId, sid)
    fun getSelectedVoice(): VoiceInfo? = prefs.getSelectedVoice()

    fun getCurrentModelInfo(): ModelInfo = prefs.getCurrentModelInfo()

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

    // ── 模型文件管理（委托给 modelFiles）──
    fun isModelDownloaded(modelInfo: ModelInfo = getCurrentModelInfo()): Boolean =
        modelFiles.isModelDownloaded(modelInfo)

    fun getDownloadedSize(): Long = modelFiles.getDownloadedSize()

    fun deleteModel(modelInfo: ModelInfo = getCurrentModelInfo()) {
        modelFiles.deleteModel(modelInfo)
        // 状态流同步复位：此前删完模型流里仍是 READY(旧模型)，
        // 设置页状态与实际不符
        if (_state.value is EngineState.READY || _state.value is EngineState.FAILED) {
            _state.value = EngineState.MODEL_NOT_FOUND
        }
        // 进度流也复位：删除后 Completed 招留会让 collect 保持
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
     * 实现见 `TtsModelDownloader.kt` 的 [downloadModelLocked]。
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
     * 实现见 `EmbeddedTtsEngineLifecycle.kt` 的 [initializeEngine]。
     *
     * @param language 书籍语言（"en"/"zh"/null），Kokoro 据此裁剪中文 G2P 资源
     *   以获得更快的首声（详见实现处的说明）。
     */
    suspend fun initialize(
        modelInfo: ModelInfo = getCurrentModelInfo(),
        language: String? = null,
    ): Boolean = initializeEngine(modelInfo, language)

    /**
     * 预热取消标志：用户点朗读时设 true，warmUp 的 generate 回调返回 0 中止合成，
     * 释放 speakMutex 让用户请求立即开始。warmUp 是优化，绝不能阻塞用户 10 秒。
     */
    @Volatile
    internal var warmUpCancelled = false

    /**
     * 后台预热（实现见 [warmUpEngine]）：提前消化 ONNX Runtime 首次 generate 的
     * 一次性开销（图优化、线程池爬升、arena 内存池扩张与物理页缺页）。
     */
    suspend fun warmUp() = warmUpEngine()

    /**
     * 预合成一段短文本并把 PCM 存入 [pcmCache]（实现见 [prewarmSynthesisEngine]）：
     * 朗读路径命中缓存时跳过 generate 直接播，避开 Kokoro ~2s 的固定开销。
     */
    suspend fun prewarmSynthesis(text: String, speed: Float = 1.0f) =
        prewarmSynthesisEngine(text, speed)

    /**
     * 朗读一段文字（阻塞至音频播放完毕，由调用方在协程中调用）。
     *
     * 流式播放：文本按句入队，sherpa-onnx 每合成完一小段（内部按句）就回调，
     * 采样边合成边写入 MODE_STREAM 的 AudioTrack 立即出声——Kokoro 这类大模型
     * "整段合成完才开始播"的首句静默等待（手机 CPU 上可达十几秒）被压缩到
     * 首小段的合成时间。实现见 `TtsSpeakOrchestrator.kt`。
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
        val ok = runSpeakQueue(splitSentences(text), speed, onSentenceDone = null)
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
    ): Boolean = runSpeakQueue(sentences, speed, onSentenceDone)

    /**
     * 停止当前播放。
     */
    fun stop() {
        // 取消全部 speak 协程：让 executeSpeakQueueLocked 立刻退出（协程取消时
        // kotlinx coroutines Mutex.withLock 会在 finally 释放锁）。
        // 之前只停 AudioTrack 会导致旧 speak 继续在 mutex 里跑完整段，
        // 用户的"停止"按钮实际无效——新的 speak 必须等旧协程跑完才能进。
        // 必须取消"所有"调用者：正在出声的与挂在锁上等待的，
        // 只取消一个时另一个会跨过下一句继续播
        speakJobRegistry.cancelAll()
        synchronized(audioInfra.trackSlot.lock) {
            try {
                audioInfra.trackSlot.track?.let {
                    if (it.state == AudioTrack.STATE_INITIALIZED) {
                        it.pause()
                        it.flush()
                    }
                    it.release()
                }
            } catch (_: Exception) {}
            audioInfra.trackSlot.track = null
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
                nativeEngine.release()
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
     *（2026-09-05 实测：点喇叭后 8 秒无声，实为 warmUp 收尾期排队）。
     */
    fun isWarmedUp(): Boolean = nativeEngine.isWarmedUp()

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

    companion object {
        internal const val TAG = "EmbeddedTtsEngine"

        /**
         * 推理预热文本（见 [warmUpEngine]）：长度必须接近真实首块负载
         *（~90 字符 ≈ 4-6 秒音频）。用 "Ok." 这类短句预热时，ONNX Runtime
         * 的 arena 内存池只长到小句规模，真实首句推理仍要触发大额 arena
         * 扩张与物理页缺页，冷启动成本大部分重现（2026-09-05 真机实测：
         * 短句预热后首句出声仍 ~8s）。长句预热把内存池/页表一次性长到
         * 峰值形状，真实首句直接复用。合成出的音频直接丢弃，
         * 不建 AudioTrack、不申请音频焦点。
         */
        internal const val WARMUP_TEXT =
            "The morning sun rises slowly over the quiet hills, and the birds begin to sing in the trees."

        /**
         * 预合成仅面向短文本（单词/短语）：超长文本的每次 generate 固定开销
         * 占比小，缓存价值低且浪费内存。
         */
        internal const val MAX_PREWARM_CHARS = 40
    }
}
