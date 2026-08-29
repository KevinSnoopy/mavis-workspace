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
import com.eareyereading.data.local.entity.ReadingStatsEntity
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
    val rsvpInterval: Int = 1,    // 1-3，影响加粗词间隔
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
    val interval: Int = 1,
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

    private fun showTtsInstallPrompt(reason: TtsHelper.InitFailureReason, force: Boolean = false) {
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
        val engines = TtsEngineHelper.listAvailableEngines(context)
        val fallback = TtsEngineHelper.findFallbackEngine(context)
        val discovered = TtsEngineHelper.discoverAllTtsEngines(context)
        val systemDefaultPkg = TtsEngineHelper.getSystemDefaultEnginePackage(context)
        val isPhantom = TtsEngineHelper.isPhantomDefaultState(context)
        val hasPlay = TtsEngineHelper.hasGooglePlay(context)
        val uninstalledTtsApps = TtsEngineHelper.listUninstalledThirdPartyTtsApps(context)

        // 内置 TTS 状态（embeddedEngine 已在上方防抖判断时获取，此处复用）
        val embeddedDownloaded = embeddedEngine.isModelDownloaded()
        val embeddedModelInfo = embeddedEngine.getCurrentModelInfo()
        val embeddedSizeText = formatBytes(embeddedModelInfo.sizeBytes)

        // 决定场景
        val scenario = when {
            discovered.isNotEmpty() ->
                TtsInstallPrompt.DialogScenario.HAS_DISCOVERED_ENGINES
            // 系统设置指向一个**已知 TTS 引擎包**（最常见的就是刚装好的 com.google.android.tts）
            // 且用户实际上可以"重试连接"让它生效
            systemDefaultPkg != null &&
                TtsEngineHelper.isKnownTtsEnginePackage(systemDefaultPkg) ->
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
                "discovered=${discovered.size}, systemDefault=$systemDefaultPkg, " +
                "fallback=${fallback?.packageName}, embeddedDownloaded=$embeddedDownloaded"
        )
        _ttsInstallPrompt.tryEmit(
            TtsInstallPrompt(
                reason = reason,
                availableEngines = engines,
                fallbackEnginePackage = fallback?.packageName,
                discoveredEngines = discovered,
                systemDefaultEnginePackage = systemDefaultPkg,
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
        val embeddedEngine = ttsHelper.getEmbeddedEngine()
        val modelInfo = embeddedEngine.getCurrentModelInfo()
        showToast("开始下载内置 TTS 模型（约 ${modelInfo.sizeBytes / 1_000_000}MB），请保持网络...")
        viewModelScope.launch {
            val ok = embeddedEngine.downloadModel(modelInfo) { progress ->
                // 这里可以做更精细的进度提示，但简单起见只 log
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
        }
    }

    /**
     * 使用指定的引擎包名重新尝试初始化 TTS。
     *
     * 特殊值 `__EMBEDDED__` 表示激活内置 sherpa-onnx TTS。
     */
    private fun retryTtsInitWithEngine(enginePackage: String?) {
        viewModelScope.launch {
            val ok = if (enginePackage == "__EMBEDDED__") {
                // 特殊值：激活内置 TTS
                try {
                    ttsHelper.initializeEmbeddedForced()
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
    private fun handleTtsInitFailure(prefix: String) {
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
    }
    // 本次阅读会话的统计（用于 saveProgress/cleanup 时写入 DB）
    private var sessionCharsRead: Long = 0L
    private var lastRecordedParagraphIndex: Int = -1

    // TTS 引导弹窗防抖：本会话内已经弹过则不再弹（避免用户每次点朗读都看到同一个弹窗）
    private var ttsPromptShownThisSession = false
    // TTS 初始化过程中用于协调 reader 协程间的等待锁
    private val ttsInitLock = Any()

    init {
        viewModelScope.launch {
            combine(
                settingsRepository.getRsvpSpeed(),
                settingsRepository.getRsvpStrength(),
                settingsRepository.getRsvpInterval(),
                settingsRepository.getFontSize(),
                settingsRepository.getTheme(),
                settingsRepository.getTranslationAlpha(),
            ) { values ->
                val speed = values[0] as Int
                val strength = values[1] as Int
                val interval = values[2] as Int
                val fontSize = values[3] as Int
                val theme = values[4] as ReadingTheme
                val alpha = values[5] as Float
                ReadingSettings(speed, fontSize, theme, alpha, strength, interval)
            }.collect { s ->
                _uiState.update {
                    it.copy(
                        rsvpSpeed = s.speed,
                        rsvpStrength = s.strength,
                        rsvpInterval = s.interval,
                        fontSize = s.fontSize,
                        theme = s.theme,
                        translationAlpha = s.alpha,
                    )
                }
            }
        }
    }

    fun loadBook(bookId: Long) {
        currentBookId = bookId
        readingStartTime = System.currentTimeMillis()
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
            vocabularyRepository.getAllVocabulary().collect { vocabList ->
                val known = vocabList.filter { it.isLearned }.map { it.word.lowercase() }.toSet()
                val allWords = vocabList.map { it.word.lowercase() }.toSet()
                _uiState.update { it.copy(knownWords = known, learnedWords = allWords) }
            }
        }

        bookJob = viewModelScope.launch {
            // 用 first() 而非 collect() — 单次拉取，避免 updateProgress 后 Flow 重发射时
            // 错误地将 currentParagraphIndex 重置为保存的旧位置（覆盖用户当前阅读进度）
            val book = bookRepository.getBookById(bookId).first()
            if (book == null) {
                _uiState.update { it.copy(isLoading = false) }
                return@launch
            }
            val paragraphs = if (book.content.isNotBlank()) {
                book.content.split("\n\n").filter { it.isNotBlank() }
            } else {
                epubParser.parseBook(book.filePath)
            }
            val state = readingRepository.getState(bookId)
            val totalChars = paragraphs.joinToString(" ").length.toLong()

            _uiState.update {
                it.copy(
                    book = book,
                    paragraphs = paragraphs,
                    currentParagraphIndex = state?.currentParagraph ?: 0,
                    currentWordIndex = state?.currentPosition ?: 0,
                    readingMode = state?.readingMode ?: ReadingMode.NORMAL,
                    rsvpSpeed = state?.rsvpSpeed ?: it.rsvpSpeed,
                    totalReadChars = totalChars,
                    isLoading = false,
                )
            }

            // 初始化 TTS
            if (!_uiState.value.ttsInitialized) {
                val ok = try {
                    ttsHelper.initialize(book.language)
                } catch (e: TimeoutCancellationException) {
                    android.util.Log.w("ReaderViewModel", "TTS init timed out", e)
                    false
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
                    bookmarkDao.getBookmarksForBook(bookId).collect { bookmarks ->
                        _uiState.update {
                            it.copy(bookmarkedParagraphs = bookmarks.map { b -> b.paragraphIndex }.toSet())
                        }
                    }
                }

                // 加载高亮
                highlightsJob?.cancel()
                highlightsJob = viewModelScope.launch {
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

        // 初始化 TTS
        if (!_uiState.value.ttsInitialized) {
            viewModelScope.launch {
                val ok = try {
                    ttsHelper.initialize(_uiState.value.book?.language ?: "en")
                } catch (e: TimeoutCancellationException) {
                    android.util.Log.w("ReaderViewModel", "TTS init timed out", e)
                    false
                } catch (e: Exception) {
                    android.util.Log.e("ReaderViewModel", "TTS init failed", e)
                    false
                }
                _uiState.update { it.copy(ttsInitialized = ok) }
                if (ok) {
                    doStartAutoRead(paragraphs)
                } else {
                    handleTtsInitFailure("自动朗读不可用")
                }
            }
        } else {
            doStartAutoRead(paragraphs)
        }
    }

    private fun doStartAutoRead(paragraphs: List<String>) {
        _uiState.update { it.copy(isAutoReading = true, autoReadingParaIndex = 0, currentSentenceIndex = 0) }

        autoReadJob = viewModelScope.launch {
            val startParaIdx = _uiState.value.currentParagraphIndex

            for (paraIdx in startParaIdx until paragraphs.size) {
                if (!_uiState.value.isAutoReading) break

                val para = paragraphs[paraIdx]
                if (para.isBlank()) {
                    _uiState.update { it.copy(autoReadingParaIndex = paraIdx, currentParagraphIndex = paraIdx) }
                    continue
                }

                _uiState.update { it.copy(autoReadingParaIndex = paraIdx, currentParagraphIndex = paraIdx) }

                // 按句子分割。
                // 用 (?<=[.!?])\s+(?=[A-Z"\(]) 切分，要求句末标点后跟空白 + 大写字母/引号/左括号，
                // 这样 "Aug." "Mr." "Dr." 这种缩写后的 "." + 空格 + 小写/数字不会误切，
                // 而 "happened. The" 这种正常句子边界仍能切出来。
                val sentences = para.split(Regex("(?<=[.!?])\\s+(?=[A-Z\"\\(])")).filter { it.isNotBlank() }
                _uiState.update { it.copy(currentSentences = sentences) }

                suspendCancellableCoroutine<Unit> { cont ->
                    // 用 AtomicBoolean 防止 race；并优先靠 cont.isActive 守门
                    val completed = java.util.concurrent.atomic.AtomicBoolean(false)

                    fun finishOnce() {
                        if (completed.compareAndSet(false, true) && cont.isActive) {
                            cont.resume(Unit)
                        }
                    }

                    ttsHelper.speakSentences(
                        sentences = sentences,
                        onSentenceDone = { sentenceIdx ->
                            _uiState.update { it.copy(currentSentenceIndex = sentenceIdx) }
                        },
                        onAllDone = {
                            finishOnce()
                        },
                    )

                    // 超时保护（每段最长 60 秒）。
                    // 用 cont.context 派生子协程，cont 被取消时子协程自动取消，
                    // 避免 viewModelScope 派生的协程在父协程死掉后还跑。
                    kotlinx.coroutines.CoroutineScope(cont.context).launch {
                        kotlinx.coroutines.delay(60_000)
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

    fun setReadingMode(mode: ReadingMode) {
        rsvpJob?.cancel()
        speedJob?.cancel()
        if (_uiState.value.isAutoReading) {
            autoReadJob?.cancel()
            ttsHelper.stop()
            _uiState.update { it.copy(isAutoReading = false, currentSentences = emptyList(), currentSentenceIndex = 0) }
        }

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
            _uiState.update { it.copy(isPlaying = false) }
        } else {
            // 确保 TTS 初始化
            if (!_uiState.value.ttsInitialized) {
                viewModelScope.launch {
                    val ok = try {
                        ttsHelper.initialize(_uiState.value.book?.language ?: "en")
                    } catch (e: TimeoutCancellationException) {
                        android.util.Log.w("ReaderViewModel", "TTS init timed out", e)
                        false
                    } catch (e: Exception) {
                        android.util.Log.e("ReaderViewModel", "TTS init failed", e)
                        false
                    }
                    _uiState.update { it.copy(ttsInitialized = ok) }
                    if (ok) {
                        startRsvp()
                    } else {
                        handleTtsInitFailure("RSVP 不可用")
                    }
                }
                return
            }
            startRsvp()
        }
    }

    private fun startRsvp() {
        _uiState.update { it.copy(isPlaying = true) }
        rsvpJob = viewModelScope.launch {
            val words = getCurrentParagraphWords()
            val interval = (60_000L / _uiState.value.rsvpSpeed)
            for (i in _uiState.value.currentWordIndex until words.size) {
                if (!_uiState.value.isPlaying) break
                _uiState.update { it.copy(currentWordIndex = i) }
                val word = words.getOrNull(i) ?: break
                ttsHelper.speak(word)
                delay(interval)
            }
            _uiState.update { it.copy(isPlaying = false) }
        }
    }

    fun toggleSpeed() {
        if (_uiState.value.isPlaying) {
            speedJob?.cancel()
            ttsHelper.stop()
            _uiState.update { it.copy(isPlaying = false) }
        } else {
            // 确保 TTS 初始化
            if (!_uiState.value.ttsInitialized) {
                viewModelScope.launch {
                    val ok = try {
                        ttsHelper.initialize(_uiState.value.book?.language ?: "en")
                    } catch (e: TimeoutCancellationException) {
                        android.util.Log.w("ReaderViewModel", "TTS init timed out", e)
                        false
                    } catch (e: Exception) {
                        android.util.Log.e("ReaderViewModel", "TTS init failed", e)
                        false
                    }
                    _uiState.update { it.copy(ttsInitialized = ok) }
                    if (ok) {
                        startSpeed()
                    } else {
                        handleTtsInitFailure("速读不可用")
                    }
                }
                return
            }
            startSpeed()
        }
    }

    private fun startSpeed() {
        _uiState.update { it.copy(isPlaying = true) }
        speedJob = viewModelScope.launch {
            val paragraphs = _uiState.value.paragraphs
            for (i in _uiState.value.currentParagraphIndex until paragraphs.size) {
                if (!_uiState.value.isPlaying) break
                _uiState.update { it.copy(currentParagraphIndex = i) }

                // 按句切分（跟自动朗读用同一个 regex，保证句边界一致）
                val sentences = paragraphs[i]
                    .split(Regex("(?<=[.!?])\\s+(?=[A-Z\"\\(])"))
                    .filter { it.isNotBlank() }
                _uiState.update { it.copy(currentSentences = sentences) }

                if (sentences.isEmpty()) {
                    // 没有句子（极少见），按原 WPM 停留时间跳过
                    val wordCount = paragraphs[i].split(Regex("\\s+")).count { it.isNotBlank() }
                    delay((wordCount * 60L / SPEED_READ_WPM).coerceAtLeast(SPEED_READ_MIN_DELAY_MS))
                    continue
                }

                // 调 speakSentences — UI 会按句推进 currentSentenceIndex
                suspendCancellableCoroutine<Unit> { cont ->
                    val completed = java.util.concurrent.atomic.AtomicBoolean(false)
                    fun finishOnce() {
                        if (completed.compareAndSet(false, true) && cont.isActive) {
                            cont.resume(Unit)
                        }
                    }

                    ttsHelper.speakSentences(
                        sentences = sentences,
                        onSentenceDone = { sentenceIdx ->
                            _uiState.update { it.copy(currentSentenceIndex = sentenceIdx) }
                        },
                        onAllDone = { finishOnce() },
                    )

                    // 超时保护（每段最长 5 分钟）
                    kotlinx.coroutines.CoroutineScope(cont.context).launch {
                        kotlinx.coroutines.delay(5 * 60_000)
                        finishOnce()
                    }

                    cont.invokeOnCancellation {
                        completed.set(true)
                        ttsHelper.stop()
                    }
                }

                // 段间停顿：按单词数计算 WPM 停留
                val wordCount = paragraphs[i].split(Regex("\\s+")).count { it.isNotBlank() }
                delay((wordCount * 60L / SPEED_READ_WPM).coerceAtLeast(SPEED_READ_MIN_DELAY_MS))
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

        if (_uiState.value.isTtsPlaying) {
            ttsHelper.pause()
            _uiState.update { it.copy(isTtsPlaying = false) }
        } else {
            // TTS 未初始化：尝试初始化
            if (!_uiState.value.ttsInitialized) {
                viewModelScope.launch {
                    val ok = try {
                        ttsHelper.initialize(_uiState.value.book?.language ?: "en")
                    } catch (e: TimeoutCancellationException) {
                        android.util.Log.w("ReaderViewModel", "TTS init timed out", e)
                        false
                    } catch (e: Exception) {
                        android.util.Log.e("ReaderViewModel", "TTS init failed", e)
                        false
                    }
                    _uiState.update { it.copy(ttsInitialized = ok) }
                    if (ok) {
                        doToggleTts()
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
        val nextIdx = (_uiState.value.currentParagraphIndex + 1).coerceAtMost(paragraphs.size - 1)
        _uiState.update { it.copy(currentParagraphIndex = nextIdx, currentWordIndex = 0) }
        saveProgress()
        if (_uiState.value.readingMode == ReadingMode.CLOZE) generateCloze()
        if (_uiState.value.readingMode == ReadingMode.FUZZY) generateFuzzy()
    }

    fun prevParagraph() {
        val paragraphs = _uiState.value.paragraphs
        if (paragraphs.isEmpty()) return
        val prevIdx = (_uiState.value.currentParagraphIndex - 1).coerceAtLeast(0)
        _uiState.update { it.copy(currentParagraphIndex = prevIdx, currentWordIndex = 0) }
        saveProgress()
        if (_uiState.value.readingMode == ReadingMode.CLOZE) generateCloze()
        if (_uiState.value.readingMode == ReadingMode.FUZZY) generateFuzzy()
    }

    fun goToParagraph(index: Int) {
        val paragraphs = _uiState.value.paragraphs
        if (paragraphs.isEmpty()) return
        val idx = index.coerceIn(0, paragraphs.size - 1)
        _uiState.update { it.copy(currentParagraphIndex = idx, currentWordIndex = 0) }
        saveProgress()
        if (_uiState.value.readingMode == ReadingMode.CLOZE) generateCloze()
        if (_uiState.value.readingMode == ReadingMode.FUZZY) generateFuzzy()
    }

    fun setFontSize(size: Int) {
        viewModelScope.launch {
            _uiState.update { it.copy(fontSize = size.coerceIn(12, 32)) }
            settingsRepository.setFontSize(size)
        }
    }

    fun setRsvpSpeed(speed: Int) {
        viewModelScope.launch {
            _uiState.update { it.copy(rsvpSpeed = speed.coerceIn(100, 800)) }
            settingsRepository.setRsvpSpeed(speed)
            currentBookId?.let { readingRepository.updateRsvpSpeed(it, speed) }
        }
    }

    fun setRsvpStrength(strength: Int) {
        viewModelScope.launch {
            _uiState.update { it.copy(rsvpStrength = strength.coerceIn(1, 5)) }
            settingsRepository.setRsvpStrength(strength)
        }
    }

    fun setRsvpInterval(interval: Int) {
        viewModelScope.launch {
            _uiState.update { it.copy(rsvpInterval = interval.coerceIn(1, 3)) }
            settingsRepository.setRsvpInterval(interval)
        }
    }

    fun selectWord(word: String) {
        val clean = word.trim().replace(Regex("[^a-zA-Z]"), "")
        if (clean.isBlank()) return

        val level = collinsClassifier.classify(clean)
        viewModelScope.launch {
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
        }
    }

    fun addToVocabulary(word: String, context: String?) {
        viewModelScope.launch {
            val vocabToSave = _uiState.value.selectedVocab?.copy(
                bookId = currentBookId,
                bookTitle = _uiState.value.book?.title,
                context = context,
            ) ?: return@launch

            // 已收录则直接关闭，不重复添加
            val existing = vocabularyRepository.getWord(word)
            if (existing != null) {
                _uiState.update { it.copy(showWordDialog = false, selectedVocab = null) }
                return@launch
            }

            // 捕获 DB 生成的 id，替换 selectedVocab 使「加入复习」拿到正确 vocabularyId
            try {
                val id = vocabularyRepository.addWord(vocabToSave)
                _uiState.update {
                    it.copy(
                        showWordDialog = false,
                        selectedVocab = vocabToSave.copy(id = id),
                    )
                }
            } catch (e: Exception) {
                android.util.Log.e("ReaderViewModel", "Failed to add word to vocabulary", e)
            }
        }
    }

    fun hideWord() {
        val hidden = _uiState.value.clozeWords.find { it.isHidden }
        _uiState.update { it.copy(hiddenWordAnswer = hidden?.text) }
    }

    fun toggleTranslation() {
        val show = !_uiState.value.showTranslation
        _uiState.update { it.copy(showTranslation = show) }

        // 如果是打开翻译，且还没翻译过，则触发翻译
        if (show && _uiState.value.paragraphTranslations.isEmpty() && !_uiState.value.isTranslating) {
            translateAllParagraphs()
        }
    }

    // 揭示所有模糊文本（回译模式）
    fun revealAllFuzzy() {
        // 切换到普通模式显示原文
        setReadingMode(ReadingMode.NORMAL)
    }

    private fun translateAllParagraphs() {
        viewModelScope.launch {
            _uiState.update { it.copy(isTranslating = true) }
            try {
                val paragraphs = _uiState.value.paragraphs
                val translations = translationHelper.translateParagraphs(paragraphs)
                _uiState.update { it.copy(
                    paragraphTranslations = translations,
                    isTranslating = false,
                ) }
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
        viewModelScope.launch { doSaveProgress() }
    }

    /**
     * 持久化阅读进度 + 更新会话统计。
     *
     * 提取为 suspend 函数是因为 [cleanup] 可能在 viewModelScope 已取消时
     * （即 [onCleared] 路径）被调用；此时 [saveProgress] 内的
     * viewModelScope.launch 永远不会执行。提取后，[onCleared] 可通过
     * runBlocking 同步调用本函数，确保进度不丢。
     */
    private suspend fun doSaveProgress() {
        val bookId = currentBookId ?: return
        val state = _uiState.value
        val totalChars = state.paragraphs.joinToString("\n\n").length
        val progress = if (totalChars > 0) {
            state.currentParagraphIndex.toFloat() / state.paragraphs.size.coerceAtLeast(1)
        } else 0f
        bookRepository.updateProgress(bookId, progress, state.currentParagraphIndex)

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
            val newParagraphs = (lastRecordedParagraphIndex + 1)..newParaIndex
            val charsAdded = newParagraphs.sumOf { idx ->
                state.paragraphs.getOrNull(idx)?.length ?: 0
            }
            sessionCharsRead += charsAdded
            lastRecordedParagraphIndex = newParaIndex
        }
    }

    private suspend fun flushSessionStats(bookId: Long) {
        if (sessionCharsRead <= 0 || readingStartTime <= 0) return
        val now = System.currentTimeMillis()
        val minutesRead = ((now - readingStartTime) / 60_000).toInt().coerceAtLeast(1)
        val dateFormat = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.getDefault())
        val today = dateFormat.format(java.util.Date(now))
        try {
            // 先删同书同日旧记录，再插入新记录（保持每日每书一条）
            readingStatsDao.deleteForBookAndDate(bookId, today)
            readingStatsDao.insertStat(
                ReadingStatsEntity(
                    bookId = bookId,
                    date = today,
                    readingMinutes = minutesRead,
                    charsRead = sessionCharsRead.toInt(),
                    paragraphsRead = (lastRecordedParagraphIndex + 1).coerceAtLeast(1),
                )
            )
        } catch (e: Exception) {
            android.util.Log.e("ReaderViewModel", "Failed to record stats", e)
        }
    }

    private fun getCurrentParagraphWords(): List<String> {
        val para = _uiState.value.paragraphs.getOrNull(_uiState.value.currentParagraphIndex) ?: return emptyList()
        return wordAnalyzer.extractWords(para)
    }

    /**
     * 取消所有运行中的作业并停止 TTS。
     *
     * 如果 viewModelScope 仍然存活（Composable onDispose 调用），进度和统计
     * 异步保存；如果已取消（[onCleared] 路径），则用 runBlocking 同步保存，
     * 避免 viewModelScope.launch 在已取消的 scope 上静默丢弃保存操作。
     */
    fun cleanup() {
        rsvpJob?.cancel()
        speedJob?.cancel()
        autoReadJob?.cancel()
        vocabJob?.cancel()
        bookmarksJob?.cancel()
        highlightsJob?.cancel()
        bookJob?.cancel()
        ttsHelper.stop()
        if (viewModelScope.isActive) {
            viewModelScope.launch {
                doSaveProgress()
                currentBookId?.let { flushSessionStats(it) }
            }
        } else {
            // onCleared() 路径：scope 已取消，同步保存避免数据丢失
            runBlocking(Dispatchers.IO) {
                doSaveProgress()
                currentBookId?.let { flushSessionStats(it) }
            }
        }
    }

    fun setTranslationAlpha(alpha: Float) {
        viewModelScope.launch {
            _uiState.update { it.copy(translationAlpha = alpha.coerceIn(TRANSLATION_ALPHA_MIN, TRANSLATION_ALPHA_MAX)) }
            settingsRepository.setTranslationAlpha(alpha.coerceIn(TRANSLATION_ALPHA_MIN, TRANSLATION_ALPHA_MAX))
        }
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
            val result = translationHelper.translateSentence(sentence)
            _sentenceTranslation.value = result
        }
    }

    fun dismissSentenceTranslation() {
        _selectedSentence.value = null
        _sentenceTranslation.value = null
    }

    // ── 书签 ─────────────────────────────────
    fun toggleBookmark(paragraphIndex: Int) {
        val bookId = currentBookId ?: return
        viewModelScope.launch {
            val existing = bookmarkDao.getBookmarkAt(bookId, paragraphIndex)
            if (existing != null) {
                bookmarkDao.delete(existing)
            } else {
                bookmarkDao.insert(BookmarkEntity(bookId = bookId, paragraphIndex = paragraphIndex))
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
        }
    }

    fun removeHighlight(highlightId: Long) {
        viewModelScope.launch {
            highlightDao.deleteById(highlightId)
        }
    }

    // ── 听写练习 ─────────────────────────────
    fun startDictation(paragraphIndex: Int) {
        val para = _uiState.value.paragraphs.getOrNull(paragraphIndex) ?: return
        val words = Regex("([a-zA-Z]+)").findAll(para).map { it.value }.toList()
        if (words.isEmpty()) return
        val hiddenIndices = words.indices.shuffled().take(maxOf(1, words.size / 3))
        val cloze = words.mapIndexed { i, word ->
            ClozeWord(text = word, isHidden = i in hiddenIndices)
        }
        _uiState.update {
            it.copy(
                readingMode = ReadingMode.DICTATION,
                clozeWords = cloze,
                hiddenWordAnswer = null,
                currentParagraphIndex = paragraphIndex,
            )
        }
    }

    fun getReadingDurationMinutes(): Long {
        val start = _uiState.value.readingStartTime
        return if (start > 0) (System.currentTimeMillis() - start) / 60_000 else 0
    }

    override fun onCleared() {
        super.onCleared()
        cleanup()
    }
}
