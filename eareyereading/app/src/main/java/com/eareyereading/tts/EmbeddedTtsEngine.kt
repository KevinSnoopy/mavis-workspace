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

    // 当前正在跑的 speak() 协程的 Job。stop() 取消它，连带释放 mutex。
    private val speakJobLock = Any()
    private var currentSpeakJob: Job? = null

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
         * 用户在 UI 中可以选择用哪个。
         *
         * 默认推荐 MeloTTS-zh_en：中英双语，最适合本 app 的双语阅读场景。
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
                displayName = "VITS LJS 英文女声（约 109MB）",
                language = "en",
                sizeBytes = 109_000_000L,
                tarballUrl = "https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/vits-ljs.tar.bz2",
                files = listOf(
                    ModelFile(
                        "vits-ljs/model.onnx",
                        url = "https://hf-mirror.com/csukuangfj/vits-ljs/resolve/main/model.onnx",
                        mirrorUrls = listOf(
                            "https://huggingface.co/csukuangfj/vits-ljs/resolve/main/model.onnx",
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

    // 其他数字 (含小数)：转英文
    // 不含已处理过的年份/时间
    s = Regex("(?<!\\d)(\\d+)(?!\\d|:)").replace(s) { match ->
        numberToWords(match.value.toIntOrNull() ?: return@replace match.value)
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
    // 其他常见 OOV 符号
    s = s.replace("@", " at ")
    s = s.replace("&", " and ")
    s = s.replace("+", " plus ")
    s = s.replace("=", " equals ")
    s = s.replace("#", " number ")
    s = s.replace("%", " percent ")
    s = s.replace("\\", " ")
    s = s.replace("/", " ")            // 日期斜杠

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
 * 整数 → 英文单词（0-9999）。超过 9999 返回数字字符串本身。
 * 不处理负数（文本里基本不会有）。
 */
private fun numberToWords(n: Int): String {
    if (n < 0) return n.toString()
    if (n > 9999) return n.toString()
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

private fun splitForTts(text: String, maxChunkLen: Int = 200): List<String> {
    if (text.length <= maxChunkLen) return listOf(text)

    // 用同一个"句末 + 大写开头"的边界切
    val sentenceBoundary = Regex("(?<=[.!?])\\s+(?=[A-Z\"\\(])")
    val sentences = text.split(sentenceBoundary).filter { it.isNotBlank() }

    val chunks = mutableListOf<String>()
    val sb = StringBuilder()
    for (s in sentences) {
        // 单句已经超过 maxChunkLen（极少见，如超长标题）：硬切到 maxChunkLen
        if (s.length > maxChunkLen) {
            if (sb.isNotEmpty()) {
                chunks.add(sb.toString().trim())
                sb.clear()
            }
            var i = 0
            while (i < s.length) {
                val end = (i + maxChunkLen).coerceAtMost(s.length)
                chunks.add(s.substring(i, end))
                i = end
            }
            continue
        }
        // 累积句直到再加就会超过 maxChunkLen
        if (sb.isNotEmpty() && sb.length + 1 + s.length > maxChunkLen) {
            chunks.add(sb.toString().trim())
            sb.clear()
        }
        if (sb.isNotEmpty()) sb.append(' ')
        sb.append(s)
    }
    if (sb.isNotEmpty()) chunks.add(sb.toString().trim())
    return chunks
}

/**
 * 纯逐句切分（不累积）。按句末标点 . ! ? 后跟空白切分，
 * 每个元素是一个独立句子，适合逐句送入 sherpa-onnx generate()。
 * 单句仍可能很长（如标题），调用方自行截断。
 */
private fun splitSentences(text: String): List<String> {
    if (text.isBlank()) return emptyList()
    // 句末标点 + 空白 + 下一句开头（大写/引号/左括号/数字）
    val sentenceBoundary = Regex("(?<=[.!?])\\s+(?=[A-Z\"\\(\\d])")
    return text.split(sentenceBoundary)
        .map { it.trim() }
        .filter { it.isNotBlank() }
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
        return AVAILABLE_MODELS.firstOrNull { it.id == currentModelName }
            ?: AVAILABLE_MODELS.first { it.id == getSelectedModelId() }
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
                    showDownloadNotification(1f, "下载完成，正在启用...")
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
                    showDownloadNotification(null, "下载失败，请重试")
                    return@withContext false
                }
            }
            _downloadProgress.value = 1f
            showDownloadNotification(1f, "下载完成，正在启用...")
            true
        } catch (e: Exception) {
            Log.e(TAG, "downloadModel failed", e)
            _state.value = EngineState.DOWNLOAD_FAILED(e.message ?: "未知错误")
            _downloadProgress.value = null
            showDownloadNotification(null, "下载失败：${e.message ?: "未知错误"}")
            false
        }
    }

    /**
     * 下载 tarball 并解压到 models 目录。
     * tarball 内顶层目录应为模型 id（如 vits-melo-tts-zh_en/），解压后路径与 files.relativePath 对齐。
     * 下载到临时文件（支持断点续传），解压成功后删除 tarball 并为每个文件写 .complete 标记。
     */
    private fun downloadAndExtractTarball(
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
                val progress = if (totalSize > 0) {
                    (tarballFile.length().toFloat() / totalSize.toFloat())
                } else 0f
                val pClamped = min(progress, 1f)
                _downloadProgress.value = pClamped
                onProgress(pClamped)
                val now = System.currentTimeMillis()
                if (now - lastNotifyMs > 500) {
                    lastNotifyMs = now
                    showDownloadNotification(pClamped, "下载中 ${(pClamped * 100).toInt()}%")
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
            java.io.FileInputStream(tarballFile).use { fis ->
                BZip2CompressorInputStream(fis).use { bzis ->
                    TarArchiveInputStream(bzis).use { tis ->
                        var entry = tis.nextEntry
                        while (entry != null) {
                            val name = entry.name
                            // 安全：跳过 .. 路径
                            if (name.contains("..")) {
                                entry = tis.nextEntry
                                continue
                            }
                            val outFile = File(modelsDir, name)
                            if (entry.isDirectory) {
                                outFile.mkdirs()
                            } else {
                                outFile.parentFile?.mkdirs()
                                FileOutputStream(outFile).use { out ->
                                    tis.copyTo(out)
                                }
                            }
                            entry = tis.nextEntry
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "解压失败", e)
            showDownloadNotification(null, "解压失败")
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
    private fun downloadFileWithResume(
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
        } catch (e: Exception) {
            Log.e(TAG, "downloadFile failed for $url", e)
            false
        } finally {
            conn?.disconnect()
        }
    }

    private fun appendStream(
        conn: HttpURLConnection,
        target: File,
        existingLen: Long,
        onChunkDownloaded: (Int) -> Unit,
    ): Boolean {
        // 校验：Range 请求返回的 Content-Range 起始应等于 existingLen
        val contentRange = conn.getHeaderField("Content-Range")
        if (contentRange != null) {
            // 形如 "bytes 12345-99999/100000"
            val start = contentRange.substringAfter("bytes ").substringBefore("-").toLongOrNull()
            if (start != null && start != existingLen) {
                // 服务器返回的起始不匹配，回退全量
                return fullStream(conn, target, onChunkDownloaded)
            }
        }
        return try {
            conn.inputStream.use { input ->
                java.io.FileOutputStream(target, /* append = */ true).use { output ->
                    val buffer = ByteArray(8192)
                    while (true) {
                        val read = input.read(buffer)
                        if (read == -1) break
                        output.write(buffer, 0, read)
                        onChunkDownloaded(read)
                    }
                }
            }
            true
        } catch (e: Exception) {
            Log.e(TAG, "appendStream failed", e)
            false
        }
    }

    private fun fullStream(
        conn: HttpURLConnection,
        target: File,
        onChunkDownloaded: (Int) -> Unit,
    ): Boolean {
        return try {
            conn.inputStream.use { input ->
                java.io.FileOutputStream(target, /* append = */ false).use { output ->
                    val buffer = ByteArray(8192)
                    while (true) {
                        val read = input.read(buffer)
                        if (read == -1) break
                        output.write(buffer, 0, read)
                        onChunkDownloaded(read)
                    }
                }
            }
            true
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
            if (tts != null && currentModelName == modelInfo.id) {
                // 已加载同模型
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
                synchronized(this) {
                    // 替换前 shutdown 旧的
                    tts?.let { try { it.release() } catch (_: Exception) {} }
                    tts = newTts
                    currentModelName = modelInfo.id
                    sampleRate = newTts.sampleRate()
                }
                _state.value = EngineState.READY(modelInfo.id)
                Log.i(TAG, "Initialized sherpa-onnx OfflineTts: model=${modelInfo.id}, sampleRate=$sampleRate")
                true
            } catch (e: Exception) {
                Log.e(TAG, "initialize failed", e)
                _state.value = EngineState.FAILED(e.message ?: "初始化失败")
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
        // 同步 currentSpeakJob，stop() 可以取消当前播放（连同 mutex 一起释放）。
        synchronized(speakJobLock) {
            currentSpeakJob = coroutineContext[Job]
        }
        try {
            speakMutex.withLock {
                doSpeakLocked(text, speed, onDone)
            }
        } finally {
            synchronized(speakJobLock) {
                if (currentSpeakJob === coroutineContext[Job]) {
                    currentSpeakJob = null
                }
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
            for ((idx, sentence) in sentences.withIndex()) {
                if (sentence.isBlank()) continue
                // 每句之前检查协程是否已被取消（stop() 调用）。
                kotlinx.coroutines.yield()
                kotlinx.coroutines.currentCoroutineContext()[Job]?.let { job ->
                    if (!job.isActive) throw kotlinx.coroutines.CancellationException("stop() requested")
                }
                // 单句仍可能超长（如超长标题无句末标点），硬切到 150 字符避免 native 崩溃
                val safeSentence = if (sentence.length > 150) sentence.substring(0, 150) else sentence
                try {
                    val audio = currentTts.generate(safeSentence, sid = 0, speed = speed)
                    val pcm = audio.samples
                    val sr = audio.sampleRate
                    Log.i(
                        TAG,
                        "Embedded TTS sentence $idx/${sentences.size}: len=${safeSentence.length}, " +
                            "generated ${pcm.size} samples, duration=${"%.1f".format(pcm.size / sr.toFloat())}s",
                    )
                    playPcm(pcm, sr)
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // 单句 generate 崩溃（如 native G2P bug）：跳过该句，继续下一句
                    Log.e(TAG, "sentence $idx generate failed, skipping: '${safeSentence.take(60)}'", e)
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
        // 取消当前 speak 协程：让 doSpeakLocked 立刻退出（协程取消时
        // kotlinx coroutines Mutex.withLock 会在 finally 释放锁）。
        // 之前只停 AudioTrack 会导致旧 speak 继续在 mutex 里跑完整段，
        // 用户的"停止"按钮实际无效——新的 speak 必须等旧协程跑完才能进。
        synchronized(speakJobLock) {
            currentSpeakJob?.cancel()
            currentSpeakJob = null
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

        track.write(pcm16, 0, pcm16.size)
        track.play()

        // 等待播放完成
        val durationMs = (pcm16.size * 1000L) / sampleRate
        var elapsed = 0L
        while (elapsed < durationMs) {
            // delay 而非 Thread.sleep：释放 IO 线程，且能响应协程取消。
            // delay 在协程被 cancel 时会抛 CancellationException，自动退出循环。
            kotlinx.coroutines.delay(50)
            elapsed += 50
            synchronized(audioTrackLock) {
                if (audioTrack !== track) {
                    // 被 stop() 打断（新 track 或 null）
                    track.release()
                    return
                }
            }
        }
        track.release()
        synchronized(audioTrackLock) {
            if (audioTrack === track) audioTrack = null
        }
    }

    /**
     * 释放所有资源。
     */
    fun release() {
        stop()
        synchronized(this) {
            tts?.let { try { it.release() } catch (_: Exception) {} }
            tts = null
        }
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

    /** 取消下载通知。 */
    fun cancelDownloadNotification() {
        // P1 修复: 同上
        notificationManager?.cancel(DL_NOTIFICATION_ID)
    }
}