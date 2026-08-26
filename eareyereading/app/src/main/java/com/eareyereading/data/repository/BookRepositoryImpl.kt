package com.eareyereading.data.repository
@file:Suppress("WildcardImport", "FunctionNaming", "UnusedParameter", "MatchingDeclarationName", "EmptyFunctionBlock", "UnusedPrivateProperty")

import com.eareyereading.data.local.dao.BookDao
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
) : BookRepository {

    override fun getAllBooks(): Flow<List<Book>> =
        bookDao.getAllBooks().map { entities -> entities.map { it.toDomain() } }

    override fun getArchivedBooks(): Flow<List<Book>> =
        bookDao.getArchivedBooks().map { entities -> entities.map { it.toDomain() } }

    override fun getBookById(id: Long): Flow<Book?> =
        bookDao.getBookByIdFlow(id).map { it?.toDomain() }

    override suspend fun addBook(book: Book): Long {
        // URL 导入的文章：有 content，无 filePath
        val paragraphs = if (book.content.isNotBlank()) {
            book.content.split("\n\n").filter { it.isNotBlank() }
        } else if (book.filePath.isNotBlank()) {
            epubParser.parseBook(book.filePath)
        } else {
            emptyList()
        }

        val totalWords = paragraphs.joinToString(" ").split("\\s+".toRegex())
            .filter { it.isNotBlank() }.size

        val entity = book.toEntity().copy(
            totalWords = totalWords,
            addedAt = book.addedAt.ifBlank { "" },
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
        bookDao.getBookById(bookId)?.let { bookDao.delete(it) }
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
