package com.eareyereading.tts

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Build
import android.util.Log
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsKokoroModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsVitsModelConfig
import com.eareyereading.util.NotificationService
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.min
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream

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
    @ApplicationContext private val context: Context,
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

    private val audioTrackLock = Any()
    private var audioTrack: AudioTrack? = null

    // 是否正在播放
    private val isPlaying = AtomicBoolean(false)

    // speak() 调用串行化锁：
    // sherpa-onnx OfflineTts 的 native 指针不能并发使用，
    // 否则两个协程同时调 generate() 会触发 JNI 段错误 (SIGSEGV)。
    // TtsHelper.speak() 在 scope.launch 里多次调用本方法，必须串行。
    private val speakMutex = Mutex()

    // ── 音频焦点：此前完全不申请，朗读会压在音乐/播客上（或被电话打断后不恢复）。
    // 用 TRANSIENT_MAY_DUCK：朗读期间让其他音频让路，结束后自动恢复
    private val audioManager: AudioManager? by lazy {
        context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
    }
    private val focusListener = AudioManager.OnAudioFocusChangeListener { change ->
        when (change) {
            // 电话/闹钟/其他媒体抢焦点：旧实现不响应，朗读继续压在通话上。
            // 先发射外部停止信号再 stop()：同一 Main 调度器上 FIFO，UI 层
            // collect 先把 isAutoReading/isPlaying/isTtsPlaying 清零，
            // 循环播放驱动（自动朗读/速读/RSVP）才不会在 stop() 取消当前句后
            // 又推进到下一段继续压着通话读
            AudioManager.AUDIOFOCUS_LOSS,
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                Log.i(TAG, "audio focus lost ($change), stopping playback")
                audioFocusHeld = false
                _externalStop.tryEmit(Unit)
                stop()
            }
            else -> { /* GAIN/DUCK 无需响应：我们 duck 别人，别人 duck 我们不影响朗读 */ }
        }
    }
    @Volatile
    private var audioFocusHeld = false

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
     * 焦点请求（API 26+ 新 API）：旧版 requestAudioFocus(listener, STREAM_MUSIC,
     * gain) 走 legacy stream 焦点路径，与无 streamType 的 AudioTrack 属性不一致，
     * MIUI 焦点状态机在此错配下行为不可预期（deprecated 警告即源于此）。
     */
    private val focusRequest: AudioFocusRequest = AudioFocusRequest
        .Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
        .setAudioAttributes(playbackAudioAttributes)
        .setOnAudioFocusChangeListener(focusListener)
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

    private fun requestAudioFocusIfNeeded() {
        if (audioFocusHeld) return
        val am = audioManager ?: return
        try {
            val result = am.requestAudioFocus(focusRequest)
            // 只在真正拿到焦点时置位：系统拒给焦点（通话中）时若照样置 true，
            // 一来 abandon 会归还我们没持有的焦点，二来"未持焦点"语义丢失
            audioFocusHeld = result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
            if (!audioFocusHeld) {
                Log.w(TAG, "audio focus request denied (result=$result), playing without focus")
            }
        } catch (e: Exception) {
            Log.w(TAG, "requestAudioFocus failed", e)
        }
    }

    private fun abandonAudioFocusIfHeld() {
        if (!audioFocusHeld) return
        audioFocusHeld = false
        val am = audioManager ?: return
        try {
            am.abandonAudioFocusRequest(focusRequest)
        } catch (e: Exception) {
            Log.w(TAG, "abandonAudioFocus failed", e)
        }
    }

    /**
     * 主动归还音频焦点（供调用方在一次朗读会话结束时调用）。
     * 句子链/单段朗读自然播完不会走 stop()，若不归还，
     * 被 duck 的背景音乐/播客会一直保持压低状态直到进程结束。
     * 幂等：stop()/release() 已归还过时重复调用无副作用
     */
    fun abandonAudioFocus() {
        abandonAudioFocusIfHeld()
    }

    // 当前正在跑的 speak() 协程的 Job 集合。stop() 全部取消，连带释放 mutex。
    // 单值字段不够：speak A 持锁播放、speak B 挂在锁上等待时，
    // stop() 只会取消后注册的 B，A 的句循环跨过下一句继续出声——停止看似无效
    private val speakJobLock = Any()
    private val activeSpeakJobs = mutableSetOf<Job>()

    // 引擎状态（用于 UI 显示）
    private val _state = MutableStateFlow<EngineState>(EngineState.NOT_INITIALIZED)
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

    private val _downloadProgress = MutableStateFlow<Progress>(Progress.Idle)
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

    /**
     * 模型配置：模型名 → CDN URL
     *
     * 使用 k2-fsa 官方 HuggingFace 上的预编译模型。
     */
    data class ModelInfo(
        val id: String,
        val displayName: String,
        val language: String,
        val sizeBytes: Long,
        val files: List<ModelFile>,
        /**
         * GitHub release 整包 tarball URL（推荐，国内可达性优于 HuggingFace）。
         * 下载后解压到 models 目录。若为 null 则回退到逐文件下载。
         */
        val tarballUrl: String? = null,
        val tarballMirrorUrls: List<String> = emptyList(),
        /**
         * Piper 系模型：G2P 走 espeak-ng（需模型目录下的 espeak-ng-data/），
         * 不用 lexicon。初始化时 dataDir 指向 espeak-ng-data 目录。
         */
        val usesEspeakNg: Boolean = false,
        /**
         * Kokoro 系模型（kokoro-multi-lang-v1_1）：初始化走
         * OfflineTtsKokoroModelConfig（voices.bin + 双 lexicon + ruleFsts），
         * generate() 时传 sid 选择 103 种音色之一。
         */
        val isKokoro: Boolean = false,
    ) {
        fun tarballAllUrls(): List<String> =
            (listOfNotNull(tarballUrl) + tarballMirrorUrls)
                // 镜像列表里历史上混入过裸 model.onnx URL：它会被写成 .tar.bz2
                // 导致 bzip2 解压必然失败，还白白下载上百 MB。这里只接受 tarball。
                .filter { it.endsWith(".tar.bz2") }
    }

    data class ModelFile(
        val relativePath: String,  // 在 app models 目录下的相对路径
        val url: String,
        /**
         * 备选镜像 URL 列表（按优先级排序）。主 URL 失败后依次尝试。
         * 用于解决 HuggingFace 在国内不可达的问题。
         */
        val mirrorUrls: List<String> = emptyList(),
    ) {
        /** 按优先级返回所有可用 URL（主 URL 在前）。 */
        fun allUrls(): List<String> = listOf(url) + mirrorUrls
    }

    companion object {
        private const val TAG = "EmbeddedTtsEngine"
        private const val MODELS_DIR_NAME = "sherpa_tts_models"
        private const val NUM_THREADS = 2
        /** Kokoro（82M 参数）合成开销大，官方演示对带 voices 的模型用 4 线程 */
        private const val NUM_THREADS_KOKORO = 4
        /** 文件完整下载标记后缀。存在表示该文件已完整下载，避免误用残缺文件。 */
        private const val COMPLETE_SUFFIX = ".complete"
        /**
         * 解压总字节上限：正常 Piper 归档解压后 ~120MB，给 2 倍余量。
         * 防 bzip2 解压炸弹（恶意/损坏归档写满整盘）。（1.3：512MB → 256MB）
         */
        private const val MAX_EXTRACT_BYTES = 256L * 1_000_000L

        /**
         * 解压 IO 缓冲：读（BufferedInputStream 归并 BZip2 位读取器的小粒度
         * read）与写（整轮复制的复制缓冲）共用 256KB，减少 syscall 与 GC 压力。
         */
        private const val EXTRACTION_IO_BUFFER_BYTES = 256 * 1024

        /**
         * 流式朗读的 AudioTrack 环形缓冲（秒）：大于典型单句音频时长，
         * 句间合成抖动不产生断音；合成快于播放时阻塞写自然形成背压。
         */
        private const val STREAM_BUFFER_SECONDS = 4

        /**
         * 开播前预缓冲（秒）：首次 offer 攒够约 0.8 秒 PCM 才建轨并 play()。
         *
         * 为什么需要：旧实现第一段采样一到就 play()，AudioTrack 缓冲垫≈0，
         * 合成稍有抖动（如系统 binder 停顿/线程调度）就耗尽缓冲触发 underrun，
         * AudioTrack 被系统禁用后 restartIfDisabled 重启还伴随百毫秒级 binder
         * 停顿，听感为卡顿/长停顿。0.8 秒的权衡：远小于当前 6 秒级的首块合成
         * 时间（首声延迟几乎不变），又足够吸收一次秒级合成抖动。
         */
        private const val PREBUFFER_SECONDS = 0.8f

        /**
         * 单块合成文本最大字符数。
         *
         * 2026-09-05：从 80 提高到 200。配合 maxNumSentences=4，native 端把
         * 块内句子并行合成（默认 maxNumSentences=1 串行，每次 generate ~2s
         * 固定开销，5 句 = 10s）。200 字符在 sherpa-onnx 安全范围内（~200 以内
         * 稳定），native 端自己按句切分并行处理，回调仍按句触发流式出声。
         * Kotlin 层不再切成 80 字符小块串行——那是固定开销倍增的根因。
         */
        private const val MAX_CHUNK_CHARS = 200

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

        /**
         * AudioTrack 硬件采样率：固定 48000（设备 primary output 原生率）。
         *
         * 为什么不用模型原生率（Kokoro 24000 / Piper 22050）：2026-09-05 真机
         * 诊断——MIUI（afSampleRate=48000）上 24kHz 的流写入成功、start
         * 成功、自报 PLAYING，但 mixer 恒不消费（playbackHeadPosition=0，
         * 扬声器无声；pcmPeak/musicVol 均正常）。疑为 AudioPolicy 把非
         * 原生率流路由到不支持重采样的 direct/low-power 输出线程
         * （audio_lowpower_app_list.xml 即该策略配置文件）。统一上采样到
         * 48k 建轨，强制走 primary mixer 原生路径。
         */
        private const val TRACK_SAMPLE_RATE = 48000

        /**
         * 内置可用模型列表。
         *
         * 默认 = Piper lessac-medium（见 DEFAULT_MODEL_ID）：韵律自然、英文发音
         * 地道、体积小；G2P 走 espeak-ng（归档自带 espeak-ng-data/）。
         *
         * Kokoro int8 中英双语（2026-09-04 新增）：103 种音色 + 原生中英混读。
         * 归档含 jieba dict/、三个 ruleFst（phone/date/number-zh.fst，中文数字
         * 日期归一化）与双 lexicon；官方 Android 演示同款配置。
         */
        val AVAILABLE_MODELS = listOf(
            ModelInfo(
                id = "vits-piper-en_US-lessac-medium",
                displayName = "Piper 英文男声·自然语调（约 66MB）",
                language = "en",
                sizeBytes = 66_000_000L,
                // 主源用 ghfast.top 镜像（国内可达性更稳），保留 GitHub release 作 fallback。
                // 注意：ghfast.top 嵌套 GitHub URL 时必须把内层 scheme/路径做 URL 转义，
                // 否则 Java URL.openConnection 会发送未转义的 `://` 给边缘节点，
                // 部分 CDN 会判定为非法资源 → 404 或卡死握手。转义后的
                // 形态在浏览器和 ghfast.top 后端都稳。
                tarballUrl = "https://ghfast.top/https%3A%2F%2Fgithub.com%2Fk2-fsa%2Fsherpa-onnx%2Freleases%2Fdownload%2Ftts-models%2Fvits-piper-en_US-lessac-medium.tar.bz2",
                tarballMirrorUrls = listOf(
                    "https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/vits-piper-en_US-lessac-medium.tar.bz2",
                ),
                usesEspeakNg = true,
                // URL 留空 = 仅归档下载：espeak-ng-data 含数百个小文件，
                // 逐文件路径不可行；归档失败时由下载逻辑直接报失败
                files = listOf(
                    ModelFile("vits-piper-en_US-lessac-medium/en_US-lessac-medium.onnx", url = ""),
                    ModelFile("vits-piper-en_US-lessac-medium/tokens.txt", url = ""),
                    ModelFile("vits-piper-en_US-lessac-medium/espeak-ng-data", url = ""),
                ),
            ),
            ModelInfo(
                id = "kokoro-int8-multi-lang-v1_1",
                displayName = "Kokoro 中英双语·多音色（约 205MB，103 种音色）",
                language = "zh,en",
                // 解压后总大小（model.int8.onnx 114MB + voices.bin 54MB + 词典/分词数据）。
                // 下载进度分母优先用响应 Content-Length（压缩包 ~147MB），此处数值
                // 仅作磁盘预检（×3）与解压估算的基准
                sizeBytes = 205_000_000L,
                tarballUrl = "https://ghfast.top/https%3A%2F%2Fgithub.com%2Fk2-fsa%2Fsherpa-onnx%2Freleases%2Fdownload%2Ftts-models%2Fkokoro-int8-multi-lang-v1_1.tar.bz2",
                tarballMirrorUrls = listOf(
                    "https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/kokoro-int8-multi-lang-v1_1.tar.bz2",
                ),
                usesEspeakNg = true,
                isKokoro = true,
                // URL 留空 = 仅归档下载：espeak-ng-data / dict 各含数百个小文件
                files = listOf(
                    ModelFile("kokoro-int8-multi-lang-v1_1/model.int8.onnx", url = ""),
                    ModelFile("kokoro-int8-multi-lang-v1_1/voices.bin", url = ""),
                    ModelFile("kokoro-int8-multi-lang-v1_1/tokens.txt", url = ""),
                    ModelFile("kokoro-int8-multi-lang-v1_1/espeak-ng-data", url = ""),
                    ModelFile("kokoro-int8-multi-lang-v1_1/dict", url = ""),
                    ModelFile("kokoro-int8-multi-lang-v1_1/lexicon-us-en.txt", url = ""),
                    ModelFile("kokoro-int8-multi-lang-v1_1/lexicon-zh.txt", url = ""),
                    ModelFile("kokoro-int8-multi-lang-v1_1/lexicon-gb-en.txt", url = ""),
                    ModelFile("kokoro-int8-multi-lang-v1_1/phone-zh.fst", url = ""),
                    ModelFile("kokoro-int8-multi-lang-v1_1/date-zh.fst", url = ""),
                    ModelFile("kokoro-int8-multi-lang-v1_1/number-zh.fst", url = ""),
                ),
            ),
        )

        // 内置默认 = Piper 英文声（英文阅读主线；Kokoro 为用户可选升级）
        val DEFAULT_MODEL_ID = "vits-piper-en_US-lessac-medium"

        /** 用户当前选中的模型 ID（用 SharedPreferences 持久化） */
        private const val PREFS_NAME = "embedded_tts_prefs"
        private const val KEY_SELECTED_MODEL = "selected_model"

        /** 用户选中的音色 sid，按模型分别持久化（Piper 无多音色，仅 Kokoro 使用） */
        private const val KEY_SELECTED_VOICE_PREFIX = "selected_voice_"

        /**
         * Kokoro（kokoro-multi-lang-v1_1）的 103 个音色。
         * sid→名称映射来自官方文档；前缀含义：af=美式女声 bf=英式女声
         * zf=中文女声 zm=中文男声。所有音色均可中英混读，只是口音倾向不同。
         */
        data class VoiceInfo(val sid: Int, val name: String) {
            val category: String
                get() = when {
                    name.startsWith("af") -> "美式女声"
                    name.startsWith("bf") -> "英式女声"
                    name.startsWith("zf") -> "中文女声"
                    name.startsWith("zm") -> "中文男声"
                    else -> "其他"
                }
            val displayName: String get() = "$name · $category"
        }

        private val KOKORO_VOICE_NAMES = listOf(
            "af_maple", "af_sol", "bf_vale",
            "zf_001", "zf_002", "zf_003", "zf_004", "zf_005", "zf_006", "zf_007",
            "zf_008", "zf_017", "zf_018", "zf_019", "zf_021", "zf_022", "zf_023",
            "zf_024", "zf_026", "zf_027", "zf_028", "zf_032", "zf_036", "zf_038",
            "zf_039", "zf_040", "zf_042", "zf_043", "zf_044", "zf_046", "zf_047",
            "zf_048", "zf_049", "zf_051", "zf_059", "zf_060", "zf_067", "zf_070",
            "zf_071", "zf_072", "zf_073", "zf_074", "zf_075", "zf_076", "zf_077",
            "zf_078", "zf_079", "zf_083", "zf_084", "zf_085", "zf_086", "zf_087",
            "zf_088", "zf_090", "zf_092", "zf_093", "zf_094", "zf_099",
            "zm_009", "zm_010", "zm_011", "zm_012", "zm_013", "zm_014", "zm_015",
            "zm_016", "zm_020", "zm_025", "zm_029", "zm_030", "zm_031", "zm_033",
            "zm_034", "zm_035", "zm_037", "zm_041", "zm_045", "zm_050", "zm_052",
            "zm_053", "zm_054", "zm_055", "zm_056", "zm_057", "zm_058", "zm_061",
            "zm_062", "zm_063", "zm_064", "zm_065", "zm_066", "zm_068", "zm_069",
            "zm_080", "zm_081", "zm_082", "zm_089", "zm_091", "zm_095", "zm_096",
            "zm_097", "zm_098", "zm_100",
        )

        /** Kokoro 音色目录（sid 顺序与官方 voices.bin 对齐） */
        val KOKORO_VOICES: List<VoiceInfo> =
            KOKORO_VOICE_NAMES.mapIndexed { sid, name -> VoiceInfo(sid, name) }
    }

