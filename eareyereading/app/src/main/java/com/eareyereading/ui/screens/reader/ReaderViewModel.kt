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
import com.eareyereading.tts.EmbeddedTtsEngine
import com.eareyereading.ui.theme.*
import com.eareyereading.util.*
import com.eareyereading.util.CollinsClassifier.WordLevel
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.isActive
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import javax.inject.Inject
import kotlin.coroutines.resume

/**
 * TTS 引擎引导弹窗的事件载荷。
 */
/**
 * TTS 引导提示（自 2026-08-30 系统 TTS 下线后已大幅简化）：
 * 只剩"提醒用户去下载嵌入式模型"一种场景。系统引擎选择/Google TTS 安装/
 * 第三方 TTS app 安装等场景全部删掉（TtsEngineHelper 已被删除）。
 *
 * 保留字段签名以避免 UI 侧广泛改动。
 */
data class TtsInstallPrompt(
    @Suppress("UNUSED_PARAMETER") val reason: TtsHelper.InitFailureReason = TtsHelper.InitFailureReason.NO_ENGINE,
    @Suppress("UNUSED_PARAMETER") val availableEngines: List<Any> = emptyList(),
    @Suppress("UNUSED_PARAMETER") val fallbackEnginePackage: String? = null,
    @Suppress("UNUSED_PARAMETER") val discoveredEngines: List<Any> = emptyList(),
    @Suppress("UNUSED_PARAMETER") val systemDefaultEnginePackage: String? = null,
    @Suppress("UNUSED_PARAMETER") val isPhantomDefaultState: Boolean = false,
    @Suppress("UNUSED_PARAMETER") val hasGooglePlay: Boolean = false,
    @Suppress("UNUSED_PARAMETER") val uninstalledThirdPartyTtsApps: List<Any> = emptyList(),
    @Suppress("UNUSED_PARAMETER") val installGuideSteps: List<String> = emptyList(),
    @Suppress("UNUSED_PARAMETER") val scenario: DialogScenario = DialogScenario.NO_ENGINE,
    /** 内置 TTS 模型是否已下载（剩余唯一影响 UI 的字段） */
    val embeddedModelDownloaded: Boolean = false,
    /** 内置 TTS 模型显示名 */
    val embeddedModelDisplayName: String = "",
    /** 内置 TTS 模型大小（人类可读） */
    val embeddedModelSizeText: String = "",
) {
    @Suppress("unused")
    enum class DialogScenario {
        HAS_DISCOVERED_ENGINES,
        SYSTEM_DEFAULT_INSTALLED_BUT_UNREACHABLE,
        NO_ENGINE,
    }
}

/**
 * 用户对 TTS 引导弹窗的回应动作（仅剩"下载内置模型"和"关闭"两类）。
 */
sealed class TtsInstallAction {
    /** 下载内置 TTS 模型 */
    data object DownloadEmbeddedTts : TtsInstallAction()
    /** 关闭弹窗 */
    data object Dismiss : TtsInstallAction()
    // 占位旧枚举（兼容现有 UI 调用方，运行时不再创建）
    @Suppress("unused") data class OpenEngineSettings(val enginePackage: String?) : TtsInstallAction()
    @Suppress("unused") data object InstallGoogleTts : TtsInstallAction()
    @Suppress("unused") data object OpenUnknownSourcesSettings : TtsInstallAction()
    @Suppress("unused") data class RetryWithEngine(val enginePackage: String) : TtsInstallAction()
    @Suppress("unused") data class InstallThirdPartyTtsApp(val app: Any) : TtsInstallAction()
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
    // 正文字体：true=衬线（阅读 App 的灵魂配置，Kindle/微信读书标配）
    val serifFont: Boolean = false,
    // 阅读方式：true=左右翻页（仿书页 HorizontalPager），false=上下滚动
    val pageMode: Boolean = false,
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
    // 阶段文案（"下载中 65%" / "解压中 (2/3) tokens.txt" / "正在初始化…"）
    // 让用户知道现在到底在干什么——避免"进度条停 95%"误判为卡死
    val embeddedDownloadStage: String? = null,
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
    val collinsHighlight: Boolean = false,
    val serifFont: Boolean = false,
    val pageMode: Boolean = false,
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

    /**
     * 暴露注入的 CollinsClassifier 单例给渲染层：此前 ReaderScreen 两个视图
     * 各自 remember { CollinsClassifier() } 手动 new，词表双份内存且在组合期
     * 构建卡首帧；统一走单例后全 App 一份词表、首次进入阅读页前已就绪。
     */
    val wordClassifier: CollinsClassifier get() = collinsClassifier

