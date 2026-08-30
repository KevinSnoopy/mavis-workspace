@file:Suppress("TooGenericExceptionCaught")

package com.eareyereading.ui.screens.reader

import android.content.Context
import androidx.compose.ui.graphics.Color
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.eareyereading.data.local.dao.BookmarkDao
import com.eareyereading.data.local.dao.HighlightDao
import com.eareyereading.data.local.dao.ReadingStatsDao
import com.eareyereading.data.local.entity.BookmarkEntity
import com.eareyereading.data.local.entity.HighlightEntity
import com.eareyereading.domain.model.*
import com.eareyereading.domain.repository.*
import com.eareyereading.ui.theme.*
import com.eareyereading.util.*
import com.eareyereading.util.CollinsClassifier.WordLevel
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.isActive
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import javax.inject.Inject
import kotlin.coroutines.resume

/**
 * TTS 引擎引导弹窗的事件载荷。
 */
data class TtsInstallPrompt(
    val reason: TtsHelper.InitFailureReason,
    val availableEngines: List<TtsEngineHelper.TtsEngineInfo>,
    /** 自动回退候选引擎包名（如果不为空，弹窗会提示用户已自动尝试过此引擎） */
    val fallbackEnginePackage: String? = null,
    /** 通过 Intent 扫描发现的引擎（讯飞/百度/Google翻译等可能在这里） */
    val discoveredEngines: List<TtsEngineHelper.TtsEngineInfo> = emptyList(),
    /** 系统设置中选择的 TTS 引擎包名（Settings.Secure.TTS_DEFAULT_SYNTH） */
    val systemDefaultEnginePackage: String? = null,
    /** 是否处于"幽灵默认"状态：系统设置指向不存在的包，OEM 引擎不对第三方 app 开放 */
    val isPhantomDefaultState: Boolean = false,
    /** 设备上是否安装了 Google Play 商店 */
    val hasGooglePlay: Boolean = false,
    /** 未安装的第三方 TTS app（用于引导安装） */
    val uninstalledThirdPartyTtsApps: List<TtsEngineHelper.ThirdPartyTtsApp> = emptyList(),
    /** 安装指南步骤（当设备完全没有可用引擎时显示） */
    val installGuideSteps: List<String> = emptyList(),
    /** 当前 dialog 的对话场景（用于驱动 UI） */
    val scenario: DialogScenario = DialogScenario.NO_ENGINE,
    /** 内置 TTS 模型是否已下载 */
    val embeddedModelDownloaded: Boolean = false,
    /** 内置 TTS 模型显示名 */
    val embeddedModelDisplayName: String = "",
    /** 内置 TTS 模型大小（人类可读） */
    val embeddedModelSizeText: String = "",
) {
    enum class DialogScenario {
        HAS_DISCOVERED_ENGINES,
        SYSTEM_DEFAULT_INSTALLED_BUT_UNREACHABLE,
        NO_ENGINE,
    }
}

/**
 * 用户对 TTS 引导弹窗的回应动作。
 */
sealed class TtsInstallAction {
    /** 打开指定引擎（或默认）的系统设置 */
    data class OpenEngineSettings(val enginePackage: String?) : TtsInstallAction()
    /** 跳转到安装 Google TTS 的界面（Google Play 商店或浏览器） */
    data object InstallGoogleTts : TtsInstallAction()
    /** 跳转到"未知来源应用"设置（让用户允许安装第三方 APK） */
    data object OpenUnknownSourcesSettings : TtsInstallAction()
    /** 用指定的引擎包名重新初始化 */
    data class RetryWithEngine(val enginePackage: String) : TtsInstallAction()
    /** 跳转到安装第三方 TTS app 的下载页面 */
    data class InstallThirdPartyTtsApp(val app: TtsEngineHelper.ThirdPartyTtsApp) : TtsInstallAction()
    /** 下载内置 TTS 模型 */
    data object DownloadEmbeddedTts : TtsInstallAction()
    /** 关闭弹窗 */
    data object Dismiss : TtsInstallAction()
}

data class ReaderUiState(
    val book: Book? = null,
    val paragraphs: List<String> = emptyList(),
    val currentParagraphIndex: Int = 0,
    val currentWordIndex: Int = 0,
    val readingMode: ReadingMode = ReadingMode.NORMAL,
    val rsvpSpeed: Int = 300,
    val rsvpStrength: Int = 3,    // 1-5，影响加粗字母占比
    val fontSize: Int = 18,
    val theme: ReadingTheme = ReadingTheme.LIGHT,
    val isPlaying: Boolean = false,
    val isTtsPlaying: Boolean = false,
    val ttsInitialized: Boolean = false,
    // 自动朗读（句子级同步）
    val isAutoReading: Boolean = false,
    val autoReadingParaIndex: Int = 0,
    val currentSentences: List<String> = emptyList(),
    val currentSentenceIndex: Int = 0,
    // 生词本词汇（用于阅读时高亮）
    val knownWords: Set<String> = emptySet(),
    val learnedWords: Set<String> = emptySet(),
    // 挖空
    val clozeWords: List<ClozeWord> = emptyList(),
    val hiddenWordAnswer: String? = null,
    // 模糊
    val fuzzyWords: List<FuzzyWord> = emptyList(),
    // 生词提示
    val wordDefinition: String? = null,
    val selectedWordLevel: WordLevel = WordLevel.UNKNOWN,
    val showWordDialog: Boolean = false,
    // 选中词汇（加入生词本后此处会更新为带 DB id 的完整 Vocabulary 对象）
    val selectedVocab: Vocabulary? = null,
    // 全文翻译
    val showTranslation: Boolean = false,
    val paragraphTranslations: Map<Int, String> = emptyMap(),
    val isTranslating: Boolean = false,
    val translationAlpha: Float = 0.85f,
    // Collins 词频色彩
    val showWordLevelColors: Boolean = false,
    // 生词本高亮
    val showKnownWordsHighlight: Boolean = true,
    // 导航
    val showModeSelector: Boolean = false,
    val showSettings: Boolean = false,
    val showChapterNav: Boolean = false,
    // 阅读统计
    val readingStartTime: Long = 0L,
    val totalReadChars: Long = 0L,
    // 书签
    val bookmarkedParagraphs: Set<Int> = emptySet(),
    // 高亮
    val highlights: Map<Int, List<HighlightData>> = emptyMap(),
    // 内置 TTS 模型下载进度（0..1）；null = 无下载任务。
    // 阅读页引导弹窗内直接展示，不再只能去设置页看进度
    val embeddedDownloadProgress: Float? = null,
    // 加载
    val isLoading: Boolean = true,
)

// 高亮数据（用于渲染）
data class HighlightData(
    val id: Long,
    val startOffset: Int,
    val endOffset: Int,
    val text: String,
    val color: Color,
)

private data class ReadingSettings(
    val speed: Int,
    val fontSize: Int,
    val theme: ReadingTheme,
    val alpha: Float,
    val strength: Int = 3,
)