/**
 * 把文本切成 sherpa-onnx 能安全合成的块。
 *
 * 背景：sherpa-onnx VITS/MeloTTS 对单次 generate() 输入长度敏感，~200 字符以内稳定，
 * 太长会触发 ONNX Runtime 内存分配异常（Scudo: invalid chunk state）+ JNI crash。
 * 切分优先按句子边界（. ! ? 后跟空白 + 大写/引号/左括号），保证切出来的块仍是自然句子。
 */
/**
 * 把文本里 sherpa-onnx Piper 模型不认识的字符替换成可发音的等价物，
 * 并把所有 CJK 中日韩字符替换为占位符以保证纯英文 TTS 行为。
 *
 * 为什么强制过滤 CJK（即使书里偶尔出现中文 / 引用 / 跳跃来源）：
 *  1) 当前内置只有 Piper 英文男声，无中英双语模型可用——CJK 字符进 generate()
 *     模型无法产生对应音，会触发 OOV → 静音或段错误。
 *  2) Piper G2P 对 [0-9]+ 'year' 这种组合的归一化在不同语料下表现不稳定，
 *     偶发被听成单字拼音风味（如 "2026" 像 "er ling er liu"），
 *     与我们目标"标准英文朗读"不符。
 *  3) 只读英文书时 CJK 段一般是标题 / 作者名 / 引用，朗读意义不大，
 *     替换为占位符可以提高可听性（"Title: [Chinese text omitted]"）。
 *
 * 已知 OOV 列表（来自实际 logcat）：
 *  - 数字 '0'-'99'：被 Ignore OOV 直接跳过，导致 tensor 索引越界 → SIGSEGV
 *  - 标点 'í'（西班牙语重音字符）、'—'（em-dash）、'"' '"'（smart quotes）、'(' ')'：同样 OOV
 *  - '$' '&' '+' '@' '#' '%' '=' '<' '>' '\\' '`' '~' '^' '|' 等特殊符号
 *  - CJK Unified Ideographs (U+4E00–U+9FFF)、CJK Ext A/B (U+3400–U+4DBF, U+20000+)、
 *    Hiragana (U+3040–U+309F)、Katakana (U+30A0–U+30FF)、Hangul (U+AC00–U+D7AF)：
 *    用 [CJK] 占位
 *
 * 替换策略：
 *  - 4 位年份 (2026) → "twenty twenty-six"，避免被切成 "twenty" + "twenty-six"
 *  - 其他数字 (65, 28, 10, 07) → 英文单词
 *  - 标点 → ASCII 等价（'—' → ", ", '"' → '"'）
 *  - 货币、特殊符号 → 英文读法
 *  - CJK 字符整段 → "[Chinese/Korean text]" 占位符
 */
private object TtsPreprocess {
    val YEAR = Regex("\\b(1\\d{3}|20\\d{2})\\b")
    val TIME = Regex("\\b(\\d{1,2}):(\\d{2})\\b")
    val CURRENCY = Regex("\\$(\\d+)?(?:\\.(\\d{1,2}))?")
    val NUMBER = Regex("(?<!\\d)(\\d+)(?!\\d|:)")
    val CJK_RUN = Regex("([\\u4E00-\\u9FFF\\u3400-\\u4DBF\\u3040-\\u309F\\u30A0-\\u30FF\\uAC00-\\uD7AF]+)")
    val UPPERCASE_RUN = Regex("\\b[A-Z]{3,}\\b")
    val WHITESPACE = Regex("\\s+")
    val TIME_SUFFIX = Regex("\\d{1,2}:\\d{1,2}$")

    val US = Regex("\\bU\\.S\\.\\b")
    val USA = Regex("\\bU\\.S\\.A\\.\\b")
    val UK = Regex("\\bU\\.K\\.\\b")
    val EU = Regex("\\bE\\.U\\.\\b")
    val PM_DOTTED = Regex("\\bP\\.M\\.\\b")
    val AM_DOTTED = Regex("\\bA\\.M\\.\\b")
    val DC = Regex("\\bD\\.C\\.\\b")
    val NY = Regex("\\bN\\.Y\\.\\b")
    val PM = Regex("\\bPM\\b")
    val AM = Regex("\\bAM\\b")
    val AP = Regex("\\bAP\\b")
    val CEO = Regex("\\bCEO\\b")
    val GDP = Regex("\\bGDP\\b")
    val NASA = Regex("\\bNASA\\b")
    val FBI = Regex("\\bFBI\\b")
    val CIA = Regex("\\bCIA\\b")

    /** 时区缩写正则缓存（disambiguateTimeZoneAbb 的 abbrev 参数只有 4 个取值）。 */
    private val tzAbbRegexes = java.util.concurrent.ConcurrentHashMap<String, Regex>()
    fun tzAbbRegex(abbrev: String): Regex =
        tzAbbRegexes.computeIfAbsent(abbrev) { Regex("\\b$it\\b") }

    /** 单字符 → 读法/替身的单趟映射（替代约 35 次逐字符 String.replace 扫描）。 */
    val LITERAL_REPLACEMENTS: Map<Char, String> = mapOf(
        '\u2014' to ", ",   // em-dash → comma+space
        '\u2013' to "-",    // en-dash → hyphen
        '\u2018' to "'",    // left single quote
        '\u2019' to "'",    // right single quote
        '\u201C' to "\"",   // left double quote
        '\u201D' to "\"",   // right double quote
        '\u00ed' to "i",    // í → i (Rodríguez → Rodriguez)
        '\u00e9' to "e",    // é → e
        '\u00e1' to "a",    // á → a
        '\u00f1' to "n",    // ñ → n
        '\u00fc' to "u",    // ü → u
        '\u00e7' to "c",    // ç → c
        // 货币符号
        '$' to " dollars ",
        '\u20ac' to " euros ",
        '\u00a3' to " pounds ",
        '\u00a5' to " yen ",
        // 其他常见 OOV 符号（含 '<' '>' —— 此前遗漏未替换）
        '@' to " at ",
        '&' to " and ",
        '+' to " plus ",
        '=' to " equals ",
        '#' to " number ",
        '%' to " percent ",
        '\\' to " ",
        '/' to " ",        // 日期斜杠
        '<' to " less than ",
        '>' to " greater than ",
        '*' to " ",
        '[' to ", ",
        ']' to ", ",
        '_' to " ",
        '{' to ", ",
        '}' to ", ",
        '(' to ", ",
        ')' to ", ",
        '\u00a0' to " ",   // non-breaking space
        '`' to "'",        // backtick
        '|' to " ",
        '^' to " ",
        '~' to " ",
        '\u2026' to "...", // ellipsis
    )

