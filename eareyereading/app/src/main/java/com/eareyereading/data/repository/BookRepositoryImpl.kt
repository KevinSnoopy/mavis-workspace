package com.eareyereading.data.repository

import com.eareyereading.data.local.dao.BookDao
import com.eareyereading.domain.model.Book
import com.eareyereading.domain.repository.BookRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
internal class BookRepositoryImpl @Inject constructor(
    private val bookDao: BookDao,
    private val importer: BookImporter,
    private val deleter: BookDeleter,
) : BookRepository {

    override fun getAllBooks(): Flow<List<Book>> =
        bookDao.getAllBooks()
            .map { entities -> entities.map { BookMapper.toDomain(it) } }
            // 实体→领域对象的全量重建放 Default 调度器：
            // Room Flow 在查询执行器发射，但下游 map 跑在收集者上下文（主线程），
            // 大书库时每次失效重发射都在主线程做整列表拷贝
            .flowOn(Dispatchers.Default)

    override fun getArchivedBooks(): Flow<List<Book>> =
        bookDao.getArchivedBooks()
            .map { entities -> entities.map { BookMapper.toDomain(it) } }
            .flowOn(Dispatchers.Default)

    override fun getBookById(id: Long): Flow<Book?> =
        bookDao.getBookByIdFlow(id).map { it?.let { BookMapper.toDomain(it) } }

    override suspend fun addBook(book: Book): Long = importer.addBook(book)

    override suspend fun updateBook(book: Book) {
        bookDao.update(BookMapper.toEntity(book))
    }

    override suspend fun updateProgress(bookId: Long, progress: Float, position: Int) {
        bookDao.updateProgress(bookId, progress, position)
    }

    override suspend fun setArchived(bookId: Long, archived: Boolean) {
        bookDao.setArchived(bookId, archived)
    }

    override suspend fun updateCategory(bookId: Long, category: String) {
        // 空白分类归一化为"未分类"，保证分组永远有落点
        bookDao.updateCategory(bookId, category.trim().ifBlank { "未分类" }.take(12))
    }

    override suspend fun updateCoverStyle(bookId: Long, style: Int) {
        // v2：预设封面下标（-1..14），越界值防御性归 -1（回退内嵌封面）
        bookDao.updateCoverStyle(bookId, if (style in -1..14) style else -1)
    }

    override suspend fun deleteBook(bookId: Long) = deleter.deleteBook(bookId)

    override fun searchBooks(query: String): Flow<List<Book>> =
        // issue 10.6：把查询里的 LIKE 通配符转义为字面量（与 DAO 的 ESCAPE '\' 配套），
        // 避免用户输入 %/_ 时语义被破坏；同时截断超长搜索词防全表跑来兜底
        bookDao.searchBooks(escapeForLike(query).take(64))
            .map { entities -> entities.map { BookMapper.toDomain(it) } }
            .flowOn(Dispatchers.Default)

    private fun escapeForLike(raw: String): String =
        raw.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")
}
