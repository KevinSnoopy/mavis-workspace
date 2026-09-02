package com.eareyereading.domain.model

data class Book(
    val id: Long = 0,
    val title: String,
    val author: String,
    val coverPath: String? = null,
    val filePath: String,
    // issue 9.9：外部 content:// URI（SAF/ACTION_VIEW 导入），本地拷贝失效时回退读取
    val sourceUri: String? = null,
    // issue 9.7：EPUB OPF dc:identifier（书籍唯一标识，跨导入去重）
    val identifier: String? = null,
    // issue 9.2：是否截断 + 截断前原文累计字符数（书库卡片提示）
    val isTruncated: Boolean = false,
    val originalCharCount: Int = 0,
    val totalWords: Int = 0,
    val readProgress: Float = 0f,
    val lastReadPosition: Int = 0,
    val lastReadTime: Long = System.currentTimeMillis(),
    val dateAdded: Long = System.currentTimeMillis(),
    val language: String = "en",
    val isArchived: Boolean = false,
    val content: String = "",  // 文章正文（URL导入时存储）
    val addedAt: String = "",   // 添加时间（格式：yyyy-MM-dd HH:mm）
    // 内容（运行时加载）
    val paragraphs: List<String> = emptyList(),
)

data class Vocabulary(
    val id: Long = 0,
    val word: String,
    val phonetic: String? = null,
    val definition: String? = null,
    val bookId: Long? = null,
    val bookTitle: String? = null,
    val context: String? = null,
    val translation: String? = null,
    val isLearned: Boolean = false,
    val reviewCount: Int = 0,
    val lastReviewTime: Long? = null,
    val dateAdded: Long = System.currentTimeMillis(),
    val note: String? = null,
    val example: String? = null,
    // Collins 词频等级：1=CORE, 2=INTERMEDIATE, 3=UPPER_INTERMEDIATE, 4=ADVANCED, 5=RARE, 0=未知
    val level: Int = 0,
)

data class ReadingState(
    val bookId: Long,
    val currentPosition: Int = 0,
    val currentParagraph: Int = 0,
    val totalCharacters: Int = 0,
    val totalParagraphs: Int = 0,
    val readingMode: ReadingMode = ReadingMode.NORMAL,
    val rsvpSpeed: Int = 300,
    val fontSize: Int = 18,
    val theme: ReadingTheme = ReadingTheme.LIGHT,
)

enum class ReadingMode(val value: String, val displayName: String) {
    NORMAL("normal", "普通阅读"),
    RSVP("rsvp", "仿生阅读"),
    SPEED("speed", "快速阅读"),
    CLOZE("cloze", "挖空练习"),
    FUZZY("fuzzy", "模糊听读"),
    DICTATION("dictation", "听写练习"),
    SPLIT("split", "分栏对照"),
    BACK_TRANSLATION("back_translation", "中译英回译"),
    POS_ANALYSIS("pos_analysis", "成分分析"),
}

enum class ReadingTheme(val value: String, val displayName: String) {
    LIGHT("light", "明亮"),
    DARK("dark", "暗黑"),
    SEPIA("sepia", "护眼"),
}

/**
 * 翻页样式（普通阅读模式的呈现方式）：
 * - SCROLL：连续滚动（默认，一页看整段长文随手指滚动）
 * - HORIZONTAL：像纸质书左右翻页，一页一段
 * - VERTICAL：上下翻页，一页一段
 *
 * 留存 DataStore，重进阅读页仍保持上次选择。
 */
enum class PageTurningStyle(val value: String, val displayName: String) {
    SCROLL("scroll", "连续滚动"),
    HORIZONTAL("horizontal", "左右翻页"),
    VERTICAL("vertical", "上下翻页"),
}