    /** 无特殊字符时原样返回（免 StringBuilder）；有则单趟替换。 */
    fun applyLiteralReplacements(s: String): String {
        var hasSpecial = false
        for (c in s) {
            if (c in LITERAL_REPLACEMENTS) {
                hasSpecial = true
                break
            }
        }
        if (!hasSpecial) return s
        val sb = StringBuilder(s.length + 16)
        for (c in s) {
            val rep = LITERAL_REPLACEMENTS[c]
            if (rep != null) sb.append(rep) else sb.append(c)
        }
        return sb.toString()
    }

    // numberToWords 的词表（原实现每次调用重建 3 个数组）
    val UNITS = arrayOf("", "one", "two", "three", "four", "five", "six", "seven", "eight", "nine")
    val TEENS = arrayOf(
        "ten", "eleven", "twelve", "thirteen", "fourteen", "fifteen",
        "sixteen", "seventeen", "eighteen", "nineteen",
    )
    val TENS = arrayOf(
        "", "", "twenty", "thirty", "forty", "fifty",
        "sixty", "seventy", "eighty", "ninety",
    )

    // digitsToWords 的数字名映射（原实现每次调用重建 Map）
    val DIGIT_NAMES: Map<Char, String> = mapOf(
        '0' to "zero", '1' to "one", '2' to "two", '3' to "three",
        '4' to "four", '5' to "five", '6' to "six", '7' to "seven",
        '8' to "eight", '9' to "nine",
    )
}

/**
 * 朗读前文本归一化（句子级热路径，正则/词表全部预编译见 [TtsPreprocess]）。
 */
private fun preprocessForTts(text: String): String {
    var s = text

    // 4 位年份 (1000-2099)：转成英文单词
    // 注意：要在普通数字转换之前，避免 "2026" 被切成 "two thousand" + "twenty-six"
    s = TtsPreprocess.YEAR.replace(s) { match ->
        numberToWords(match.value.toInt())
    }

    // 时间格式 "10:07" → "ten oh seven"
    s = TtsPreprocess.TIME.replace(s) { match ->
        val (h, m) = match.groupValues[1] to match.groupValues[2]
        "${numberToWords(h.toInt())} oh ${numberToWords(m.toInt())}"
    }

    // 货币 + 数字组合（如 "$100"）：必须在通用数字转换之前，
    // 否则 "$100" 会先变成 "$one hundred" 再变成 " dollars one hundred"（语序颠倒）
    // 整数部分可选：$.50 / $0.99 也要命中（旧正则要求 $ 后紧跟数字，
    // "$.50" 漏匹配后 "$" 被兜底替换成 " dollars " → 读成 "dollars point fifty"）
    s = TtsPreprocess.CURRENCY.replace(s) { match ->
        fun words(digits: String): String =
            digits.toIntOrNull()?.takeIf { it in 0..9999 }?.let { numberToWords(it) }
                ?: digitsToWords(digits)
        val dollars = match.groupValues[1]
        val cents = match.groupValues[2]
        when {
            cents.isEmpty() && dollars.isEmpty() -> match.value // 裸 "$"：交给后续兜底替换
            cents.isEmpty() -> "${words(dollars)} dollars"
            dollars.isEmpty() || dollars == "0" -> "${words(cents)} cents"
            else -> "${words(dollars)} dollars ${words(cents)} cents"
        }
    }

    // 其他数字 (含小数)：转英文
    // 不含已处理过的年份/时间。
    // 关键：超过 Int 或超过支持范围的数字必须逐位转成单词，
    // 绝不能把裸数字留给 generate()——本文件注释明确记载数字会触发
    // native tensor 索引越界 SIGSEGV，且信号无法被 catch 拦截
    s = TtsPreprocess.NUMBER.replace(s) { match ->
        val num = match.value.toIntOrNull()
        if (num != null && num in 0..9999) {
            numberToWords(num)
        } else {
            digitsToWords(match.value)
        }
    }

    // 单字符符号替换：约 35 个逐字符 String.replace 合并为单趟扫描
    //（每个命中字符原先都要全串拷贝一次）
    s = TtsPreprocess.applyLiteralReplacements(s)

    // CJK 强制过滤：把连续中日韩段替换为占位符（参见函数头注释）。
    // 用 capture group + lookahead 实现"整段连续 CJK" 的合并替换，单字符替换的话
    // 每字一字 placeholder，TTS 会读得稀碎。
    s = TtsPreprocess.CJK_RUN.replace(s) { match ->
        // 短中文段（如 "的"）直接沉默；长段提示用户已跳过
        if (match.value.length <= 3) " " else " [Chinese or other text omitted] "
    }

    // 常见缩写展开（MeloTTS lexicon 不含这些，G2P fallback 可能触发 native 空指针）
    s = s.replace(TtsPreprocess.US, "United States")
    s = s.replace(TtsPreprocess.USA, "United States of America")
    s = s.replace(TtsPreprocess.UK, "United Kingdom")
    s = s.replace(TtsPreprocess.EU, "European Union")
    s = s.replace(TtsPreprocess.PM_DOTTED, "P M")
    s = s.replace(TtsPreprocess.AM_DOTTED, "A M")
    s = s.replace(TtsPreprocess.DC, "D C")
    s = s.replace(TtsPreprocess.NY, "New York")
    // 时间缩写 PM/AM（无点号的全大写）
    s = s.replace(TtsPreprocess.PM, "P M")
    s = s.replace(TtsPreprocess.AM, "A M")
    // 时区缩写 ET/CT/PT/MT：语境白名单启发式（2.10）——
    // 仅当明确是时间/时段语境才展开为时区名，否则按缩写逐字母读，
    // 避免 "CT scan" 被误读为 "Central Time scan"。
    s = disambiguateTimeZoneAbb(s, "ET", "Eastern Time")
    s = disambiguateTimeZoneAbb(s, "CT", "Central Time")
    s = disambiguateTimeZoneAbb(s, "PT", "Pacific Time")
    s = disambiguateTimeZoneAbb(s, "MT", "Mountain Time")
    s = s.replace(TtsPreprocess.AP, "Associated Press")
    s = s.replace(TtsPreprocess.CEO, "C E O")
    s = s.replace(TtsPreprocess.GDP, "G D P")
    s = s.replace(TtsPreprocess.NASA, "N A S A")
    s = s.replace(TtsPreprocess.FBI, "F B I")
    s = s.replace(TtsPreprocess.CIA, "C I A")

    // 把连续 3+ 大写字母拆成单字母（如 "NATO" → "N A T O"），
    // MeloTTS lexicon 有单字母发音，避免 G2P 对未知缩写崩溃
    s = TtsPreprocess.UPPERCASE_RUN.replace(s) { match ->
        match.value.toCharArray().joinToString(" ")
    }

    // 把连续空白合并
    s = s.replace(TtsPreprocess.WHITESPACE, " ").trim()
    return s
}

/**
 * Kokoro 双语模型的轻量预处理：仅合并空白。
 *
 * Kokoro 前端自带完整 G2P（espeak-ng 英文 + jieba/词典中文 + ruleFst 数字
 * 归一化），数字、缩写、中英混排、全半角标点均可原生朗读。Piper 专用的
 * 数字→英文单词、CJK→占位符、括号替换等操作在这里反而有害（把 "2026 年"
 * 改成 "twenty twenty-six 年"、把中文整段替换成 "[Chinese text omitted]"）。
 */
private fun preprocessForTtsLight(text: String): String =
    text.replace(TtsPreprocess.WHITESPACE, " ").trim()

/**
 * 时区缩写歧义消解（2.10，词典白名单启发式）：
 * ET/CT/PT/MT 既是时区名也是通用缩写，不能无条件展开——
 * "CT scan"（计算机断层扫描）会被误读成 "Central Time scan"。
 * 仅当明确是时间/时段语境时展开为时区名，否则按缩写逐字母读（"C T scan"）。
 * 语境判定白名单：后跟 time/am/pm，或前跟数字（小时 / HH:mm）。
 */
private fun disambiguateTimeZoneAbb(s: String, abbrev: String, timezone: String): String {
    return TtsPreprocess.tzAbbRegex(abbrev).replace(s) { m ->
        val after = s.substring(m.range.last + 1).trimStart()
        val before = s.substring(0, m.range.first).trimEnd()
        val timeContext =
            after.startsWith("time", ignoreCase = true) ||
                after.startsWith("am", ignoreCase = true) ||
                after.startsWith("pm", ignoreCase = true) ||
                before.lastOrNull()?.isDigit() == true ||
                TtsPreprocess.TIME_SUFFIX.containsMatchIn(before)
        if (timeContext) timezone else abbrev.map(Char::toString).joinToString(" ")
    }
}

/**
 * 整数 → 英文单词（0-9999）。超过 9999 逐位读出。
 * 永不返回裸数字字符串——裸数字进 generate() 是文档记载的
 * native SIGSEGV 类别（见 preprocessForTts 注释）。
 */
private fun numberToWords(n: Int): String {
    if (n < 0) return digitsToWords(n.toString().removePrefix("-"))
    if (n > 9999) return digitsToWords(n.toString())
    if (n == 0) return "zero"

    val units = TtsPreprocess.UNITS
    val teens = TtsPreprocess.TEENS
    val tens = TtsPreprocess.TENS

    fun under1000(x: Int): String {
        if (x == 0) return ""
        val hundreds = x / 100
        val rest = x % 100
        val h = if (hundreds > 0) "${units[hundreds]} hundred " else ""
        val r = when {
            rest == 0 -> ""
            rest < 10 -> units[rest]
            rest < 20 -> teens[rest - 10]
            else -> "${tens[rest / 10]}${if (rest % 10 > 0) " ${units[rest % 10]}" else ""}"
        }
        return "$h$r".trim()
    }

    val thousands = n / 1000
    val rest = n % 1000
    val t = if (thousands > 0) "${under1000(thousands)} thousand " else ""
    return "$t${under1000(rest)}".trim()
}

/** 数字串逐位读出（电话号/编号/超范围数值），保证不留裸数字。 */
private fun digitsToWords(digits: String): String {
    val names = TtsPreprocess.DIGIT_NAMES
    return digits.mapNotNull { names[it] }.joinToString(" ").ifEmpty { "zero" }
}

/**
 * 纯逐句切分（不累积）。
 * 中文全角句点 。！？；（允许尾随闭引号/括号）：中文散文不靠空白分句，
 * 此前只认 ASCII 边界，整段中文被当成一个"句子"再被 150 字符截断，
 * 而默认模型恰是 MeloTTS 中英——等于中文书每段只读前 150 字。
 * ASCII 边界保留原规则：句末标点 + 空白 + 下一句开头（大写/引号/左括号/数字）。
 */
private fun splitSentences(text: String): List<String> {
    if (text.isBlank()) return emptyList()
    val cjkBoundary = Regex("(?<=[。！？；][”’」』]?)")
    val asciiBoundary = Regex("(?<=[.!?])\\s+(?=[A-Z\"\\(\\d])")
    return text.split(cjkBoundary)
        .flatMap { it.split(asciiBoundary) }
        .map { it.trim() }
        .filter { it.isNotBlank() }
}

/**
 * 超长句切块（≤maxLen）：优先在空白处断，找不到就硬切。
 * 替代旧的 substring(0,150)——那是直接丢弃 150 字符以后的全部内容。
 */
