package com.eareyereading.tts

import android.content.Context
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import com.eareyereading.MainActivity
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsVitsModelConfig
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
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
 * **模型选择**：
 * - 英文：VITS-LJS（单说话人女声，约30MB）
 * - 中文：VITS-MeloTTS-zh_en（中英双语，约100MB，最匹配本 app 的双语场景）
 *
 * 模型文件从 CDN 下载到 app 的私有目录（首次启动约 60-100MB 下载）。
 */
@Singleton
class EmbeddedTtsEngine @Inject constructor(
    @ApplicationContext private val context: Context,
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
    private val focusListener = AudioManager.OnAudioFocusChangeListener { /* 短焦点无需响应变化 */ }
    @Volatile
    private var audioFocusHeld = false

    private fun requestAudioFocusIfNeeded() {
        if (audioFocusHeld) return
        val am = audioManager ?: return
        try {
            @Suppress("DEPRECATION")
            am.requestAudioFocus(
                focusListener,
                AudioManager.STREAM_MUSIC,
                AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK,
            )
            audioFocusHeld = true
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

    // 当前下载进度（0.0 - 1.0），null 表示没在下载
    private val _downloadProgress = MutableStateFlow<Float?>(null)
    val downloadProgress: StateFlow<Float?> = _downloadProgress.asStateFlow()

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
        private const val DL_CHANNEL_ID = "eareye_tts_download"
        private const val DL_NOTIFICATION_ID = 2001

        /**
         * 内置可用模型列表。
         *
         * 默认推荐 MeloTTS-zh_en：中英双语，最适合中文书/混合场景。
         * 注意：vits-melo-tts-zh_en 由 MeloTTS-Chinese 导出、只有 1 个中文说话人
         * （官方文档明确），用它读英文口音重、语调平，英文数字词会被读出
         * 中文音——纯英文书应路由到 language="en" 的纯英文模型
         * （见 resolveModelForLanguage）。
         */
        val AVAILABLE_MODELS = listOf(
            ModelInfo(
                id = "vits-melo-tts-zh_en",
                displayName = "MeloTTS 中英双语（约 167MB）",
                language = "zh+en",
                sizeBytes = 167_000_000L,
                tarballUrl = "https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/vits-melo-tts-zh_en.tar.bz2",
                tarballMirrorUrls = listOf(
                    "https://hf-mirror.com/csukuangfj/sherpa-onnx-melo-tts-zh-en/resolve/main/model.onnx",
                ),
                files = listOf(
                    ModelFile(
                        "vits-melo-tts-zh_en/model.onnx",
                        url = "https://hf-mirror.com/csukuangfj/sherpa-onnx-melo-tts-zh-en/resolve/main/model.onnx",
                        mirrorUrls = listOf(
                            "https://huggingface.co/csukuangfj/sherpa-onnx-melo-tts-zh-en/resolve/main/model.onnx",
                        ),
                    ),
                    ModelFile(
                        "vits-melo-tts-zh_en/tokens.txt",
                        url = "https://hf-mirror.com/csukuangfj/sherpa-onnx-melo-tts-zh-en/resolve/main/tokens.txt",
                        mirrorUrls = listOf(
                            "https://huggingface.co/csukuangfj/sherpa-onnx-melo-tts-zh-en/resolve/main/tokens.txt",
                        ),
                    ),
                    ModelFile(
                        "vits-melo-tts-zh_en/lexicon.txt",
                        url = "https://hf-mirror.com/csukuangfj/sherpa-onnx-melo-tts-zh-en/resolve/main/lexicon.txt",
                        mirrorUrls = listOf(
                            "https://huggingface.co/csukuangfj/sherpa-onnx-melo-tts-zh-en/resolve/main/lexicon.txt",
                        ),
                    ),
                    ModelFile(
                        "vits-melo-tts-zh_en/dict",
                        url = "https://hf-mirror.com/csukuangfj/sherpa-onnx-melo-tts-zh-en/resolve/main/dict",
                        mirrorUrls = listOf(
                            "https://huggingface.co/csukuangfj/sherpa-onnx-melo-tts-zh-en/resolve/main/dict",
                        ),
                    ),
                ),
            ),
            ModelInfo(
                id = "vits-ljs",
                displayName = "VITS LJS 纯英文女声（约 109MB）",
                language = "en",
                sizeBytes = 109_000_000L,
                tarballUrl = "https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/vits-ljs.tar.bz2",
                files = listOf(
                    // 注意：HF 仓库只有 int8 量化版（vits-ljs.int8.onnx），
                    // fp32 版只在 GitHub tarball 里。逐文件回退下载 int8 存为
                    // model.onnx——sherpa-onnx 两种精度都能加载
                    ModelFile(
                        "vits-ljs/model.onnx",
                        url = "https://hf-mirror.com/csukuangfj/vits-ljs/resolve/main/vits-ljs.int8.onnx",
                        mirrorUrls = listOf(
                            "https://huggingface.co/csukuangfj/vits-ljs/resolve/main/vits-ljs.int8.onnx",
                        ),
                    ),
                    ModelFile(
                        "vits-ljs/tokens.txt",
                        url = "https://hf-mirror.com/csukuangfj/vits-ljs/resolve/main/tokens.txt",
                        mirrorUrls = listOf(
                            "https://huggingface.co/csukuangfj/vits-ljs/resolve/main/tokens.txt",
                        ),
                    ),
                    ModelFile(
                        "vits-ljs/lexicon.txt",
                        url = "https://hf-mirror.com/csukuangfj/vits-ljs/resolve/main/lexicon.txt",
                        mirrorUrls = listOf(
                            "https://huggingface.co/csukuangfj/vits-ljs/resolve/main/lexicon.txt",
                        ),
                    ),
                ),
            ),
        )

        val DEFAULT_MODEL_ID = "vits-melo-tts-zh_en"

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
 * 把文本里 sherpa-onnx MeloTTS 模型不认识的字符替换成可发音的等价物。
 *
 * 已知 OOV 列表（来自实际 logcat）：
 *  - 数字 '0'-'99'：被 Ignore OOV 直接跳过，导致 tensor 索引越界 → SIGSEGV
 *  - 标点 'í'（西班牙语重音字符）、'—'（em-dash）、'"' '"'（smart quotes）、'(' ')'：同样 OOV
 *  - '$' '&' '+' '@' '#' '%' '=' '<' '>' '\\' '`' '~' '^' '|' 等特殊符号
 *
 * 替换策略：
 *  - 4 位年份 (2026) → "twenty twenty-six"，避免被切成 "twenty" + "twenty-six"
 *  - 其他数字 (65, 28, 10, 07) → 英文单词
 *  - 标点 → ASCII 等价（'—' → ", ", '"' → '"'）
 *  - 货币、特殊符号 → 英文读法
 */
private fun preprocessForTts(text: String): String {
    var s = text

    // 4 位年份 (1000-2099)：转成英文单词
    // 注意：要在普通数字转换之前，避免 "2026" 被切成 "two thousand" + "twenty-six"
    s = Regex("\\b(1\\d{3}|20\\d{2})\\b").replace(s) { match ->
        numberToWords(match.value.toInt())
    }

    // 时间格式 "10:07" → "ten oh seven"
    s = Regex("\\b(\\d{1,2}):(\\d{2})\\b").replace(s) { match ->
        val (h, m) = match.groupValues[1] to match.groupValues[2]
        "${numberToWords(h.toInt())} oh ${numberToWords(m.toInt())}"
    }

    // 货币 + 数字组合（如 "$100"）：必须在通用数字转换之前，
    // 否则 "$100" 会先变成 "$one hundred" 再变成 " dollars one hundred"（语序颠倒）
    s = Regex("\\$(\\d+)").replace(s) { match ->
        val num = match.groupValues[1].toIntOrNull()
        "${if (num != null && num <= 9999) numberToWords(num) else digitsToWords(match.groupValues[1])} dollars"
    }

    // 其他数字 (含小数)：转英文
    // 不含已处理过的年份/时间。
    // 关键：超过 Int 或超过支持范围的数字必须逐位转成单词，
    // 绝不能把裸数字留给 generate()——本文件注释明确记载数字会触发
    // native tensor 索引越界 SIGSEGV，且信号无法被 catch 拦截
    s = Regex("(?<!\\d)(\\d+)(?!\\d|:)").replace(s) { match ->
        val num = match.value.toIntOrNull()
        if (num != null && num in 0..9999) {
            numberToWords(num)
        } else {
            digitsToWords(match.value)
        }
    }

    // 标点替换
    s = s.replace("\u2014", ", ")     // em-dash → comma+space
    s = s.replace("\u2013", "-")      // en-dash → hyphen
    s = s.replace("\u2018", "'")      // left single quote
    s = s.replace("\u2019", "'")      // right single quote
    s = s.replace("\u201C", "\"")     // left double quote
    s = s.replace("\u201D", "\"")     // right double quote
    s = s.replace("\u00ed", "i")      // í → i (Rodríguez → Rodriguez)
    s = s.replace("\u00e9", "e")      // é → e
    s = s.replace("\u00e1", "a")      // á → a
    s = s.replace("\u00f1", "n")      // ñ → n
    s = s.replace("\u00fc", "u")      // ü → u
    s = s.replace("\u00e7", "c")      // ç → c
    // 货币符号
    s = s.replace("$", " dollars ")
    s = s.replace("\u20ac", " euros ")  // €
    s = s.replace("\u00a3", " pounds ") // £
    s = s.replace("\u00a5", " yen ")    // ¥
    // 其他常见 OOV 符号（含注释中记载的 '<' '>' —— 此前遗漏未替换）
    s = s.replace("@", " at ")
    s = s.replace("&", " and ")
    s = s.replace("+", " plus ")
    s = s.replace("=", " equals ")
    s = s.replace("#", " number ")
    s = s.replace("%", " percent ")
    s = s.replace("\\", " ")
    s = s.replace("/", " ")            // 日期斜杠
    s = s.replace("<", " less than ")
    s = s.replace(">", " greater than ")
    s = s.replace("*", " ")
    s = s.replace("[", ", ")
    s = s.replace("]", ", ")
    s = s.replace("_", " ")
    s = s.replace("{", ", ")
    s = s.replace("}", ", ")

    // 括号、特殊括号、引号变体
    s = s.replace("(", ", ")
    s = s.replace(")", ", ")
    s = s.replace("\u00a0", " ")        // non-breaking space
    s = s.replace("`", "'")             // backtick
    s = s.replace("|", " ")
    s = s.replace("^", " ")
    s = s.replace("~", " ")
    s = s.replace("\u2026", "...")      // ellipsis

    // 常见缩写展开（MeloTTS lexicon 不含这些，G2P fallback 可能触发 native 空指针）
    s = s.replace(Regex("\\bU\\.S\\.\\b"), "United States")
    s = s.replace(Regex("\\bU\\.S\\.A\\.\\b"), "United States of America")
    s = s.replace(Regex("\\bU\\.K\\.\\b"), "United Kingdom")
    s = s.replace(Regex("\\bE\\.U\\.\\b"), "European Union")
    s = s.replace(Regex("\\bP\\.M\\.\\b"), "P M")
    s = s.replace(Regex("\\bA\\.M\\.\\b"), "A M")
    s = s.replace(Regex("\\bD\\.C\\.\\b"), "D C")
    s = s.replace(Regex("\\bN\\.Y\\.\\b"), "New York")
    // 时间缩写 PM/AM/ET/CT/PT/MT（无点号的全大写）
    s = s.replace(Regex("\\bPM\\b"), "P M")
    s = s.replace(Regex("\\bAM\\b"), "A M")
    s = s.replace(Regex("\\bET\\b"), "Eastern Time")
    s = s.replace(Regex("\\bCT\\b"), "Central Time")
    s = s.replace(Regex("\\bPT\\b"), "Pacific Time")
    s = s.replace(Regex("\\bMT\\b"), "Mountain Time")
    s = s.replace(Regex("\\bAP\\b"), "Associated Press")
    s = s.replace(Regex("\\bCEO\\b"), "C E O")
    s = s.replace(Regex("\\bGDP\\b"), "G D P")
    s = s.replace(Regex("\\bNASA\\b"), "N A S A")
    s = s.replace(Regex("\\bFBI\\b"), "F B I")
    s = s.replace(Regex("\\bCIA\\b"), "C I A")

    // 把连续 3+ 大写字母拆成单字母（如 "NATO" → "N A T O"），
    // MeloTTS lexicon 有单字母发音，避免 G2P 对未知缩写崩溃
    s = Regex("\\b[A-Z]{3,}\\b").replace(s) { match ->
        match.value.toCharArray().joinToString(" ")
    }

    // 把连续空白合并
    s = s.replace(Regex("\\s+"), " ").trim()
    return s
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

    val units = arrayOf("", "one", "two", "three", "four", "five", "six", "seven", "eight", "nine")
    val teens = arrayOf("ten", "eleven", "twelve", "thirteen", "fourteen", "fifteen",
                        "sixteen", "seventeen", "eighteen", "nineteen")
    val tens = arrayOf("", "", "twenty", "thirty", "forty", "fifty",
                       "sixty", "seventy", "eighty", "ninety")

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
    val names = mapOf(
        '0' to "zero", '1' to "one", '2' to "two", '3' to "three",
        '4' to "four", '5' to "five", '6' to "six", '7' to "seven",
        '8' to "eight", '9' to "nine",
    )
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
     * 按书籍语言解析理想模型（不保证已下载）：
     * 纯英文书优先纯英文模型——默认的中英双语模型实际是中文说话人
     * （MeloTTS-Chinese 导出），读英文语调平、数字词带中文音；
     * 中文/其他语言用当前选择/默认的中英双语模型。
     * 引导弹窗/下载入口用它决定给用户推荐哪个模型。
     */
    fun resolveModelForLanguage(language: String?): ModelInfo {
        if (language?.lowercase(java.util.Locale.ROOT)?.startsWith("en") == true) {
            AVAILABLE_MODELS.firstOrNull { it.language == "en" }?.let { return it }
        }
        return getCurrentModelInfo()
    }

    /**
     * 初始化时实际可加载的模型：语言对应的理想模型已下载则用它，
     * 否则退回用户选择/默认模型（已下载时），都不可用返回 null。
     */
    fun modelForInitialize(language: String?): ModelInfo? {
        val ideal = resolveModelForLanguage(language)
        if (isModelDownloaded(ideal)) return ideal
        val fallback = getCurrentModelInfo()
        return if (isModelDownloaded(fallback)) fallback else null
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
            File(dir, file.relativePath).let { if (it.exists()) it.delete() }
            File(dir, file.relativePath + COMPLETE_SUFFIX).let { if (it.exists()) it.delete() }
        }
        // 状态流同步复位：此前删完模型流里仍是 READY(旧模型)，
        // 设置页状态与实际不符
        if (_state.value is EngineState.READY || _state.value is EngineState.FAILED) {
            _state.value = EngineState.MODEL_NOT_FOUND
        }
    }

    /** 下载互斥：设置页与阅读页弹窗是两个独立入口，各自的 UI 守卫挡不住跨入口并发。
     * 两个下载协程交错写同一批文件/解压同一个 tarball 会产出损坏模型 */
    private val downloadMutex = Mutex()

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
        _state.value = EngineState.DOWNLOADING
        _downloadProgress.value = 0f
        showDownloadNotification(0f, "准备下载 ${modelInfo.displayName}")
        try {
            val dir = File(context.filesDir, MODELS_DIR_NAME)
            if (!dir.exists()) dir.mkdirs()

            // 优先路径：下载 GitHub release tarball 并解压（国内可达性优于 HuggingFace）
            if (modelInfo.tarballAllUrls().isNotEmpty()) {
                val ok = downloadAndExtractTarball(modelInfo, dir, onProgress)
                if (ok) {
                    _downloadProgress.value = 1f
                    showDownloadCompleteNotification("下载完成，正在启用...")
                    return@withContext true
                }
                Log.w(TAG, "tarball 下载/解压失败，回退到逐文件下载")
                showDownloadNotification(0f, "回退到逐文件下载...")
            }

            // 回退路径：逐文件下载（HuggingFace，国内可能不可达）
            val totalSize = modelInfo.sizeBytes
            var downloadedTotal = 0L
            var lastNotifyMs = 0L

            for (file in modelInfo.files) {
                val targetFile = File(dir, file.relativePath)
                val completeFile = File(dir, file.relativePath + COMPLETE_SUFFIX)
                targetFile.parentFile?.mkdirs()

                // 已完整下载则跳过
                if (completeFile.exists() && targetFile.exists() && targetFile.length() > 0) {
                    downloadedTotal += targetFile.length()
                    val p = min(downloadedTotal.toFloat() / totalSize.toFloat(), 1f)
                    _downloadProgress.value = p
                    onProgress(p)
                    continue
                }

                // 多镜像回退：依次尝试所有 URL，任一成功即可
                var fileOk = false
                for (url in file.allUrls()) {
                    val ok = downloadFileWithResume(url, targetFile) { bytesRead ->
                        downloadedTotal += bytesRead
                        val progress = if (totalSize > 0) {
                            downloadedTotal.toFloat() / totalSize.toFloat()
                        } else 0f
                        val pClamped = min(progress, 1f)
                        _downloadProgress.value = pClamped
                        onProgress(pClamped)
                        val now = System.currentTimeMillis()
                        if (now - lastNotifyMs > 500) {
                            lastNotifyMs = now
                            showDownloadNotification(pClamped, "${(pClamped * 100).toInt()}% · ${file.relativePath.substringAfterLast('/')}")
                        }
                    }
                    if (ok && targetFile.length() > 0) {
                        completeFile.createNewFile()
                        fileOk = true
                        break
                    }
                    Log.w(TAG, "下载失败，尝试下一个镜像：$url")
                }
                if (!fileOk) {
                    _state.value = EngineState.DOWNLOAD_FAILED("下载失败：${file.relativePath}（所有镜像均不可用）")
                    _downloadProgress.value = null
                    // 终态通知必须可划掉：showDownloadNotification 是 ongoing 的，
                    // 失败时留着一条划不掉的"下载失败"通知只能杀进程消失
                    cancelDownloadNotification()
                    return@withContext false
                }
            }
            _downloadProgress.value = 1f
            showDownloadCompleteNotification("下载完成，正在启用...")
            true
        } catch (e: kotlinx.coroutines.CancellationException) {
            // 调用方取消（离开页面等）：清掉 ongoing 通知、复位状态后向上传播。
            // 旧实现状态流永远停在 DOWNLOADING，UI 显示"下载中"直到进程重启
            cancelDownloadNotification()
            _downloadProgress.value = null
            _state.value = EngineState.MODEL_NOT_FOUND
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "downloadModel failed", e)
            _state.value = EngineState.DOWNLOAD_FAILED(e.message ?: "未知错误")
            _downloadProgress.value = null
            cancelDownloadNotification()
            false
        }
    }

    /**
     * 下载 tarball 并解压到 models 目录。
     * tarball 内顶层目录应为模型 id（如 vits-melo-tts-zh_en/），解压后路径与 files.relativePath 对齐。
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
        var lastNotifyMs = 0L

        // 下载 tarball（多镜像回退 + 断点续传）
        var downloaded = false
        for (url in modelInfo.tarballAllUrls()) {
            // 若已有完整 tarball 标记，跳过下载直接解压
            if (tarballComplete.exists() && tarballFile.exists() && tarballFile.length() > 0) {
                downloaded = true
                break
            }
            val ok = downloadFileWithResume(url, tarballFile) { bytesRead ->
                // sizeBytes 是解压后大小，而此阶段下载的是压缩后的 tar.bz2，
                // 拿它当分母进度会卡在 ~60% 再跳到"解压中"。
                // 压缩前后大小无可靠元数据，直接按不确定进度展示
                _downloadProgress.value = null
                onProgress(0f)
                val now = System.currentTimeMillis()
                if (now - lastNotifyMs > 500) {
                    lastNotifyMs = now
                    showDownloadNotification(null, "下载中（${tarballFile.length() / 1_000_000}MB）")
                }
            }
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
        try {
            val canonicalRoot = modelsDir.canonicalPath + File.separator
            var copiedSinceCheck = 0L
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
                            // 替代只查 ".." 子串的旧检查（会误杀 foo..bar、漏掉符号链接）
                            if ((!entry.isFile && !entry.isDirectory) ||
                                !File(modelsDir, name).canonicalPath.startsWith(canonicalRoot)
                            ) {
                                entry = tis.nextEntry
                                continue
                            }
                            val outFile = File(modelsDir, name)
                            if (entry.isDirectory) {
                                outFile.mkdirs()
                            } else {
                                outFile.parentFile?.mkdirs()
                                FileOutputStream(outFile).use { out ->
                                    val buf = ByteArray(262144)
                                    while (true) {
                                        val n = tis.read(buf)
                                        if (n == -1) break
                                        out.write(buf, 0, n)
                                        copiedSinceCheck += n
                                        if (copiedSinceCheck >= 262144) {
                                            copiedSinceCheck = 0
                                            kotlin.coroutines.coroutineContext[kotlinx.coroutines.Job]
                                                ?.ensureActive()
                                        }
                                    }
                                }
                            }
                            entry = tis.nextEntry
                        }
                    }
                }
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            // 被取消：清掉半截解压产物和 tarball，避免下次误用残文件
            Log.w(TAG, "extraction cancelled, cleaning partial files")
            modelInfo.files.forEach { f ->
                File(modelsDir, f.relativePath).let { if (it.exists()) it.delete() }
            }
            if (tarballFile.exists()) tarballFile.delete()
            cancelDownloadNotification()
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "解压失败", e)
            // 删掉损坏的 tarball 与完成标记：否则下次进来标记检查直接跳过
            // 下载、反复解压同一个坏归档，失败回退路径还可能让 ~100MB 永久驻盘
            if (tarballFile.exists()) tarballFile.delete()
            if (tarballComplete.exists()) tarballComplete.delete()
            cancelDownloadNotification()
            return false
        }

        // 为所有文件写 .complete 标记
        for (f in modelInfo.files) {
            val target = File(modelsDir, f.relativePath)
            // 目录：存在即可；文件：存在且非空
            val ok = if (target.isDirectory) target.exists() else target.exists() && target.length() > 0
            if (ok) {
                File(modelsDir, f.relativePath + COMPLETE_SUFFIX).createNewFile()
            } else {
                Log.e(TAG, "解压后文件缺失或为空：${f.relativePath}")
                // 归档缺文件：同样清掉 tarball，强制下次重新下载而不是重复解压
                if (tarballFile.exists()) tarballFile.delete()
                if (tarballComplete.exists()) tarballComplete.delete()
                return false
            }
        }

        // 删除 tarball 释放空间
        tarballFile.delete()
        tarballComplete.delete()
        return true
    }

    /**
     * 带断点续传的文件下载。
     * 若 target 已存在部分内容，通过 Range: bytes=offset- 请求续传。
     * 服务器不支持 Range 时回退为全量覆盖下载。
     */
    private suspend fun downloadFileWithResume(
        url: String,
        target: File,
        onChunkDownloaded: (Int) -> Unit,
    ): Boolean {
        var conn: HttpURLConnection? = null
        return try {
            val existingLen = if (target.exists()) target.length() else 0L
            conn = URL(url).openConnection() as HttpURLConnection
            conn.connectTimeout = 15_000
            conn.readTimeout = 30_000
            conn.doInput = true
            conn.instanceFollowRedirects = true
            if (existingLen > 0) {
                conn.setRequestProperty("Range", "bytes=$existingLen-")
            }
            conn.connect()

            val code = conn.responseCode
            when (code) {
                206 -> {
                    // Partial Content — 续传
                    appendStream(conn, target, existingLen, onChunkDownloaded)
                }
                200 -> {
                    // 全量 — 服务器不支持 Range 或无已有内容，覆盖下载
                    fullStream(conn, target, onChunkDownloaded)
                }
                else -> {
                    Log.e(TAG, "downloadFile: HTTP $code for $url")
                    false
                }
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            // 调用方协程已取消（如离开下载页面）：向上传播，不能吞掉
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "downloadFile failed for $url", e)
            false
        } finally {
            conn?.disconnect()
        }
    }

    private suspend fun appendStream(
        conn: HttpURLConnection,
        target: File,
        existingLen: Long,
        onChunkDownloaded: (Int) -> Unit,
    ): Boolean {
        // 校验：Range 请求返回的 Content-Range 起始必须精确等于 existingLen。
        // 旧实现在不匹配时把 206 响应当全量写——但该响应体是从 start 开始的，
        // 会产出缺了 [0, start) 字节的损坏文件且照常被标记 .complete
        val contentRange = conn.getHeaderField("Content-Range")
        val start = contentRange
            ?.substringAfter("bytes ", "")
            ?.substringBefore("-")
            ?.toLongOrNull()
        if (start == null || start != existingLen) {
            Log.w(TAG, "Content-Range mismatch (start=$start, existing=$existingLen), aborting resume")
            return false
        }
        return try {
            conn.inputStream.use { input ->
                java.io.FileOutputStream(target, /* append = */ true).use { output ->
                    val buffer = ByteArray(8192)
                    var sinceCheck = 0
                    while (true) {
                        val read = input.read(buffer)
                        if (read == -1) break
                        output.write(buffer, 0, read)
                        onChunkDownloaded(read)
                        // 协作式取消：字节循环本身无挂起点，取消的协程会把
                        // 整个大文件下完才退出。每 256KB 检查一次。
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
        onChunkDownloaded: (Int) -> Unit,
    ): Boolean {
        return try {
            conn.inputStream.use { input ->
                java.io.FileOutputStream(target, /* append = */ false).use { output ->
                    val buffer = ByteArray(8192)
                    var sinceCheck = 0
                    while (true) {
                        val read = input.read(buffer)
                        if (read == -1) break
                        output.write(buffer, 0, read)
                        onChunkDownloaded(read)
                        // 同 appendStream：周期性响应协程取消
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
                return@withContext true
            }
            _state.value = EngineState.INITIALIZING
            try {
                if (!isModelDownloaded(modelInfo)) {
                    _state.value = EngineState.MODEL_NOT_FOUND
                    return@withContext false
                }
                val dir = File(context.filesDir, MODELS_DIR_NAME)
                val modelDir = File(dir, modelInfo.id)

                // 通过文件名在已下载文件中查找路径，避免依赖 files 数组下标顺序
                fun findFile(name: String): String? =
                    modelInfo.files.firstOrNull { it.relativePath.endsWith("/$name") }
                        ?.let { File(dir, it.relativePath).absolutePath }

                val modelPath = findFile("model.onnx")
                    ?: throw IllegalStateException("缺少 model.onnx")
                val tokensPath = findFile("tokens.txt")
                    ?: throw IllegalStateException("缺少 tokens.txt")
                val lexiconPath = findFile("lexicon.txt") // 可选，英文 VITS-LJS 也用
                // MeloTTS 的 jieba 词典目录名为 dict
                val dictDirPath = modelInfo.files
                    .firstOrNull { it.relativePath.endsWith("/dict") }
                    ?.let { File(dir, it.relativePath).absolutePath }

                // VITS 模型配置（使用构造参数，避免依赖 var 字段默认值）
                val vitsConfig = OfflineTtsVitsModelConfig(
                    model = modelPath,
                    tokens = tokensPath,
                    lexicon = lexiconPath ?: "",
                    dataDir = modelDir.absolutePath,
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
                            // 替换前 shutdown 旧的
                            tts?.let { try { it.release() } catch (_: Exception) {} }
                            tts = newTts
                            assigned = true
                            currentModelName = modelInfo.id
                            sampleRate = newTts.sampleRate()
                            // 状态写入也进锁：出锁再写会与 release()（同锁内置
                            // tts=null + NOT_INITIALIZED）交错出 READY∧tts=null 的
                            // 说谎状态——之后所有 speak 静默失败而 UI 显示就绪
                            _state.value = EngineState.READY(modelInfo.id)
                        }
                    }
                } finally {
                    // 构造成功但从未赋值（等锁时被取消/异常）：显式释放，
                    // 上百 MB 的 native 模型不该只等 GC finalizer
                    if (!assigned) {
                        try { newTts.release() } catch (_: Exception) {}
                    }
                }
                Log.i(TAG, "Initialized sherpa-onnx OfflineTts: model=${modelInfo.id}, sampleRate=$sampleRate")
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

        val bufferSize = pcm16.size * 2
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

        // 等待播放完成
        val durationMs = (pcm16.size * 1000L) / sampleRate
        var elapsed = 0L
        try {
            while (elapsed < durationMs) {
                // delay 而非 Thread.sleep：释放 IO 线程，且能响应协程取消。
                // delay 在协程被 cancel 时会抛 CancellationException，自动退出循环。
                kotlinx.coroutines.delay(50)
                elapsed += 50
                synchronized(audioTrackLock) {
                    if (audioTrack !== track) {
                        // 被 stop()/新的 playPcm 接管：对方已在锁内 release 过这个 track。
                        // 这里不能再 release（双重释放会抛 IllegalStateException）
                        return
                    }
                }
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

    // ── 下载通知（保活 + 进度可见）──────────────────────
    // P1 修复: getSystemService 在系统服务被禁用/移除时返回 null,改 `as?` 防御
    private val notificationManager: NotificationManager? by lazy {
        context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
    }

    private fun ensureDownloadChannel() {
        // P1 修复: 同上,服务不可用时跳过 channel 创建(不阻塞 TTS 下载逻辑)
        val nm = notificationManager ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = nm.getNotificationChannel(DL_CHANNEL_ID)
            if (ch == null) {
                nm.createNotificationChannel(
                    NotificationChannel(
                        DL_CHANNEL_ID,
                        "语音模型下载",
                        NotificationManager.IMPORTANCE_LOW,
                    ).apply { setShowBadge(false) },
                )
            }
        }
    }

    /** 显示/更新下载进度通知。progress 0..1，null 表示不确定。 */
    fun showDownloadNotification(progress: Float?, contentText: String) {
        ensureDownloadChannel()
        // P1 修复: notificationManager 可能为 null,跳过通知发送(下载本身仍正常)
        val nm = notificationManager ?: return
        val builder = NotificationCompat.Builder(context, DL_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("下载内置语音模型")
            .setContentText(contentText)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
        if (progress != null) {
            builder.setProgress(100, (progress * 100).toInt(), false)
        } else {
            builder.setProgress(0, 0, true)
        }
        // 点击跳转 MainActivity（设置页）
        val pi = PendingIntent.getActivity(
            context, 0,
            Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        builder.setContentIntent(pi)
        nm.notify(DL_NOTIFICATION_ID, builder.build())
    }

    /**
     * 下载成功后的收尾通知：替换掉 ongoing 的进度通知。
     * 原实现成功后只重发 setOngoing(true) 的通知，永远不可划掉；
     * 这里改为普通通知 + autoCancel，同时结束进度通知的常驻状态。
     */
    private fun showDownloadCompleteNotification(contentText: String) {
        ensureDownloadChannel()
        val nm = notificationManager ?: run {
            // 服务不可用时至少要把 ongoing 进度通知撤掉
            cancelDownloadNotification()
            return
        }
        val builder = NotificationCompat.Builder(context, DL_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle("内置语音模型")
            .setContentText(contentText)
            .setOngoing(false)
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
        nm.notify(DL_NOTIFICATION_ID, builder.build())
    }

    /** 取消下载通知。 */
    fun cancelDownloadNotification() {
        // P1 修复: 同上
        notificationManager?.cancel(DL_NOTIFICATION_ID)
    }
}