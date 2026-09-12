package com.eareyereading.ui.screens.reader

import androidx.compose.ui.graphics.Color
import com.eareyereading.domain.model.*
import com.eareyereading.util.CollinsClassifier.WordLevel
import com.eareyereading.util.*

/**
 * 阅读页 UI 状态与事件模型：界面状态（ReaderUiState）、高亮数据、
 * TTS 引导弹窗载荷与动作、DataStore 设置聚合快照。
 */
/**
 * TTS 引擎引导弹窗的事件载荷。
 */
/**
 * TTS 引导提示（自 2026-08-30 系统 TTS 下线后已大幅简化）：
 * 只剩"提醒用户下载 / 启用内置模型"一种场景。
 *
 * ── 重构说明（YAGNI）──
 * 此前这里保留了 10 个系统 TTS 时代的字段（失败原因、引擎列表、回退包名、
 * 幻影默认态、Google Play 可用性、第三方 TTS app 列表、安装引导步骤、
 * 场景枚举），并逐个挂上 @Suppress("UNUSED_PARAMETER")。它们既不参与任何
 * UI 渲染也不再被读取，纯粹是"下线系统 TTS 时顺手留下的兼容层"。
 * 兼容层没有外部消费者，只会误导后续维护者以为还有系统引擎分支要处理。
 */
data class TtsInstallPrompt(
    /** 内置 TTS 模型是否已下载：决定弹窗走"下载"还是"启用" */
    val embeddedModelDownloaded: Boolean = false,
    /** 内置 TTS 模型显示名 */
    val embeddedModelDisplayName: String = "",
    /** 内置 TTS 模型大小（人类可读） */
    val embeddedModelSizeText: String = "",
)

/**
 * 用户对 TTS 引导弹窗的回应动作。
 *
 * ── 重构说明 ──
 * 原 sealed class 含 5 个系统 TTS 时代的子类，其中 4 个（打开引擎设置 /
 * 安装 Google TTS / 打开未知来源设置 / 安装第三方 TTS app）从未被任何 UI
 * 构造；唯一被构造的 `RetryWithEngine("__EMBEDDED__")` 在处理器里落在 no-op
 * 分支——于是引导弹窗上"✅ 启用内置 TTS"按钮点了完全没反应，模型已下载的
 * 用户反而无法启用引擎。现收敛为 3 个语义明确、且都有真实实现的动作。
 */
sealed class TtsInstallAction {
    /** 下载内置 TTS 模型 */
    data object DownloadEmbeddedTts : TtsInstallAction()
    /** 启用已下载的内置 TTS 模型（初始化引擎） */
    data object EnableEmbeddedTts : TtsInstallAction()
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
    // 数据层译文（渐进更新）：全量结果 + 分批落库来源，同时也是分栏（SPLIT）、
    // 回译（BACK_TRANSLATION）、挖空等模式的渲染源——那些模式的语义就是
    // "译文逐段浮现"，需要实时值
    val paragraphTranslations: Map<Int, String> = emptyMap(),
    // 上屏层译文：滚动/翻页两种正文阅读视图的唯一渲染源，同时是翻页分页的
    // 唯一译文输入。它比数据层"慢一拍"——视口内的段落要等这一屏翻完才一起
    // 上屏，视口外的随时放行。详见 commitReaderTranslations 的注释
    val readerTranslations: Map<Int, String> = emptyMap(),
    val isTranslating: Boolean = false,
    // 整本翻译进度（本次需要补翻的段落数）：译文改为"整本译完才上屏"后，
    // 必须让用户看到后台确实在推进，否则静默等待会被当成卡死
    val translationDone: Int = 0,
    val translationTotal: Int = 0,
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

internal data class ReadingSettings(
    val speed: Int,
    val fontSize: Int,
    val theme: ReadingTheme,
    val alpha: Float,
    val strength: Int = 3,
    val collinsHighlight: Boolean = false,
    val serifFont: Boolean = false,
    val pageMode: Boolean = false,
)