    /**
     * 显示 TTS 引导弹窗——已下线系统 TTS 探测，只剩"提醒下载内置引擎"一种场景。
     */
    private suspend fun showTtsInstallPrompt(@Suppress("UNUSED_PARAMETER") reason: TtsHelper.InitFailureReason, force: Boolean = false) {
        val embeddedEngine = ttsHelper.getEmbeddedEngine()
        val embeddedNotDownloaded = EmbeddedTtsEngine.AVAILABLE_MODELS.none {
            embeddedEngine.isModelDownloaded(it)
        }
        // 防抖：未下载内置模型时强制每次弹出（用户关掉后仍能找到入口）；
        // 已下载则不再骚扰
        if (!force && !embeddedNotDownloaded && ttsPromptShownThisSession) {
            android.util.Log.d("ReaderViewModel", "TTS install prompt suppressed (already shown this session)")
            return
        }

        val embeddedModelInfo = embeddedEngine.resolveModelForLanguage(_uiState.value.book?.language)
        val embeddedDownloaded = embeddedEngine.isModelDownloaded(embeddedModelInfo)
        val embeddedSizeText = formatBytes(embeddedModelInfo.sizeBytes)

        android.util.Log.i(
            "ReaderViewModel",
            "Showing TTS prompt (embedded-only): embeddedDownloaded=$embeddedDownloaded",
        )
        _ttsInstallPrompt.tryEmit(
            TtsInstallPrompt(
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
     * 用户对 TTS 引导弹窗的操作：只剩"下载内置模型"和"关闭"。
     * 旧的系统 TTS 相关 action 全部 no-op（保留枚举以兼容 UI 调用方）。
     */
    fun onTtsInstallAction(action: TtsInstallAction) {
        when (action) {
            is TtsInstallAction.DownloadEmbeddedTts -> {
                downloadEmbeddedTtsModel()
            }
            is TtsInstallAction.Dismiss -> { /* no-op */ }
            // 占位旧 action — 系统 TTS 已下线，全部 no-op（保留让 UI 编译过）
            is TtsInstallAction.OpenEngineSettings -> {}
            is TtsInstallAction.InstallGoogleTts -> {}
            is TtsInstallAction.OpenUnknownSourcesSettings -> {}
            is TtsInstallAction.RetryWithEngine -> {}
            is TtsInstallAction.InstallThirdPartyTtsApp -> {}
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
        // 清掉上次下载被取消后残留的中间态进度，避免 collect 立即收到
        // Downloading/Extracting/Initializing 显示残留进度条
        embeddedEngine.resetStaleDownloadProgress()
        // 按本书语言下载对应模型（兼容旧调用，当前实现只下 Piper）
        val bookLanguage = _uiState.value.book?.language
        val modelInfo = embeddedEngine.resolveModelForLanguage(bookLanguage)
        showToast("开始下载内置 TTS 模型（约 ${modelInfo.sizeBytes / 1_000_000}MB），请保持网络...")
        downloadJob = viewModelScope.launch {
            // 页内进度可见：引擎的 downloadProgress 流镜像进 uiState，
            // 引导弹窗保持打开并显示进度条（原实现进度只 log，弹窗直接关闭，
            // 想看进度只能去设置页）。按整百分比节流，避免高频重组
            // 注意：embeddedEngine.downloadProgress 现在是 sealed Progress，不是 Float?；
            // 我们把 fraction 和 stage 都映射进 uiState 让 UI 既能画进度条又能显示阶段文案。
            var lastEmittedPct = -999
            var lastEmittedStage: String? = null
            val progressJob = launch {
                embeddedEngine.downloadProgress.collect { progress ->
                    val (frac, stage) = when (progress) {
                        is com.eareyereading.tts.EmbeddedTtsEngine.Progress.Downloading ->
                            progress.fraction to "下载中 ${(progress.fraction * 100).toInt()}%"
                        is com.eareyereading.tts.EmbeddedTtsEngine.Progress.Extracting -> {
                            // 1.3：不再预扫统计文件数，进度按字节推进并附 ETA。
                            // 显示当前正在解压的文件名，让用户看到进展而非只看数字跳
                            val shortEntry = progress.currentEntryName?.substringAfterLast('/')
                            val pct = (progress.fraction * 100).toInt().coerceIn(0, 100)
                            val eta = when {
                                progress.fraction <= 0.01f || progress.fraction >= 0.99f || progress.elapsedMs <= 0 -> ""
                                else -> {
                                    val remainingMs = (progress.elapsedMs / progress.fraction * (1f - progress.fraction)).toLong()
                                    if (remainingMs > 0) " · 剩余约${(remainingMs / 1000).coerceAtMost(999)}s" else ""
                                }
                            }
                            progress.fraction to if (shortEntry != null) {
                                "解压中 $pct%$eta $shortEntry"
                            } else {
                                "解压中 $pct%$eta"
                            }
                        }
                        com.eareyereading.tts.EmbeddedTtsEngine.Progress.Initializing ->
                            0.99f to "正在初始化模型…"
                        com.eareyereading.tts.EmbeddedTtsEngine.Progress.Completed ->
                            1f to "✅ 已启用"
                        is com.eareyereading.tts.EmbeddedTtsEngine.Progress.Failed ->
                            0f to "下载失败：${progress.reason}"
                        com.eareyereading.tts.EmbeddedTtsEngine.Progress.Idle ->
                            0f to null
                    }
                    val pctInt = (frac * 100).toInt()
                    // 把 fraction 转成 Float?（让已有 UI 字段继续工作）
                    // null 表示"无任务"，由 UI 层处理
                    // Completed 也置 null：阅读页弹窗在下载成功后由 showToast 提示，
                    // 进度条该消失而非停在 100%（与设置页不同，设置页有持续状态卡片）
                    val fracOut: Float? = when (progress) {
                        is com.eareyereading.tts.EmbeddedTtsEngine.Progress.Idle,
                        is com.eareyereading.tts.EmbeddedTtsEngine.Progress.Failed,
                        com.eareyereading.tts.EmbeddedTtsEngine.Progress.Completed -> null
                        else -> frac
                    }
                    // 去重只看整百分比与阶段文案：旧条件里
                    // `fracOut != _uiState.value.embeddedDownloadProgress` 拿原始
                    // 浮点比较，下载期间进度小数每 100ms 都在变 → 条件恒真，
                    // 阅读页（整本书的渲染列表）每秒重组 10 次，肉眼可见掉帧
                    if (pctInt != lastEmittedPct || stage != lastEmittedStage) {
                        lastEmittedPct = pctInt
                        lastEmittedStage = stage
                        _uiState.update {
                            it.copy(
                                embeddedDownloadProgress = fracOut,
                                embeddedDownloadStage = stage,
                            )
                        }
                    }
                }
            }
            try {
                val ok = embeddedEngine.downloadModel(modelInfo) { progress ->
                    android.util.Log.d("ReaderViewModel", "Embedded TTS download progress: ${(progress * 100).toInt()}%")
                }
                if (ok) {
                    showToast("下载完成，正在启用内置 TTS...")
                    // initialize() 内部会写 Progress.Completed，collect 会把 fracOut 置 null，
                    // 进度条自然消失；此处不再手动维持 1f
                    val initOk = ttsHelper.initializeEmbeddedForced(bookLanguage)
                    if (initOk) {
                        showToast("✅ 内置 TTS 已启用！现在可以朗读了")
                    } else {
                        showToast("模型下载完成但初始化失败")
                    }
                } else {
                    // 带上引擎的具体失败原因（存储空间不足/镜像均不可用/解压失败等），
                    // 不再是笼统的"检查网络"——空间不足时那条提示会误导用户反复重试
                    // 引擎统一入口给出裸失败原因（存储空间不足/镜像均不可用/
                    // 解压失败等），展示层只负责加前缀——不再是笼统的"检查网络"
                    val reason = embeddedEngine.downloadFailureReasonOrNull()
                    showToast(
                        if (reason.isNullOrBlank()) "下载失败，请检查网络后重试"
                        else "下载失败：$reason",
                    )
                    // 失败才把进度清掉，让 UI 退出"下载中"
                    _uiState.update { it.copy(embeddedDownloadProgress = null) }
                }
            } finally {
                progressJob.cancel()
            }
        }
    }

    /**
     * 重新初始化 TTS——已下线系统 TTS 探测，仅初始化内置 sherpa-onnx。
     * 入参 enginePackage 保留兼容旧调用方（值不再被使用）。
     */
    @Suppress("UNUSED_PARAMETER")
    private fun retryTtsInitWithEngine(enginePackage: String?) {
        ttsInitJob?.cancel()
        ttsInitJob = viewModelScope.launch {
            val ok = try {
                ttsHelper.initializeEmbeddedForced(_uiState.value.book?.language)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                android.util.Log.e("ReaderViewModel", "Embedded TTS init failed (retry)", e)
                false
            }
            _uiState.update { it.copy(ttsInitialized = ok) }
            if (ok) {
                ttsPromptShownThisSession = false
                showToast("内置 TTS 已启用")
            } else {
                showToast("内置 TTS 初始化仍然失败，请稍后再试")
            }
        }
    }

    /**
     * 处理 TTS 初始化失败：系统 TTS 失败现已不可能发生（TtsHelper 不再尝试系统 TTS），
     * 所以这条路径只会触发"内置 TTS 未下载 / 模型损坏"，直接引导用户去设置页下载。
     */
    private suspend fun handleTtsInitFailure(prefix: String) {
        _uiState.update { it.copy(ttsInitialized = false) }
        val embeddedEngine = ttsHelper.getEmbeddedEngine()
        val embeddedNotDownloaded = !embeddedEngine.isModelDownloaded()
        val message = if (embeddedNotDownloaded) {
            val sizeMB = embeddedEngine.resolveModelForLanguage(_uiState.value.book?.language).sizeBytes / 1_000_000
            "$prefix：需要下载内置语音模型（约 ${sizeMB}MB）"
        } else {
            "$prefix：内置 TTS 初始化失败"
        }
        showToast(message)
        // 总是弹引导（让用户能进设置页下载/重试）
        showTtsInstallPrompt(TtsHelper.InitFailureReason.NO_ENGINE)
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

        // 整书翻译并发上限：几百段一次性 async 同时压 ML Kit（各自还可能
        // 等模型就绪/触发下载限流），限流后吞吐更高也更稳
        private const val TRANSLATION_CONCURRENCY = 6

        // 逐段渐进上屏的合批窗口：翻页模式下每次译文更新触发整书重新分页，
        // 400ms 合并一次把重组/测量开销压到常数级，视觉上仍是"逐段浮现"
        private const val TRANSLATION_UI_FLUSH_MS = 400L

        // 译文分批落库批大小：中途取消/失败时已译段落不丢，也避免整本一个大事务
        private const val TRANSLATION_SAVE_BATCH = 16

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

    /**
     * 段落翻译入口：委托 TranslationHelper.translateParagraph——
     * LLM 已配置时整段带上下文一次成文（文学化译文），否则按句切分
     * 逐句机翻拼接（规避 ML Kit 长输入截断）。
     */
    private suspend fun translateParagraphBySentences(
        paragraph: String,
        sourceLang: String,
    ): String? = translationHelper.translateParagraph(paragraph, sourceLang)
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
    private var sentenceTranslateJob: kotlinx.coroutines.Job? = null
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
                    settingsRepository.getCollinsHighlight(),
                    settingsRepository.getSerifFont(),
                    settingsRepository.getReadingPageMode(),
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
                    @Suppress("UNCHECKED_CAST")
                    val collinsHighlight = values[5] as? Boolean ?: false
                    @Suppress("UNCHECKED_CAST")
                    val serifFont = values[6] as? Boolean ?: false
                    @Suppress("UNCHECKED_CAST")
                    val pageMode = values[7] as? Boolean ?: false
                    ReadingSettings(speed, fontSize, theme, alpha, strength, collinsHighlight, serifFont, pageMode)
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
                            showWordLevelColors = s.collinsHighlight,
                            serifFont = s.serifFont,
                            pageMode = s.pageMode,
                        )
                    }
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                android.util.Log.e("ReaderViewModel", "settings combine failed", e)
            }
        }

        // 引擎外部停止信号（音频焦点被电话/闹钟抢走等）：引擎 stop() 只能
        // 取消正在出声的那一句，循环播放由本 VM 的 Job 驱动——必须在这里
        // 收闸（清 isAutoReading/isPlaying/isTtsPlaying + 取消驱动 Job），
        // 否则焦点丢失后自动朗读/速读会推进到下一段继续压着通话读；
        // 单段朗读的 onComplete 也会被取消路径吞掉导致 isTtsPlaying 卡 true
        viewModelScope.launch {
            try {
                ttsHelper.getEmbeddedEngine().externalStop.collect {
                    android.util.Log.i("ReaderViewModel", "external stop received, halting all playback")
                    stopAllPlayback()
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                android.util.Log.e("ReaderViewModel", "externalStop collect failed", e)
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

        // 切换书籍前，把上一本书的会话统计先落库（若有未落库部分）。
        // 必须快照传参：viewModelScope 是 Main 调度器，launch 体要等本函数
        // 让出线程后才执行，而下面同步把 sessionCharsRead 归零/前移基准——
        // 旧实现让 flush 协程读字段，永远读到 0 直接早返回，上一本书的
        // 阅读时长/字数在每次换书时静默丢失
        currentBookId?.let { prevId ->
            val pendingChars = sessionCharsRead
            if (pendingChars > 0) {
                val flushBase = lastFlushTime
                val flushHighWater = (lastRecordedParagraphIndex + 1).coerceAtLeast(1)
                viewModelScope.launch {
                    flushSessionStats(
                        prevId,
                        chars = pendingChars,
                        baseTime = flushBase,
                        paragraphsHighWater = flushHighWater,
                        clearSession = false,   // 字段已被下方同步重置，不能再清
                    )
                }
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
        // 全书翻译 Job 也必须取消：它捕获的是旧书段落，翻译结果是按
        // 段落下标键控的 Map——不取消的话，慢翻译（首次要下载 ML Kit 模型）
        // 落地后会把旧书译文写进新书的同名下标，新书段落顶着别人的译文
        translationJob?.cancel()
        // 点词/句子翻译的异步结果同样属于旧书：A 书点词后立刻换 B 书，
        // 慢查询落地会把 A 书的词卡写进 B 书 UI（issue 3.2）
        selectWordJob?.cancel()
        sentenceTranslateJob?.cancel()

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
                    // split 是 O(全书) 的字符串切分 + 一次性分配全部段子串，
                    // 10M 字符的书在主线程执行可感知卡顿——与 EPUB 重解析
                    // 同样下沉后台调度器
                    withContext(Dispatchers.Default) {
                        book.content.split("\n\n").filter { it.isNotBlank() }
                    }
                } else {
                    // parseBook 是阻塞式 zip IO + 正则解析，viewModelScope 跑在
                    // Main 上——大书打开时直接 ANR（R9 修过 addBook 同款调用点，
                    // 阅读加载路径这条漏网）
                    // issue 9.9：统一读取代理，本地文件失效时回退用持久化的 content:// URI 读取
                    withContext(Dispatchers.IO) {
                        epubParser.parseBook(book.filePath, book.sourceUri, context.contentResolver).paragraphs
                    }
                }
                val state = readingRepository.getState(bookId)
                // 与 saveState 持久化的 totalCharacters 口径一致（都按段落分隔符拼接）
                val totalChars = paragraphs.joinToString("\n\n").length.toLong()
                // 内容可能比重导入/重切分，持久化的位置必须按新内容收敛，
                // 否则 Slider/进度/朗读索引全部越界
                val maxIdx = (paragraphs.size - 1).coerceAtLeast(0)
                // issue 8.5：优先从 Room 读本书语言对的翻译缓存。回译/分栏模式
                // 重开书直接展示已缓存的译文，不再重跑整本翻译；翻译结果首次落地后
                // 由 translateAllParagraphs 写入缓存表
                val bookLang = book.language.takeIf { it.isNotBlank() } ?: "en"
                // 缓存键分层（LLM/机翻分开缓存）：见 TranslationHelper.effectiveCacheLangPair
                val cachedTranslations = readingRepository.getTranslations(
                    bookId,
                    translationHelper.effectiveCacheLangPair("$bookLang>zh"),
                )

                _uiState.update {
                    it.copy(
                        // content 剥离：paragraphs 已是全文的段落形态，再在 uiState
                        // 持有 content 即整书双份常驻内存（10M 字符书 ≈ 40MB+）。
                        // 后续需要重解析时（content 为空分支）由本地 book 变量兜底
                        book = book.copy(content = ""),
                        paragraphs = paragraphs,
                        currentParagraphIndex = (state?.currentParagraph ?: 0).coerceIn(0, maxIdx),
                        currentWordIndex = (state?.currentPosition ?: 0).coerceAtLeast(0),
                        readingMode = state?.readingMode ?: ReadingMode.NORMAL,
                        rsvpSpeed = state?.rsvpSpeed ?: it.rsvpSpeed,
                        // 每本书持久化的字号/主题随书恢复（此前只写不读，往返不对称）
                        fontSize = state?.fontSize ?: it.fontSize,
                        theme = state?.theme ?: it.theme,
                        totalReadChars = totalChars,
                        // 换书必须清掉上一本书的派生状态，否则旧书内容在新书里诈尸：
                        // 译文 Map 按下标键控会直接张冠李戴；词卡/答案弹窗引用旧书内容
                        // issue 8.5：不再硬清 paragraphTranslations，改为读新书的 Room 缓存
                        paragraphTranslations = cachedTranslations,
                        showTranslation = false,
                        isTranslating = false,
                        selectedVocab = null,
                        showWordDialog = false,
                        wordDefinition = null,
                        hiddenWordAnswer = null,
                        // 书签/高亮 collect 到新书首帧前是旧书数据：
                        // 短暂残留即"幽灵书签"（issue 3.1）
                        bookmarkedParagraphs = emptySet(),
                        highlights = emptyMap(),
                        isLoading = false,
                    )
                }
                // 句子翻译弹窗同样属于上一本书的内容，一并清掉
                _selectedSentence.value = null
                _sentenceTranslation.value = null
                bookLoaded = true
                // 字符统计的高水位从"恢复后的位置"起算，而不是 -1：
                // 否则退出时 doSaveProgress 会把 0..恢复位置 的整段前缀当成本次新读，
                // 累计写库后每次重开同一本书今日字数都会虚增一截
                lastRecordedParagraphIndex = (state?.currentParagraph ?: 0).coerceIn(0, maxIdx)

                // 恢复的阅读模式若依赖派生数据（挖空/模糊/全书译文），必须立即
                // 生成/拉取，否则重开书是空白页或"正在获取译文..."假加载态
                // （此前只有 setReadingMode 会生成）
                when (_uiState.value.readingMode) {
                    ReadingMode.CLOZE -> generateCloze()
                    ReadingMode.FUZZY -> generateFuzzy()
                    // 全文翻译改为"总是补缺"：loadBook 已把 Room 缓存灌进
                    // paragraphTranslations，若只在 isEmpty 时才触发，部分缓存
                    // （上次中途取消/失败）的书永远缺着尾巴不补
                    ReadingMode.BACK_TRANSLATION, ReadingMode.SPLIT ->
                        translateAllParagraphs()
                    else -> Unit
                }

                // TTS 是单例、跨书复用：无论是否已初始化都要同步语言，
                // 否则读完英文书再开中文书会用旧 locale 一直读下去
                ttsHelper.setLanguage(book.language)

                // 预翻译预热：进书即后台拉起 ML Kit 翻译模型下载/就绪，
                // 首次开启全文翻译不再阻塞等待模型（最多 30s）
                viewModelScope.launch {
                    try {
                        translationHelper.warmUp(bookLang)
                    } catch (e: kotlinx.coroutines.CancellationException) {
                        throw e
                    } catch (_: Exception) {
                        // 预热失败静默：正式翻译路径仍有重试窗口兜底
                    }
                }

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
            return
        }
        // 与 toggleTts 同一道防剧透闸：溢出菜单"自动朗读"直达本函数，
        // 没有这道守卫时挖空/听写/模糊模式下会把含答案的原文整本读出来
        when (_uiState.value.readingMode) {
            ReadingMode.CLOZE, ReadingMode.DICTATION, ReadingMode.FUZZY -> {
                showToast("当前模式含隐藏内容，朗读会泄露答案")
                return
            }
            else -> Unit
        }
        startAutoRead()
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
            // 内置模型与本书语言不匹配时先切换（英文书→纯英文模型），
            // 已匹配/无对应模型时为 no-op
            ttsHelper.switchEmbeddedModelIfNeeded(_uiState.value.book?.language)
            hintEmbeddedVoiceMismatchIfNeeded()
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

    /**
     * 内置音色与本书语言不匹配时给一次性提示（会话内只提示一次）：
     * 英文书落在中文声 → 口音重、数字带中文音；
     * 中文书落在英文声 → 中文字读不出（静音）。都引导去设置下载对应模型。
     *
     * 2026-08-30: 现内置只 Piper 英文声，此函数不再能产生 mismatch 分支，
     * 保留为 no-op 直到新增多语种模型为止。调用点保留兼容。
     */
    private var embeddedVoiceMismatchHintShown = false
    private fun hintEmbeddedVoiceMismatchIfNeeded() {
        if (embeddedVoiceMismatchHintShown) return
        if (ttsHelper.ttsMode != TtsHelper.TtsMode.EMBEDDED) return
        val model = ttsHelper.getEmbeddedEngine().getCurrentModelInfo()
        // 现在只有 Piper 英文声，不会出现 wantsEnglish != modelIsEnglish
        embeddedVoiceMismatchHintShown = true
        if (model.language != "en") {
            // 防御性兜底：未来加回多语种时这条 toast 仍能起作用
            showToast("当前内置音色（${model.displayName}）与本书语言不匹配")
        }
    }

    private fun doStartAutoRead(paragraphs: List<String>) {
        val startParaIdx = _uiState.value.currentParagraphIndex
        // autoReadingParaIndex 从实际起播段开始（原实现恒置 0，与起播位置不符）
        _uiState.update { it.copy(isAutoReading = true, autoReadingParaIndex = startParaIdx, currentSentenceIndex = 0) }

        autoReadJob = viewModelScope.launch {
            for (paraIdx in startParaIdx until paragraphs.size) {
                if (!_uiState.value.isAutoReading) break

                val para = paragraphs[paraIdx]
                // 空段/插图标记段无可读文本：推进索引与统计后跳过
                if (para.isBlank() || BookImages.isImageMarker(para)) {
                    _uiState.update { it.copy(autoReadingParaIndex = paraIdx, currentParagraphIndex = paraIdx) }
                    recordParagraphVisit(paraIdx)  // issue 3.6
                    continue
                }

                _uiState.update { it.copy(autoReadingParaIndex = paraIdx, currentParagraphIndex = paraIdx) }
                recordParagraphVisit(paraIdx)  // issue 3.6：自动朗读逐段累计

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

        // 回译/分栏模式依赖全书译文，但旧实现里全书翻译只有
        // toggleTranslation() 一个入口——从没开过翻译开关就进回译模式，
        // 页面永远停在"正在获取译文..."的假加载态（没有任何任务在跑）。
        // 总是补缺：部分缓存的书也继续翻完剩余段落
        if (mode == ReadingMode.BACK_TRANSLATION || mode == ReadingMode.SPLIT) {
            translateAllParagraphs()
        }

        viewModelScope.launch {
            _uiState.update { it.copy(readingMode = mode, showModeSelector = false) }
            currentBookId?.let { readingRepository.updateMode(it, mode) }
        }
    }

    /** 回译模式译文缺失时的手动重试入口（翻译失败后视图提供重试按钮）。 */
    fun retryTranslation() {
        // 与 toggleTranslation 同语义：总是补缺（部分缓存的书也能续翻剩余段落）
        translateAllParagraphs()
    }

    fun generateCloze() {
        val paragraphs = _uiState.value.paragraphs
        val currentIdx = _uiState.value.currentParagraphIndex
        if (currentIdx < paragraphs.size) {
            // 插图标记剔除后再生成（标记不是可挖空的文本）
            val text = BookImages.stripImageMarkers(paragraphs[currentIdx])
            val clozeWords = wordAnalyzer.generateClozeText(text, ratio = CLOZE_RATIO)
            _uiState.update { it.copy(clozeWords = clozeWords, hiddenWordAnswer = null) }
        }
    }

    fun generateFuzzy() {
        val paragraphs = _uiState.value.paragraphs
        val currentIdx = _uiState.value.currentParagraphIndex
        if (currentIdx < paragraphs.size) {
            // 插图标记剔除后再生成（标记不是可模糊的文本）
            val text = BookImages.stripImageMarkers(paragraphs[currentIdx])
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
                // 内置模型与本书语言不匹配时先切换，已匹配时为 no-op
                ttsHelper.switchEmbeddedModelIfNeeded(_uiState.value.book?.language)
                hintEmbeddedVoiceMismatchIfNeeded()
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
                // 内置模型与本书语言不匹配时先切换，已匹配时为 no-op
                ttsHelper.switchEmbeddedModelIfNeeded(_uiState.value.book?.language)
                hintEmbeddedVoiceMismatchIfNeeded()
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
                recordParagraphVisit(i)  // issue 3.6：速读逐段累计

                // 插图标记段无文本：不驱动 TTS/句子高亮，直接滑过
                if (BookImages.isImageMarker(paragraphs[i])) continue

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
            // 句链被 stop 打断时 onAllDone 也会清一次；这里同步清，保证
            // UI 立即退出句子高亮态（等回调会有一帧延迟）
            _uiState.update {
                it.copy(isTtsPlaying = false, currentSentences = emptyList(), currentSentenceIndex = 0)
            }
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
                        hintEmbeddedVoiceMismatchIfNeeded()
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
            // 已初始化但内置模型可能与本书语言不匹配（换了书）：
            // 播放前复查并切换（已匹配/无对应模型时是廉价 no-op）。
            // 纳入被追踪的 ttsInitJob：切换窗口内用户改主意可被仲裁取消
            ttsInitJob?.cancel()
            ttsInitJob = viewModelScope.launch {
                ttsHelper.switchEmbeddedModelIfNeeded(_uiState.value.book?.language)
                hintEmbeddedVoiceMismatchIfNeeded()
                val s = _uiState.value
                if (!s.isPlaying && !s.isAutoReading && !s.isTtsPlaying) {
                    doToggleTts()
                }
            }
        }
    }

    private fun doToggleTts() {
        val para = _uiState.value.paragraphs.getOrNull(_uiState.value.currentParagraphIndex) ?: return
        // 插图标记段无文本可读：直接跳过，不进入 TTS 状态
        if (BookImages.isImageMarker(para)) return
        // 按句朗读（与自动朗读/速读同一套切分与链式播放）：原实现整段
        // 一次合成，长段既无句级推进高亮，也没法按句暂停跟进
        val sentences = splitSentencesCompat(para)
        if (sentences.isEmpty()) return
        _uiState.update {
            it.copy(isTtsPlaying = true, currentSentences = sentences, currentSentenceIndex = 0)
        }
        ttsHelper.speakSentences(
            sentences = sentences,
            onSentenceDone = { sentenceIdx ->
                // 同自动朗读语义："第 sentenceIdx 句已读完"，高亮推进到下一句
                _uiState.update {
                    it.copy(currentSentenceIndex = (sentenceIdx + 1).coerceAtMost(sentences.size - 1))
                }
            },
            onAllDone = {
                viewModelScope.launch {
                    _uiState.update {
                        it.copy(isTtsPlaying = false, currentSentences = emptyList(), currentSentenceIndex = 0)
                    }
                }
            },
        )
    }

    fun nextParagraph() {
        val paragraphs = _uiState.value.paragraphs
        if (paragraphs.isEmpty()) return
        // 手动跳转必须停掉进行中的播放：否则朗读循环下一步会把
        // currentParagraphIndex 又写回它自己的进度，视口被拽回
        stopAllPlayback()
        val nextIdx = (_uiState.value.currentParagraphIndex + 1).coerceAtMost(paragraphs.size - 1)
        _uiState.update { it.copy(currentParagraphIndex = nextIdx, currentWordIndex = 0) }
        recordParagraphVisit(nextIdx)  // issue 3.6：原子累计，不等防抖保存
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
        recordParagraphVisit(idx)  // issue 3.6
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
        recordParagraphVisit(index)  // issue 3.6：视口滚动前进按段累计
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

    /** 底部栏快捷字号调节（A- / A+ 按钮）：±1sp 步进，复用 setFontSize 的收敛与防抖。 */
    fun adjustFontSize(delta: Int) {
        setFontSize(_uiState.value.fontSize + delta)
    }

    /**
     * 阅读主题循环切换（明亮 → 护眼 → 暗黑 → 明亮），供底部栏快捷胶囊使用。
     * 主题本身是全局设置：写 DataStore 后设置流会回填 uiState.theme。
     */
    fun cycleReadingTheme() {
        val next = when (_uiState.value.theme) {
            ReadingTheme.LIGHT -> ReadingTheme.SEPIA
            ReadingTheme.SEPIA -> ReadingTheme.DARK
            ReadingTheme.DARK -> ReadingTheme.LIGHT
        }
        _uiState.update { it.copy(theme = next) }
        viewModelScope.launch {
            try {
                settingsRepository.setTheme(next)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                android.util.Log.e("ReaderViewModel", "setTheme failed", e)
            }
        }
    }

    /** 衬线字体切换（阅读器正文字体，全局设置持久化）。 */
    fun toggleSerifFont() {
        val next = !_uiState.value.serifFont
        _uiState.update { it.copy(serifFont = next) }
        viewModelScope.launch {
            try {
                settingsRepository.setSerifFont(next)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                android.util.Log.e("ReaderViewModel", "setSerifFont failed", e)
            }
        }
    }

    /** 阅读方式切换：上下滚动 ⇄ 左右翻页（仿书页，全局设置持久化）。 */
    fun togglePageMode() {
        val next = !_uiState.value.pageMode
        _uiState.update { it.copy(pageMode = next) }
        viewModelScope.launch {
            try {
                settingsRepository.setReadingPageMode(next)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                android.util.Log.e("ReaderViewModel", "setReadingPageMode failed", e)
            }
        }
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
                // issue 8.1：源语言随书取（不再写死 en→zh），书是法/日/中文时
                // ML Kit 也用对应语言模型做源，避免中文串被当英文翻译致空/乱码
                val sourceLang = _uiState.value.book?.language?.takeIf { it.isNotBlank() } ?: "en"
                // 如果没有释义，用 ML Kit 翻译
                val definition = existing?.definition
                    ?: translationHelper.translateWord(clean, sourceLang)
                    ?: "未找到释义"
                _uiState.update {
                    it.copy(
                        selectedVocab = existing ?: Vocabulary(
                            word = clean,
                            level = level.level,
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
        // 书身份快照：launch 体执行时用户可能已换书（点词弹窗开着按返回再进 B 书），
        // 此时 currentBookId 已是 B 书——不对照快照，A 书生词会记到 B 书的 bookId/title 下
        val myBookId = currentBookId
        viewModelScope.launch {
            val currentVocab = _uiState.value.selectedVocab ?: return@launch
            // issue: 生词入库时把查好的释义一起持久化，否则"词汇本"里每词无翻译
            val wordDef = _uiState.value.wordDefinition
                ?.takeIf { it.isNotBlank() && it != "未找到释义" }
            val vocabToSave = currentVocab.copy(
                bookId = myBookId,
                bookTitle = _uiState.value.book?.takeIf { it.id == myBookId }?.title,
                context = context,
                definition = wordDef ?: currentVocab.definition,
            )

            // 去重查询也纳入 try：它是 Room 调用，原实现留在 try 外，
            // 数据库异常会在"加入生词本"时直接崩 app
            try {
                // 去重与保存用同一个词：此前去重查 word 参数、保存却用 selectedVocab，
                // 点词竞态下两者不一致会反复插入失败且无提示
                val dedupeWord = vocabToSave.word.ifBlank { word }
                val existing = vocabularyRepository.getWord(dedupeWord)
                if (existing != null) {
                    // 此前重复词静默关闭弹窗，与成功路径无差别——用户不知道
                    // 到底加没加进去；补一条明确提示
                    _uiState.update { it.copy(showWordDialog = false, selectedVocab = null) }
                    showToast("「$dedupeWord」已在生词本中")
                    return@launch
                }

                // 捕获 DB 生成的 id，替换 selectedVocab 使「加入复习」拿到正确 vocabularyId
                val id = vocabularyRepository.addWord(vocabToSave)

                // 写库期间换书：丢弃这次写入的 UI 更新，不把 A 书的弹窗状态安到 B 书
                if (currentBookId != myBookId) return@launch

                // 阅读页加入的生词此前从不进复习队列：due count 永远 0，
                // "点词 → 加生词本 → 等复习"主流程断链（issue 11.3）
                vocabularyRepository.addWordToReview(id, vocabToSave.word)

                _uiState.update {
                    it.copy(
                        showWordDialog = false,
                        selectedVocab = vocabToSave.copy(id = id),
                    )
                }
                showToast("已加入生词本")
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
        // 关闭翻译：取消正在进行的全书翻译 Job 并清空译文，避免偷跑流量后台继续
        // 翻译全部段落（issue 8.10）
        if (!show) {
            translationJob?.cancel()
            _uiState.update { it.copy(showTranslation = false, isTranslating = false, paragraphTranslations = emptyMap()) }
            return
        }
        // isTranslating 必须同步置位：标志原来在 launch 内部才设置，
        // 快速开-关-开会在两次 launch 都未执行前连过两次守卫 → 并发双份全书翻译
        _uiState.update { it.copy(showTranslation = true, isTranslating = true) }
        // 打开翻译总是走"补缺"：loadBook 已把 Room 缓存灌进 paragraphTranslations，
        // 旧实现只要缓存非空就跳过——部分缓存的书（上次中途取消）永远缺尾巴
        translateAllParagraphs()
    }

    private fun translateAllParagraphs() {
        // 已在翻译中则不重复启动
        if (translationJob?.isActive == true) return
        // 书本身份快照：换书会取消本 Job，但取消/失败的收尾写仍可能落在
        // 新书加载之后——所有 uiState 写入与 toast 都要先核对当前书
        val myBookId = currentBookId
        val sourceLang = _uiState.value.book?.language?.takeIf { it.isNotBlank() } ?: "en"
        translationJob = viewModelScope.launch {
            // 缓存键分层（LLM/机翻分开缓存）：开启 AI 翻译后旧书的机翻缓存
            // 不会被命中，整本按 LLM 重新翻译落库（挂起读取需在协程内）
            val langPair = translationHelper.effectiveCacheLangPair("$sourceLang>zh")
            _uiState.update { it.copy(isTranslating = true) }
            try {
                val paragraphs = _uiState.value.paragraphs
                val bookId = myBookId
                if (bookId == null) {
                    if (currentBookId == myBookId) {
                        _uiState.update { it.copy(isTranslating = false, showTranslation = false) }
                    }
                    return@launch
                }
                // issue 8.5：优先读 Room 缓存，只有未缓存的段落才重新翻译
                val cached = readingRepository.getTranslations(bookId, langPair)
                val merged = cached.toMutableMap()
                // 缓存先上屏：开关一开立即可读，不必等全书补翻完成
                if (merged.isNotEmpty() && currentBookId == myBookId) {
                    _uiState.update { it.copy(paragraphTranslations = merged.toMap()) }
                }
                // 需要翻译的段落：有源文、尚未缓存；插图标记段无文本不参与翻译
                val missing = paragraphs.indices.filter { idx ->
                    paragraphs[idx].isNotBlank() && !merged.containsKey(idx) &&
                        !BookImages.isImageMarker(paragraphs[idx])
                }
                if (missing.isNotEmpty()) {
                    // 逐段翻译 · 预翻译优先：从当前阅读位置向两侧扩散排序，
                    // 用户正在看的段落最先翻译上屏；后台仍 Semaphore 限流并发
                    val center = _uiState.value.currentParagraphIndex
                    val ordered = missing.sortedBy { kotlin.math.abs(it - center) }
                    val semaphore = Semaphore(TRANSLATION_CONCURRENCY)
                    // 渐进上屏合批：翻页模式下 paragraphTranslations 每次更新都会
                    // 触发整书重新分页测量，逐段直推 = O(段落数²) 测量开销；
                    // 按时间双阈值合并成 ~2.5 次/秒
                    var uiDirty = false
                    var lastUiFlushMs = 0L
                    fun flushUi(force: Boolean = false) {
                        if (!uiDirty) return
                        val now = android.os.SystemClock.elapsedRealtime()
                        if (!force && now - lastUiFlushMs < TRANSLATION_UI_FLUSH_MS) return
                        lastUiFlushMs = now
                        uiDirty = false
                        if (currentBookId == myBookId) {
                            _uiState.update { it.copy(paragraphTranslations = merged.toMap()) }
                        }
                    }
                    // 分批落库：每 N 段一个事务，中途取消/失败时已译段落不丢
                    val pendingSave = LinkedHashMap<Int, String>()
                    suspend fun flushSave() {
                        if (pendingSave.isEmpty()) return
                        val batch = pendingSave.toMap()
                        pendingSave.clear()
                        try {
                            readingRepository.saveTranslations(bookId, langPair, paragraphs, batch)
                        } catch (e: Exception) {
                            android.util.Log.e("ReaderViewModel", "save translation cache failed", e)
                        }
                    }
                    coroutineScope {
                        val jobs = ordered.map { idx ->
                            async(Dispatchers.IO) {
                                semaphore.withPermit {
                                    idx to translateParagraphBySentences(paragraphs[idx], sourceLang)
                                }
                            }
                        }
                        // 逐段渐进：按"离当前阅读位置由近及远"的顺序 await，
                        // 译完一段记一段，达阈值成批上屏 + 落库
                        for (job in jobs) {
                            val (idx, result) = job.await()
                            // issue 8.3：失败段（null/空）不写入显示，也不落缓存
                            if (!result.isNullOrBlank()) {
                                merged[idx] = result
                                pendingSave[idx] = result
                                uiDirty = true
                            }
                            flushUi()
                            if (pendingSave.size >= TRANSLATION_SAVE_BATCH) flushSave()
                        }
                    }
                    flushUi(force = true)
                    flushSave()
                }
                // 全空视为失败：所有段落要么失败要么无缓存——非空 Map 会把
                // hasTranslation 顶成 true——回译视图变永久空白栏，
                // retryTranslation 的 isEmpty() 守卫又让重试永远不可达
                if (merged.values.none { it.isNotBlank() } && paragraphs.isNotEmpty()) {
                    if (currentBookId == myBookId) {
                        _uiState.update { it.copy(isTranslating = false, showTranslation = false) }
                        showToast("翻译失败：翻译模型不可用，请检查网络后重试")
                    }
                    return@launch
                }
                // 取消是非抢占的：cancel() 若恰好落在 translate 返回之后，
                // 本段仍会执行——按书核对，旧书译文不写进新书状态
                if (currentBookId != myBookId) return@launch
                _uiState.update { it.copy(
                    paragraphTranslations = merged.toMap(),
                    isTranslating = false,
                ) }
            } catch (e: kotlinx.coroutines.CancellationException) {
                // 协程取消必须向上传播，否则取消后还会继续更新状态；
                // 只在还是同一本书时复位标志——换书取消后这里若落地，
                // 会把新书正在进行的翻译 spinner 提前掐灭
                if (currentBookId == myBookId) {
                    _uiState.update { it.copy(isTranslating = false) }
                }
                throw e
            } catch (e: com.google.mlkit.common.MlKitException) {
                android.util.Log.e("ReaderViewModel", "ML Kit translation failed", e)
                // 失败必须可见：旧实现只 log，回译模式永远停在"正在获取译文..."，
                // NORMAL 模式开关开着却什么都没有，用户无任何线索
                if (currentBookId == myBookId) {
                    // issue 8.3：失败分支显式清空译文，isEmpty() 失败判定
                    // 才能重新触发，"重试"入口可达
                    _uiState.update {
                        it.copy(
                            isTranslating = false,
                            showTranslation = false,
                            paragraphTranslations = emptyMap(),
                        )
                    }
                    showToast("翻译失败：模型下载或翻译出错，请稍后重试")
                }
            } catch (e: java.lang.RuntimeException) {
                android.util.Log.e("ReaderViewModel", "Translation failed", e)
                if (currentBookId == myBookId) {
                    _uiState.update {
                        it.copy(
                            isTranslating = false,
                            showTranslation = false,
                            paragraphTranslations = emptyMap(),
                        )
                    }
                    showToast("翻译失败，请稍后重试")
                }
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
        // 总字数在 loadBook 时已按同一口径（段落 "\n\n" 拼接）算好并存进
        // totalReadChars——这里每次防抖保存都 joinToString 整本书是 O(book)
        // 字符串构建，滑杆拖动时 300ms 一次全在主线程上
        val totalChars = if (state.totalReadChars > 0) {
            state.totalReadChars.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        } else {
            state.paragraphs.joinToString("\n\n").length
        }
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

        // 记录阅读统计（仅新增段落计入字符数）。
        // issue 3.6：段落计数改为在"进入/推进段落"时原子记录（recordParagraphVisit），
        // 不再等 300ms 防抖的 doSaveProgress 才推进 lastRecordedParagraphIndex。
        // 否则快速连跳/朗读循环里，防抖窗口内的状态与高水位错位，累计字数会虚增。
        // 这里只负责按需把会话统计落库（增量/兜底）。

        // 增量落库：距上次落库满 1 分钟就写一次，进程被杀不再丢整段会话。
        // 1 分钟门槛同时避免每次保存都记 1 分钟（收尾的零星部分由 cleanup 兜底）
        val now = System.currentTimeMillis()
        if (now - lastFlushTime >= 60_000) {
            flushSessionStats(bookId)
        }
    }

    /**
     * issue 3.6：原子记录"读到某段"的字符累计。
     * 只在严格前进（高水位上升）时累加，并把高水位 lastRecordedParagraphIndex
     * 同步推进到 newIndex——由此段落计数不再依赖 300ms 防抖的保存时机，
     * 朗读/速读循环里连过数段也按实际经过段落准确累计，不会虚增或漏记。
     * 大跳转（>2 段）只计目标段，防止拖进度条刷满整本书字数。
     */
    private fun recordParagraphVisit(newIndex: Int) {
        if (newIndex <= lastRecordedParagraphIndex) return
        val paragraphs = _uiState.value.paragraphs
        val jumped = newIndex - lastRecordedParagraphIndex
        val charsAdded = if (jumped <= 2) {
            (lastRecordedParagraphIndex + 1..newIndex).sumOf { idx ->
                paragraphs.getOrNull(idx)?.length ?: 0
            }
        } else {
            paragraphs.getOrNull(newIndex)?.length ?: 0
        }
        sessionCharsRead += charsAdded
        lastRecordedParagraphIndex = newIndex
    }

    /**
     * 会话统计落库。
     *
     * 默认读会话字段（增量落库/cleanup 收尾路径）；换书路径由 loadBook
     * 在重置字段前快照传入，clearSession=false 表示字段已重置、落库后
     * 不再清（清了也无害，但会误清掉新书的全新会话起始基准）。
     */
    private suspend fun flushSessionStats(
        bookId: Long,
        chars: Long = sessionCharsRead,
        baseTime: Long = lastFlushTime,
        paragraphsHighWater: Int = (lastRecordedParagraphIndex + 1).coerceAtLeast(1),
        clearSession: Boolean = true,
    ) {
        // 幂等：成功落库后 sessionCharsRead 归零，第二次调用（如
        // onDispose + onCleared 双路径）会在这里早返回，不会重复写
        if (chars <= 0) return
        val now = System.currentTimeMillis()
        // 按"距上次落库"计分钟：增量落库后基准前移，收尾只补尾部，
        // 不再把整个会话时长重复计入
        val base = if (baseTime > 0) baseTime else readingStartTime
        val minutesRead = ((now - base) / 60_000).toInt().coerceAtLeast(1)
        val dateFormat = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.getDefault())
        val today = dateFormat.format(java.util.Date(now))
        try {
            // 原子累计：@Transaction + (bookId,date) 唯一索引兜底
            readingStatsDao.accumulateDailyStat(
                bookId = bookId,
                date = today,
                addMinutes = minutesRead,
                addChars = chars.toInt(),
                paragraphsHighWater = paragraphsHighWater,
            )
            if (clearSession) {
                sessionCharsRead = 0L
                lastFlushTime = now
            }
            // statsFlushed 只在"会话结束式"收尾时置位（见 cleanup），
            // 增量落库后仍可继续累计
        } catch (e: Exception) {
            android.util.Log.e("ReaderViewModel", "Failed to record stats", e)
        }
    }

    private fun getCurrentParagraphWords(): List<String> {
        val para = _uiState.value.paragraphs.getOrNull(_uiState.value.currentParagraphIndex) ?: return emptyList()
        // 插图标记段无词可读（RSVP 不闪 "[[IMG" 碎片）
        return wordAnalyzer.extractWords(BookImages.stripImageMarkers(para))
    }

    /**
     * 取消所有运行中的作业并停止 TTS，完成最后一次保存。
     *
     * 保存分两条路径（issue 3.10）：
     * - 默认（onDispose 触发，scope 仍存活）：异步保存，不阻塞主线程
     * - [synchronous]（onCleared 触发）：lifecycle-viewmodel 在 onCleared
     *   返回之后才取消 viewModelScope，此时必须 runBlocking 同步写完，
     *   否则异步保存在第一个挂起点就被取消 —— 退出进度静默丢失。
     * flushSessionStats 以 sessionCharsRead==0 天然单飞，
     * onDispose + onCleared 双路径不会重复写。
     */
    fun cleanup(synchronous: Boolean = false) {
        rsvpJob?.cancel()
        speedJob?.cancel()
        autoReadJob?.cancel()
        ttsInitJob?.cancel()
        downloadJob?.cancel()
        saveJob?.cancel()
        selectWordJob?.cancel()
        sentenceTranslateJob?.cancel()
        translationJob?.cancel()
        bookmarkToggleJob?.cancel()
        vocabJob?.cancel()
        bookmarksJob?.cancel()
        highlightsJob?.cancel()
        bookJob?.cancel()
        ttsHelper.stop()
        // issue 8.2：close() 此前全项目无人调用，ML Kit Translator
        // native handle 永不释放，模型被系统回收后翻译静默失效
        translationHelper.close()
        // 防抖窗口内未落盘的设置写入：取消计时、同步冲刷，
        // 用户拖完滑杆立刻退页也不会丢设置
        settingsPersistJobs.values.forEach { it.cancel() }
        val pendingSettings = settingsPendingWrites.values.toList()
        settingsPendingWrites.clear()
        val finalSave: suspend () -> Unit = {
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
        if (synchronous) {
            runBlocking(Dispatchers.IO) { finalSave() }
        } else {
            // onDispose 路径：scope 仍存活，异步写不卡主线程；
            // 若随后 VM 销毁触发 onCleared，其同步保存兜底（且取消本异步任务也无碍）
            viewModelScope.launch(Dispatchers.IO) { finalSave() }
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

    /**
     * 单词/句子弹窗里的"播放发音"按钮：对给定文本执行一次朗读。
     * TTS 未初始化则先初始化（用当前书语言），失败静默告警不打断弹窗。
     */
    fun speakOnDemand(text: String) {
        if (text.isBlank()) return
        viewModelScope.launch {
            try {
                if (!_uiState.value.ttsInitialized) {
                    try {
                        ttsHelper.initialize(_uiState.value.book?.language ?: "en")
                    } catch (e: kotlinx.coroutines.CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        android.util.Log.w("ReaderViewModel", "TTS init for on-demand speak failed", e)
                    }
                }
                ttsHelper.speak(text)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                android.util.Log.e("ReaderViewModel", "on-demand speak failed", e)
            }
        }
    }

    fun toggleWordLevelColors() {
        // 持久化到 DataStore（复用 COLLINS_HIGHLIGHT），再次进入阅读详情页时由 init 的
        // settings combine 恢复，不再每次默认退回关闭
        val newValue = !_uiState.value.showWordLevelColors
        _uiState.update { it.copy(showWordLevelColors = newValue) }
        viewModelScope.launch { settingsRepository.setCollinsHighlight(newValue) }
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
        // 与 selectWord 同款串行化：旧实现每次双击各起一个不取消的协程，
        // 慢翻译（首次要下载 ML Kit 模型）的旧结果会后到覆盖新句子的弹窗——
        // 用户看到的是句子 B 配译文 A
        sentenceTranslateJob?.cancel()
        sentenceTranslateJob = viewModelScope.launch {
            // issue 8.8：必须先清旧译文再换标题。两个 StateFlow 分开发射，
            // 若先写 sentence 再写 null，Compose 可能在中间帧读到
            // "新句子 + 旧译文"（标题已换译文还是旧的）。先清译文，
            // 中间帧只会是"旧句子 + 空译文"，不会张冠李戴。
            _sentenceTranslation.value = null
            _selectedSentence.value = sentence
            // issue 8.1：随书语言翻译句子，不再写死 en→zh
            val sourceLang = _uiState.value.book?.language?.takeIf { it.isNotBlank() } ?: "en"
            // 抛异常与返回 null 同样按失败处理：不拦会崩 app，
            // 且弹窗以 == null 判定"加载中"，异常后不写值会永远转圈
            val result = try {
                translationHelper.translateSentence(sentence, sourceLang)
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

    /** 句子翻译失败后的重试入口：对当前选中句子重新翻译。 */
    fun retrySentenceTranslation() {
        _selectedSentence.value?.let { translateSentence(it) }
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
        // 插图标记不是可听写文本，剔除后再取词
        val allWords = wordAnalyzer.extractWords(BookImages.stripImageMarkers(para))
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
        // onCleared 返回后 viewModelScope 立即被取消：这里必须同步写完，
        // 否则收尾保存落在已取消的 scope 上全部丢失
        cleanup(synchronous = true)
    }
}