private fun hardChunks(sentence: String, maxLen: Int): List<String> {
    if (sentence.length <= maxLen) return listOf(sentence)
    val chunks = mutableListOf<String>()
    var start = 0
    while (start < sentence.length) {
        val end = minOf(start + maxLen, sentence.length)
        val cut = if (end < sentence.length) {
            val ws = sentence.lastIndexOf(' ', end)
            if (ws > start + maxLen - 40) ws else end
        } else {
            end
        }
        chunks.add(sentence.substring(start, cut).trim())
        start = if (cut >= end) end else cut + 1
    }
    return chunks.filter { it.isNotBlank() }
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
    private val downloadMutex = Mutex()

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

    private suspend fun downloadModelLocked(
        modelInfo: ModelInfo,
        onProgress: (Float) -> Unit,
    ): Boolean = withContext(Dispatchers.IO) {
        // 已完整下载（全部 .complete 标记在位）：跳过网络阶段直接进"初始化"语义。
        // 旧实现无条件置 DOWNLOADING(0%)，用户重进页面点下载会闪一下"下载中 0%"
        // 才跳初始化，像是又下了一遍
        if (isModelDownloaded(modelInfo)) {
            Log.i(TAG, "downloadModel: already complete on disk, skip to initialize")
            _downloadProgress.value = Progress.Initializing
            return@withContext true
        }
        _state.value = EngineState.DOWNLOADING
        _downloadProgress.value = Progress.Downloading(0L, modelInfo.sizeBytes)
        showDownloadNotification(0f, "准备下载 ${modelInfo.displayName}")
        try {
            val dir = File(context.filesDir, MODELS_DIR_NAME)
            if (!dir.exists()) dir.mkdirs()

            // 磁盘空间预检：tarball 本体 + 解压产物（解压后通常比 bz2 大）+ 余量。
            // 空间不足时 fail-fast 给明确原因，而不是下到一半/解压到一半抛
            // 不可读的 IOException，还留下几十 MB 残片
            val usable = dir.usableSpace
            val needed = modelInfo.sizeBytes * 3
            if (usable > 0 && usable < needed) {
                val msg = "存储空间不足（需要约 ${needed / 1_000_000}MB，仅剩 ${usable / 1_000_000}MB）"
                Log.e(TAG, "downloadModel: $msg")
                _state.value = EngineState.DOWNLOAD_FAILED(msg)
                _downloadProgress.value = Progress.Failed(msg)
                cancelDownloadNotification()
                return@withContext false
            }

            // 优先路径：下载 GitHub release tarball 并解压（国内可达性优于 HuggingFace）
            if (modelInfo.tarballAllUrls().isNotEmpty()) {
                val ok = downloadAndExtractTarball(modelInfo, dir, onProgress)
                if (ok) {
                    Log.i(TAG, "downloadModel: tarball extracted + verified, initializing OfflineTts…")
                    _downloadProgress.value = Progress.Initializing
                    showDownloadCompleteNotification("下载完成，正在启用...")
                    return@withContext true
                }
                Log.w(TAG, "tarball 下载/解压失败，回退到逐文件下载")
                showDownloadNotification(0f, "回退到逐文件下载...")
            }

            // 仅归档模型（Piper 类：整目录树、无逐文件镜像）：
            // 归档失败即下载失败，不进逐文件路径（空 URL 只会逐个报错）
            if (modelInfo.files.all { f -> f.allUrls().all { it.isBlank() } }) {
                _state.value = EngineState.DOWNLOAD_FAILED("模型归档下载失败，请检查网络后重试")
                _downloadProgress.value = Progress.Failed("模型归档下载失败")
                cancelDownloadNotification()
                return@withContext false
            }

            // 回退路径：逐文件下载（HuggingFace，国内可能不可达）
            val totalSize = modelInfo.sizeBytes
            var downloadedTotal = 0L
            var lastNotifyMs = 0L
            var lastProgressEmitMs = 0L

            for (file in modelInfo.files) {
                val targetFile = File(dir, file.relativePath)
                val completeFile = File(dir, file.relativePath + COMPLETE_SUFFIX)
                targetFile.parentFile?.mkdirs()

                // 已完整下载则跳过
                if (completeFile.exists() && targetFile.exists() && targetFile.length() > 0) {
                    downloadedTotal += targetFile.length()
                    _downloadProgress.value = Progress.Downloading(downloadedTotal, totalSize)
                    onProgress(_downloadProgress.value.fractionOrZero())
                    continue
                }
                // 未完成的残片会计入已下载量：断点续传从残片长度继续，
                // 不把存量计入分子会让进度条在续传期间停滞、完成时突跳
                if (targetFile.exists() && targetFile.length() > 0) {
                    downloadedTotal += targetFile.length()
                }

                // 多镜像回退：依次尝试所有 URL，任一成功即可
                var fileOk = false
                for (url in file.allUrls()) {
                    val ok = downloadFileWithResume(
                        url = url,
                        target = targetFile,
                        onChunkDownloaded = { bytesRead, totalBytes ->
                            // bytesRead 是本批增量；totalBytes 是当前已下载（含 previous part）。
                            // 这里仍按"累计"口径算分母，与 onTotalSizeKnown 配合
                            downloadedTotal += bytesRead
                            val denom = if (totalSize > 0) totalSize else totalBytes
                            val now = System.currentTimeMillis()
                            // 状态流节流：每 8KB chunk 发射一次 = 66MB 模型 8000+ 次
                            // 发射、收集端每秒数百次重组；100ms 粒度对进度视觉无差别
                            if (now - lastProgressEmitMs >= 100) {
                                lastProgressEmitMs = now
                                val p = if (denom > 0) {
                                    (downloadedTotal.toFloat() / denom.toFloat()).coerceIn(0f, 1f)
                                } else 0f
                                _downloadProgress.value = Progress.Downloading(downloadedTotal, denom)
                                onProgress(p)
                                if (now - lastNotifyMs > 500) {
                                    lastNotifyMs = now
                                    showDownloadNotification(
                                        p,
                                        "${(p * 100).toInt()}% · ${file.relativePath.substringAfterLast('/')}",
                                    )
                                }
                            }
                            // totalBytes 在此回调里也只是参考值，留着供调试使用
                            @Suppress("UNUSED_VARIABLE")
                            val unusedTotal = totalBytes
                        },
                    )
                    if (ok && targetFile.length() > 0) {
                        completeFile.createNewFile()
                        fileOk = true
                        break
                    }
                    Log.w(TAG, "下载失败，尝试下一个镜像：$url")
                }
                if (!fileOk) {
                    _state.value = EngineState.DOWNLOAD_FAILED("下载失败：${file.relativePath}（所有镜像均不可用）")
                    _downloadProgress.value = Progress.Failed("下载失败：${file.relativePath}")
                    // 终态通知必须可划掉：showDownloadNotification 是 ongoing 的，
                    // 失败时留着一条划不掉的"下载失败"通知只能杀进程消失
                    cancelDownloadNotification()
                    return@withContext false
                }
            }
            _downloadProgress.value = Progress.Initializing
            showDownloadCompleteNotification("下载完成，正在启用...")
            true
        } catch (e: kotlinx.coroutines.CancellationException) {
            // 调用方取消（离开页面等）：清掉 ongoing 通知、复位状态后向上传播。
            // 旧实现状态流永远停在 DOWNLOADING，UI 显示"下载中"直到进程重启
            cancelDownloadNotification()
            _downloadProgress.value = Progress.Idle
            _state.value = EngineState.MODEL_NOT_FOUND
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "downloadModel failed", e)
            _state.value = EngineState.DOWNLOAD_FAILED(e.message ?: "未知错误")
            _downloadProgress.value = Progress.Failed(e.message ?: "下载失败")
            cancelDownloadNotification()
            false
        }
    }

    /** 当 sealed Progress 没有 fraction 时返回 0f；仅给 onProgress 兼容旧回调用 */
    private fun Progress.fractionOrZero(): Float = when (this) {
        is Progress.Downloading -> fraction
        is Progress.Extracting -> fraction
        Progress.Initializing -> 0.99f
        Progress.Completed -> 1f
        is Progress.Failed -> 0f
        Progress.Idle -> 0f
    }

    /**
     * 预扫与解压共用的条目过滤口径：只数会真正落盘的常规文件/目录，
     * 且落点必须在 modelsDir 内。两处口径不一致时（预扫把 symlink 也计入，
     * 解压循环却跳过），分母 > 分子 → 进度永远停在 99.x%。
     */
    private fun shouldCountTarEntry(
        entry: org.apache.commons.compress.archivers.tar.TarArchiveEntry,
        modelsDir: File,
        canonicalRoot: String,
    ): Boolean =
        (entry.isFile || entry.isDirectory) &&
            File(modelsDir, entry.name).canonicalPath.startsWith(canonicalRoot)

    /**
     * 下载 tarball 并解压到 models 目录。
     * tarball 内顶层目录应为模型 id（如 vits-piper-en_US-lessac-medium/），
     * 解压后路径与 files.relativePath 对齐。
     * 下载到临时文件（支持断点续传），解压成功后删除 tarball 并为每个文件写 .complete 标记。
     */
    private suspend fun downloadAndExtractTarball(
        modelInfo: ModelInfo,
        modelsDir: File,
        onProgress: (Float) -> Unit,
    ): Boolean {
        val tarballFile = File(modelsDir, "${modelInfo.id}.tar.bz2")
        val tarballComplete = File(modelsDir, "${modelInfo.id}.tar.bz2${COMPLETE_SUFFIX}")
        // 若已解压完成（所有文件 .complete 存在），直接返回
        if (modelInfo.files.all { f ->
                File(modelsDir, f.relativePath + COMPLETE_SUFFIX).exists() &&
                    File(modelsDir, f.relativePath).exists()
            }) {
            return true
        }

        val totalSize = modelInfo.sizeBytes
        var tarballTotalSize = 0L    // 响应 Content-Length，由 onTotalSizeKnown 回填
        var lastNotifyMs = 0L
        var lastProgressEmitMs = 0L

        // 下载 tarball（多镜像回退 + 断点续传）
        var downloaded = false
        for (url in modelInfo.tarballAllUrls()) {
            // 若已有完整 tarball 标记，跳过下载直接解压
            if (tarballComplete.exists() && tarballFile.exists() && tarballFile.length() > 0) {
                downloaded = true
                break
            }
            val ok = downloadFileWithResume(
                url = url,
                target = tarballFile,
                onChunkDownloaded = { _, totalBytes ->
                    // 按 tarball 自身大小算分母：用 Content-Length，否则用 modelInfo.sizeBytes
                    val denominator = if (tarballTotalSize > 0) tarballTotalSize else totalSize
                    val now = System.currentTimeMillis()
                    // 状态流节流（与逐文件路径同款）：8KB/chunk 全量发射是重组风暴
                    if (now - lastProgressEmitMs >= 100) {
                        lastProgressEmitMs = now
                        val p = if (denominator > 0) {
                            (totalBytes.toFloat() / denominator.toFloat()).coerceIn(0f, 1f)
                        } else 0f
                        _downloadProgress.value = Progress.Downloading(totalBytes, denominator)
                        onProgress(p)
                        if (now - lastNotifyMs > 500) {
                            lastNotifyMs = now
                            val downloadedMB = totalBytes / 1_000_000
                            val totalMB = if (denominator > 0) denominator / 1_000_000 else 0
                            showDownloadNotification(p, "下载中（${downloadedMB}/${totalMB}MB）")
                        }
                    }
                },
                onTotalSizeKnown = { totalBytes ->
                    if (totalBytes > 0) {
                        Log.i(TAG, "tarball total size from Content-Length: $totalBytes bytes")
                        tarballTotalSize = totalBytes
                    }
                },
            )
            if (ok && tarballFile.length() > 0) {
                tarballComplete.createNewFile()
                downloaded = true
                break
            }
            Log.w(TAG, "tarball 下载失败，尝试下一个镜像：$url")
        }
        if (!downloaded) {
            Log.e(TAG, "所有 tarball 镜像均不可用")
            return false
        }

        // 解压
        showDownloadNotification(null, "解压中...")
        Log.i(TAG, "extraction: starting, tarball=${tarballFile.length()} bytes at ${tarballFile.absolutePath}")
        // 立刻把阶段推到 Extracting，让 UI 立即切到"解压中"，不要在 Downloading(100%) 停住。
        // 1.3：不再预扫（旧 countTarEntries 会完整解压一遍 bzip2 数条目，白费一整个解压时长）。
        // 分母改用"解压放大系数 × 归档大小"估算总字节；末态强制分子对齐到 100%。
        val extractionStartMs = System.currentTimeMillis()
        // 估算解压总字节：tar.bz2 ~66MB → 解压 ~120MB（约 1.8 倍），取 1.5 倍保守留余地
        val estimatedTotalBytes = (modelInfo.sizeBytes * 3L) / 2L
        var totalExtractedBytes = 0L
        var lastExtractionEntry: String? = null
        var lastProgressPushMs = 0L
        _downloadProgress.value = Progress.Extracting(bytesDone = 0, bytesTotal = 1, currentEntryName = null, elapsedMs = 0)
        onProgress(0f)
        // 解压进度按字节节流：每 100ms 至多推一次；isFinal=true 强制推末态保证收敛。
        fun pushExtractionProgress(isFinal: Boolean = false) {
            val doneBytes = totalExtractedBytes.coerceAtLeast(0L)
            // 进行中分母恒比进度略大（estimated、至少 done+1），末态强制分子分母对齐，
            // 否则估算偏差会让最后一帧停在 99.x% 永不收敛
            val totalBytes = if (isFinal) maxOf(doneBytes, 1L) else maxOf(estimatedTotalBytes, doneBytes + 1)
            val now = System.currentTimeMillis()
            if (!isFinal && now - lastProgressPushMs < 100) return
            lastProgressPushMs = now
            _downloadProgress.value = Progress.Extracting(
                bytesDone = doneBytes,
                bytesTotal = totalBytes,
                currentEntryName = lastExtractionEntry,
                elapsedMs = now - extractionStartMs,
            )
            onProgress(_downloadProgress.value.fractionOrZero())
        }
        try {
            val tarballCanonical = tarballFile.canonicalPath
            Log.d(TAG, "extraction: opening BZip2+Tar streams on $tarballCanonical")
            val canonicalRoot = modelsDir.canonicalPath + File.separator
            var copiedSinceCheck = 0L
            // 解压性能优化：
            // 1. BufferedInputStream：commons-compress 的 BZip2 位读取器对底层流做
            //    大量小粒度 read，裸 FileInputStream 时每次都是一次 syscall；
            //    66MB 归档能放大成百万级系统调用，缓冲后归并成 256KB 级读取
            // 2. 复制缓冲整轮解压只分配一次：旧实现每个条目 new 一个 256KB 数组，
            //    espeak-ng-data 几百个小文件 = 几百次大对象分配的 GC 压力
            // 3. 已建目录 HashSet 缓存：跳过同目录连续文件重复 mkdirs 的 stat 调用
            // 4. 条目级 Log.d 撤掉（每文件 2-3 条 × 几百文件），只保留采样日志
            // 解压总量上限：正常归档解压后 ~120MB，256MB 上限防 bzip2 解压炸弹（1.3）
            java.io.BufferedInputStream(
                java.io.FileInputStream(tarballFile),
                EXTRACTION_IO_BUFFER_BYTES,
            ).use { bis ->
                BZip2CompressorInputStream(bis).use { bzis ->
                    TarArchiveInputStream(bzis).use { tis ->
                        val copyBuf = ByteArray(EXTRACTION_IO_BUFFER_BYTES)
                        val createdDirs = HashSet<String>()
                        var entryCount = 0
                        var entry = tis.nextEntry
                        while (entry != null) {
                            // 协作式取消：~100MB 归档的解压没有天然挂起点，
                            // 旧实现离开页面后还要解压几十秒并留下半截文件
                            copiedSinceCheck += entry.size.coerceAtLeast(0)
                            if (copiedSinceCheck >= 262144) {
                                copiedSinceCheck = 0
                                kotlin.coroutines.coroutineContext[kotlinx.coroutines.Job]
                                    ?.ensureActive()
                            }
                            val name = entry.name
                            // 安全：entry 名来自远端 CDN 归档，不可信。
                            // 只接受常规文件/目录（跳过符号链接/硬链接等特殊条目），
                            // 并用 canonical 路径校验落点必须在 modelsDir 内，
                            // 替代只查 ".." 子串的旧检查（会误杀 foo..bar、漏掉符号链接）。
                            // 过滤口径与预扫 countTarEntries 共用，分子分母不漂移
                            if (!shouldCountTarEntry(entry, modelsDir, canonicalRoot)) {
                                entry = tis.nextEntry
                                continue
                            }
                            val outFile = File(modelsDir, name)
                            lastExtractionEntry = name
                            if (entry.isDirectory) {
                                outFile.mkdirs()
                                createdDirs.add(outFile.canonicalPath)
                            } else {
                                val parentPath = outFile.parentFile?.canonicalPath
                                if (parentPath != null && createdDirs.add(parentPath)) {
                                    outFile.parentFile?.mkdirs()
                                }
                                java.io.BufferedOutputStream(
                                    FileOutputStream(outFile),
                                    64 * 1024,
                                ).use { out ->
                                    var fileBytes = 0L
                                    while (true) {
                                        val n = tis.read(copyBuf)
                                        if (n == -1) break
                                        out.write(copyBuf, 0, n)
                                        fileBytes += n
                                        copiedSinceCheck += n
                                        totalExtractedBytes += n
                                        if (totalExtractedBytes > MAX_EXTRACT_BYTES) {
                                            throw java.io.IOException(
                                                "归档解压总量超过 ${MAX_EXTRACT_BYTES / 1_000_000}MB，已中止（疑似损坏归档）",
                                            )
                                        }
                                        if (copiedSinceCheck >= 262144) {
                                            copiedSinceCheck = 0
                                            kotlin.coroutines.coroutineContext[kotlinx.coroutines.Job]
                                                ?.ensureActive()
                                        }
                                    }
                                }
                            }
                            // 解压进度按字节推进（已解压字节在上层累计），每个条目推一次，
                            // pushExtractionProgress 按 100ms 节流
                            pushExtractionProgress(isFinal = false)
                            entryCount++
                            if (entryCount == 1 || entryCount % 100 == 0) {
                                Log.d(
                                    TAG,
                                    "extraction: $entryCount entries done, " +
                                        "$totalExtractedBytes bytes so far",
                                )
                            }
                            entry = tis.nextEntry
                        }
                        // 流结束：强制推一次末态，保证 UI 看到解压 100%
                        pushExtractionProgress(isFinal = true)
                        Log.i(
                            TAG,
                            "extraction: tar stream fully consumed ($entryCount entries), " +
                                "took ${(System.currentTimeMillis() - extractionStartMs) / 1000}s",
                        )
                    }
                }
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            // 被取消：清掉半截解压产物和 tarball，避免下次误用残文件
            Log.w(TAG, "extraction cancelled, cleaning partial files")
            cleanExtractionPartials(modelsDir, modelInfo, tarballFile, tarballComplete)
            cancelDownloadNotification()
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "解压失败", e)
            // 删掉损坏的 tarball、完成标记与半截解压产物：否则下次进来标记检查
            // 直接跳过下载、反复解压同一个坏归档，失败回退路径还可能让
            // ~100MB tarball 与几十 MB 残片（espeak-ng-data 半拉子目录）永久驻盘
            cleanExtractionPartials(modelsDir, modelInfo, tarballFile, tarballComplete)
            cancelDownloadNotification()
            return false
        }

        // 为所有文件写 .complete 标记
        Log.i(TAG, "extraction: verifying ${modelInfo.files.size} files from manifest")
        for (f in modelInfo.files) {
            val target = File(modelsDir, f.relativePath)
            // 目录：存在即可；文件：存在且非空
            val ok = if (target.isDirectory) target.exists() else target.exists() && target.length() > 0
            if (ok) {
                File(modelsDir, f.relativePath + COMPLETE_SUFFIX).createNewFile()
            } else {
                Log.e(TAG, "解压后文件缺失或为空：${f.relativePath}")
                // 归档缺文件：同样清掉 tarball 与残片，强制下次重新下载而不是重复解压
                cleanExtractionPartials(modelsDir, modelInfo, tarballFile, tarballComplete)
                return false
            }
        }

        // 删除 tarball 释放空间
        tarballFile.delete()
        tarballComplete.delete()
        return true
    }

    /**
     * 解压失败/取消/校验缺文件时的统一清理：
     * 删 tarball + 完成标记 + manifest 声明的全部产物。
     *
     * 用 deleteRecursively 而不是 delete()：Piper 的 espeak-ng-data 是几百个
     * 文件的目录树，File.delete() 对非空目录静默失败，会留下最多 ~30MB 残片
     * 白占存储（没有 .complete 标记不会误用，但空间泄漏）。
     */
    private fun cleanExtractionPartials(
        modelsDir: File,
        modelInfo: ModelInfo,
        tarballFile: File,
        tarballComplete: File,
    ) {
        modelInfo.files.forEach { f ->
            File(modelsDir, f.relativePath).let { if (it.exists()) it.deleteRecursively() }
            File(modelsDir, f.relativePath + COMPLETE_SUFFIX).let { if (it.exists()) it.delete() }
        }
        if (tarballFile.exists()) tarballFile.delete()
        if (tarballComplete.exists()) tarballComplete.delete()
    }

    /**
     * 带断点续传的文件下载。
     * 若 target 已存在部分内容，通过 Range: bytes=offset- 请求续传。
     * 服务器不支持 Range 时回退为全量覆盖下载。
     */
    /**
     * 构造一个走指定 Proxy 的 HttpURLConnection（仅构造不连接；调用方负责
     * 设置超时并 connect()）。失败时 catch 后由调用方决定切下一条 proxy。
     */
    private fun openConnection(url: String, proxy: java.net.Proxy?): HttpURLConnection {
        val u = URL(url)
        return (if (proxy != null) u.openConnection(proxy) else u.openConnection()) as HttpURLConnection
    }

    private suspend fun downloadFileWithResume(
        url: String,
        target: File,
        onChunkDownloaded: (Int, Long) -> Unit,
        onTotalSizeKnown: ((Long) -> Unit)? = null,
    ): Boolean {
        val existingLen = if (target.exists()) target.length() else 0L
        // 构造候选 proxy 列表：先系统代理，后常见端口兜底，最后 None（直连）
        val candidateProxies = buildProxyCandidates(url)
        var lastError: Throwable? = null
        for ((idx, p) in candidateProxies.withIndex()) {
            var conn: HttpURLConnection? = null
            // 是否已把连接交接给 streamResponse：交接后由 fullStream/appendStream
            // 关闭流，finally 里不再 disconnect
            var handedOff = false
            try {
                conn = openConnection(url, p)
                conn.connectTimeout = 8_000
                conn.readTimeout = 120_000
                conn.doInput = true
                conn.instanceFollowRedirects = false   // 关键：禁用自动跟随，自己手动跟，
                                                       // 否则 followRedirect 会丢失 Proxy
                conn.setRequestProperty("Connection", "close")
                conn.setRequestProperty("User-Agent", "eareyereading/1.0 Android")
                if (existingLen > 0) conn.setRequestProperty("Range", "bytes=$existingLen-")
                conn.connect()
                val code = conn.responseCode
                Log.i(TAG, "download: candidate #$idx (proxy=${describeProxy(p)}) responded HTTP $code for $url")
                when {
                    code in 200..299 || code == 206 -> {
                        // 拿到响应 Content-Length 写到外面闭包的引用，供上层计算分母
                        val contentLen = conn.getHeaderField("Content-Length")?.toLongOrNull() ?: -1L
                        handedOff = true
                        return streamResponse(
                            conn, code, contentLen, existingLen, target,
                            onChunkDownloaded, onTotalSizeKnown,
                        )
                    }
                    code in 300..399 -> {
                        // 手动跟随重定向：复用同一个 Proxy；只跟一次以避免循环
                        val loc = conn.getHeaderField("Location")
                        Log.w(TAG, "download: HTTP $code -> Location: $loc")
                        conn.disconnect()
                        if (loc == null) return false
                        val nextUrl = if (loc.startsWith("http")) loc else {
                            // 相对路径重定向：拼成绝对 URL
                            val base = URL(url)
                            URL(base, loc).toString()
                        }
                        // 重定向 1 次，递归走一次 candidate 循环（不递归函数，避免栈深）
                        val redirectedConn = openConnection(nextUrl, p)
                        redirectedConn.connectTimeout = 8_000
                        redirectedConn.readTimeout = 120_000
                        redirectedConn.doInput = true
                        redirectedConn.instanceFollowRedirects = false
                        redirectedConn.setRequestProperty("Connection", "close")
                        redirectedConn.setRequestProperty("User-Agent", "eareyereading/1.0 Android")
                        if (existingLen > 0) redirectedConn.setRequestProperty("Range", "bytes=$existingLen-")
                        redirectedConn.connect()
                        val redirectedCode = redirectedConn.responseCode
                        Log.i(TAG, "download: redirected (via same proxy) -> HTTP $redirectedCode")
                        conn = redirectedConn
                        if (redirectedCode in 200..299 || redirectedCode == 206) {
                            val contentLen = redirectedConn.getHeaderField("Content-Length")?.toLongOrNull() ?: -1L
                            handedOff = true
                            return streamResponse(
                                redirectedConn, redirectedCode, contentLen, existingLen, target,
                                onChunkDownloaded, onTotalSizeKnown,
                            )
                        }
                        if (redirectedCode == 416) {
                            // 与下方 416 分支同理：残片已失效，删掉让下个候选从头下
                            Log.w(TAG, "download: HTTP 416 after redirect, deleting stale partial ${target.name}")
                            target.delete()
                            return false
                        }
                        lastError = RuntimeException("HTTP $redirectedCode after redirect")
                    }
                    code == 416 -> {
                        // Range 不可满足：本地残片比服务端资源还长或已失效。
                        // 删掉残片并短路返回——不删的话每个候选都会带着同一个
                        // 越界 Range 再 416 一次，死循环浪费所有镜像
                        Log.w(TAG, "download: HTTP 416, deleting stale partial ${target.name} (${existingLen}B)")
                        target.delete()
                        return false
                    }
                    else -> {
                        Log.e(TAG, "downloadFile: HTTP $code for $url via ${describeProxy(p)}")
                        // 4xx 不重试（除非 408 timeout）；5xx 下一条候选
                        if (code in 400..499 && code != 408) return false
                        lastError = RuntimeException("HTTP $code")
                    }
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "download: candidate #$idx (${describeProxy(p)}) failed: ${e.javaClass.simpleName}: ${e.message}")
                lastError = e
            } finally {
                // 没交接出去的连接统一断开；已交接给 streamResponse 的由
                // fullStream/appendStream 关流。
                // 旧实现用 conn.inputStream != null 探测——getInputStream() 有副作用
                // （部分实现在此时就开始读 socket buffer），可能把 200/206 响应的
                // 头几个字节提前消费掉，导致下载体残缺、解压必败且重试也失败
                if (!handedOff) try { conn?.disconnect() } catch (_: Exception) {}
            }
        }
        Log.e(TAG, "download: all ${candidateProxies.size} candidates failed for $url", lastError)
        return false
    }

    /**
     * 构造候选 proxy 列表：
     *   [0] = ProxySelector 系统代理（如有）
     *   [1..] = 127.0.0.1 常见端口 [7897, 7892, 7890, 1080, 8888]
     *   [last] = null（直连，urlObj.openConnection() 不带 Proxy）
     *
     * 实测踩坑：MIUI 全局代理经常指向 7892 而不是 7897；批量探测可绕开
     * "代理存在但端口不对"的问题。下载主循环会以 connect 成功判定 probe 成功。
     */
    private fun buildProxyCandidates(url: String): List<java.net.Proxy?> {
        val out = mutableListOf<java.net.Proxy?>()
        val uri = try { URL(url).toURI() } catch (_: Exception) { null }
        if (uri != null) {
            try {
                java.net.ProxySelector.getDefault()?.select(uri)?.forEach { out.add(it) }
            } catch (_: Exception) { /* 吞 */ }
        }
        intArrayOf(7897, 7892, 7890, 1080, 8888).forEach { port ->
            out.add(java.net.Proxy(java.net.Proxy.Type.HTTP, java.net.InetSocketAddress("127.0.0.1", port)))
        }
        out.add(null)  // 直连
        return out
    }

    private fun describeProxy(p: java.net.Proxy?): String =
        if (p == null) "DIRECT" else "HTTP@${p.address()}"

    /**
     * 按响应码把响应体写入 target：
     *  - 206 + 本地有残片 → Content-Range 校验后 append 续传；
     *  - 200（服务器忽略 Range）→ 全量覆盖（fullStream 以 truncate 模式打开）。
     *
     * 此前 206 也走 fullStream（append=false）：本地残片被截断后只写入尾部
     * 字节，产出"只有后半截"的损坏文件，length>0 又骗过完成校验 → 必解压
     * 失败。Content-Length 统一折算成"完整文件总长"汇报，让上层进度分母稳定。
     */
    private suspend fun streamResponse(
        conn: HttpURLConnection,
        code: Int,
        contentLen: Long,
        existingLen: Long,
        target: File,
        onChunkDownloaded: (Int, Long) -> Unit,
        onTotalSizeKnown: ((Long) -> Unit)?,
    ): Boolean {
        return if (code == 206 && existingLen > 0) {
            // 续传：Content-Length 只是剩余字节数，总长要加上本地已有部分
            onTotalSizeKnown?.invoke(if (contentLen > 0) existingLen + contentLen else -1L)
            appendStream(conn, target, existingLen, onChunkDownloaded)
        } else {
            if (existingLen > 0) {
                Log.i(TAG, "server ignored Range (HTTP $code), full re-download of ${target.name}")
            }
            onTotalSizeKnown?.invoke(if (contentLen > 0) contentLen else -1L)
            fullStream(conn, target, onChunkDownloaded)
        }
    }

    /**
     * 续传流（append）：服务器返回 206 时从 existingLen 处继续写入。
     * Content-Range 校验失败说明本地残片与服务端资源对不上（过期/损坏），
     * 直接删掉残片返回 false，让后续候选走全量下载而不是叠加错数据。
     */
    private suspend fun appendStream(
        conn: HttpURLConnection,
        target: File,
        existingLen: Long,
        onChunkDownloaded: (Int, Long) -> Unit,
    ): Boolean {
        val contentRange = conn.getHeaderField("Content-Range")
        val start = contentRange
            ?.substringAfter("bytes ", "")
            ?.substringBefore("-")
            ?.toLongOrNull()
        if (start == null || start != existingLen) {
            Log.w(TAG, "Content-Range mismatch (start=$start, existing=$existingLen), deleting partial and aborting resume")
            // 残片与远端对不上（重发包/远端更新）：继续保留只会让后续候选
            // 带着同样的 existingLen 反复 mismatch，删掉让下轮全量重来。
            // 响应体未被消费，外层 finally 的 inputStream 探测在 206 上会成功
            // （opened=true 不断开）——这里显式 disconnect，别留给 GC
            target.delete()
            try { conn.disconnect() } catch (_: Exception) {}
            return false
        }
        return try {
            conn.inputStream.use { input ->
                java.io.FileOutputStream(target, /* append = */ true).use { output ->
                    // 256KB 缓冲：66MB 模型 tarball 旧 8KB 缓冲要 8000+ 次
                    // read/write 系统调用 + 8000+ 次进度回调闭包，纯 CPU 浪费
                    val buffer = ByteArray(262144)
                    var sinceCheck = 0
                    var totalRead = existingLen
                    while (true) {
                        val read = input.read(buffer)
                        if (read == -1) break
                        output.write(buffer, 0, read)
                        totalRead += read
                        onChunkDownloaded(read, totalRead)
                        sinceCheck += read
                        if (sinceCheck >= 262144) {
                            sinceCheck = 0
                            kotlin.coroutines.coroutineContext[kotlinx.coroutines.Job]
                                ?.ensureActive()
                        }
                    }
                }
            }
            true
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "appendStream failed", e)
            false
        }
    }

    private suspend fun fullStream(
        conn: HttpURLConnection,
        target: File,
        onChunkDownloaded: (Int, Long) -> Unit,
    ): Boolean {
        return try {
            Log.i(TAG, "fullStream: opening input stream from $conn")
            conn.inputStream.use { input ->
                java.io.FileOutputStream(target, /* append = */ false).use { output ->
                    // 256KB 缓冲：与 appendStream 同款，理由见彼处注释
                    val buffer = ByteArray(262144)
                    var totalRead = 0L
                    var sinceCheck = 0L
                    var lastTraceMs = 0L
                    while (true) {
                        val read = input.read(buffer)
                        if (read == -1) {
                            Log.i(TAG, "fullStream: EOF, totalBytes=$totalRead")
                            break
                        }
                        output.write(buffer, 0, read)
                        totalRead += read
                        // 把本 batch 的字节数 + 累计字节吐给上层，让上层算 progress 分母
                        onChunkDownloaded(read, totalRead)
                        // 每 500ms 打一次 trace：能看到 byte stream 真在流
                        val now = System.currentTimeMillis()
                        if (now - lastTraceMs > 500) {
                            lastTraceMs = now
                            Log.d(TAG, "fullStream: progress ${totalRead / 1024}KB")
                        }
                        // 同 appendStream：周期性响应协程取消。
                        // 旧实现用 totalRead % 262144 < read 概率探测，末尾
                        // chunk 越小命中概率越低（<1KB 时 ~0.4%），最后 256KB
                        // 基本不响应取消——改 sinceCheck 累加器（与 appendStream 一致）
                        sinceCheck += read
                        if (sinceCheck >= 262144L) {
                            sinceCheck = 0
                            kotlin.coroutines.coroutineContext[kotlinx.coroutines.Job]
                                ?.ensureActive()
                        }
                    }
                }
            }
            true
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "fullStream failed", e)
            false
        }
    }

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
                val config = OfflineTtsConfig(
                    model = modelConfig,
                    ruleFsts = ruleFsts,
                    // maxNumSentences：native 端一次 generate 内并行合成的最大句子数。
                    // 默认 1 = 逐句串行，每次 generate 有 ~2s 固定开销，5 句 = 10s。
                    // 设为 4：native 端把输入按句切分后并行合成，固定开销摊薄，
                    // 整段朗读速度显著提升。回调仍按句触发，流式出声不受影响。
                    maxNumSentences = 4,
                )
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
     *
     * **预热形状必须匹配真实负载**：用 [WARMUP_TEXT]（~90 字符，与首块
     * 同规模）。短句预热（如 "Ok."）只把 arena 内存池长到小句规模，
     * 真实长句推理时大额扩张与缺页成本会重现，预热近乎无效。
     *
     * 锁语义：tryLock 立即返回——拿不到锁说明有真实朗读正在合成
     * （它自己就会完成预热，[doSpeakQueueLocked] 成功后同样置位），
     * 本次预热直接放弃，绝不排在用户请求后面反向增加首声延迟；
     * 预热期间新来的真实请求会挂在 mutex 上等预热结束，但预热剩余
     * 时间 ≤ 无预热时该请求自己要付的冷启动时间，只会更快不会更慢。
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
            try {
                // 与真实朗读同一代码路径（generateWithCallback + sid），
                // 确保 ONNX 会话/内存池/线程池全部被预热
                engine.generateWithCallback(WARMUP_TEXT, sid = sid, speed = 1.0f) { _ -> 1 }
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
        val player = StreamingTrackPlayer(currentTts.sampleRate())
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
                    for ((idx, raw) in rawSentences.withIndex()) {
                        if (raw.isBlank()) continue
                        // 每句之前检查协程是否已被取消（stop() 调用）
                        kotlinx.coroutines.yield()
                        if (speakJob?.isActive == false) {
                            throw kotlinx.coroutines.CancellationException("stop() requested")
                        }
                        val cleaned = if (isKokoro) preprocessForTtsLight(raw) else preprocessForTts(raw)
                        // 清洗后无内容（如 Piper 把短中文段过滤成空白）：跳过且不计入
                        // 熔断失败数——与旧实现（先清洗再过滤空白句）语义一致
                        if (cleaned.isBlank()) continue
                        val framesBeforeSentence = player.framesOffered
                        for (sub in splitSentences(cleaned)) {
                            // 单句仍可能超长（超长标题/无标点中文长段）：切成 ≤MAX_CHUNK_CHARS
                            // 字符的块逐块合成，旧实现 substring(0,150) 直接丢弃 150 字符后的
                            // 全部内容。块长从 150 收紧到 80：块内无句末标点时 sherpa 内部
                            // 按句切分的回调不触发，整块合成完才回调——块越长首声延迟越大
                            for (chunk in hardChunks(sub, MAX_CHUNK_CHARS)) {
                                val framesBeforeChunk = player.framesOffered
                                // 预合成缓存命中（单词弹窗 selectWord 时后台预合成）：
                                // 跳过 generate 直接播缓存 PCM——Kokoro 每次 generate 有
                                // ~2s 固定开销（与文本长度无关），单词现场合成必然卡
                                val cachedPcm = pcmCache.get(cacheKey(chunk, sid, speed))
                                if (cachedPcm != null) {
                                    if (speakJob?.isActive != false) {
                                        player.offer(cachedPcm)
                                        Log.i(
                                            TAG,
                                            "Embedded TTS chunk from cache: len=${chunk.length}, " +
                                                "samples=${cachedPcm.size}",
                                        )
                                    }
                                } else {
                                val audio = try {
                                    currentTts.generateWithCallback(chunk, sid = sid, speed = speed) { samples ->
                                        // 返回 1 继续合成；协程已取消时返回 0 让 native 立即中止
                                        if (speakJob?.isActive == false) 0 else player.offer(samples)
                                    }
                                } catch (e: kotlinx.coroutines.CancellationException) {
                                    throw e
                                } catch (e: Exception) {
                                    // 单块 generate 崩溃（如 native G2P bug）：跳过该块，继续
                                    Log.e(TAG, "chunk generate failed, skipping: '${chunk.take(60)}'", e)
                                    null
                                }
                                // 兜底与日志均以 framesOffered（含预缓冲 pending 里的帧）为基准：
                                // 预缓冲期间 framesWritten 恒为 0，若用它判断"一帧未写"会把
                                // 首块音频在兜底路径重复 offer 一遍（声音重叠）
                                // 链已被 stop() 取消时必须禁用兜底：回调被取消检查挡住
                                // （返回 0 中止合成）并不代表"JNI 回调静默失效"，此时整段
                                // 补写会让一条已停止的链在数秒后突然出声——真机表现为
                                // "点了停止，几秒后突然又开始读"，且与用户随后启动的新链
                                // 叠音（2026-09-05 顶栏两播报按钮"冲突"的机理）
                                if (audio != null && player.framesOffered == framesBeforeChunk &&
                                    audio.samples.isNotEmpty() && speakJob?.isActive != false
                                ) {
                                    // 兜底：JNI 回调静默失效（一帧未写）时整段补写，保证有声
                                    player.offer(audio.samples)
                                }
                                }
                                if (player.framesOffered > framesBeforeChunk) {
                                    // 真实合成成功 = 本模型的首次推理开销已被消化，
                                    // 与 warmUp() 的置位语义一致（幂等，@Volatile 写）
                                    warmedUpModelId = currentModelName
                                    Log.i(
                                        TAG,
                                        "Embedded TTS chunk queued: len=${chunk.length}, " +
                                            "samples=${player.framesOffered - framesBeforeChunk}, " +
                                            "totalFrames=${player.framesOffered}",
                                    )
                                }
                            }
                        }
                        if (player.framesOffered > framesBeforeSentence) {
                            consecutiveFailures = 0
                            if (onSentenceDone != null) {
                                // 句完成水位 = 该句最后一帧入队位置。用 framesOffered（含
                                // 预缓冲 pending 里的帧）而非 framesWritten：预缓冲期间
                                // framesWritten 恒为 0，首句水位会立即被 head≥0 满足，
                                // 音频还没播就回调"句完成"——不超前语义被破坏
                                pendingWatermarks.add(player.framesOffered to idx)
                            }
                        } else {
                            consecutiveFailures++
                            if (consecutiveFailures >= 3) {
                                Log.e(
                                    TAG,
                                    "3 consecutive sentence failures — aborting speak (model likely broken)",
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
     * 流式播放器：一条朗读链专用一条 MODE_STREAM AudioTrack。
     *
     * sherpa-onnx 的 generateWithCallback 每合成完一小段（内部按句）就回调一次，
     * 采样转 16-bit PCM 后直接写入流式 AudioTrack 立即出声——后续小段还在
     * 合成时本段已在播放，把"按下朗听到听见声音"的等待从整段合成时间缩短到
     * 首小段合成时间。
     *
     * 预缓冲：首次 [offer] 不立即开播，先把采样攒进 [pending] 队列，累计达到
     * [PREBUFFER_SECONDS]（约 0.8 秒）才建轨、把 pending 一次性写入硬件并 play()
     * ——旧实现零缓冲垫开播，合成稍有抖动就 underrun（AudioTrack 被系统禁用 +
     * restartIfDisabled 重启伴随百毫秒级 binder 停顿，听感为卡顿）。0.8 秒远小于
     * 首块合成时间（数秒级），首声延迟几乎不受影响。
     *
     * 线程模型：[offer] 由 JNI 回调在合成线程（与 generateWithCallback 同线程）
     * 调用，阻塞写提供天然背压（缓冲写满时等硬件消费，合成永不跑飞内存）；
     * [awaitWatermark]/[currentHead] 由水位监视协程轮询。与 stop() 的互斥靠
     * audioTrack 字段 + audioTrackLock：stop() 释放并置空字段后，写失败/
     * 水位检查发现轨道已死并快速中止。pending 队列同样仅合成线程读写，
     * 无需加锁；stop()/取消路径下随 player 整体丢弃，不泄漏。
     */
    private inner class StreamingTrackPlayer(private val sampleRate: Int) {

        /** 已写入硬件的帧数（水位基准）；仅合成线程写，监视协程读快照 */
        var framesWritten: Long = 0L
            private set

        /**
         * 已接受的帧总数（含仍在预缓冲 [pending] 队列、尚未写入硬件的帧）。
         * 仅合成线程写。chunk 兜底判断与句完成水位用它而非 [framesWritten]：
         * 预缓冲期间 framesWritten 恒为 0，用它会把首块误判为"一帧未写"
         * （兜底路径重复 offer → 声音重叠）、把首句水位提前满足（音频没播
         * 就回调句完成）
         */
        var framesOffered: Long = 0L
            private set

        private var track: AudioTrack? = null

        /** 轨道已损坏（构建/播放/写入失败）：后续 offer 拒绝 */
        @Volatile
        private var broken = false

        /**
         * 预缓冲 pending 队列：开播前攒够 [prebufferFrames] 的 16-bit PCM。
         * 仅合成线程（offer/drain）读写，无需加锁；stop()/取消路径下随
         * player 整体丢弃，不存在泄漏
         */
        private val pending = ArrayDeque<ShortArray>()

        /** pending 队列里尚未写入硬件的帧数 */
        private var pendingFrames = 0L

        /** 开播前的预缓冲目标帧数（约 [PREBUFFER_SECONDS] 秒音频，按轨道采样率计） */
        private val prebufferFrames = (TRACK_SAMPLE_RATE * PREBUFFER_SECONDS).toLong()

        /**
         * 源采样率（模型输出，[sampleRate]）→ 轨道采样率（[TRACK_SAMPLE_RATE]）
         * 的线性插值上采样。见 TRACK_SAMPLE_RATE 注释：24kHz 流在 MIUI 上
         * mixer 不消费，必须按设备原生率 48k 建轨。速率相等时原样返回。
         */
        private fun upsampleToTrackRate(src: FloatArray): FloatArray {
            if (sampleRate == TRACK_SAMPLE_RATE) return src
            val ratio = TRACK_SAMPLE_RATE.toDouble() / sampleRate
            val dstLen = (src.size * ratio).toInt()
            if (dstLen <= 0) return FloatArray(0)
            val dst = FloatArray(dstLen)
            for (i in 0 until dstLen) {
                val pos = i / ratio
                val idx = pos.toInt()
                val frac = (pos - idx).toFloat()
                val a = if (idx < src.size) src[idx] else 0f
                val b = if (idx + 1 < src.size) src[idx + 1] else a
                dst[i] = a + (b - a) * frac
            }
            return dst
        }

        /**
         * 写入一段采样（[-1,1] Float → 16-bit PCM）。在 JNI 回调内调用，
         * 绝不能抛异常（会穿过 JNI 边界变成 pending exception 破坏后续调用）。
         * @return 1 继续合成；0 立即中止合成（轨道坏/写入失败）
         */
        fun offer(samples: FloatArray): Int {
            if (broken || samples.isEmpty()) return 1
            val resampled = upsampleToTrackRate(samples)
            val pcm16 = ShortArray(resampled.size) { i ->
                (resampled[i] * 32767f).toInt().coerceIn(-32768, 32767).toShort()
            }
            framesOffered += pcm16.size
            val t = track
            if (t != null) {
                // 已开播：直接写硬件（pending 必已清空，单写者不变式）
                return if (writePcm(t, pcm16)) 1 else 0
            }
            // 未开播：先入 pending 攒预缓冲
            pending.addLast(pcm16)
            pendingFrames += pcm16.size
            if (pendingFrames < prebufferFrames) return 1
            // 攒够预缓冲：建轨 → play() → pending 一次性写入硬件。
            // 顺序说明（2026-09-05 19:34 日志定案）：PERFORMANCE_MODE_LATENCY
            // 下 AudioTrack 走 fast track 路径（AUDIO_OUTPUT_FLAG_FAST），
            // fast track FIFO 很小，play() 前写数据会阻塞/只写少量到 FIFO，
            // play() 后只播 FIFO 里的数据就 underrun（head=16704/36998，
            // 只播 45%）。先 play() 让硬件开始消费，再写数据，write() 的
            // 阻塞由硬件消费驱动，数据能持续流入。
            logPlaybackDiagnostics()
            val newTrack = buildTrack() ?: return 0
            if (startTrack(newTrack) == 0) return 0
            return if (drainPending(newTrack)) 1 else 0
        }

        /**
         * 整条链生成完毕时调用（[doSpeakQueueLocked] 的 for 循环结束后、
         * awaitWatermark 之前）：整链音频总量不足预缓冲阈值时全部帧还在
         * pending 里、轨道从未开播——冲刷出去并开播，否则最后一段静音丢失。
         * 已开播（pending 必空）或整链无音频时是空操作。
         */
        fun flushPendingAndPlay() {
            if (broken) {
                pending.clear()
                pendingFrames = 0L
                return
            }
            if (pendingFrames == 0L) return
            logPlaybackDiagnostics()
            val newTrack = buildTrack() ?: return
            if (!drainPending(newTrack)) return
            startTrack(newTrack)
        }

        /**
         * 播放诊断（2026-09-05 "AudioTrack start 成功但扬声器无声"定位用）：
         * 一次开播打一条，三个字段各自排除一类根因——
         *   peak=0        → PCM 数据本身是静音（NaN/全零转换结果），合成/缓存层问题；
         *   musicVol=0    → 媒体音量为 0（音量键在无媒体播放时调的是铃声音量）；
         *   以上正常但 awaitWatermark 的 head 不动 → 硬件不消费（焦点/路由/系统策略）。
         */
        private fun logPlaybackDiagnostics() {
            var peak = 0
            for (chunk in pending) {
                for (s in chunk) {
                    val v = kotlin.math.abs(s.toInt())
                    if (v > peak) peak = v
                }
            }
            val vol = audioManager?.getStreamVolume(AudioManager.STREAM_MUSIC) ?: -1
            val volMax = audioManager?.getStreamMaxVolume(AudioManager.STREAM_MUSIC) ?: -1
            // mode（0=NORMAL/1=RINGTONE/2=IN_CALL/3=IN_COMMUNICATION）：后台挂着
            // 微信语音/电话时媒体流会被系统静音或路由听筒——head=0 无声的
            // 高频环境根因；outputs 看实际路由（是否真到扬声器）
            val mode = audioManager?.mode ?: -1
            val speakerOn = audioManager?.isSpeakerphoneOn
            val musicActive = audioManager?.isMusicActive
            val outputDevices = try {
                audioManager?.getDevices(AudioManager.GET_DEVICES_OUTPUTS)?.toList() ?: emptyList()
            } catch (e: Exception) {
                emptyList()
            }
            val outputs = outputDevices.joinToString { "${it.type}:${it.productName}" }
            // A2DP/蓝牙设备路由检测：type 7=A2DP, 8=SCO, 26=HEARING_AID, 27=BLE_SPEAKER
            // 蓝牙手表（如华为 Watch 3 Pro）连着但无扬声器/休眠时，AudioTrack 写入
            // 成功、PLAYING，但 mixer 恒不消费（head=0）——2026-09-05 18:02 日志定案
            val hasBtOutput = outputDevices.any {
                it.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP ||
                    it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO ||
                    it.type == AudioDeviceInfo.TYPE_HEARING_AID ||
                    it.type == AudioDeviceInfo.TYPE_BLE_SPEAKER
            }
            if (hasBtOutput) {
                Log.w(
                    TAG,
                    "TTS routed to Bluetooth device (likely not consuming): " +
                        "outputs=[$outputs]. If head stays 0, will try forcing speaker.",
                )
            }
            Log.i(
                TAG,
                "TTS playback diag: pcmPeak=$peak, musicVol=$vol/$volMax, mode=$mode, " +
                    "speakerOn=$speakerOn, musicActive=$musicActive, outputs=[$outputs], " +
                    "pendingFrames=$pendingFrames, srcRate=$sampleRate, trackRate=$TRACK_SAMPLE_RATE",
            )
        }

        /** 当前硬件已播帧数；轨道未建/已被外部接管（stop()）返回 -1。 */
        fun currentHead(): Long {
            val t = track ?: return -1L
            return synchronized(audioTrackLock) {
                if (audioTrack !== t) -1L else t.playbackHeadPosition.toLong()
            }
        }

        /**
         * 轨道是否已被外部接管（stop() 释放了 audioTrack 字段）。
         * 轨道尚未建立时返回 false——生成还在进行，稍后会有音频写入，
         * 水位监视必须继续等待而不是退出。
         */
        fun isTrackTakenOver(): Boolean {
            val t = track ?: return false
            return synchronized(audioTrackLock) { audioTrack !== t }
        }

        /**
         * 等待水位（已播帧数 ≥ [frames]）。轨道被外部接管（stop() 释放）时
         * 返回 false；自然排空返回 true。
         */
        suspend fun awaitWatermark(frames: Long): Boolean {
            val t = track ?: return frames <= 0L
            var lastLogMs = 0L
            // MIUI/HyperOS workaround：play() 后 mixer 可能不消费（head 恒 0）。
            // 检测到该现象持续 >1.5s 时重新 play() 一次——实测部分 MIUI 版本
            // 二次 play 能激活 mixer 消费（首次 play 被低功耗策略拦截）。
            // 重试上限 2 次，避免无限循环；每次重试间隔 1.5s。
            var stillSinceMs = System.currentTimeMillis()
            var replayAttempts = 0
            val maxReplayAttempts = 2
            val replayThresholdMs = 1500L
            while (true) {
                kotlinx.coroutines.delay(20)
                val head = synchronized(audioTrackLock) {
                    if (audioTrack !== t) return false
                    t.playbackHeadPosition.toLong()
                }
                // 播放诊断（临时）：head 不增长 = 硬件不消费（焦点/路由/音量问题），
                // 与 pcmPeak/musicVol 组合可三分定位"start 成功但无声"
                val now = System.currentTimeMillis()
                if (now - lastLogMs > 500) {
                    lastLogMs = now
                    Log.d(TAG, "awaitWatermark: head=$head/$frames, playState=${t.playState}")
                }
                if (head >= frames) return true
                // head 增长说明 mixer 已开始消费，重置计时
                if (head > 0) {
                    stillSinceMs = now
                } else if (now - stillSinceMs > replayThresholdMs && replayAttempts < maxReplayAttempts) {
                    replayAttempts++
                    Log.w(TAG, "awaitWatermark: head stuck at 0 for ${now - stillSinceMs}ms, replay attempt $replayAttempts/$maxReplayAttempts")
                    // 首次重试：尝试强制切扬声器（绕过蓝牙 A2DP 路由）
                    // 2026-09-05 18:02 日志定案：蓝牙手表 A2DP 连接但无扬声器/休眠时，
                    // mixer 恒不消费。setSpeakerphoneOn(true) 在 MODE_NORMAL 下可能
                    // 无效，但部分 MIUI 版本会响应并切到扬声器。
                    if (replayAttempts == 1) {
                        try {
                            audioManager?.let { am ->
                                if (!am.isSpeakerphoneOn) {
                                    am.isSpeakerphoneOn = true
                                    Log.i(TAG, "forced speakerphone on (A2DP workaround)")
                                }
                            }
                        } catch (e: Exception) {
                            Log.w(TAG, "setSpeakerphoneOn failed", e)
                        }
                    } else if (replayAttempts == 2) {
                        // 第二次重试：MODE_IN_COMMUNICATION + setSpeakerphoneOn 组合。
                        // MODE_NORMAL 下 setSpeakerphoneOn 无效（19:01 日志已证伪），
                        // MODE_IN_COMMUNICATION 改变音频路由策略，强制走通信通道+
                        // 扬声器，绕过 MIUI 媒体流的低功耗策略。播放结束后在
                        // releaseIfCurrent 恢复 MODE_NORMAL。
                        try {
                            audioManager?.let { am ->
                                if (am.mode != AudioManager.MODE_IN_COMMUNICATION) {
                                    am.mode = AudioManager.MODE_IN_COMMUNICATION
                                    Log.i(TAG, "set mode IN_COMMUNICATION (mixer workaround)")
                                }
                                if (!am.isSpeakerphoneOn) {
                                    am.isSpeakerphoneOn = true
                                    Log.i(TAG, "forced speakerphone on (mode=IN_COMMUNICATION)")
                                }
                            }
                        } catch (e: Exception) {
                            Log.w(TAG, "mode/speaker workaround failed", e)
                        }
                    }
                    synchronized(audioTrackLock) {
                        if (audioTrack === t && t.state == AudioTrack.STATE_INITIALIZED) {
                            try {
                                // 不 flush：保留已写入的音频数据。
                                // pause→play 触发 AudioFlinger 重新挂载这条流到 mixer，
                                // 部分 MIUI 版本首次 play 被低功耗策略拦截，二次能激活。
                                t.pause()
                                t.play()
                                stillSinceMs = System.currentTimeMillis()
                            } catch (e: Exception) {
                                Log.w(TAG, "replay failed", e)
                            }
                        }
                    }
                }
            }
        }

        /** 释放轨道（仅当仍是当前 audioTrack，避免与 stop() 双重释放）。 */
        fun releaseIfCurrent() {
            val t = track ?: return
            synchronized(audioTrackLock) {
                if (audioTrack === t) {
                    audioTrack = null
                    // 欠载诊断（API 24+）：硬件侧欠载计数在 release 前读取。
                    // >0 说明播放期缓冲仍被击穿，真机可据此加大 PREBUFFER_SECONDS
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                        val underruns = t.underrunCount
                        if (underruns > 0) {
                            Log.w(TAG, "AudioTrack underrun count: $underruns over $framesWritten frames")
                        }
                    }
                    try {
                        if (t.state == AudioTrack.STATE_INITIALIZED) {
                            t.pause()
                            t.flush()
                        }
                        t.release()
                    } catch (_: Exception) {}
                    // 恢复音频模式：自愈逻辑可能设了 MODE_IN_COMMUNICATION，
                    // 不恢复会影响后续系统音频（通话/铃声路由异常）
                    try {
                        audioManager?.let { am ->
                            if (am.mode == AudioManager.MODE_IN_COMMUNICATION) {
                                am.mode = AudioManager.MODE_NORMAL
                                Log.i(TAG, "restored audio mode to NORMAL")
                            }
                        }
                    } catch (_: Exception) {}
                }
            }
            track = null
        }

        /**
         * 阻塞写一段 PCM 到硬件轨道。失败（轨道被 stop() 释放等）置 broken
         * 并返回 false，调用方中止合成、交由上层按句跳过。
         */
        private fun writePcm(t: AudioTrack, pcm16: ShortArray): Boolean {
            var offset = 0
            while (offset < pcm16.size) {
                val written = t.write(pcm16, offset, pcm16.size - offset)
                if (written < 0) {
                    Log.w(TAG, "stream write failed: $written")
                    broken = true
                    return false
                }
                offset += written
                framesWritten += written
            }
            return true
        }

        /** 把 pending 队列一次性写入硬件；写失败返回 false（broken 已置位）。 */
        private fun drainPending(newTrack: AudioTrack): Boolean {
            while (pending.isNotEmpty()) {
                val chunk = pending.removeFirst()
                pendingFrames -= chunk.size
                if (!writePcm(newTrack, chunk)) return false
            }
            return true
        }

        /**
         * play() 建好的轨道。从旧的 buildAndStartTrack 拆出：play 必须发生在
         * pending 数据写入硬件之后（play 时缓冲内已有 ≥预缓冲量的音频）。
         * @return 1 成功；0 失败（broken 已置位，合成中止）
         */
        private fun startTrack(newTrack: AudioTrack): Int {
            return try {
                newTrack.play()
                1
            } catch (e: Exception) {
                Log.w(TAG, "stream track play failed", e)
                releaseIfCurrent()
                broken = true
                0
            }
        }

        /** 构建并注册轨道（不 play）：stop() 需能通过 audioTrack 字段接管。 */
        private fun buildTrack(): AudioTrack? {
            if (broken) return null
            val newTrack = try {
                val minBuffer = AudioTrack.getMinBufferSize(
                    TRACK_SAMPLE_RATE,
                    AudioFormat.CHANNEL_OUT_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                )
                val targetBytes = TRACK_SAMPLE_RATE * 2 * STREAM_BUFFER_SECONDS
                val bufferSize = if (minBuffer > 0) maxOf(minBuffer, targetBytes) else targetBytes
                val builder = AudioTrack.Builder()
                    // 与焦点请求共用同一 AudioAttributes（见 playbackAudioAttributes
                    // 注释：SPEECH 内容类型在 MIUI 上会被语音通道策略静默）
                    .setAudioAttributes(playbackAudioAttributes)
                    .setAudioFormat(
                        AudioFormat.Builder()
                            .setSampleRate(TRACK_SAMPLE_RATE)
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                            .build()
                    )
                    .setBufferSizeInBytes(bufferSize)
                    .setTransferMode(AudioTrack.MODE_STREAM)
                // PERFORMANCE_MODE_LATENCY（API 26+）：强制走低延迟路径，
                // 绕过 MIUI 低功耗策略（audio_lowpower_app_list.xml 加载失败时
                // STREAM 模式被路由到不消费的输出线程）。2026-09-05 19:01 日志：
                // 蓝牙已断开、路由到扬声器、pcmPeak/musicVol/mode 均正常，
                // 但 head 恒 0——mixer 不消费 STREAM 流。
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    // PERFORMANCE_MODE_LATENCY = 1（API 26+）：强制走低延迟路径
                    builder.setPerformanceMode(1)
                }
                builder.build()
            } catch (e: Exception) {
                Log.w(TAG, "stream track build failed", e)
                broken = true
                return null
            }
            // 注册进 audioTrack：stop() 才能立刻停掉正在播的流
            synchronized(audioTrackLock) { audioTrack = newTrack }
            track = newTrack
            // 播放前申请音频焦点：让音乐/播客让路，朗读结束/停止后归还
            requestAudioFocusIfNeeded()
            return newTrack
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
        synchronized(audioTrackLock) {
            try {
                audioTrack?.let {
                    if (it.state == AudioTrack.STATE_INITIALIZED) {
                        it.pause()
                        it.flush()
                    }
                    it.release()
                }
            } catch (_: Exception) {}
            audioTrack = null
        }
        isPlaying.set(false)
        // 停止即归还音频焦点，让被压低的音乐/播客恢复
        abandonAudioFocusIfHeld()
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
        abandonAudioFocusIfHeld()
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
    private fun showDownloadCompleteNotification(contentText: String) {
        notificationService.showTtsDownloadComplete(contentText)
    }

    /** 取消下载通知。 */
    fun cancelDownloadNotification() {
        notificationService.cancelTtsDownloadNotification()
    }
}