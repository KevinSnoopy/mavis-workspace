@file:Suppress("SwallowedException", "TooGenericExceptionCaught")

package com.eareyereading.ui.screens.library

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.eareyereading.data.local.dao.ReviewRecordDao
import com.eareyereading.data.local.dao.ReadingStatsDao
import com.eareyereading.data.repository.CategoryPrefs
import com.eareyereading.domain.model.ArticleSource
import com.eareyereading.domain.model.ArticleSources
import com.eareyereading.domain.model.Book
import com.eareyereading.domain.model.ClassicBook
import com.eareyereading.domain.model.ClassicBooks
import com.eareyereading.domain.repository.BookRepository
import com.eareyereading.util.EpubParseException
import com.eareyereading.domain.repository.VocabularyRepository
import com.eareyereading.util.ArticleParser
import com.eareyereading.util.ArticleResult
import com.eareyereading.util.RssParser
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

data class ReadingStatsSummary(
    val todayMinutes: Int = 0,
    val todayChars: Int = 0,
    val totalBooks: Int = 0,
    val totalMinutes: Int = 0,
    val streakDays: Int = 0,
)

data class LibraryUiState(
    val books: List<Book> = emptyList(),
    val searchQuery: String = "",
    val isLoading: Boolean = false,
    val loadingMessage: String = "",
    // issue 11.16：Snackbar 同字符串去重。loadingMessage 会连续发射相同文案
    // （如两次"导入成功"），收集端若只以消息值为 key，相同字符串会被折叠成一条、
    // 第二条不显示。每条消息带自增 eventId，收集端以 isLoading+eventId 为 key 展示，
    // 使相同文案也能重复出现。
    val messageEventId: Long = 0L,
    val totalWordCount: Int = 0,
    val learnedWordCount: Int = 0,
    val dueReviewCount: Int = 0,
    val readingStats: ReadingStatsSummary = ReadingStatsSummary(),
    val showArchived: Boolean = false,
    // ── 书架分类：null = 全部（分组展示），非空 = 只看该分类 ──
    val selectedCategory: String? = null,
    val categories: List<String> = emptyList(),
    // v2 分类自定义：用户为分类定义的图标/颜色元数据（name → Meta）；
    // 无元数据的分类 UI 按 name hash 派生默认值
    val categoryMeta: Map<String, CategoryPrefs.Meta> = emptyMap(),
    // 用户自建但还没有书挂上的分类（meta 存在即分类存在）
    val customCategories: List<String> = emptyList(),
    // v2 导入后待完善信息的书（选分类 + 选封面）；null = 无待完善流程
    val pendingRefineBook: Book? = null,
    val showUrlDialog: Boolean = false,
    val urlInput: String = "",
    // 文章广场
    val selectedTab: Int = 0,               // 0=书库, 1=文章
    val articleSources: List<ArticleSource> = ArticleSources.sources,
    val selectedSource: ArticleSource? = null,
    val articles: List<RssParser.RssArticle> = emptyList(),
    val articlesLoading: Boolean = false,
    val articlesError: String? = null,
    val showSourceSheet: Boolean = false,
    // 英文经典名著（Project Gutenberg）：一键下载整本长篇
    val classics: List<ClassicBook> = ClassicBooks.list,
    val downloadingClassicIds: Set<String> = emptySet(),
    val ownedClassicIds: Set<String> = emptySet(),
)

/**
 * 书库门面 ViewModel（SRP 重构后）。
 *
 * 本类仅做委托编排，业务逻辑分散到以下 internal 单一职责协作类：
 * - [LibraryStateController]：UI 状态与消息管理
 * - [LibraryDataLoader]：书库数据组合流与搜索筛选
 * - [ReadingStatsLoader]：阅读统计与待复习数
 * - [BookImporter]：文件/URL/经典书导入
 * - [BookLifecycleManager]：删除/归档
 * - [CategoryManager]：分类 CRUD 与元数据
 * - [BookRefineManager]：导入后完善信息流程
 * - [ArticleSquareManager]：文章广场（RSS/文章抓取与入库）
 *
 * 外部 API（public 方法签名）保持不变，LibraryScreen / MainActivity 无需改动。
 */
