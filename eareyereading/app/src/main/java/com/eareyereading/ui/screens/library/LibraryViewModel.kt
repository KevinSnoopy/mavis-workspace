@file:Suppress("SwallowedException", "TooGenericExceptionCaught")

package com.eareyereading.ui.screens.library

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.eareyereading.data.local.dao.ReviewRecordDao
import com.eareyereading.data.local.dao.ReadingStatsDao
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.*
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

@HiltViewModel
class LibraryViewModel @Inject constructor(
    private val bookRepository: BookRepository,
    private val vocabularyRepository: VocabularyRepository,
    private val articleParser: ArticleParser,
    private val rssParser: RssParser,
    private val reviewRecordDao: ReviewRecordDao,
    private val readingStatsDao: ReadingStatsDao,
    @ApplicationContext private val context: Context,
) : ViewModel() {

    private val _uiState = MutableStateFlow(LibraryUiState())
    val uiState: StateFlow<LibraryUiState> = _uiState.asStateFlow()

    /** issue 11.16：结果消息自增序列号，保证相同文案也能触发 Snackbar 重新展示 */
    private var messageEventId = 0L

    /** 每条结果消息统一入口：message 写入 loadingMessage，并递增 eventId 供收集端去重展示 */
    private fun setResultMessage(message: String) {
        messageEventId += 1
        _uiState.update { it.copy(loadingMessage = message, messageEventId = messageEventId) }
    }

    private val searchQuery = MutableStateFlow("")

    /**
     * 已成功加入书库的文章链接集合。
     * 旧实现在点击瞬间乐观置"已添加"，异步抓取失败后卡片永远卡在已添加态
     * 且无法重试；现在只有导入真正成功才标记
     */
    private val _addedArticleLinks = MutableStateFlow<Set<String>>(emptySet())
    val addedArticleLinks: StateFlow<Set<String>> = _addedArticleLinks.asStateFlow()

    /** 抓取中的文章链接集合：双击时两个点击事件都会先于任一成功通过"已成功"检查，
     * 需在点击瞬间同步占位去重（Main 单线程，读写无竞态） */
    private val inFlightArticleLinks = mutableSetOf<String>()

    /** 并发导入操作计数：任一操作先结束不得清掉其它操作的加载态 */
    private val activeImportOps = java.util.concurrent.atomic.AtomicInteger(0)

    /** 待复习数查询基准时间：不能冻结在 init 时刻，长时间停留本页面时
     * 陆续到期的卡片要能计入（与 ReviewViewModel 同款方案） */
    private val dueCountTimestamp = MutableStateFlow(System.currentTimeMillis())

    /** 文章源抓取任务：切换源时取消旧抓取，防陈旧结果覆盖新选择 */
    private var articlesFetchJob: kotlinx.coroutines.Job? = null

    private fun beginImportOp(message: String) {
        activeImportOps.incrementAndGet()
        _uiState.update { it.copy(isLoading = true, loadingMessage = message) }
    }

    private fun endImportOp() {
        if (activeImportOps.decrementAndGet() <= 0) {
            _uiState.update { it.copy(isLoading = false) }
        }
    }

    private fun refreshDueTimestamp() {
        dueCountTimestamp.value = System.currentTimeMillis()
    }

    init {
        viewModelScope.launch {
            try {
                combine(
                    searchQuery,
                    bookRepository.getAllBooks(),
                    vocabularyRepository.getTotalCount(),
                    vocabularyRepository.getLearnedCount(),
                ) { query, books, total, learned ->
                    val classicDir = File(context.filesDir, "books/classics").absolutePath
                    val ownedClassics = ClassicBooks.list
                        .filter { c -> books.any { it.filePath.startsWith("$classicDir/${c.id}.") } }
                        .map { it.id }
                        .toSet()
                    // 分类列表：按首现顺序去重，"未分类"固定垫底
                    val cats = books.map { it.category.ifBlank { "未分类" } }
                        .distinct()
                        .sortedBy { it == "未分类" }
                    _uiState.value.copy(
                        books = filterBooks(query, books),
                        searchQuery = query,
                        totalWordCount = total,
                        learnedWordCount = learned,
                        ownedClassicIds = ownedClassics,
                        categories = cats,
                    )
                }.collect { state ->
                    _uiState.update { it.copy(
                        books = state.books,
                        searchQuery = state.searchQuery,
                        totalWordCount = state.totalWordCount,
                        learnedWordCount = state.learnedWordCount,
                        ownedClassicIds = state.ownedClassicIds,
                        categories = state.categories,
                    ) }
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                // 数据层异常不再让整个 App 崩溃：书库保持空态可用
                android.util.Log.e("LibraryViewModel", "library combine failed", e)
            }
        }

        // 待复习数（独立更新，避免 timestamp 变化干扰 combine）
        // 基准时间走 dueCountTimestamp：长停留页面时新到期卡片能计入
        viewModelScope.launch {
            try {
                dueCountTimestamp
                    .flatMapLatest { now -> reviewRecordDao.getDueReviewCount(now) }
                    .collect { count ->
                        _uiState.update { it.copy(dueReviewCount = count) }
                    }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                android.util.Log.e("LibraryViewModel", "due count collect failed", e)
            }
        }

        // 加载阅读统计（切回书库 tab 时刷新，见 setTab）
        loadReadingStats()

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

    private fun loadReadingStats() {
        viewModelScope.launch {
            try {
                val todayDate = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
                    .format(Date())
                // reading_stats 每日每书一行：必须用 SUM 聚合，
                // 取单行会在用户一天读多本书时少报
                val todayMinutes = readingStatsDao.getTotalMinutesForDate(todayDate) ?: 0
                val todayChars = readingStatsDao.getTotalCharsForDate(todayDate) ?: 0
                val allStats = readingStatsDao.getAllStats()
                val totalMinutes = allStats.sumOf { it.readingMinutes }
                val streakDays = calculateStreak(allStats)
                _uiState.update {
                    it.copy(
                        readingStats = ReadingStatsSummary(
                            todayMinutes = todayMinutes,
                            todayChars = todayChars,
                            totalBooks = allStats.distinctBy { s -> s.bookId }.size,
                            totalMinutes = totalMinutes,
                            streakDays = streakDays,
                        )
                    )
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: java.lang.RuntimeException) {
                // DB may not have records yet - use default stats
                android.util.Log.d("LibraryViewModel", "Stats not available yet", e)
            }
        }
    }

    /** Streak calc converged into ReadingStreak: single-source-of-truth for the
     * calendar-day rule shared by Home/Library/Settings. */
    private fun calculateStreak(stats: List<com.eareyereading.data.local.entity.ReadingStatsEntity>): Int =
        com.eareyereading.util.ReadingStreak.calculate(stats)

    private fun filterBooks(query: String, books: List<Book>): List<Book> {
        return if (query.isBlank()) books
        else books.filter {
            it.title.contains(query, ignoreCase = true) ||
            it.author.contains(query, ignoreCase = true)
        }
    }

    fun onSearchQueryChange(query: String) {
        searchQuery.value = query
    }

    // ── URL 导入文章 ─────────────────────────────
    fun showUrlDialog() {
        _uiState.update { it.copy(showUrlDialog = true, urlInput = "") }
    }

    fun hideUrlDialog() {
        _uiState.update { it.copy(showUrlDialog = false, urlInput = "") }
    }

    fun onUrlInputChange(url: String) {
        _uiState.update { it.copy(urlInput = url) }
    }

    fun importFromUrl() {
        val url = _uiState.value.urlInput.trim()
        if (url.isBlank()) return

        // 自动补全 https://
        val fullUrl = if (!url.startsWith("http://") && !url.startsWith("https://")) {
            "https://$url"
        } else url

        viewModelScope.launch {
            beginImportOp("正在抓取文章...")
            _uiState.update { it.copy(showUrlDialog = false) }
            try {
                val result = articleParser.parseFromUrl(fullUrl)
                if (result != null && result.paragraphs.isNotEmpty()) {
                    // 保存为本地"书"
                    val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
                    val timestamp = dateFormat.format(Date())

                    val book = Book(
                        title = result.title.ifBlank { extractDomain(fullUrl) },
                        author = extractDomain(fullUrl),
                        filePath = "",
                        content = result.paragraphs.joinToString("\n\n"),
                        category = "文章",
                        addedAt = timestamp,
                    )
                    bookRepository.addBook(book)
                    refreshDueTimestamp()
                    setResultMessage("文章已加入书库")
                } else {
                    setResultMessage("抓取失败，请检查链接")
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: java.io.IOException) {
                setResultMessage("抓取失败: 网络错误")
            } catch (e: java.lang.RuntimeException) {
                setResultMessage("抓取失败: ${e.message}")
            } finally {
                endImportOp()
            }
        }
    }

    private fun extractDomain(url: String): String {
        return try {
            val u = java.net.URL(url)
            u.host.removePrefix("www.")
        } catch (e: java.net.MalformedURLException) {
            "Web Article"
        }
    }

    // ── 文件导入 ─────────────────────────────────
    /** 导入文件体积上限：SAF 可递任意大文件（如数 GB 视频），
     * 不设限会写满内部存储 */
    private val maxImportBytes = 200L * 1024 * 1024

    fun importBook(uri: Uri) {
        viewModelScope.launch {
            beginImportOp("正在导入...")
            var destFile: File? = null
            try {
                // issue 9.9：尝试持久化原始 content:// 读权限，使本地拷贝失效后仍能
                // 凭 contentResolver 重新打开；Provider 拒绝只告警不阻断拷贝
                tryTakePersistableReadPermission(uri)
                val inputStream = context.contentResolver.openInputStream(uri)
                    ?: throw java.io.IOException("无法读取文件内容")
                // SAF content:// URI 的 lastPathSegment 常常只是文档 id（"12"）或通用名（"book.epub"），
                // 直接复用会覆盖上次导入的同名文件（静默数据丢失）。加时间戳前缀并去掉路径分隔符。
                val rawName = uri.lastPathSegment?.substringAfterLast('/') ?: "book.epub"
                val fileName = "${System.currentTimeMillis()}_${rawName.replace('/', '_')}"
                val dest = File(context.filesDir, "books/$fileName")
                destFile = dest
                dest.parentFile?.mkdirs()
                // 整文件拷贝放 IO 线程，避免主线程拷贝大文件 ANR；
                // 带上限并计数，超限即中止
                withContext(Dispatchers.IO) {
                    inputStream.use { input ->
                        dest.outputStream().use { output ->
                            // 256KB 缓冲：SAF 拷贝几十 MB 的 EPUB 时，
                            // 8KB 缓冲 = 数万次 read/write 系统调用
                            val buffer = ByteArray(262144)
                            var copied = 0L
                            while (true) {
                                val n = input.read(buffer)
                                if (n < 0) break
                                copied += n
                                if (copied > maxImportBytes) {
                                    throw java.io.IOException("File exceeds import size limit")
                                }
                                output.write(buffer, 0, n)
                            }
                        }
                    }
                }

                val book = Book(
                    title = rawName.removeSuffix(".epub").removeSuffix(".txt"),
                    author = "Unknown",
                    filePath = dest.absolutePath,
                    // issue 9.9：持久化原始 content:// URI，本地拷贝失效时回退读取
                    sourceUri = uri.toString(),
                )
                bookRepository.addBook(book)
                destFile = null // 成功入库后文件由 deleteBook 生命周期接管
                refreshDueTimestamp()
                setResultMessage("导入成功")
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: EpubParseException) {
                android.util.Log.e("LibraryViewModel", "Error importing book file", e)
                // EPUB 解析失败此前一律"文件读取错误"（issue 9.3）：
                // 按异常类型区分损坏/加密/空文件/缺 OPF/无可读章节
                setResultMessage("导入失败: ${e.message}")
            } catch (e: java.io.IOException) {
                android.util.Log.e("LibraryViewModel", "Error importing book file", e)
                setResultMessage("导入失败: 文件读取错误")
            } catch (e: java.lang.SecurityException) {
                android.util.Log.e("LibraryViewModel", "Security error importing book", e)
                setResultMessage("导入失败: 权限错误")
            } catch (e: Exception) {
                android.util.Log.e("LibraryViewModel", "Unexpected error importing book", e)
                setResultMessage("导入失败: ${e.javaClass.simpleName}")
            } finally {
                // 失败/取消时删除已拷贝的孤儿文件，防存储泄漏
                destFile?.delete()
                endImportOp()
            }
        }
    }

    /**
     * 经典书并发下载上限：下载完每本都要走 addBook 的整本解析 +
     * 词频统计（CPU 密集），不限并发时批量点下载会同时开 N 条
     * HttpURLConnection + N 个解析任务，GC/IO 风暴拖慢全进程。
     * 2 路（下载一队、解析一队）实测吞吐与流畅度的平衡点
     */
    private val classicDownloadLimiter = Semaphore(2)

    /**
     * 一键下载英文经典名著（Project Gutenberg 纯文本）并加入书库。
     * 确定性路径名（books/classics/{id}.txt）让重复下载走 addBook 的 filePath 去重。
     */
    fun downloadClassic(classic: ClassicBook) {
        if (classic.id in _uiState.value.downloadingClassicIds) return
        if (classic.id in _uiState.value.ownedClassicIds) return
        viewModelScope.launch {
            var destFile: File? = null
            try {
                _uiState.update { it.copy(downloadingClassicIds = it.downloadingClassicIds + classic.id) }
                beginImportOp("正在下载《${classic.title}》...")
                classicDownloadLimiter.withPermit {
                    // mkdirs/exists/下载全部放 IO 调度器（旧实现 mkdirs/exists 跑在
                    // Main）；失败以异常抛出由外层 catch 统一处理
                    val dest = withContext(Dispatchers.IO) {
                        val dir = File(context.filesDir, "books/classics").apply { mkdirs() }
                        val d = File(dir, "${classic.id}.txt")
                        if (!d.exists()) {
                            val conn = (java.net.URL(classic.url).openConnection() as java.net.HttpURLConnection).apply {
                                connectTimeout = 20_000
                                readTimeout = 120_000
                                setRequestProperty("User-Agent", "Mozilla/5.0")
                                setRequestProperty("Accept", "text/plain,*/*")
                                instanceFollowRedirects = true
                            }
                            try {
                                if (conn.responseCode != java.net.HttpURLConnection.HTTP_OK) {
                                    throw java.io.IOException("HTTP ${conn.responseCode}")
                                }
                                // 30MB 上限：整本长篇绰绰有余，同时防异常来源撑爆磁盘
                                val max = 30L * 1024 * 1024
                                conn.inputStream.use { input ->
                                    d.outputStream().use { output ->
                                        val buffer = ByteArray(262144)
                                        var done = 0L
                                        while (true) {
                                            val n = input.read(buffer)
                                            if (n < 0) break
                                            done += n
                                            if (done > max) throw java.io.IOException("File too large")
                                            output.write(buffer, 0, n)
                                        }
                                    }
                                }
                            } finally {
                                conn.disconnect()
                            }
                        }
                        d
                    }
                    destFile = dest
                    bookRepository.addBook(
                        Book(
                            title = classic.title,
                            author = classic.author,
                            filePath = dest.absolutePath,
                            language = "en",
                            category = "经典名著",
                        ),
                    )
                }
                destFile = null // 成功入库后文件交 deleteBook 生命周期接管
                refreshDueTimestamp()
                setResultMessage("已加入书库：${classic.title}")
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: java.io.IOException) {
                android.util.Log.e("LibraryViewModel", "download classic ${classic.id} failed", e)
                setResultMessage("下载失败：${e.message ?: "网络错误"}")
            } catch (e: Exception) {
                android.util.Log.e("LibraryViewModel", "download classic ${classic.id} failed", e)
                setResultMessage("下载失败：${e.javaClass.simpleName}")
            } finally {
                destFile?.delete()
                _uiState.update { it.copy(downloadingClassicIds = it.downloadingClassicIds - classic.id) }
                endImportOp()
            }
        }
    }

    /** issue 9.9：为 SAF/ACTION_VIEW 转发的 content:// URI 申请可持久化读权限。
     * 仅在带 FLAG_GRANT_PERSISTABLE_URI_PERMISSION 且 Provider 支持时才生效；
     * 权限不足会抛 SecurityException，此处只告警不阻断导入（本地拷贝仍可读）。 */
    private fun tryTakePersistableReadPermission(uri: Uri) {
        try {
            context.contentResolver.takePersistableUriPermission(
                uri,
                android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION,
            )
        } catch (e: SecurityException) {
            android.util.Log.w("LibraryViewModel", "takePersistableUriPermission denied for $uri", e)
        }
    }

    fun deleteBook(bookId: Long) {
        viewModelScope.launch {
            // issue 11.14：删除是级联清理（书/进度/生词/复习记录/文件），
            // 任一环抛异常都会把未捕获异常直接甩进 viewModelScope 崩 app
            try {
                bookRepository.deleteBook(bookId)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                android.util.Log.e("LibraryVM", "deleteBook failed", e)
                setResultMessage("删除失败：${e.message ?: "未知错误"}")
            }
        }
    }

    fun archiveBook(bookId: Long) {
        viewModelScope.launch {
            bookRepository.setArchived(bookId, true)
        }
    }

    /** Snackbar"撤销"入口：滑动归档可一键还原（归档目前无浏览入口，必须可撤销）。 */
    fun unarchiveBook(bookId: Long) {
        viewModelScope.launch {
            try {
                bookRepository.setArchived(bookId, false)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                android.util.Log.e("LibraryVM", "unarchiveBook failed", e)
            }
        }
    }

    fun dismissLoadingMessage() {
        _uiState.update { it.copy(loadingMessage = "") }
    }

    // ── 书架分类 ─────────────────────────────────
    /** 分类筛选：null = 全部（分组展示所有书）。 */
    fun setCategory(category: String?) {
        _uiState.update { it.copy(selectedCategory = category) }
    }

    /** 修改书籍分类（书卡菜单入口；空串/空白在仓库层归一化为"未分类"）。
     *  分类列表由 books Flow 异步刷新；被清空的选中分类由 UI 层
     *  （effectiveCategory = selectedCategory?.takeIf { it in categories }）兜底回"全部"。 */
    fun updateBookCategory(bookId: Long, category: String) {
        viewModelScope.launch {
            try {
                bookRepository.updateCategory(bookId, category)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                android.util.Log.e("LibraryVM", "updateCategory failed", e)
                setResultMessage("分类修改失败")
            }
        }
    }

    // ── 文章广场 ──────────────────────────────────
    fun setTab(index: Int) {
        _uiState.update { it.copy(selectedTab = index) }
        if (index == 0) {
            // 回到书库：刷新到期数基准时间与今日统计，避免长停留后数据陈旧
            refreshDueTimestamp()
            loadReadingStats()
        }
    }

    fun selectSource(source: ArticleSource) {
        _uiState.update { it.copy(
            selectedSource = source,
            articles = emptyList(),
            articlesLoading = true,
            articlesError = null,
        ) }
        // 取消上一个源的抓取：快速切换源时旧结果不得覆盖新选择
        articlesFetchJob?.cancel()
        articlesFetchJob = viewModelScope.launch {
            try {
                val feed = if (source.isRss) {
                    withContext(Dispatchers.IO) { rssParser.parse(source.url) }
                } else {
                    // 非 RSS 源：抓取首页，尝试从中提取文章链接
                    fetchArticleLinks(source)
                }
                // 结果落地前再核对当前选择：极端时序下仍可能有陈旧写入
                if (_uiState.value.selectedSource?.id != source.id) return@launch
                if (feed != null && feed.items.isNotEmpty()) {
                    // 去重：同一篇 feed 中可能出现重复的 (link, title) 组合，
                    // LazyColumn 的 key 必须唯一，否则触发 IllegalArgumentException 崩溃。
                    val uniqueArticles = feed.items.distinctBy { it.link to it.title }
                    _uiState.update { it.copy(
                        articles = uniqueArticles,
                        articlesLoading = false,
                    ) }
                } else if (feed == null) {
                    _uiState.update { it.copy(
                        articlesLoading = false,
                        articlesError = "加载失败：无法读取该源（网络异常或 RSS 格式异常）",
                    ) }
                } else {
                    _uiState.update { it.copy(
                        articlesLoading = false,
                        articlesError = "该源暂无文章",
                    ) }
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: java.io.IOException) {
                _uiState.update { it.copy(
                    articlesLoading = false,
                    articlesError = "加载失败: 网络错误",
                ) }
            } catch (e: org.xmlpull.v1.XmlPullParserException) {
                _uiState.update { it.copy(
                    articlesLoading = false,
                    articlesError = "加载失败: RSS 格式错误",
                ) }
            } catch (e: java.lang.RuntimeException) {
                val msg = e.message ?: e.javaClass.simpleName
                _uiState.update { it.copy(
                    articlesLoading = false,
                    articlesError = "加载失败: $msg",
                ) }
            } catch (e: Exception) {
                _uiState.update { it.copy(
                    articlesLoading = false,
                    articlesError = "加载失败: ${e.javaClass.simpleName}",
                ) }
            }
        }
    }

    private suspend fun fetchArticleLinks(source: ArticleSource): RssParser.RssFeed? {
        // 非 RSS 源：先尝试提取真实文章链接
        val linkResult = articleParser.parseArticleLinks(source.url)
        if (linkResult != null && linkResult.links.isNotEmpty()) {
            val articles = linkResult.links.map { link ->
                RssParser.RssArticle(
                    title = link.title,
                    link = resolveUrl(source.url, link.url),
                    description = null,
                    pubDate = null,
                    pubTimestamp = System.currentTimeMillis(),
                )
            }
            return RssParser.RssFeed(
                title = linkResult.title,
                description = null,
                link = source.url,
                items = articles,
            )
        }
        // 回退：从首页提取段落内容
        val result = articleParser.parseFromUrl(source.url) ?: return null
        if (result.paragraphs.isEmpty()) return null
        val articles = result.paragraphs.take(10).map { p ->
            RssParser.RssArticle(
                title = p.take(80),
                link = source.url,
                description = p.take(200),
                pubDate = null,
                pubTimestamp = System.currentTimeMillis(),
            )
        }
        return RssParser.RssFeed(
            title = result.title,
            description = null,
            link = source.url,
            items = articles,
        )
    }

    /** 相对链接解析统一交给 URI.resolve：正确处理 ./、../、协议相对
     * （//host/...）与带查询串的 base，手写拼接在这些形态上会产出坏链 */
    private fun resolveUrl(base: String, relative: String): String {
        if (relative.startsWith("http://") || relative.startsWith("https://")) return relative
        return try {
            java.net.URI(base).resolve(relative).toString()
        } catch (_: Exception) {
            relative
        }
    }

    fun clearSelectedSource() {
        _uiState.update { it.copy(
            selectedSource = null,
            articles = emptyList(),
            articlesError = null,
        ) }
    }

    fun addArticleToLibrary(article: RssParser.RssArticle) {
        if (article.link.isBlank()) {
            setResultMessage("文章链接无效，无法导入")
            return
        }
        // 已成功的导入不重复抓取入库
        if (article.link in _addedArticleLinks.value) return
        // 双击防护：成功集合要等抓取结束才写入，两次快速点击都会通过上面的检查。
        // 点击瞬间同步占位（Main 单线程无竞态），成功或失败后释放
        if (!inFlightArticleLinks.add(article.link)) return
        viewModelScope.launch {
            beginImportOp("正在导入文章...")
            try {
                // issue 7.3：优先用 feed 自带的完整正文（content:encoded），
                // 仅在为空时才回退去抓 link——NPR 等源的 <link> 指向 SPA 渲染页，
                // HttpURLConnection 拿到的是空壳 HTML，抓取路径基本必失败
                // expandInlineMarkers：正文里的 [[IMG:url]] 插图标记统一规整为
                // 独立段落（feed 正文图片由 RssParser 转成标记，见 BookImages）
                val feedParagraphs = article.content
                    ?.split(Regex("\n\\s*\n"))
                    ?.map { it.trim() }
                    ?.filter { it.isNotEmpty() }
                    ?.let { com.eareyereading.util.BookImages.expandInlineMarkers(it) }
                    .orEmpty()
                val result = if (feedParagraphs.isNotEmpty()) {
                    ArticleResult(
                        title = article.title.ifBlank { "Web Article" },
                        paragraphs = feedParagraphs,
                    )
                } else {
                    articleParser.parseFromUrl(article.link)
                }
                if (result != null && result.paragraphs.isNotEmpty()) {
                    val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
                    val book = Book(
                        title = article.title.ifBlank { "Web Article" },
                        author = extractDomain(article.link),
                        filePath = "",
                        content = result.paragraphs.joinToString("\n\n"),
                        category = "文章",
                        addedAt = dateFormat.format(Date()),
                    )
                    bookRepository.addBook(book)
                    // 成功后才标记"已添加"：失败时卡片保持可重试状态
                    _addedArticleLinks.update { it + article.link }
                    refreshDueTimestamp()
                    setResultMessage("「${article.title.take(20)}...」已加入书库！")
                    _uiState.update { it.copy(selectedTab = 0) }
                } else {
                    setResultMessage("该源文章内容为空，无法导入")
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: java.io.IOException) {
                setResultMessage("导入失败: 网络错误")
            } catch (e: java.lang.RuntimeException) {
                val msg = e.message ?: e.javaClass.simpleName
                setResultMessage("导入失败: $msg")
            } finally {
                inFlightArticleLinks.remove(article.link)
                endImportOp()
            }
        }
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
