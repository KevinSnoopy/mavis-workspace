package com.eareyereading.data.local.dao

import androidx.room.*
import com.eareyereading.data.local.entity.BookEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface BookDao {
    @Query("SELECT * FROM books WHERE isArchived = 0 ORDER BY lastReadTime DESC")
    fun getAllBooks(): Flow<List<BookEntity>>

    @Query("SELECT * FROM books WHERE isArchived = 1 ORDER BY lastReadTime DESC")
    fun getArchivedBooks(): Flow<List<BookEntity>>

    @Query("SELECT * FROM books WHERE id = :id")
    suspend fun getBookById(id: Long): BookEntity?

    @Query("SELECT * FROM books WHERE id = :id")
    fun getBookByIdFlow(id: Long): Flow<BookEntity?>

    // issue 9.7：同路径重复导入去重——addBook 命中已存在路径时直接复用旧 id，
    // 不再无限插入重复书籍
    @Query("SELECT * FROM books WHERE filePath = :filePath LIMIT 1")
    suspend fun findByFilePath(filePath: String): BookEntity?

    // issue 9.7：按 OPF dc:identifier 去重（SAF 重复导入同一本 EPUB 时，
    // 本地拷贝路径每次不同，filePath 去重挡不住，靠 identifier 唯一索引兜底）
    @Query("SELECT * FROM books WHERE identifier = :identifier LIMIT 1")
    suspend fun findByIdentifier(identifier: String): BookEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(book: BookEntity): Long

    @Update
    suspend fun update(book: BookEntity)

    @Query("UPDATE books SET readProgress = :progress, lastReadPosition = :position, lastReadTime = :time WHERE id = :bookId")
    suspend fun updateProgress(bookId: Long, progress: Float, position: Int, time: Long = System.currentTimeMillis())

    @Query("UPDATE books SET isArchived = :archived WHERE id = :bookId")
    suspend fun setArchived(bookId: Long, archived: Boolean)

    @Delete
    suspend fun delete(book: BookEntity)

    // issue 10.6：先用 ESCAPE 转义查询里的 %/_/\\，LIKE 通配符不再被当元字符注入搜索词
    @Query("SELECT * FROM books WHERE (title LIKE '%' || :query || '%' ESCAPE '\\' OR author LIKE '%' || :query || '%' ESCAPE '\\') AND isArchived = 0 ORDER BY lastReadTime DESC")
    fun searchBooks(query: String): Flow<List<BookEntity>>

    @Query("SELECT COUNT(*) FROM books")
    suspend fun getBookCount(): Int
}
