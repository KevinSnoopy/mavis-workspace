package com.eareyereading.data.repository

import android.content.Context
import androidx.room.withTransaction
import com.eareyereading.data.local.dao.BookDao
import com.eareyereading.data.local.dao.BookmarkDao
import com.eareyereading.data.local.dao.HighlightDao
import com.eareyereading.data.local.dao.ReadingStateDao
import com.eareyereading.data.local.dao.ReadingStatsDao
import com.eareyereading.data.local.dao.WordFrequencyDao
import com.eareyereading.data.local.database.AppDatabase
import com.eareyereading.data.local.entity.BookEntity
import com.eareyereading.domain.model.Book
import com.eareyereading.domain.repository.BookRepository
import com.eareyereading.util.EpubParser
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class BookRepositoryImpl @Inject constructor(
    private val bookDao: BookDao,
    private val epubParser: EpubParser,
    private val bookmarkDao: BookmarkDao,
    private val highlightDao: HighlightDao,
    private val readingStateDao: ReadingStateDao,
    private val readingStatsDao: ReadingStatsDao,
    private val wordFrequencyDao: WordFrequencyDao,
    private val database: AppDatabase,
    @ApplicationContext private val context: Context,
) : BookRepository {

    private companion object {
        /** 纯文本导入读取上限，防超大文件撑爆内存（与 EPUB 全局上限同量级）。 */
        const val MAX_PLAIN_TEXT_CHARS = 10_000_000
    }

    override fun getAllBooks(): Flow<List<Book>> =
        bookDao.getAllBooks().map { entities -> entities.map { it.toDomain() } }

    override fun getArchivedBooks(): Flow<List<Book>> =
        bookDao.getArchivedBooks().map { entities -> entities.map { it.toDomain() } }

    override fun getBookById(id: Long): Flow<Book?> =
        bookDao.getBookByIdFlow(id).map { it?.toDomain() }

    override suspend fun addBook(book: Book): Long = withContext(Dispatchers.IO) {
        val paragraphs = if (book.content.isNotBlank()) {
            book.content.split("\n\n").filter { it.isNotBlank() }
        } else if (book.filePath.isNotBlank()) {
            // parseBook 内部吞掉 IO 错误返回空列表；空结果即文件不可解析。
            // 这里必须让调用方感知，否则会静默创建一本 0 词的空书，
            // 且调用方拿到的 rowId 与成功导入无法区分。
            // 解析是重 IO + 正则工作：整体放 IO 调度器，主线程只拿结果，
            // 否则大 EPUB 的 zip 读取会阻塞 UI 线程（ANR）。
            val parsed = if (book.filePath.lowercase(Locale.ROOT).endsWith(".txt")) {
                parsePlainText(File(book.filePath))
            } else {
                epubParser.parseBook(book.filePath)
            }
            if (parsed.isEmpty()) {
                throw java.io.IOException("Failed to parse book file: ${book.filePath}")
            }
            parsed
        } else {
            emptyList()
        }

        val joined = paragraphs.joinToString(" ")
        val tokens = joined.split("\\s+".toRegex()).filter { it.isNotBlank() }
        // 中文等无空白语言按空白切分只得 1 个"词"：此时按 CJK 字符数计词，
        // 避免"少数派"类中文文章整书报 1 词
        val cjkChars = joined.count { it in '\u4E00'..'\u9FFF' }
        val totalWords = if (cjkChars > tokens.size) cjkChars else tokens.size

        val contentToSave = if (book.content.isNotBlank()) book.content
            else paragraphs.joinToString("\n\n")

        val entity = book.toEntity().copy(
            totalWords = totalWords,
            content = contentToSave,
            addedAt = book.addedAt,
        )
        bookDao.insert(entity)
    }

    /**
     * 纯文本文件按空行分段导入（与 EPUB 段落结构对齐）。
     * 读取有字符上限，防超大文本撑爆内存；解析失败返回空列表，
     * 由 addBook 抛 IOException 让调用方感知。
     */
    private fun parsePlainText(file: File): List<String> {
        if (!file.exists()) return emptyList()
        return try {
            val text = file.inputStream().reader().use { reader ->
                val sb = StringBuilder()
                val buf = CharArray(8192)
                while (sb.length < MAX_PLAIN_TEXT_CHARS) {
                    val n = reader.read(buf)
                    if (n < 0) break
                    sb.append(buf, 0, minOf(n, MAX_PLAIN_TEXT_CHARS - sb.length))
                }
                sb.toString()
            }
            text.split(Regex("\\n\\s*\\n"))
                .map { it.trim() }
                .filter { it.isNotBlank() }
        } catch (e: java.io.IOException) {
            android.util.Log.e("BookRepository", "Error reading plain text file", e)
            emptyList()
        }
    }

    override suspend fun updateBook(book: Book) {
        bookDao.update(book.toEntity())
    }

    override suspend fun updateProgress(bookId: Long, progress: Float, position: Int) {
        bookDao.updateProgress(bookId, progress, position)
    }

    override suspend fun setArchived(bookId: Long, archived: Boolean) {
        bookDao.setArchived(bookId, archived)
    }

    override suspend fun deleteBook(bookId: Long) {
        // 删除前记下文件路径：事务成功后清理导入时拷贝的书籍文件，
        // 防孤儿文件无限累积（仅限应用 books 目录内的文件）
        val filePath = bookDao.getBookById(bookId)?.filePath.orEmpty()
        // 在单个事务中级联删除，保证原子性：要么全部成功，要么全部回滚
        database.withTransaction {
            bookmarkDao.deleteAllForBook(bookId)
            highlightDao.deleteAllForBook(bookId)
            readingStateDao.deleteForBook(bookId)
            readingStatsDao.deleteForBook(bookId)
            wordFrequencyDao.deleteForBook(bookId)
            bookDao.getBookById(bookId)?.let { bookDao.delete(it) }
        }
        if (filePath.isNotBlank()) {
            withContext(Dispatchers.IO) {
                try {
                    val file = File(filePath)
                    val booksDir = File(context.filesDir, "books")
                    // 只清理本应用导入目录内的文件，绝不碰用户目录
                    if (file.canonicalPath.startsWith(booksDir.canonicalPath + File.separator)) {
                        file.delete()
                    }
                    Unit
                } catch (e: java.io.IOException) {
                    android.util.Log.w("BookRepository", "Failed to delete book file", e)
                }
            }
        }
    }

    override fun searchBooks(query: String): Flow<List<Book>> =
        bookDao.searchBooks(query).map { entities -> entities.map { it.toDomain() } }

    private fun BookEntity.toDomain() = Book(
        id = id, title = title, author = author, coverPath = coverPath,
        filePath = filePath, totalWords = totalWords, readProgress = readProgress,
        lastReadPosition = lastReadPosition, lastReadTime = lastReadTime,
        dateAdded = dateAdded, language = language, isArchived = isArchived,
        content = content, addedAt = addedAt,
    )

    private fun Book.toEntity() = BookEntity(
        id = id, title = title, author = author, coverPath = coverPath,
        filePath = filePath, totalWords = totalWords, readProgress = readProgress,
        lastReadPosition = lastReadPosition, lastReadTime = lastReadTime,
        dateAdded = dateAdded, language = language, isArchived = isArchived,
        content = content, addedAt = addedAt,
    )
}