@HiltViewModel
class ReaderViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val bookRepository: BookRepository,
    private val vocabularyRepository: VocabularyRepository,
    private val readingRepository: ReadingRepository,
    private val settingsRepository: SettingsRepository,
    private val wordAnalyzer: WordAnalyzer,
    private val ttsHelper: TtsHelper,
    private val translationHelper: TranslationHelper,
    private val epubParser: EpubParser,
    private val collinsClassifier: CollinsClassifier,
    private val bookmarkDao: BookmarkDao,
    private val highlightDao: HighlightDao,
    private val readingStatsDao: ReadingStatsDao,
) : ViewModel() {

    private val _uiState = MutableStateFlow(ReaderUiState())
    val uiState: StateFlow<ReaderUiState> = _uiState.asStateFlow()

    // 一次性 UI 提示（错误 / 警告），UI 层收集后弹 Toast
    private val _toastMessage = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val toastMessage: SharedFlow<String> = _toastMessage.asSharedFlow()

    // TTS 引擎引导事件：弹窗引导用户去设置/安装 TTS 引擎
    private val _ttsInstallPrompt = MutableSharedFlow<TtsInstallPrompt>(extraBufferCapacity = 2)
    val ttsInstallPrompt: SharedFlow<TtsInstallPrompt> = _ttsInstallPrompt.asSharedFlow()

    private fun showToast(msg: String) {
        _toastMessage.tryEmit(msg)
    }

    private suspend fun showTtsInstallPrompt(reason: TtsHelper.InitFailureReason, force: Boolean = false) {
        // 防抖：本会话内已经弹过则不再弹。
        // 例外：当完全没有系统 TTS 引擎且内置模型未下载时，弹窗是用户触达"下载内置 TTS"
        // 的唯一入口，必须允许每次点击朗读都重新弹出，否则用户关掉一次就再也找不到下载按钮。
        val embeddedEngine = ttsHelper.getEmbeddedEngine()
        val embeddedNotDownloaded = !embeddedEngine.isModelDownloaded()
        val isNoEngineCritical = reason == TtsHelper.InitFailureReason.NO_ENGINE && embeddedNotDownloaded
        if (!force && !isNoEngineCritical && ttsPromptShownThisSession) {
            android.util.Log.d("ReaderViewModel", "TTS install prompt suppressed (already shown this session)")
            return
        }
        // 引擎扫描全是 PackageManager/Intent 解析查询：放 IO 线程。
        // 这个弹窗恰恰出现在最卡的国产低端机上，主线程扫描是看得见的卡顿/ANR
        var engines: List<TtsEngineHelper.TtsEngineInfo> = emptyList()
        var fallback: TtsEngineHelper.TtsEngineInfo? = null
        var discovered: List<TtsEngineHelper.TtsEngineInfo> = emptyList()
        var systemDefaultPkg: String? = null
        var isPhantom = false
        var hasPlay = false
        var uninstalledTtsApps: List<TtsEngineHelper.ThirdPartyTtsApp> = emptyList()
        withContext(Dispatchers.IO) {
            engines = TtsEngineHelper.listAvailableEngines(context)
            fallback = TtsEngineHelper.findFallbackEngine(context)
            discovered = TtsEngineHelper.discoverAllTtsEngines(context)
            systemDefaultPkg = TtsEngineHelper.getSystemDefaultEnginePackage(context)
            isPhantom = TtsEngineHelper.isPhantomDefaultState(context)
            hasPlay = TtsEngineHelper.hasGooglePlay(context)
            uninstalledTtsApps = TtsEngineHelper.listUninstalledThirdPartyTtsApps(context)
        }
        // withContext 里赋值的 var 不能智能转换：取本地快照供下方判断
        val sdPkg = systemDefaultPkg

        // 内置 TTS 状态（embeddedEngine 已在上方防抖判断时获取，此处复用）
        val embeddedDownloaded = embeddedEngine.isModelDownloaded()
        val embeddedModelInfo = embeddedEngine.getCurrentModelInfo()
        val embeddedSizeText = formatBytes(embeddedModelInfo.sizeBytes)

        // 决定场景
        val scenario = when {
            discovered.isNotEmpty() ->
                TtsInstallPrompt.DialogScenario.HAS_DISCOVERED_ENGINES
            // 系统设置指向**已安装**的已知引擎包才算"可重试连接"：
            // 指向已知但未安装的包（phantom 场景）时给用户"重试连接"按钮
            // 只会白等 15s 超时，应走 NO_ENGINE 引导安装
            sdPkg != null &&
                !isPhantom &&
                TtsEngineHelper.isKnownTtsEnginePackage(sdPkg) &&
                TtsEngineHelper.checkPackage(context, sdPkg) != null ->
                TtsInstallPrompt.DialogScenario.SYSTEM_DEFAULT_INSTALLED_BUT_UNREACHABLE
            else ->
                TtsInstallPrompt.DialogScenario.NO_ENGINE
        }
        val installGuide = if (scenario == TtsInstallPrompt.DialogScenario.NO_ENGINE) {
            TtsEngineHelper.getInstallGuideSteps(context)
        } else {
            emptyList()
        }

        android.util.Log.i(
            "ReaderViewModel",
            "Showing TTS prompt: scenario=$scenario, " +
                "discovered=${discovered.size}, systemDefault=$sdPkg, " +
                "fallback=${fallback?.packageName}, embeddedDownloaded=$embeddedDownloaded"
        )
        _ttsInstallPrompt.tryEmit(
            TtsInstallPrompt(
                reason = reason,
                availableEngines = engines,
                fallbackEnginePackage = fallback?.packageName,
                discoveredEngines = discovered,
                systemDefaultEnginePackage = sdPkg,
                isPhantomDefaultState = isPhantom,
                hasGooglePlay = hasPlay,
                uninstalledThirdPartyTtsApps = uninstalledTtsApps,
                installGuideSteps = installGuide,
                scenario = scenario,
                embeddedModelDownloaded = embeddedDownloaded,
                embeddedModelDisplayName = embeddedModelInfo.displayName,
                embeddedModelSizeText = embeddedSizeText,
            )
        )
        ttsPromptShownThisSession = true
    }

    private fun formatBytes(bytes: Long): String {
        if (bytes < 1024) return "$bytes B"
        val kb = bytes / 1024.0
        if (kb < 1024) return "%.1f KB".format(kb)
        val mb = kb / 1024.0
        return "%.0f MB".format(mb)
    }

    /**
     * 当用户手动尝试某个引擎成功后，重置防抖标记，以便后续失败仍能弹窗。
     */
    fun onTtsInitSucceeded() {
        ttsPromptShownThisSession = false
    }

    /**
     * 用户确认安装引导后，由 UI 层调用，处理后续跳转或安装动作。
     */
    fun onTtsInstallAction(action: TtsInstallAction) {
        when (action) {
            is TtsInstallAction.OpenEngineSettings -> {
                val intent = TtsEngineHelper.buildTtsSettingsIntent(action.enginePackage)
                intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                try {
                    context.startActivity(intent)
                } catch (e: android.content.ActivityNotFoundException) {
                    android.util.Log.w("ReaderViewModel", "TTS settings not found", e)
                    showToast("未找到 TTS 设置入口")
                }
            }
            is TtsInstallAction.InstallGoogleTts -> {
                val intent = if (TtsEngineHelper.hasGooglePlay(context)) {
                    TtsEngineHelper.buildGooglePlayIntentForGoogleTts()
                } else {
                    TtsEngineHelper.buildApkDownloadIntentForGoogleTts()
                }
                try {
                    context.startActivity(intent)
                } catch (e: android.content.ActivityNotFoundException) {
                    android.util.Log.w("ReaderViewModel", "Google TTS install not found", e)
                    showToast("未找到 Google TTS 安装入口，请手动到应用商店搜索下载")
                }
            }
            is TtsInstallAction.InstallThirdPartyTtsApp -> {
                val intent = TtsEngineHelper.buildInstallAppIntent(
                    action.app,
                    hasPlay = TtsEngineHelper.hasGooglePlay(context),
                )
                try {
                    context.startActivity(intent)
                } catch (e: android.content.ActivityNotFoundException) {
                    android.util.Log.w("ReaderViewModel", "Third-party TTS install not found", e)
                    showToast("未找到下载入口，请手动到应用商店搜索「${action.app.displayName}」")
                }
            }
            is TtsInstallAction.OpenUnknownSourcesSettings -> {
                val intent = TtsEngineHelper.buildUnknownSourcesSettingsIntent(context)
                try {
                    context.startActivity(intent)
                    showToast("请找到浏览器/文件管理器并允许安装未知应用")
                } catch (e: android.content.ActivityNotFoundException) {
                    android.util.Log.w("ReaderViewModel", "Unknown sources settings not found", e)
                    showToast("未找到对应设置，请到设置→安全→未知来源 手动开启")
                }
            }
            is TtsInstallAction.DownloadEmbeddedTts -> {
                downloadEmbeddedTtsModel()
            }
            is TtsInstallAction.RetryWithEngine -> {
                retryTtsInitWithEngine(action.enginePackage)
            }
            is TtsInstallAction.Dismiss -> { /* no-op */ }
        }
    }

    /**
     * 下载内置 TTS 模型，下载完后自动初始化并启用。
     */
    private fun downloadEmbeddedTtsModel() {
        // 防重入：弹窗按钮在下载期间仍可点，连点会并发下载同一个模型
        if (downloadJob?.isActive == true) {
            showToast("下载进行中，请稍候")
            return
        }
        val embeddedEngine = ttsHelper.getEmbeddedEngine()
        val modelInfo = embeddedEngine.getCurrentModelInfo()
        showToast("开始下载内置 TTS 模型（约 ${modelInfo.sizeBytes / 1_000_000}MB），请保持网络...")
        downloadJob = viewModelScope.launch {
            // 页内进度可见：引擎的 downloadProgress 流镜像进 uiState，
            // 引导弹窗保持打开并显示进度条（原实现进度只 log，弹窗直接关闭，
            // 想看进度只能去设置页）。按整百分比节流，避免高频重组
            var lastEmittedPct = -1
            val progressJob = launch {
                embeddedEngine.downloadProgress.collect { p ->
                    val pct = p?.let { (it * 100).toInt() } ?: -1
                    if (pct != lastEmittedPct) {
                        lastEmittedPct = pct
                        _uiState.update { it.copy(embeddedDownloadProgress = p) }
                    }
                }
            }
            try {
                val ok = embeddedEngine.downloadModel(modelInfo) { progress ->
                    android.util.Log.d("ReaderViewModel", "Embedded TTS download progress: ${(progress * 100).toInt()}%")
                }
                if (ok) {
                    showToast("下载完成，正在启用内置 TTS...")
                    val initOk = ttsHelper.initializeEmbeddedForced()
                    if (initOk) {
                        showToast("✅ 内置 TTS 已启用！现在可以朗读了")
                    } else {
                        showToast("模型下载完成但初始化失败")
                    }
                } else {
                    showToast("下载失败，请检查网络后重试")
                }
            } finally {
                progressJob.cancel()
                _uiState.update { it.copy(embeddedDownloadProgress = null) }
            }
        }
    }

    /**
     * 使用指定的引擎包名重新尝试初始化 TTS。
     *
     * 特殊值 `__EMBEDDED__` 表示激活内置 sherpa-onnx TTS。
     */
    private fun retryTtsInitWithEngine(enginePackage: String?) {
        // 追踪重试协程：退出页面时随 cleanup() 一起取消，
        // 不会在用户离开后继续跑 15 秒初始化再弹窗
        ttsInitJob?.cancel()
        ttsInitJob = viewModelScope.launch {
            val ok = if (enginePackage == "__EMBEDDED__") {
                // 特殊值：激活内置 TTS。
                // CancellationException 必须重抛：cleanup()/stopAllPlayback() 取消本 job 后，
                // 若被这里的 catch (Exception) 吞掉，协程会继续往下写 uiState、
                // 甚至在用户已离开后弹引导窗（迟到状态写入）
                try {
                    ttsHelper.initializeEmbeddedForced()
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    android.util.Log.e("ReaderViewModel", "Embedded TTS init failed", e)
                    false
                }
            } else {
                try {
                    ttsHelper.initializeWith(
                        language = _uiState.value.book?.language ?: "en",
                        enginePackage = enginePackage,
                    )
                } catch (e: TimeoutCancellationException) {
                    android.util.Log.w("ReaderViewModel", "TTS init timed out (retry)", e)
                    false
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    android.util.Log.e("ReaderViewModel", "TTS init failed (retry)", e)
                    false
                }
            }
            _uiState.update { it.copy(ttsInitialized = ok) }
            if (ok) {
                ttsPromptShownThisSession = false // 重置防抖
                showToast("TTS 已启用")
            } else {
                val reason = ttsHelper.lastFailureReason
                // 用户主动重试时强制弹窗
                if (reason != null) showTtsInstallPrompt(reason, force = true)
                else showToast("TTS 初始化仍然失败")
            }
        }
    }

    /**
     * 处理 TTS 初始化失败：设置状态、弹提示/引导。
     */
    private suspend fun handleTtsInitFailure(prefix: String) {
        _uiState.update { it.copy(ttsInitialized = false) }
        val reason = ttsHelper.lastFailureReason
        // 特化：系统 TTS 全失败且内置模型未下载 — 这是国产手机最常见场景，
        // 提示应指向"下载内置 TTS"而非"安装系统引擎"。
        val embeddedNotDownloaded = !ttsHelper.getEmbeddedEngine().isModelDownloaded()
        val message = when {
            reason == TtsHelper.InitFailureReason.NO_ENGINE && embeddedNotDownloaded ->
                "$prefix：系统 TTS 不可用，需下载内置语音模型（约 ${ttsHelper.getEmbeddedEngine().getCurrentModelInfo().sizeBytes / 1_000_000}MB）"
            reason == null -> "$prefix：设备未安装 TTS 引擎"
            TtsEngineHelper.isChineseDevice() -> {
                // 国产手机：附加品牌专属提示
                val vendorHint = TtsEngineHelper.getEngineDisplayInfo(
                    TtsEngineHelper.findFallbackEngine(context)?.packageName ?: ""
                ).first
                "$prefix：${reason.userMessage}（已尝试 $vendorHint 引擎）"
            }
            else -> "$prefix：${reason.userMessage}"
        }
        showToast(message)
        if (reason != null && reason != TtsHelper.InitFailureReason.LANGUAGE_UNSUPPORTED) {
            // 引导用户去安装/启用 TTS 引擎（语言不支持不引导，避免骚扰）
            showTtsInstallPrompt(reason)
        }
    }

    private var rsvpJob: Job? = null
    private var speedJob: Job? = null
    private var autoReadJob: Job? = null
    private var vocabJob: Job? = null
    private var bookmarksJob: Job? = null
    private var highlightsJob: Job? = null
    private var bookJob: Job? = null
    private var currentBookId: Long? = null
    private var readingStartTime: Long = 0L

    companion object {
        // 自动朗读：段落间停顿时间（毫秒）
        private const val PARAGRAPH_PAUSE_MS = 600L
        // 快速阅读：默认语速（词/分钟），用于计算每段停留时间
        private const val SPEED_READ_WPM = 130
        // 快速阅读：每段最小停留时间（毫秒）
        private const val SPEED_READ_MIN_DELAY_MS = 1500L
        // 挖空练习：挖空比例
        private const val CLOZE_RATIO = 0.15f
        // 模糊听读：可见字符比例
        private const val FUZZY_VISIBLE_RATIO = 0.3f
        // 翻译透明度下限
        private const val TRANSLATION_ALPHA_MIN = 0.3f
        private const val TRANSLATION_ALPHA_MAX = 1f

        private const val SETTINGS_PERSIST_DEBOUNCE_MS = 300L

        // 句子边界（ASCII）：句末标点 + 空白 + 大写字母/引号/左括号。
        // "Aug." "Mr." "Dr." 这类缩写后的 "." + 空格 + 小写/数字不会误切，
        // 而 "happened. The" 的正常句子边界仍能切出。提升为常量避免热路径重复编译。
        private val SENTENCE_BOUNDARY = Regex("(?<=[.!?])\\s+(?=[A-Z\"\\(])")

        // 句子边界（CJK）：全角句点 。！？；（允许尾随闭引号/括号）。
        // 中文不靠空白分句；不处理的话整段中文是一个"句子"，
        // 与引擎侧切分不一致且被逐句长度限制截断
        private val SENTENCE_BOUNDARY_CJK = Regex("(?<=[。！？；][”’」』]?)")
    }

    /** 与 EmbeddedTtsEngine 侧一致的句子切分：先按全角句点切，再按 ASCII 边界切。 */
    private fun splitSentencesCompat(text: String): List<String> =
        text.split(SENTENCE_BOUNDARY_CJK)
            .flatMap { it.split(SENTENCE_BOUNDARY) }
            .map { it.trim() }
            .filter { it.isNotBlank() }
    // 本次阅读会话的统计（用于 saveProgress/cleanup 时写入 DB）
    private var sessionCharsRead: Long = 0L
    private var lastRecordedParagraphIndex: Int = -1
    // 增量落库的时间基准：距上次落库满 1 分钟才增量写一次，
    // 避免进程被杀丢失整段会话，也避免每次保存都记 1 分钟
    private var lastFlushTime = 0L
    // 书籍是否成功加载过：未加载成功时退出不得写任何进度/状态（防孤儿行）
    private var bookLoaded = false
    // saveProgress 防抖/收尾用：拖动进度条不再每像素写一次 DB
    private var saveJob: kotlinx.coroutines.Job? = null

    // 设置滑杆逐像素写 DataStore 的防抖：UI 状态立即更新保证滑杆跟手，
    // 持久化合并到拖停后一次（与 saveProgress 同型）。按设置项分 key，
    // 一个滑杆的拖动不会取消另一项的待写；退出时由 cleanup() 兜底冲刷
    private val settingsPersistJobs = mutableMapOf<String, kotlinx.coroutines.Job>()
    private val settingsPendingWrites = mutableMapOf<String, suspend () -> Unit>()

    private fun persistSettingDebounced(key: String, write: suspend () -> Unit) {
        settingsPersistJobs[key]?.cancel()
        settingsPendingWrites[key] = write
        settingsPersistJobs[key] = viewModelScope.launch {
            delay(SETTINGS_PERSIST_DEBOUNCE_MS)
            write()
            // 按身份移除：只清自己这条，不误删并发排队的同名写入
            if (settingsPendingWrites[key] === write) {
                settingsPendingWrites.remove(key)
            }
        }
    }
    // 书签切换用互斥锁串行化：真正的互斥而不是 cancel 上一个
    // （cancel 不阻塞、Room 语句中途不响应取消，竞态窗口仍在）
    private val bookmarkMutex = kotlinx.coroutines.sync.Mutex()
    private var bookmarkToggleJob: kotlinx.coroutines.Job? = null
    // 内置 TTS 模型下载防重入
    private var downloadJob: kotlinx.coroutines.Job? = null
    // 点词查询串行化：后一次点词取消前一次，慢查询不再覆盖新弹窗
    private var selectWordJob: kotlinx.coroutines.Job? = null
    // 全书翻译任务追踪：退出时可取消，防止 ML Kit 在后台空转完整本书
    private var translationJob: kotlinx.coroutines.Job? = null
    // 单段朗读的初始化尝试（防初始化窗口内连点产生重复朗读）
    private var ttsInitJob: kotlinx.coroutines.Job? = null

    // TTS 引导弹窗防抖：本会话内已经弹过则不再弹（避免用户每次点朗读都看到同一个弹窗）
    private var ttsPromptShownThisSession = false

    init {
        viewModelScope.launch {
            try {
                combine(
                    settingsRepository.getRsvpSpeed(),
                    settingsRepository.getRsvpStrength(),
                    settingsRepository.getFontSize(),
                    settingsRepository.getTheme(),
                    settingsRepository.getTranslationAlpha(),
                ) { values ->
                    // P1 修复: 用 as? 安全转换 + 默认值,避免 DataStore 旧版本数据 schema
                    // 不匹配时 ClassCastException 直接死掉 init block(整个 Reader 屏开不起来)。
                    // 当前 SettingsRepository 返回类型稳定,但 as 是脆性耦合,加防御。
                    @Suppress("UNCHECKED_CAST")
                    val speed = values[0] as? Int ?: 300
                    @Suppress("UNCHECKED_CAST")
                    val strength = values[1] as? Int ?: 3
                    @Suppress("UNCHECKED_CAST")
                    val fontSize = values[2] as? Int ?: 18
                    @Suppress("UNCHECKED_CAST")
                    val theme = values[3] as? ReadingTheme ?: ReadingTheme.LIGHT
                    @Suppress("UNCHECKED_CAST")
                    val alpha = values[4] as? Float ?: 0.85f
                    ReadingSettings(speed, fontSize, theme, alpha, strength)
                }.collect { s ->
                    _uiState.update {
                        it.copy(
                            // 已打开书籍时，书籍自带的 rsvpSpeed 优先（loadBook 写入），
                            // 全局设置的（重）发射不再覆盖它，消除双写竞态
                            rsvpSpeed = if (currentBookId != null) it.rsvpSpeed else s.speed,
                            rsvpStrength = s.strength,
                            fontSize = s.fontSize,
                            theme = s.theme,
                            translationAlpha = s.alpha,
                        )
                    }
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                android.util.Log.e("ReaderViewModel", "settings combine failed", e)
            }
        }

        // TTS 语速倍率：此前设置页可写、数据层可存，但没有任何消费者（死线）。
        // 这里接到 ttsHelper.setSpeed，系统/内置朗读都会生效
        viewModelScope.launch {
            try {
                settingsRepository.getTtsSpeed().collect { speed ->
                    ttsHelper.setSpeed(speed)
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                android.util.Log.e("ReaderViewModel", "tts speed collect failed", e)
            }
        }
    }

    fun loadBook(bookId: Long) {
        // 同一 VM 重新加载（换书/重进）：先停掉所有播放，
        // 旧循环持有的是旧段落快照，继续跑会越界/错读
        stopAllPlayback()

        // 切换书籍前，把上一本书的会话统计先落库（若有未落库部分）
        currentBookId?.let { prevId ->
            if (sessionCharsRead > 0) {
                viewModelScope.launch { flushSessionStats(prevId) }
            }
        }

        currentBookId = bookId
        readingStartTime = System.currentTimeMillis()
        lastFlushTime = System.currentTimeMillis()
        sessionCharsRead = 0L
        lastRecordedParagraphIndex = -1

        // 取消旧的 Flow collectors，防止泄漏
        vocabJob?.cancel()
        bookmarksJob?.cancel()
        highlightsJob?.cancel()
        bookJob?.cancel()

        _uiState.update { it.copy(isLoading = true, readingStartTime = readingStartTime) }

        // 加载生词本（用于阅读高亮）
        vocabJob = viewModelScope.launch {
            try {
                vocabularyRepository.getAllVocabulary().collect { vocabList ->
                    val known = vocabList.filter { it.isLearned }.map { it.word.lowercase(java.util.Locale.ROOT) }.toSet()
                    val allWords = vocabList.map { it.word.lowercase(java.util.Locale.ROOT) }.toSet()
                    _uiState.update { it.copy(knownWords = known, learnedWords = allWords) }
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                android.util.Log.e("ReaderViewModel", "vocab collect failed", e)
            }
        }

        bookJob = viewModelScope.launch {
            try {
                // 用 first() 而非 collect() — 单次拉取，避免 updateProgress 后 Flow 重发射时
                // 错误地将 currentParagraphIndex 重置为保存的旧位置（覆盖用户当前阅读进度）
                val book = bookRepository.getBookById(bookId).first()
                if (book == null) {
                    // 书籍不存在（深链失效/已删除）：明确提示，由页面自动返回；
                    // 同时保持 bookLoaded = false，退出时不写孤儿进度行
                    android.util.Log.w("ReaderViewModel", "loadBook: book $bookId not found")
                    _uiState.update { it.copy(isLoading = false) }
                    showToast("书籍不存在或已被删除")
                    return@launch
                }
                val paragraphs = if (book.content.isNotBlank()) {
                    book.content.split("\n\n").filter { it.isNotBlank() }
                } else {
                    epubParser.parseBook(book.filePath)
                }
                val state = readingRepository.getState(bookId)
                // 与 saveState 持久化的 totalCharacters 口径一致（都按段落分隔符拼接）
                val totalChars = paragraphs.joinToString("\n\n").length.toLong()
                // 内容可能比重导入/重切分，持久化的位置必须按新内容收敛，
                // 否则 Slider/进度/朗读索引全部越界
                val maxIdx = (paragraphs.size - 1).coerceAtLeast(0)

                _uiState.update {
                    it.copy(
                        book = book,
                        paragraphs = paragraphs,
                        currentParagraphIndex = (state?.currentParagraph ?: 0).coerceIn(0, maxIdx),
                        currentWordIndex = (state?.currentPosition ?: 0).coerceAtLeast(0),
                        readingMode = state?.readingMode ?: ReadingMode.NORMAL,
                        rsvpSpeed = state?.rsvpSpeed ?: it.rsvpSpeed,
                        // 每本书持久化的字号/主题随书恢复（此前只写不读，往返不对称）
                        fontSize = state?.fontSize ?: it.fontSize,
                        theme = state?.theme ?: it.theme,
                        totalReadChars = totalChars,
                        isLoading = false,
                    )
                }
                bookLoaded = true
                // 字符统计的高水位从"恢复后的位置"起算，而不是 -1：
                // 否则退出时 doSaveProgress 会把 0..恢复位置 的整段前缀当成本次新读，
                // 累计写库后每次重开同一本书今日字数都会虚增一截
                lastRecordedParagraphIndex = (state?.currentParagraph ?: 0).coerceIn(0, maxIdx)

                // 恢复的阅读模式若依赖派生数据（挖空/模糊），必须立即生成，
                // 否则重开书是空白页（此前只有 setReadingMode 会生成）
                when (_uiState.value.readingMode) {
                    ReadingMode.CLOZE -> generateCloze()
                    ReadingMode.FUZZY -> generateFuzzy()
                    else -> Unit
                }

                // TTS 是单例、跨书复用：无论是否已初始化都要同步语言，
                // 否则读完英文书再开中文书会用旧 locale 一直读下去
                ttsHelper.setLanguage(book.language)

                // 初始化 TTS
                if (!_uiState.value.ttsInitialized) {
                    val ok = try {
                        ttsHelper.initialize(book.language)
                    } catch (e: TimeoutCancellationException) {
                        android.util.Log.w("ReaderViewModel", "TTS init timed out", e)
                        false
                    } catch (e: kotlinx.coroutines.CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        android.util.Log.e("ReaderViewModel", "TTS init failed", e)
                        false
                    }
                    _uiState.update { it.copy(ttsInitialized = ok) }
                    // 加载书籍时静默失败，不弹引导（等用户点击朗读时再弹）
                    if (!ok) {
                        android.util.Log.i(
                            "ReaderViewModel",
                            "TTS init failed silently on load: ${ttsHelper.lastFailureReason}",
                        )
                    }
                }

                // 加载书签
                bookmarksJob?.cancel()
                bookmarksJob = viewModelScope.launch {
                    try {
                        bookmarkDao.getBookmarksForBook(bookId).collect { bookmarks ->
                            _uiState.update {
                                it.copy(bookmarkedParagraphs = bookmarks.map { b -> b.paragraphIndex }.toSet())
                            }
                        }
                    } catch (e: kotlinx.coroutines.CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        android.util.Log.e("ReaderViewModel", "bookmarks collect failed", e)
                    }
                }

                // 加载高亮
                highlightsJob?.cancel()
                highlightsJob = viewModelScope.launch {
                    try {
                        highlightDao.getHighlightsForBook(bookId).collect { highlights ->
                            val grouped = highlights.groupBy { it.paragraphIndex }.mapValues { (_, list) ->
                                list.map { h ->
                                    HighlightData(
                                        id = h.id,
                                        startOffset = h.startOffset,
                                        endOffset = h.endOffset,
                                        text = h.text,
                                        color = parseHighlightColor(h.color),
                                    )
                                }
                            }
                            _uiState.update { it.copy(highlights = grouped) }
                        }
                    } catch (e: kotlinx.coroutines.CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        android.util.Log.e("ReaderViewModel", "highlights collect failed", e)
                    }
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                // 损坏/缺失的 EPUB、DB 异常等不再经由未捕获处理器崩 App
                android.util.Log.e("ReaderViewModel", "loadBook failed", e)
                _uiState.update { it.copy(isLoading = false) }
                showToast("书籍加载失败")
            }
        }
    }

    private fun parseHighlightColor(hex: String): Color {
        return try {
            Color(android.graphics.Color.parseColor(hex))
        } catch (e: java.lang.IllegalArgumentException) {
            android.util.Log.w("ReaderViewModel", "Invalid color hex: ${hex}", e)
            Highlight
        }
    }

    // ── 自动全文朗读 ─────────────────────────────
    fun toggleAutoRead() {
        if (_uiState.value.isAutoReading) {
            stopAutoRead()
        } else {
            startAutoRead()
        }
    }

    private fun startAutoRead() {
        val paragraphs = _uiState.value.paragraphs
        if (paragraphs.isEmpty()) return

        // 启动前停掉其他播放形态（仲裁，见 stopAllPlayback 说明）
        stopAllPlayback()

        // 初始化放进被追踪的 autoReadJob：初始化窗口内的第二次点击
        // 会先 cancel 掉第一次尝试，不再出现两条并发朗读链
        autoReadJob = viewModelScope.launch {
            if (!_uiState.value.ttsInitialized) {
                val ok = try {
                    ttsHelper.initialize(_uiState.value.book?.language ?: "en")
                } catch (e: TimeoutCancellationException) {
                    android.util.Log.w("ReaderViewModel", "TTS init timed out", e)
                    false
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    android.util.Log.e("ReaderViewModel", "TTS init failed", e)
                    false
                }
                _uiState.update { it.copy(ttsInitialized = ok) }
                if (!ok) {
                    handleTtsInitFailure("自动朗读不可用")
                    return@launch
                }
            }
            doStartAutoRead(paragraphs)
        }
    }

    /**
     * 朗读看门狗时长：按内容量估算，下限 90 秒。
     * 中文语速 ~3-4 字/秒（≈300ms/字），比英文慢得多，必须分开预算，
     * 否则 200 字以上的中文段会在朗读中途被看门狗切断。
     * 语速倍率（0.5x-2.0x）也影响实际时长，统一放宽到最慢档兜底。
     */
    private fun watchdogMs(sentences: List<String>): Long {
        val text = sentences.joinToString("")
        val hasCjk = text.any { it in '\u4e00'..'\u9fff' }
        val perCharMs = if (hasCjk) 350L else 120L
        return maxOf(90_000L, text.length * perCharMs)
    }

    private fun doStartAutoRead(paragraphs: List<String>) {
        val startParaIdx = _uiState.value.currentParagraphIndex
        // autoReadingParaIndex 从实际起播段开始（原实现恒置 0，与起播位置不符）
        _uiState.update { it.copy(isAutoReading = true, autoReadingParaIndex = startParaIdx, currentSentenceIndex = 0) }

        autoReadJob = viewModelScope.launch {
            for (paraIdx in startParaIdx until paragraphs.size) {
                if (!_uiState.value.isAutoReading) break

                val para = paragraphs[paraIdx]
                if (para.isBlank()) {
                    _uiState.update { it.copy(autoReadingParaIndex = paraIdx, currentParagraphIndex = paraIdx) }
                    continue
                }

                _uiState.update { it.copy(autoReadingParaIndex = paraIdx, currentParagraphIndex = paraIdx) }

                // 按句子分割（与速读/引擎侧共用同一套切分，保证行为一致；含中文标点）
                val sentences = splitSentencesCompat(para)
                _uiState.update { it.copy(currentSentences = sentences) }

                // 启动新链前停掉上一条链：看门狗触发或引擎迟滞时，
                // 旧链可能还在出声，直接叠新链会造成两条链交替朗读
                ttsHelper.stop()

                suspendCancellableCoroutine<Unit> { cont ->
                    // 用 AtomicBoolean 防止 race；并优先靠 cont.isActive 守门
                    val completed = java.util.concurrent.atomic.AtomicBoolean(false)
                    var watchdog: kotlinx.coroutines.Job? = null

                    fun finishOnce() {
                        if (completed.compareAndSet(false, true) && cont.isActive) {
                            // 自然完成时必须取消看门狗：原实现它作为子协程一直睡到超时，
                            // 拖延 autoReadJob 结束并补一次无意义 finish
                            watchdog?.cancel()
                            cont.resume(Unit)
                        }
                    }

                    ttsHelper.speakSentences(
                        sentences = sentences,
                        onSentenceDone = { sentenceIdx ->
                            // onSentenceDone 语义是"第 sentenceIdx 句已读完"，
                            // 当前正在读的是下一句；原实现直接写 sentenceIdx，
                            // 高亮永远落后音频一句
                            _uiState.update {
                                it.copy(currentSentenceIndex = (sentenceIdx + 1).coerceAtMost(sentences.size - 1))
                            }
                        },
                        onAllDone = {
                            finishOnce()
                        },
                    )

                    // 超时保护：按内容量估算而不是固定 60 秒——
                    // 固定 60 秒会把超过 ~150 词的段落读到一半就切断推进。
                    // 用 cont.context 派生子协程，cont 被取消时子协程自动取消。
                    watchdog = kotlinx.coroutines.CoroutineScope(cont.context).launch {
                        kotlinx.coroutines.delay(watchdogMs(sentences))
                        finishOnce()
                    }

                    // cont 取消时（父协程 stopAutoRead() 取消），立刻把 completed 标 true
                    // 防止 speakSentences 的异步回调在取消后又 resume。
                    cont.invokeOnCancellation {
                        completed.set(true)
                    }
                }

                // 段落间停顿
                kotlinx.coroutines.delay(PARAGRAPH_PAUSE_MS)
            }

            _uiState.update { it.copy(isAutoReading = false, currentSentences = emptyList()) }
        }
    }

    fun stopAutoRead() {
        autoReadJob?.cancel()
        ttsHelper.stop()
        _uiState.update { it.copy(isAutoReading = false, currentSentences = emptyList(), currentSentenceIndex = 0) }
    }

    /**
     * 播放仲裁：四种播放形态（RSVP/速读/自动朗读/单段朗读）共用同一个
     * TtsHelper 单例，启动任何一种之前必须先停掉其余所有。
     * 否则被打断的一方会把"被打断"读成"读完了"继续推进下一段
     * （SYSTEM 的 stop 补偿回调/EMBEDDED 的 finally 都会触发 onAllDone），
     * 出现自动朗读以 600ms/段 扫完全书、与 RSVP 抢引擎的乱象；
     * 同时复位全部播放标志，杜绝 isPlaying/isTtsPlaying 卡死。
     */
    private fun stopAllPlayback() {
        rsvpJob?.cancel()
        speedJob?.cancel()
        autoReadJob?.cancel()
        // ttsInitJob 也必须取消：初始化成功后会回调 doToggleTts() 启动播放，
        // 若不取消，用户在初始化窗口内切到别的播放形态后，
        // 迟到的初始化回调会在新生播放之上再叠一层单段朗读
        ttsInitJob?.cancel()
        ttsHelper.stop()
        _uiState.update {
            it.copy(
                isPlaying = false,
                isTtsPlaying = false,
                isAutoReading = false,
                currentSentences = emptyList(),
                currentSentenceIndex = 0,
            )
        }
    }

    fun setReadingMode(mode: ReadingMode) {
        // 切模式必须停掉所有形态的播放（含单段朗读），
        // 否则音频会跨模式继续播
        stopAllPlayback()

        if (mode == ReadingMode.CLOZE) {
            generateCloze()
        } else if (mode == ReadingMode.FUZZY) {
            generateFuzzy()
        }

        viewModelScope.launch {
            _uiState.update { it.copy(readingMode = mode, showModeSelector = false) }
            currentBookId?.let { readingRepository.updateMode(it, mode) }
        }
    }

    fun generateCloze() {
        val paragraphs = _uiState.value.paragraphs
        val currentIdx = _uiState.value.currentParagraphIndex
        if (currentIdx < paragraphs.size) {
            val text = paragraphs[currentIdx]
            val clozeWords = wordAnalyzer.generateClozeText(text, ratio = CLOZE_RATIO)
            _uiState.update { it.copy(clozeWords = clozeWords, hiddenWordAnswer = null) }
        }
    }

    fun generateFuzzy() {
        val paragraphs = _uiState.value.paragraphs
        val currentIdx = _uiState.value.currentParagraphIndex
        if (currentIdx < paragraphs.size) {
            val text = paragraphs[currentIdx]
            val fuzzyWords = wordAnalyzer.generateFuzzyText(text, visibleRatio = FUZZY_VISIBLE_RATIO)
            _uiState.update { it.copy(fuzzyWords = fuzzyWords) }
        }
    }

    fun togglePlay() {
        when (_uiState.value.readingMode) {
            ReadingMode.RSVP -> toggleRsvp()
            ReadingMode.SPEED -> toggleSpeed()
            // NORMAL 模式下「播放」= 从当前段开始自动朗读，与顶栏的「朗读」(只读当前段) 区分开
            ReadingMode.NORMAL -> toggleAutoRead()
            else -> toggleTts()
        }
    }

    fun toggleRsvp() {
        if (_uiState.value.isPlaying) {
            rsvpJob?.cancel()
            // 暂停即停声：原实现最后一个词会继续播完
            ttsHelper.stop()
            _uiState.update { it.copy(isPlaying = false) }
        } else {
            // 启动前停掉其他播放形态（仲裁）
            stopAllPlayback()
            // 初始化放进被追踪的 rsvpJob：初始化窗口内的连点会取消第一次尝试，
            // 不再出现两条并发播放循环交替调 speak() 的乱序音频
            rsvpJob = viewModelScope.launch {
                if (!_uiState.value.ttsInitialized) {
                    val ok = try {
                        ttsHelper.initialize(_uiState.value.book?.language ?: "en")
                    } catch (e: TimeoutCancellationException) {
                        android.util.Log.w("ReaderViewModel", "TTS init timed out", e)
                        false
                    } catch (e: kotlinx.coroutines.CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        android.util.Log.e("ReaderViewModel", "TTS init failed", e)
                        false
                    }
                    _uiState.update { it.copy(ttsInitialized = ok) }
                    if (!ok) {
                        handleTtsInitFailure("RSVP 不可用")
                        return@launch
                    }
                }
                startRsvp()
            }
        }
    }

    private fun startRsvp() {
        _uiState.update { it.copy(isPlaying = true) }
        rsvpJob = viewModelScope.launch {
            val words = getCurrentParagraphWords()
            // rsvpSpeed 来自 DataStore/阅读状态，未全程校验；0 会直接除零崩溃
            val interval = 60_000L / _uiState.value.rsvpSpeed.coerceIn(100, 800)
            // 恢复的词索引只有下限收敛；内容重切分后可能超出本段词数，
            // 越界时 range 为空 → 一声音不出就结束。在使用点收敛
            val startIdx = _uiState.value.currentWordIndex.coerceIn(0, words.size)
            for (i in startIdx until words.size) {
                if (!_uiState.value.isPlaying) break
                _uiState.update { it.copy(currentWordIndex = i) }
                val word = words.getOrNull(i) ?: break
                ttsHelper.speak(word)
                delay(interval)
            }
            // 自然播完（非暂停）把词索引归零：原实现停在最后一个词，
            // 再点播放只会读出最后一个词就停
            if (_uiState.value.isPlaying) {
                _uiState.update { it.copy(isPlaying = false, currentWordIndex = 0) }
            } else {
                _uiState.update { it.copy(isPlaying = false) }
            }
        }
    }

    fun toggleSpeed() {
        if (_uiState.value.isPlaying) {
            speedJob?.cancel()
            ttsHelper.stop()
            _uiState.update { it.copy(isPlaying = false) }
        } else {
            // 启动前停掉其他播放形态（仲裁）
            stopAllPlayback()
            // 同 toggleRsvp：初始化纳入被追踪的 job，杜绝双循环竞态
            speedJob = viewModelScope.launch {
                if (!_uiState.value.ttsInitialized) {
                    val ok = try {
                        ttsHelper.initialize(_uiState.value.book?.language ?: "en")
                    } catch (e: TimeoutCancellationException) {
                        android.util.Log.w("ReaderViewModel", "TTS init timed out", e)
                        false
                    } catch (e: kotlinx.coroutines.CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        android.util.Log.e("ReaderViewModel", "TTS init failed", e)
                        false
                    }
                    _uiState.update { it.copy(ttsInitialized = ok) }
                    if (!ok) {
                        handleTtsInitFailure("速读不可用")
                        return@launch
                    }
                }
                startSpeed()
            }
        }
    }

    private fun startSpeed() {
        _uiState.update { it.copy(isPlaying = true) }
        speedJob = viewModelScope.launch {
            val paragraphs = _uiState.value.paragraphs
            for (i in _uiState.value.currentParagraphIndex until paragraphs.size) {
                if (!_uiState.value.isPlaying) break
                _uiState.update { it.copy(currentParagraphIndex = i) }

                // 按句切分（跟自动朗读/引擎侧用同一套切分，保证句边界一致；含中文标点）
                val sentences = splitSentencesCompat(paragraphs[i])
                _uiState.update { it.copy(currentSentences = sentences) }

                if (sentences.isEmpty()) {
                    // 没有句子（极少见），按原 WPM 停留时间跳过
                    val wordCount = paragraphs[i].split(Regex("\\s+")).count { it.isNotBlank() }
                    delay((wordCount * 60L / SPEED_READ_WPM).coerceAtLeast(SPEED_READ_MIN_DELAY_MS))
                    continue
                }

                // 启动新链前停掉上一条链（同自动朗读）
                ttsHelper.stop()

                // 调 speakSentences — UI 会按句推进 currentSentenceIndex
                suspendCancellableCoroutine<Unit> { cont ->
                    val completed = java.util.concurrent.atomic.AtomicBoolean(false)
                    var watchdog: kotlinx.coroutines.Job? = null
                    fun finishOnce() {
                        if (completed.compareAndSet(false, true) && cont.isActive) {
                            watchdog?.cancel()
                            cont.resume(Unit)
                        }
                    }

                    ttsHelper.speakSentences(
                        sentences = sentences,
                        onSentenceDone = { sentenceIdx ->
                            // 同自动朗读：当前读的是"已完成句"的下一句
                            _uiState.update {
                                it.copy(currentSentenceIndex = (sentenceIdx + 1).coerceAtMost(sentences.size - 1))
                            }
                        },
                        onAllDone = { finishOnce() },
                    )

                    // 超时保护：按内容量估算，自然完成时取消看门狗
                    watchdog = kotlinx.coroutines.CoroutineScope(cont.context).launch {
                        kotlinx.coroutines.delay(watchdogMs(sentences))
                        finishOnce()
                    }

                    cont.invokeOnCancellation {
                        completed.set(true)
                        ttsHelper.stop()
                    }
                }

                // 段间停顿：音频已经在上面完整播完，这里只留短停顿。
                // 原实现在音频之后再叠加一个完整 WPM 时长的静默，
                // 每段耗时翻倍，整本书累计出数小时的死空气
                delay(PARAGRAPH_PAUSE_MS)
            }
            _uiState.update {
                it.copy(
                    isPlaying = false,
                    currentSentences = emptyList(),
                    currentSentenceIndex = 0,
                )
            }
        }
    }

    fun toggleTts() {
        // 停止自动朗读（如果正在运行）
        if (_uiState.value.isAutoReading) {
            stopAutoRead()
            return
        }

        // 挖空/听写/模糊模式的练习目标是猜出隐藏词：
        // 单段朗读读的是含答案的原文，一开口就剧透，直接拦截
        when (_uiState.value.readingMode) {
            ReadingMode.CLOZE, ReadingMode.DICTATION, ReadingMode.FUZZY -> {
                showToast("当前模式含隐藏内容，朗读会泄露答案")
                return
            }
            else -> Unit
        }

        if (_uiState.value.isTtsPlaying) {
            ttsHelper.pause()
            _uiState.update { it.copy(isTtsPlaying = false) }
        } else {
            // 启动前停掉其他播放形态（RSVP/速读可能在跑）
            stopAllPlayback()
            // TTS 未初始化：初始化纳入被追踪的 job，初始化窗口内的连点先取消上一次
            if (!_uiState.value.ttsInitialized) {
                ttsInitJob?.cancel()
                ttsInitJob = viewModelScope.launch {
                    val ok = try {
                        ttsHelper.initialize(_uiState.value.book?.language ?: "en")
                    } catch (e: TimeoutCancellationException) {
                        android.util.Log.w("ReaderViewModel", "TTS init timed out", e)
                        false
                    } catch (e: kotlinx.coroutines.CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        android.util.Log.e("ReaderViewModel", "TTS init failed", e)
                        false
                    }
                    _uiState.update { it.copy(ttsInitialized = ok) }
                    if (ok) {
                        // 初始化窗口内用户可能已启动别的播放形态（或被停止）：
                        // 复查状态，避免迟到的初始化回调在新生播放之上叠一层单段朗读
                        val s = _uiState.value
                        if (!s.isPlaying && !s.isAutoReading && !s.isTtsPlaying) {
                            doToggleTts()
                        }
                    } else {
                        handleTtsInitFailure("朗读不可用")
                    }
                }
                return
            }
            doToggleTts()
        }
    }

    private fun doToggleTts() {
        val para = _uiState.value.paragraphs.getOrNull(_uiState.value.currentParagraphIndex) ?: return
        _uiState.update { it.copy(isTtsPlaying = true) }
        ttsHelper.speak(para) {
            viewModelScope.launch {
                _uiState.update { it.copy(isTtsPlaying = false) }
            }
        }
    }

    fun nextParagraph() {
        val paragraphs = _uiState.value.paragraphs
        if (paragraphs.isEmpty()) return
        // 手动跳转必须停掉进行中的播放：否则朗读循环下一步会把
        // currentParagraphIndex 又写回它自己的进度，视口被拽回
        stopAllPlayback()
        val nextIdx = (_uiState.value.currentParagraphIndex + 1).coerceAtMost(paragraphs.size - 1)
        _uiState.update { it.copy(currentParagraphIndex = nextIdx, currentWordIndex = 0) }
        saveProgress()
        if (_uiState.value.readingMode == ReadingMode.CLOZE) generateCloze()
        if (_uiState.value.readingMode == ReadingMode.FUZZY) generateFuzzy()
    }

    fun prevParagraph() {
        val paragraphs = _uiState.value.paragraphs
        if (paragraphs.isEmpty()) return
        stopAllPlayback()
        val prevIdx = (_uiState.value.currentParagraphIndex - 1).coerceAtLeast(0)
        _uiState.update { it.copy(currentParagraphIndex = prevIdx, currentWordIndex = 0) }
        saveProgress()
        if (_uiState.value.readingMode == ReadingMode.CLOZE) generateCloze()
        if (_uiState.value.readingMode == ReadingMode.FUZZY) generateFuzzy()
    }

    fun goToParagraph(index: Int) {
        val paragraphs = _uiState.value.paragraphs
        if (paragraphs.isEmpty()) return
        stopAllPlayback()
        val idx = index.coerceIn(0, paragraphs.size - 1)
        _uiState.update { it.copy(currentParagraphIndex = idx, currentWordIndex = 0) }
        saveProgress()
        if (_uiState.value.readingMode == ReadingMode.CLOZE) generateCloze()
        if (_uiState.value.readingMode == ReadingMode.FUZZY) generateFuzzy()
    }

    /**
     * 视口滚动同步（NORMAL 模式 LazyColumn 上报可见段落）。
     * 滑动阅读时让底栏/进度/统计跟上视口；播放进行中由播放循环主导索引，忽略上报
     */
    fun onVisibleParagraphChanged(index: Int) {
        val s = _uiState.value
        if (s.isAutoReading || s.isPlaying || s.isTtsPlaying) return
        if (index < 0 || index >= s.paragraphs.size) return
        if (index == s.currentParagraphIndex) return
        _uiState.update { it.copy(currentParagraphIndex = index, currentWordIndex = 0) }
        saveProgress()
        if (s.readingMode == ReadingMode.CLOZE) generateCloze()
        if (s.readingMode == ReadingMode.FUZZY) generateFuzzy()
    }

    fun setFontSize(size: Int) {
        // 持久化收敛后的值：原实现 UI 显示收敛值、存储原始值，
        // 下次启动设置流回填时越界值会重新进入 UI。
        // 写库走防抖：滑杆拖动逐像素回调不再逐像素写 DataStore
        val coerced = size.coerceIn(12, 32)
        _uiState.update { it.copy(fontSize = coerced) }
        persistSettingDebounced("fontSize") { settingsRepository.setFontSize(coerced) }
    }

    fun setRsvpSpeed(speed: Int) {
        val coerced = speed.coerceIn(100, 800)
        _uiState.update { it.copy(rsvpSpeed = coerced) }
        val bookId = currentBookId
        persistSettingDebounced("rsvpSpeed") {
            settingsRepository.setRsvpSpeed(coerced)
            bookId?.let { readingRepository.updateRsvpSpeed(it, coerced) }
        }
    }

    fun setRsvpStrength(strength: Int) {
        val coerced = strength.coerceIn(1, 5)
        _uiState.update { it.copy(rsvpStrength = coerced) }
        persistSettingDebounced("rsvpStrength") { settingsRepository.setRsvpStrength(coerced) }
    }

    fun selectWord(word: String) {
        val clean = word.trim().replace(Regex("[^a-zA-Z]"), "")
        if (clean.isBlank()) return

        val level = collinsClassifier.classify(clean)
        // 点词串行化：快速点两个词时取消上一个查询，
        // 否则慢查询会在用户已切到新词后覆盖弹窗内容
        selectWordJob?.cancel()
        selectWordJob = viewModelScope.launch {
            // Room 查询 + ML Kit/网络翻译都可能抛运行时异常：
            // 不拦会直冲 viewModelScope 默认处理器 → 点词崩整个 app
            try {
                // 检查是否已收录
                val existing = vocabularyRepository.getWord(clean)
                // 如果没有释义，用 ML Kit 翻译
                val definition = existing?.definition
                    ?: translationHelper.translateWord(clean)
                    ?: "未找到释义"
                _uiState.update {
                    it.copy(
                        selectedVocab = existing ?: Vocabulary(
                            word = clean,
                            level = level.ordinal + 1,
                            dateAdded = System.currentTimeMillis(),
                        ),
                        wordDefinition = definition,
                        selectedWordLevel = level,
                        showWordDialog = true,
                    )
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                android.util.Log.e("ReaderViewModel", "selectWord failed", e)
                showToast("查询失败，请重试")
            }
        }
    }

    fun addToVocabulary(word: String, context: String?) {
        viewModelScope.launch {
            val vocabToSave = _uiState.value.selectedVocab?.copy(
                bookId = currentBookId,
                bookTitle = _uiState.value.book?.title,
                context = context,
            ) ?: return@launch

            // 去重查询也纳入 try：它是 Room 调用，原实现留在 try 外，
            // 数据库异常会在"加入生词本"时直接崩 app
            try {
                // 去重与保存用同一个词：此前去重查 word 参数、保存却用 selectedVocab，
                // 点词竞态下两者不一致会反复插入失败且无提示
                val dedupeWord = vocabToSave.word.ifBlank { word }
                val existing = vocabularyRepository.getWord(dedupeWord)
                if (existing != null) {
                    _uiState.update { it.copy(showWordDialog = false, selectedVocab = null) }
                    return@launch
                }

                // 捕获 DB 生成的 id，替换 selectedVocab 使「加入复习」拿到正确 vocabularyId
                val id = vocabularyRepository.addWord(vocabToSave)
                _uiState.update {
                    it.copy(
                        showWordDialog = false,
                        selectedVocab = vocabToSave.copy(id = id),
                    )
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                android.util.Log.e("ReaderViewModel", "Failed to add word to vocabulary", e)
                showToast("添加生词失败，请重试")
            }
        }
    }

    /**
     * 显示答案（挖空练习）：逐个揭示隐藏词。
     * 原实现每次只 find 第一个 isHidden 且永不清除标记，
     * 多点几次永远只能看到同一个词的答案；现在每按一次
     * 揭示一个隐藏词（清掉该位置的 isHidden），全部揭示后按钮失效
     */
    fun hideWord() {
        val words = _uiState.value.clozeWords
        val idx = words.indexOfFirst { it.isHidden }
        if (idx < 0) return
        val revealed = words[idx]
        _uiState.update {
            it.copy(
                hiddenWordAnswer = revealed.text,
                clozeWords = words.toMutableList().apply {
                    this[idx] = revealed.copy(isHidden = false)
                },
            )
        }
    }

    /**
     * 听写模式核对答案：输入与下一个隐藏词匹配才揭示。
     * @return 是否匹配成功（视图侧据此清空输入框）
     */
    fun checkDictationAnswer(input: String): Boolean {
        val words = _uiState.value.clozeWords
        val idx = words.indexOfFirst { it.isHidden }
        if (idx < 0) return false
        val target = words[idx].text
        if (!input.trim().equals(target, ignoreCase = true)) {
            showToast("不对，再试试（提示：${target.length} 个字母）")
            return false
        }
        _uiState.update {
            it.copy(
                hiddenWordAnswer = target,
                clozeWords = words.toMutableList().apply {
                    this[idx] = words[idx].copy(isHidden = false)
                },
            )
        }
        return true
    }

    fun toggleTranslation() {
        val show = !_uiState.value.showTranslation
        // isTranslating 必须同步置位：标志原来在 launch 内部才设置，
        // 快速开-关-开会在两次 launch 都未执行前连过两次守卫 → 并发双份全书翻译
        _uiState.update { it.copy(showTranslation = show, isTranslating = show && _uiState.value.paragraphTranslations.isEmpty()) }

        // 如果是打开翻译，且还没翻译过，则触发翻译
        if (show && _uiState.value.paragraphTranslations.isEmpty()) {
            translateAllParagraphs()
        }
    }

    private fun translateAllParagraphs() {
        // 已在翻译中则不重复启动
        if (translationJob?.isActive == true) return
        translationJob = viewModelScope.launch {
            _uiState.update { it.copy(isTranslating = true) }
            try {
                val paragraphs = _uiState.value.paragraphs
                val translations = translationHelper.translateParagraphs(paragraphs)
                _uiState.update { it.copy(
                    paragraphTranslations = translations,
                    isTranslating = false,
                ) }
            } catch (e: kotlinx.coroutines.CancellationException) {
                // 协程取消必须向上传播，否则取消后还会继续更新状态
                _uiState.update { it.copy(isTranslating = false) }
                throw e
            } catch (e: com.google.mlkit.common.MlKitException) {
                android.util.Log.e("ReaderViewModel", "ML Kit translation failed", e)
                _uiState.update { it.copy(isTranslating = false) }
            } catch (e: java.lang.RuntimeException) {
                android.util.Log.e("ReaderViewModel", "Translation failed", e)
                _uiState.update { it.copy(isTranslating = false) }
            }
        }
    }

    fun saveProgress() {
        // 防抖：进度条拖动时每像素都会触发一次保存，300ms 内合并成一次写库。
        // 退出路径不经这里（cleanup 直接调 doSaveProgress），不会丢进度
        saveJob?.cancel()
        saveJob = viewModelScope.launch {
            delay(300)
            doSaveProgress()
        }
    }

    /**
     * 持久化阅读进度 + 更新会话统计。
     *
     * 提取为 suspend 函数：[cleanup] 会同步（runBlocking）调用本函数，
     * 保证退出路径无论 viewModelScope 处于什么状态都能完成保存。
     */
    private suspend fun doSaveProgress() {
        val bookId = currentBookId ?: return
        // 书籍从未成功加载（如 id 不存在）：不写任何进度，防止孤儿行
        if (!bookLoaded) return
        val state = _uiState.value
        val totalChars = state.paragraphs.joinToString("\n\n").length
        // 进度语义：读完第 idx 段 = (idx+1)/size，最后一段读完应到 1.0
        // （原实现 idx/size 永远到不了 1.0，书库显示 99%）
        val progress = if (state.paragraphs.isNotEmpty()) {
            (state.currentParagraphIndex + 1).toFloat() / state.paragraphs.size
        } else 0f
        bookRepository.updateProgress(bookId, progress.coerceIn(0f, 1f), state.currentParagraphIndex)

        readingRepository.saveState(
            ReadingState(
                bookId = bookId,
                currentPosition = state.currentWordIndex,
                currentParagraph = state.currentParagraphIndex,
                totalCharacters = totalChars,
                totalParagraphs = state.paragraphs.size,
                readingMode = state.readingMode,
                rsvpSpeed = state.rsvpSpeed,
                fontSize = state.fontSize,
                theme = state.theme,
            )
        )

        // 记录阅读统计（仅新增段落计入字符数）
        val newParaIndex = state.currentParagraphIndex
        if (newParaIndex > lastRecordedParagraphIndex) {
            val jumped = newParaIndex - lastRecordedParagraphIndex
            val charsAdded = if (jumped <= 2) {
                // 顺序阅读：累计经过的段落
                (lastRecordedParagraphIndex + 1..newParaIndex).sumOf { idx ->
                    state.paragraphs.getOrNull(idx)?.length ?: 0
                }
            } else {
                // 滑杆/章节大跳转：只计目标段，防止拖一次进度条刷掉整本书的字数
                state.paragraphs.getOrNull(newParaIndex)?.length ?: 0
            }
            sessionCharsRead += charsAdded
            lastRecordedParagraphIndex = newParaIndex
        }

        // 增量落库：距上次落库满 1 分钟就写一次，进程被杀不再丢整段会话。
        // 1 分钟门槛同时避免每次保存都记 1 分钟（收尾的零星部分由 cleanup 兜底）
        val now = System.currentTimeMillis()
        if (now - lastFlushTime >= 60_000) {
            flushSessionStats(bookId)
        }
    }

    private suspend fun flushSessionStats(bookId: Long) {
        // 幂等：成功落库后 sessionCharsRead 归零，第二次调用（如
        // onDispose + onCleared 双路径）会在这里早返回，不会重复写
        if (sessionCharsRead <= 0) return
        val now = System.currentTimeMillis()
        // 按"距上次落库"计分钟：增量落库后基准前移，收尾只补尾部，
        // 不再把整个会话时长重复计入
        val base = if (lastFlushTime > 0) lastFlushTime else readingStartTime
        val minutesRead = ((now - base) / 60_000).toInt().coerceAtLeast(1)
        val dateFormat = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.getDefault())
        val today = dateFormat.format(java.util.Date(now))
        try {
            // 原子累计：@Transaction + (bookId,date) 唯一索引兜底
            readingStatsDao.accumulateDailyStat(
                bookId = bookId,
                date = today,
                addMinutes = minutesRead,
                addChars = sessionCharsRead.toInt(),
                paragraphsHighWater = (lastRecordedParagraphIndex + 1).coerceAtLeast(1),
            )
            sessionCharsRead = 0L
            lastFlushTime = now
            // statsFlushed 只在"会话结束式"收尾时置位（见 cleanup），
            // 增量落库后仍可继续累计
        } catch (e: Exception) {
            android.util.Log.e("ReaderViewModel", "Failed to record stats", e)
        }
    }

    private fun getCurrentParagraphWords(): List<String> {
        val para = _uiState.value.paragraphs.getOrNull(_uiState.value.currentParagraphIndex) ?: return emptyList()
        return wordAnalyzer.extractWords(para)
    }

    /**
     * 取消所有运行中的作业并停止 TTS，同步完成最后一次保存。
     *
     * 保存必须同步（runBlocking）：lifecycle-viewmodel 在 onCleared 返回之后
     * 才取消 viewModelScope，此前"scope 存活就异步保存"的分支十拿九稳会在
     * 异步保存落到第一个挂起点后被取消 —— 退出进度静默丢失。
     * 同步保存挂起在 Room IO 上，主线程代价短且保证写完。
     */
    fun cleanup() {
        rsvpJob?.cancel()
        speedJob?.cancel()
        autoReadJob?.cancel()
        ttsInitJob?.cancel()
        downloadJob?.cancel()
        saveJob?.cancel()
        selectWordJob?.cancel()
        translationJob?.cancel()
        bookmarkToggleJob?.cancel()
        vocabJob?.cancel()
        bookmarksJob?.cancel()
        highlightsJob?.cancel()
        bookJob?.cancel()
        ttsHelper.stop()
        // 防抖窗口内未落盘的设置写入：取消计时、同步冲刷，
        // 用户拖完滑杆立刻退页也不会丢设置
        settingsPersistJobs.values.forEach { it.cancel() }
        val pendingSettings = settingsPendingWrites.values.toList()
        settingsPendingWrites.clear()
        // flushSessionStats 内部以 sessionCharsRead==0 天然单飞，
        // onDispose + onCleared 双路径不会重复写
        runBlocking(Dispatchers.IO) {
            pendingSettings.forEach { write ->
                try {
                    write()
                } catch (e: Exception) {
                    android.util.Log.e("ReaderViewModel", "flush settings write failed", e)
                }
            }
            doSaveProgress()
            currentBookId?.let { flushSessionStats(it) }
        }
    }

    fun setTranslationAlpha(alpha: Float) {
        val coerced = alpha.coerceIn(TRANSLATION_ALPHA_MIN, TRANSLATION_ALPHA_MAX)
        _uiState.update { it.copy(translationAlpha = coerced) }
        persistSettingDebounced("translationAlpha") { settingsRepository.setTranslationAlpha(coerced) }
    }

    fun dismissModeSelector() {
        _uiState.update { it.copy(showModeSelector = false) }
    }

    fun showModeSelector() {
        _uiState.update { it.copy(showModeSelector = true) }
    }

    fun toggleSettings() {
        _uiState.update { it.copy(showSettings = !it.showSettings) }
    }

    fun dismissWordDialog() {
        _uiState.update { it.copy(showWordDialog = false, selectedVocab = null) }
    }

    fun toggleWordLevelColors() {
        _uiState.update { it.copy(showWordLevelColors = !it.showWordLevelColors) }
    }

    fun toggleKnownWordsHighlight() {
        _uiState.update { it.copy(showKnownWordsHighlight = !it.showKnownWordsHighlight) }
    }

    fun toggleChapterNav() {
        _uiState.update { it.copy(showChapterNav = !it.showChapterNav) }
    }

    // 双击选句翻译
    private val _selectedSentence = MutableStateFlow<String?>(null)
    val selectedSentence: StateFlow<String?> = _selectedSentence.asStateFlow()

    private val _sentenceTranslation = MutableStateFlow<String?>(null)
    val sentenceTranslation: StateFlow<String?> = _sentenceTranslation.asStateFlow()

    fun translateSentence(sentence: String) {
        viewModelScope.launch {
            _selectedSentence.value = sentence
            _sentenceTranslation.value = null
            // 抛异常与返回 null 同样按失败处理：不拦会崩 app，
            // 且弹窗以 == null 判定"加载中"，异常后不写值会永远转圈
            val result = try {
                translationHelper.translateSentence(sentence)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                android.util.Log.e("ReaderViewModel", "translateSentence failed", e)
                null
            }
            // 失败时写空串而不是 null：弹窗以 == null 判定"加载中"，
            // 失败写 null 会让加载指示永远转下去（"翻译失败"分支是死代码）
            _sentenceTranslation.value = result ?: ""
        }
    }

    fun dismissSentenceTranslation() {
        _selectedSentence.value = null
        _sentenceTranslation.value = null
    }

    // ── 书签 ─────────────────────────────────
    fun toggleBookmark(paragraphIndex: Int) {
        val bookId = currentBookId ?: return
        // 互斥锁串行化：原"取消上一个 job"不是真互斥（cancel 不阻塞、
        // Room 语句中途不响应取消），快速双击仍可能双读 null 各插一条。
        // 数据库侧另有 (bookId, paragraphIndex) 唯一索引 + IGNORE 兜底
        bookmarkToggleJob = viewModelScope.launch {
            // DAO 异常（约束冲突/磁盘满）不拦会崩 app：给用户提示而不是闪退
            try {
                bookmarkMutex.withLock {
                    val existing = bookmarkDao.getBookmarkAt(bookId, paragraphIndex)
                    if (existing != null) {
                        bookmarkDao.delete(existing)
                    } else {
                        bookmarkDao.insert(BookmarkEntity(bookId = bookId, paragraphIndex = paragraphIndex))
                    }
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                android.util.Log.e("ReaderViewModel", "toggleBookmark failed", e)
                showToast("书签保存失败，请重试")
            }
        }
    }

    fun isBookmarked(paragraphIndex: Int): Boolean {
        return paragraphIndex in _uiState.value.bookmarkedParagraphs
    }

    // ── 高亮 ─────────────────────────────────
    fun addHighlight(
        paragraphIndex: Int,
        startOffset: Int,
        endOffset: Int,
        text: String,
        colorHex: String = "#FFE082",
    ) {
        val bookId = currentBookId ?: return
        viewModelScope.launch {
            try {
                highlightDao.insert(
                    HighlightEntity(
                        bookId = bookId,
                        paragraphIndex = paragraphIndex,
                        startOffset = startOffset,
                        endOffset = endOffset,
                        text = text,
                        color = colorHex,
                    )
                )
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                android.util.Log.e("ReaderViewModel", "addHighlight failed", e)
                showToast("高亮保存失败，请重试")
            }
        }
    }

    fun removeHighlight(highlightId: Long) {
        viewModelScope.launch {
            try {
                highlightDao.deleteById(highlightId)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                android.util.Log.e("ReaderViewModel", "removeHighlight failed", e)
                showToast("高亮删除失败，请重试")
            }
        }
    }

    // ── 听写练习 ─────────────────────────────
    fun startDictation(paragraphIndex: Int) {
        val para = _uiState.value.paragraphs.getOrNull(paragraphIndex) ?: return
        val allWords = wordAnalyzer.extractWords(para)
        if (allWords.isEmpty()) return
        // 采样要听写的词（去重）后，复用 generateClozeText 生成**带分隔符**的
        // token 流：旧实现只放纯单词 token，渲染出来所有词连成一串没法读。
        // 答案核对走 checkDictationAnswer（输入匹配才揭示）
        val hideSet = allWords.map { it.lowercase(java.util.Locale.ROOT) }
            .filter { it.length > 2 }
            .distinct()
            .shuffled()
            .take(maxOf(1, allWords.size / 3))
            .toSet()
        val cloze = wordAnalyzer.generateClozeText(para, wordsToHide = hideSet)
        stopAllPlayback()
        _uiState.update {
            it.copy(
                readingMode = ReadingMode.DICTATION,
                clozeWords = cloze,
                hiddenWordAnswer = null,
                currentParagraphIndex = paragraphIndex,
            )
        }
        // 与 setReadingMode 对齐：持久化模式，重开书能恢复
        currentBookId?.let { id ->
            viewModelScope.launch { readingRepository.updateMode(id, ReadingMode.DICTATION) }
        }
    }

    override fun onCleared() {
        super.onCleared()
        cleanup()
    }
}
