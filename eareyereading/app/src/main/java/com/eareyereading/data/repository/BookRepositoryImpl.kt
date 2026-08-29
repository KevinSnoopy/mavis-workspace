package com.eareyereading.data.repository

import android.util.Log
import androidx.room.withTransaction
import com.eareyereading.data.local.dao.BookDao
import com.eareyereading.data.local.dao.BookmarkDao
import com.eareyereading.data.local.dao.HighlightDao
import com.eareyereading.data.local.dao.ReadingStateDao
import com.eareyereading.data.local.dao.WordFrequencyDao
import com.eareyereading.data.local.database.AppDatabase
import com.eareyereading.data.local.entity.BookEntity
import com.eareyereading.domain.model.Book
import com.eareyereading.domain.repository.BookRepository
import com.eareyereading.util.EpubParser
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class BookRepositoryImpl @Inject constructor(
    private val bookDao: BookDao,
    private val epubParser: EpubParser,
    private val bookmarkDao: BookmarkDao,
    private val highlightDao: HighlightDao,
    private val readingStateDao: ReadingStateDao,
    private val wordFrequencyDao: WordFrequencyDao,
    private val database: AppDatabase,
) : BookRepository {

    override fun getAllBooks(): Flow<List<Book>> =
        bookDao.getAllBooks().map { entities -> entities.map { it.toDomain() } }

    override fun getArchivedBooks(): Flow<List<Book>> =
        bookDao.getArchivedBooks().map { entities -> entities.map { it.toDomain() } }

    override fun getBookById(id: Long): Flow<Book?> =
        bookDao.getBookByIdFlow(id).map { it?.toDomain() }

    override suspend fun addBook(book: Book): Long {
        val paragraphs = try {
            if (book.content.isNotBlank()) {
                book.content.split("\n\n").filter { it.isNotBlank() }
            } else if (book.filePath.isNotBlank()) {
                epubParser.parseBook(book.filePath)
            } else {
                emptyList()
            }
        } catch (e: Exception) {
            Log.e("BookRepository", "Failed to parse book", e)
            emptyList()
        }

        val totalWords = paragraphs.joinToString(" ").split("\\s+".toRegex())
            .filter { it.isNotBlank() }.size

        val contentToSave = if (book.content.isNotBlank()) book.content
            else paragraphs.joinToString("\n\n")

        val entity = book.toEntity().copy(
            totalWords = totalWords,
            content = contentToSave,
            addedAt = book.addedAt,
        )
        return bookDao.insert(entity)
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
        // 在单个事务中级联删除，保证原子性：要么全部成功，要么全部回滚
        database.withTransaction {
            bookmarkDao.deleteAllForBook(bookId)
            highlightDao.deleteAllForBook(bookId)
            readingStateDao.deleteForBook(bookId)
            wordFrequencyDao.deleteForBook(bookId)
            bookDao.getBookById(bookId)?.let { bookDao.delete(it) }
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
