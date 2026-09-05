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
 * TTS 引导提示（自 2026-08-30 系统 TTS 下线后已大幅简化）：
 * 只剩"提醒用户去下载嵌入式模型"一种场景。系统引擎选择/Google TTS 安装/
 * 第三方 TTS app 安装等场景全部删掉（TtsEngineHelper 已被删除），
 * 对应的载荷字段与动作子类一并清除（YAGNI）。
 */
data class TtsInstallPrompt(
    /** 内置 TTS 模型是否已下载（影响按钮文案：下载 / 启用） */
    val embeddedModelDownloaded: Boolean = false,
    /** 内置 TTS 模型显示名 */
    val embeddedModelDisplayName: String = "",
    /** 内置 TTS 模型大小（人类可读） */
    val embeddedModelSizeText: String = "",
)

/**
 * 用户对 TTS 引导弹窗的回应动作。
 */
sealed class TtsInstallAction {
    /** 下载内置 TTS 模型 */
    data object DownloadEmbeddedTts : TtsInstallAction()
    /** 关闭弹窗 */
    data object Dismiss : TtsInstallAction()
    /**
     * "✅ 启用内置 TTS"（模型已下载场景的按钮）：
     * VM 侧当前为 no-op——启用时机由 loadBook/朗读入口自动初始化覆盖，
     * 语义保留待独立"手动启用"需求接线。
     */
    data class RetryWithEngine(val enginePackage: String) : TtsInstallAction()
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
