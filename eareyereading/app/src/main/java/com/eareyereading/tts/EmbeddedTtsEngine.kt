package com.eareyereading.tts

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.util.Log
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsVitsModelConfig
import com.eareyereading.util.NotificationService
import dagger.hilt.android.qualifiers.ApplicationContext
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
 * **模型选择**：当前仅内置 Piper en_US-lessac-medium 英文男声（韵律自然，
 *   约 66MB）。原设计中由 resolveModelForLanguage 按书籍语言路由到不同模型
 *   （中文书用 MeloTTS-zh_en），但该模型在国内无可用镜像、下载链路不稳，
 *   且与产品当前的英文阅读主线不符——2026-08-30 起下线，全部归到 Piper。
 *
 * 模型文件从 CDN 下载到 app 的私有目录（首次约 66MB）。
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
    @Volatile
    private var sampleRate: Int = 22050

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
            @Suppress("DEPRECATION")
            val result = am.requestAudioFocus(
                focusListener,
                AudioManager.STREAM_MUSIC,
                AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK,
            )
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
            @Suppress("DEPRECATION")
            am.abandonAudioFocus(focusListener)
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
        /** 文件完整下载标记后缀。存在表示该文件已完整下载，避免误用残缺文件。 */
        private const val COMPLETE_SUFFIX = ".complete"
        /**
         * 解压总字节上限：正常 Piper 归档解压后 ~120MB，给 2 倍余量。
         * 防 bzip2 解压炸弹（恶意/损坏归档写满整盘）。（1.3：512MB → 256MB）
         */
        private const val MAX_EXTRACT_BYTES = 256L * 1_000_000L

        /**
         * 内置可用模型列表。
         *
         * 默认内置模型 = Piper lessac-medium（见 DEFAULT_MODEL_ID）：
         * 韵律自然、英文发音地道，G2P 走 espeak-ng（归档自带
         * espeak-ng-data/），仅归档下载（文件是整目录树，无逐文件镜像）。
         *
         * MeloTTS-zh_en 保留给中文书：它由 MeloTTS-Chinese 导出、只有 1 个
         * 中文说话人（官方文档明确），读英文口音重、语调平、英文数字词
         * 带中文音——语言路由只对非英文书使用它（见 resolveModelForLanguage）。
         *
         * 2026-08-30: 中文模型全部下线。AVAILABLE_MODELS 仅保留 Piper，
         * 配套 modelForInitialize / resolveModelForLanguage 也不再按语言路由。
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
        )

        // 内置默认 = Piper 英文声
        val DEFAULT_MODEL_ID = "vits-piper-en_US-lessac-medium"

        /** 用户当前选中的模型 ID（用 SharedPreferences 持久化） */
        private const val PREFS_NAME = "embedded_tts_prefs"
        private const val KEY_SELECTED_MODEL = "selected_model"
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

    fun getCurrentModelInfo(): ModelInfo {
        // firstOrNull 全程兜底：持久化的模型 id 可能已被新版本移除，
        // first{} 会直接抛 NoSuchElementException（且本方法会在 Compose 组合期被调用）
        return AVAILABLE_MODELS.firstOrNull { it.id == currentModelName }
            ?: AVAILABLE_MODELS.firstOrNull { it.id == getSelectedModelId() }
            ?: AVAILABLE_MODELS.first()
    }

    /**
     * 按书籍语言解析理想模型（不保证已下载）。
     *
     * 当前内置只有 Piper 英文声，所以无论书籍语言如何都返回它。参数
     * `language` 保留是为了不让外部调用方大改——它原本用来在英文和
     * 中英双语间路由。当前阶段中文书会由英文声读出（音色偏英文口音），
     * 但更糟的情况（无声）不会出现。
     */
    fun resolveModelForLanguage(@Suppress("UNUSED_PARAMETER") language: String?): ModelInfo {
        return getCurrentModelInfo()
    }

    /**
     * 初始化时实际可加载的模型：唯一内置 Piper 已下载则用它；否则返回 null
     * 由调用方引导用户下载。language 参数保留（兼容旧调用方），不再用于路由。
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
            // 解压总量上限：正常归档解压后 ~120MB，256MB 上限防 bzip2 解压炸弹（1.3）
            java.io.FileInputStream(tarballFile).use { fis ->
                BZip2CompressorInputStream(fis).use { bzis ->
                    TarArchiveInputStream(bzis).use { tis ->
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
                                Log.d(TAG, "extraction: mkdir ${outFile.absolutePath}")
                                outFile.mkdirs()
                            } else {
                                outFile.parentFile?.mkdirs()
                                Log.d(TAG, "extraction: writing ${entry.name} (entry.size=${entry.size})")
                                FileOutputStream(outFile).use { out ->
                                    val buf = ByteArray(262144)
                                    var fileBytes = 0L
                                    while (true) {
                                        val n = tis.read(buf)
                                        if (n == -1) break
                                        out.write(buf, 0, n)
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
                                    Log.d(TAG, "extraction: wrote ${outFile.name} ${fileBytes} bytes")
                                }
                            }
                            // 解压进度按字节推进（已解压字节在上层累计），每个条目推一次，
                            // pushExtractionProgress 按 100ms 节流
                            pushExtractionProgress(isFinal = false)
                            entry = tis.nextEntry
                        }
                        // 流结束：强制推一次末态，保证 UI 看到解压 100%
                        pushExtractionProgress(isFinal = true)
                        Log.i(TAG, "extraction: tar stream fully consumed, took ${(System.currentTimeMillis() - extractionStartMs) / 1000}s")
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
                    val buffer = ByteArray(8192)
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
                    val buffer = ByteArray(8192)
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

                // VITS 模型配置（使用构造参数，避免依赖 var 字段默认值）
                val vitsConfig = OfflineTtsVitsModelConfig(
                    model = modelPath,
                    tokens = tokensPath,
                    lexicon = lexiconPath ?: "",
                    dataDir = dataDirPath,
                    dictDir = dictDirPath ?: "",
                )
                val modelConfig = OfflineTtsModelConfig(
                    vits = vitsConfig,
                    numThreads = NUM_THREADS,
                )
                val config = OfflineTtsConfig(model = modelConfig)
                val newTts = OfflineTts(config = config)
                // 关键：替换/释放旧 native 实例必须与 generate() 互斥。
                // 只加 synchronized(this) 时，另一个协程可能正持有 speakMutex
                // 在 generate() 里使用旧实例 → release() 直接 JNI use-after-free
                // （正是注释里说的 SIGSEGV 类别）。构造在锁外完成，仅替换进锁。
                var assigned = false
                try {
                    speakMutex.withLock {
                        synchronized(this) {
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
     * 朗读一段文字（同步阻塞版本，由调用方在协程中调用）。
     *
     * @param text 要朗读的文本
     * @param speed 语速倍率，1.0 = 正常
     * @param onDone 完成回调（可选）
     * @return true 表示开始播放
     */
    suspend fun speak(
        text: String,
        speed: Float = 1.0f,
        onDone: () -> Unit = {},
    ): Boolean = withContext(Dispatchers.IO) {
        // issue 2.11：入口即检查取消。TtsHelper.speak 先 cancel 旧 job 再 launch
        // 新 job——cancel() 非阻塞，旧协程可能还挂在 speakMutex 上等锁，新协程
        // 已在队列里；入口 ensureActive 让被取消的旧协程在抢锁前就放弃，避免
        // 两个 speak 协程几乎同时进入 doSpeakLocked 造成音频叠播/错序。
        coroutineContext[Job]?.ensureActive()
        // 关键：native sherpa-onnx OfflineTts 指针不能并发使用。
        // 之前 TtsHelper.speak() 在 scope.launch 里多次并发调用，
        // 两个 IO 协程同时调 generate() → JNI 段错误 SIGSEGV。
        // 用 Mutex 串行化整个 speak 调用。
        // 注册进 activeSpeakJobs：stop() 取消所有调用者（含正在播的与等锁的）
        val myJob = coroutineContext[Job]
        synchronized(speakJobLock) {
            myJob?.let { activeSpeakJobs.add(it) }
        }
        try {
            speakMutex.withLock {
                doSpeakLocked(text, speed, onDone)
            }
        } finally {
            synchronized(speakJobLock) {
                myJob?.let { activeSpeakJobs.remove(it) }
            }
        }
    }

    private suspend fun doSpeakLocked(
        text: String,
        speed: Float,
        onDone: () -> Unit,
    ): Boolean {
        val currentTts = tts ?: run {
            Log.w(TAG, "speak() called but tts not initialized")
            return false
        }
        if (text.isBlank()) {
            onDone()
            return true
        }

        try {
            isPlaying.set(true)
            // 先做文本预处理：把 sherpa-onnx MeloTTS 模型的 OOV 字符替换成可发音的等价物，
            // 否则 chunks[0] 直接传给 generate() 时会触发 native 段错误 (SIGSEGV)。
            // 已知 OOV: 'í' '—' '"' '(' ')' '65' '28' '2026' '10' '07' 等数字 + 标点。
            val cleaned = preprocessForTts(text)
            // 逐句朗读：按句子边界切分，每句单独 generate+play。
            // 不累积多句成一个 chunk——累积会导致单次 generate 输入过长触发 native 空指针崩溃。
            // 每句通常 < 150 字符，是 sherpa-onnx VITS/MeloTTS 的安全区间。
            val sentences = splitSentences(cleaned)
            Log.i(TAG, "Embedded TTS speak: inputLen=${text.length}, cleanedLen=${cleaned.length}, sentences=${sentences.size}")
            // 熔断器：模型损坏时每句都会抛异常，旧实现逐句"跳过"后照常返回成功，
            // 上层会"静音朗读"完整本书并推进进度。连续失败 3 句直接中止并报失败
            var consecutiveFailures = 0
            for ((idx, sentence) in sentences.withIndex()) {
                if (sentence.isBlank()) continue
                // 每句之前检查协程是否已被取消（stop() 调用）。
                kotlinx.coroutines.yield()
                kotlinx.coroutines.currentCoroutineContext()[Job]?.let { job ->
                    if (!job.isActive) throw kotlinx.coroutines.CancellationException("stop() requested")
                }
                // 单句仍可能超长（超长标题/无标点中文长段）：切成 ≤150 字符的块逐块合成，
                // 旧实现 substring(0,150) 直接丢弃 150 字符之后的全部内容
                val chunks = hardChunks(sentence, 150)
                var anyOk = false
                for (chunk in chunks) {
                    try {
                        val audio = currentTts.generate(chunk, sid = 0, speed = speed)
                        val pcm = audio.samples
                        val sr = audio.sampleRate
                        Log.i(
                            TAG,
                            "Embedded TTS sentence $idx/${sentences.size}: len=${chunk.length}, " +
                                "generated ${pcm.size} samples, duration=${"%.1f".format(pcm.size / sr.toFloat())}s",
                        )
                        playPcm(pcm, sr)
                        anyOk = true
                    } catch (e: kotlinx.coroutines.CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        // 单句 generate 崩溃（如 native G2P bug）：跳过该句，继续下一句
                        Log.e(TAG, "sentence $idx chunk generate failed, skipping: '${chunk.take(60)}'", e)
                    }
                }
                if (anyOk) {
                    consecutiveFailures = 0
                } else {
                    consecutiveFailures++
                    if (consecutiveFailures >= 3) {
                        Log.e(TAG, "3 consecutive sentence failures — aborting speak (model likely broken)")
                        _state.value = EngineState.FAILED("语音合成连续失败，模型可能已损坏")
                        isPlaying.set(false)
                        return false
                    }
                }
            }
            isPlaying.set(false)
            onDone()
            return true
        } catch (e: kotlinx.coroutines.CancellationException) {
            // playPcm 内部的 delay() 会在协程取消时抛出 CancellationException；
            // 不能吞掉，否则 withContext 不会正确传播取消信号。
            isPlaying.set(false)
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "speak failed", e)
            isPlaying.set(false)
            return false
        }
    }

    /**
     * 停止当前播放。
     */
    fun stop() {
        // 取消全部 speak 协程：让 doSpeakLocked 立刻退出（协程取消时
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
     * 播放 PCM 浮点音频数据。
     *
     * 改为 suspend 函数后用 [delay] 替代 [Thread.sleep]，使播放等待期间
     * 能响应协程取消（如 viewModelScope 被清除时），避免占用 IO 线程直到
     * 整段音频播完。
     */
    private suspend fun playPcm(samples: FloatArray, sampleRate: Int) {
        if (samples.isEmpty()) return
        // 注意：不能调 stop()！stop() 会 cancel currentSpeakJob（即当前协程自己），
        // 导致多 chunk 播放时第二个 chunk 的 playPcm 立刻取消整个 speak 协程。
        // 这里只需释放上一个 AudioTrack（如果有），不取消协程。
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

        // sherpa-onnx 输出范围 [-1, 1] 的 Float，AudioTrack 需要 16-bit PCM
        val pcm16 = ShortArray(samples.size) { i ->
            (samples[i] * 32767f).toInt().coerceIn(-32768, 32767).toShort()
        }

        // MODE_STATIC 要求 buffer ≥ 数据长度，同时部分设备的 AudioTrack mixer
        // 有自己的最小 buffer 门槛：只给 pcm16.size*2 时短数据 write() 返回 -22，
        // 整句静默丢弃（长句尾部 chunk 最常踩中）
        val minBuffer = AudioTrack.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        val bufferSize = maxOf(pcm16.size * 2, minBuffer)
        val track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(sampleRate)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build()
            )
            .setBufferSizeInBytes(bufferSize)
            .setTransferMode(AudioTrack.MODE_STATIC)
            .build()

        synchronized(audioTrackLock) {
            audioTrack = track
        }

        // 播放前申请音频焦点：让音乐/播客让路，朗读结束/停止后归还
        requestAudioFocusIfNeeded()

        val written = track.write(pcm16, 0, pcm16.size)
        if (written < 0) {
            // write 失败（ERROR_*）：别播了，释放该 track 交由调用方按句跳过
            Log.w(TAG, "AudioTrack.write failed: $written")
            synchronized(audioTrackLock) {
                if (audioTrack === track) {
                    audioTrack = null
                    try { track.release() } catch (_: Exception) {}
                }
            }
            return
        }
        try {
            track.play()
        } catch (e: IllegalStateException) {
            Log.w(TAG, "AudioTrack.play failed", e)
            synchronized(audioTrackLock) {
                if (audioTrack === track) {
                    audioTrack = null
                    try { track.release() } catch (_: Exception) {}
                }
            }
            return
        }

        // 等待播放完成：以硬件已消费的帧数（playbackHeadPosition）为准。
        // 旧实现用 (字节数/采样率) 估算时长，估算偏短时 release() 会把
        // 还在硬件 buffer 里排队的尾音硬切掉（句尾元音中间"啪"地断）
        try {
            while (true) {
                kotlinx.coroutines.delay(20)
                val head = synchronized(audioTrackLock) {
                    if (audioTrack !== track) {
                        // 被 stop()/新的 playPcm 接管：对方已在锁内 release 过这个 track。
                        // 这里不能再 release（双重释放会抛 IllegalStateException）
                        return
                    }
                    track.playbackHeadPosition
                }
                if (head >= pcm16.size) break
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            // scope 销毁等取消路径：若 track 仍是当前则释放，
            // 否则 MODE_STATIC 音频会继续播完且 native 资源悬挂到 GC
            synchronized(audioTrackLock) {
                if (audioTrack === track) {
                    audioTrack = null
                    try { track.release() } catch (_: Exception) {}
                }
            }
            throw e
        }
        // 自然播完：在锁内确认仍是当前 track 才释放，避免与 stop() 竞态双重释放
        synchronized(audioTrackLock) {
            if (audioTrack === track) {
                audioTrack = null
                track.release()
            }
        }
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