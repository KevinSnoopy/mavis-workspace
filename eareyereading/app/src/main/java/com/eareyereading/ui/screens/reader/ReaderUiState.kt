package com.eareyereading.ui.screens.reader

import androidx.compose.ui.graphics.Color
import com.eareyereading.domain.model.*
import com.eareyereading.util.CollinsClassifier.WordLevel
import com.eareyereading.util.TtsHelper
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
