package com.eareyereading.ui.screens.library

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.eareyereading.data.local.dao.ReviewRecordDao
import com.eareyereading.data.local.dao.ReadingStatsDao
import com.eareyereading.domain.model.Book
import com.eareyereading.domain.repository.BookRepository
import com.eareyereading.domain.repository.VocabularyRepository
import com.eareyereading.util.ArticleParser
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
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
    val totalWordCount: Int = 0,
    val learnedWordCount: Int = 0,
    val dueReviewCount: Int = 0,
    val readingStats: ReadingStatsSummary = ReadingStatsSummary(),
    val showArchived: Boolean = false,
    val showUrlDialog: Boolean = false,
    val urlInput: String = "",
)

@HiltViewModel
class LibraryViewModel @Inject constructor(
    private val bookRepository: BookRepository,
    private val vocabularyRepository: VocabularyRepository,
    private val articleParser: ArticleParser,
    private val reviewRecordDao: ReviewRecordDao,
    private val readingStatsDao: ReadingStatsDao,
    @ApplicationContext private val context: Context,
) : ViewModel() {

    private val _uiState = MutableStateFlow(LibraryUiState())
    val uiState: StateFlow<LibraryUiState> = _uiState.asStateFlow()

    private val searchQuery = MutableStateFlow("")

    init {
        viewModelScope.launch {
            combine(
                searchQuery,
                bookRepository.getAllBooks(),
                vocabularyRepository.getTotalCount(),
                vocabularyRepository.getLearnedCount(),
            ) { query, books, total, learned ->
                _uiState.value.copy(
                    books = filterBooks(query, books),
                    searchQuery = query,
                    totalWordCount = total,
                    learnedWordCount = learned,
                )
            }.collect { state ->
                _uiState.value = state
            }
        }

        // 待复习数（独立更新，避免 timestamp 变化干扰 combine）
        viewModelScope.launch {
            reviewRecordDao.getDueReviewCount(System.currentTimeMillis()).collect { count ->
                _uiState.update { it.copy(dueReviewCount = count) }
            }
        }

        // 加载阅读统计
        viewModelScope.launch {
            try {
                val todayDate = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
                    .format(Date())
                val todayStats = readingStatsDao.getStatsForDate(todayDate)
                val allStats = readingStatsDao.getAllStats()
                val totalMinutes = allStats.sumOf { it.readingMinutes }
                val streakDays = calculateStreak(allStats)
                _uiState.update {
                    it.copy(
                        readingStats = ReadingStatsSummary(
                            todayMinutes = todayStats?.readingMinutes ?: 0,
                            todayChars = todayStats?.charsRead ?: 0,
                            totalBooks = allStats.distinctBy { s -> s.bookId }.size,
                            totalMinutes = totalMinutes,
                            streakDays = streakDays,
                        )
                    )
                }
            } catch (_: Exception) { /* DB may not have records yet */ }
        }
    }

    private fun calculateStreak(stats: List<com.eareyereading.data.local.entity.ReadingStatsEntity>): Int {
        if (stats.isEmpty()) return 0
        val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
        val todayStr = dateFormat.format(Date())
        val today = dateFormat.parse(todayStr) ?: return 0

        val dates = stats.mapNotNull { stat ->
            try { dateFormat.parse(stat.date) } catch (_: Exception) { null }
        }.distinct().sorted().reversed()

        var streak = 0
        var expected = today
        for (date in dates) {
            val dayDiff = ((expected.time - date.time) / 86_400_000).toInt()
            if (dayDiff <= 1) {
                streak++
                expected = date
            } else break
        }
        return streak
    }

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
            _uiState.update { it.copy(isLoading = true, loadingMessage = "正在抓取文章...", showUrlDialog = false) }
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
                        addedAt = timestamp,
                    )
                    bookRepository.addBook(book)
                    _uiState.update { it.copy(isLoading = false, loadingMessage = "") }
                } else {
                    _uiState.update { it.copy(isLoading = false, loadingMessage = "抓取失败，请检查链接") }
                }
            } catch (e: Exception) {
                _uiState.update { it.copy(isLoading = false, loadingMessage = "抓取失败: ${e.message}") }
            }
        }
    }

    private fun extractDomain(url: String): String {
        return try {
            val u = java.net.URL(url)
            u.host.removePrefix("www.")
        } catch (e: Exception) {
            "Web Article"
        }
    }

    // ── 文件导入 ─────────────────────────────────
    fun importBook(uri: Uri) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, loadingMessage = "正在导入...") }
            try {
                val inputStream = context.contentResolver.openInputStream(uri)
                val fileName = uri.lastPathSegment ?: "book_${System.currentTimeMillis()}.epub"
                val destFile = File(context.filesDir, "books/$fileName")
                destFile.parentFile?.mkdirs()
                inputStream?.use { input ->
                    destFile.outputStream().use { output ->
                        input.copyTo(output)
                    }
                }

                val book = Book(
                    title = fileName.removeSuffix(".epub").removeSuffix(".txt"),
                    author = "Unknown",
                    filePath = destFile.absolutePath,
                )
                bookRepository.addBook(book)
            } catch (e: Exception) {
                e.printStackTrace()
            } finally {
                _uiState.update { it.copy(isLoading = false, loadingMessage = "") }
            }
        }
    }

    fun deleteBook(bookId: Long) {
        viewModelScope.launch {
            bookRepository.deleteBook(bookId)
        }
    }

    fun archiveBook(bookId: Long) {
        viewModelScope.launch {
            bookRepository.setArchived(bookId, true)
        }
    }

    fun dismissLoadingMessage() {
        _uiState.update { it.copy(loadingMessage = "") }
    }
}