@HiltViewModel
class LibraryViewModel @Inject constructor(
    private val bookRepository: BookRepository,
    private val vocabularyRepository: VocabularyRepository,
    private val articleParser: ArticleParser,
    private val rssParser: RssParser,
    private val reviewRecordDao: ReviewRecordDao,
    private val readingStatsDao: ReadingStatsDao,
    private val categoryPrefs: CategoryPrefs,
    @ApplicationContext private val context: Context,
) : ViewModel() {

    private val stateController = LibraryStateController()

    val uiState: StateFlow<LibraryUiState> = stateController.uiState

    private val statsLoader = ReadingStatsLoader(
        stateController = stateController,
        reviewRecordDao = reviewRecordDao,
        readingStatsDao = readingStatsDao,
    )

    private val dataLoader = LibraryDataLoader(
        stateController = stateController,
        bookRepository = bookRepository,
        vocabularyRepository = vocabularyRepository,
        categoryPrefs = categoryPrefs,
        context = context,
    )

    private val refineManager = BookRefineManager(
        stateController = stateController,
        bookRepository = bookRepository,
    )

    private val importer = BookImporter(
        stateController = stateController,
        bookRepository = bookRepository,
        articleParser = articleParser,
        context = context,
        onImportSuccess = { newId -> refineManager.setPendingRefineBookId(newId) },
        onDueTimestampRefresh = { statsLoader.refreshDueTimestamp() },
    )

    private val lifecycleManager = BookLifecycleManager(
        stateController = stateController,
        bookRepository = bookRepository,
    )

    private val categoryManager = CategoryManager(
        stateController = stateController,
        bookRepository = bookRepository,
        categoryPrefs = categoryPrefs,
    )

    private val articleSquareManager = ArticleSquareManager(
        stateController = stateController,
        bookRepository = bookRepository,
        articleParser = articleParser,
        rssParser = rssParser,
        onTabSwitchToLibrary = {
            statsLoader.refreshDueTimestamp()
            statsLoader.loadReadingStats(viewModelScope)
        },
        onDueTimestampRefresh = { statsLoader.refreshDueTimestamp() },
    )

    val addedArticleLinks: StateFlow<Set<String>> = articleSquareManager.addedArticleLinks

    init {
        dataLoader.startCollection(viewModelScope)
        refineManager.startCollection(viewModelScope)
        statsLoader.startDueCountCollection(viewModelScope)
        statsLoader.loadReadingStats(viewModelScope)

        // issue 9.10：系统"打开方式"选 .epub 进入（MainActivity 转发 content:// URI），
        // 复用既有 importBook 流程（同样的 loading/结果 snackbar 消息）
        handlePendingExternalImport()
    }

    /** 待导入的外部 content:// URI 队列（主线程单向入队）。 */
    private fun handlePendingExternalImport() {
        while (true) {
            val uri = pendingExternalImports.poll() ?: break
            importBook(uri)
        }
    }

    // ── 搜索 ──────────────────────────────────────

    fun onSearchQueryChange(query: String) {
        dataLoader.onSearchQueryChange(query)
    }

    // ── URL 导入文章 ─────────────────────────────

    fun showUrlDialog() {
        importer.showUrlDialog()
    }

    fun hideUrlDialog() {
        importer.hideUrlDialog()
    }

    fun onUrlInputChange(url: String) {
        importer.onUrlInputChange(url)
    }

    fun importFromUrl() {
        importer.importFromUrl(viewModelScope)
    }

    // ── 文件导入 ─────────────────────────────────

    fun importBook(uri: Uri) {
        importer.importBook(uri, viewModelScope)
    }

    fun downloadClassic(classic: ClassicBook) {
        importer.downloadClassic(classic, viewModelScope)
    }

    // ── 书籍生命周期 ──────────────────────────────

    fun deleteBook(bookId: Long) {
        lifecycleManager.deleteBook(bookId, viewModelScope)
    }

    fun archiveBook(bookId: Long) {
        lifecycleManager.archiveBook(bookId, viewModelScope)
    }

    /** Snackbar"撤销"入口：滑动归档可一键还原（归档目前无浏览入口，必须可撤销）。 */
    fun unarchiveBook(bookId: Long) {
        lifecycleManager.unarchiveBook(bookId, viewModelScope)
    }

    fun dismissLoadingMessage() {
        stateController.dismissLoadingMessage()
    }

    // ── 书架分类 ─────────────────────────────────

    /** 分类筛选：null = 全部（分组展示所有书）。 */
    fun setCategory(category: String?) {
        categoryManager.setCategory(category)
    }

    /** 修改书籍分类（书卡菜单入口；空串/空白在仓库层归一化为"未分类"）。
     *  分类列表由 books Flow 异步刷新；被清空的选中分类由 UI 层
     *  （effectiveCategory = selectedCategory?.takeIf { it in categories }）兜底回"全部"。 */
    fun updateBookCategory(bookId: Long, category: String) {
        categoryManager.updateBookCategory(bookId, category, viewModelScope)
    }

    // ── v2：分类自定义（元数据持久化到 DataStore）──────────

    /** 保存分类元数据（新建或编辑）：分类名是主键，重名即编辑。
     *  新建的分类若无书挂上，会出现在 customCategories（meta 存在即分类存在）。 */
    fun saveCategoryMeta(name: String, icon: String, color: Long) {
        categoryManager.saveCategoryMeta(name, icon, color, viewModelScope)
    }

    /** 删除分类元数据：书籍的 category 字符串保留（回退派生默认显示），不影响藏书 */
    fun deleteCategoryMeta(name: String) {
        categoryManager.deleteCategoryMeta(name, viewModelScope)
    }

    /** 拖动排序：按新顺序批量写 order（原子生效）。无 meta 的分类 UI 侧回退派生顺序。 */
    fun reorderCategories(names: List<String>) {
        categoryManager.reorderCategories(names, viewModelScope)
    }

    // ── v2：导入后完善信息（分类 + 封面）────────────────────

    /** 完善流程：写分类 + 封面背景后清除挂起状态 */
    fun finishBookRefine(category: String, coverStyle: Int) {
        refineManager.finishBookRefine(category, coverStyle, viewModelScope)
    }

    /** 跳过完善流程：保留导入时的默认分类与封面 */
    fun skipBookRefine() {
        refineManager.skipBookRefine()
    }

    // ── 文章广场 ──────────────────────────────────

    fun setTab(index: Int) {
        articleSquareManager.setTab(index)
    }

    fun selectSource(source: ArticleSource) {
        articleSquareManager.selectSource(source, viewModelScope)
    }

    fun clearSelectedSource() {
        articleSquareManager.clearSelectedSource()
    }

    fun addArticleToLibrary(article: RssParser.RssArticle) {
        articleSquareManager.addArticleToLibrary(article, viewModelScope)
    }

    companion object {
        /** issue 9.10：ACTION_VIEW 转发的 content:// URI。编辑器外部无法直达
         * nav 作用域的 LibraryViewModel 实例（MainActivity 拿不到它），用进程级
         * 队列中转：MainActivity 入队，书库 VM 首次创建时取出走 importBook。
         * 主线程单向入队，无需 CAS。 */
        private val pendingExternalImports =
            java.util.concurrent.ConcurrentLinkedQueue<Uri>()

        /** 供 MainActivity 在收到 VIEW intent 时投递待导入的 EPUB URI。 */
        fun requestImport(uri: Uri) {
            pendingExternalImports.offer(uri)
        }
    }
}
