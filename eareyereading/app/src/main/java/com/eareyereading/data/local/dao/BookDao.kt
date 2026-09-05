package com.eareyereading.data.local.dao

import androidx.room.*
import com.eareyereading.data.local.entity.BookEntity
import kotlinx.coroutines.flow.Flow

/**
 * 书库/首页/搜索列表的行投影：不含 content（整书正文，单本可达数 MB）。
 * 列表场景加载它纯属内存与 IO 浪费，且阅读时 updateProgress 每 300ms
 * 落库一次会让这些列表 Flow 反复重查——不投影等于反复全量搬运正文。
 */
data class BookListItem(
    val id: Long = 0,
    val title: String = "",
    val author: String = "",
    val coverPath: String? = null,
    val filePath: String = "",
    val sourceUri: String? = null,
    val identifier: String? = null,
    val isTruncated: Boolean = false,
    val originalCharCount: Int = 0,
    val totalWords: Int = 0,
    val readProgress: Float = 0f,
    val lastReadPosition: Int = 0,
    val lastReadTime: Long = 0,
    val dateAdded: Long = 0,
    val language: String = "en",
    val isArchived: Boolean = false,
    val category: String = "未分类",
    val addedAt: String = "",
)

/** 列表/搜索查询共用的列清单（排除 content 全文列）。 */
private const val LIST_COLUMNS =
    "id, title, author, coverPath, filePath, sourceUri, identifier, " +
        "isTruncated, originalCharCount, totalWords, readProgress, " +
        "lastReadPosition, lastReadTime, dateAdded, language, isArchived, category, addedAt"

@Dao
interface BookDao {
    @Query("SELECT $LIST_COLUMNS FROM books WHERE isArchived = 0 ORDER BY lastReadTime DESC")
    fun getAllBooks(): Flow<List<BookListItem>>

    @Query("SELECT $LIST_COLUMNS FROM books WHERE isArchived = 1 ORDER BY lastReadTime DESC")
    fun getArchivedBooks(): Flow<List<BookListItem>>

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

    /** 书架分类：定向 UPDATE 分类名（空串归一化为"未分类"由调用方保证）。 */
    @Query("UPDATE books SET category = :category WHERE id = :bookId")
    suspend fun updateCategory(bookId: Long, category: String)

    /** 导入后补写封面路径：定向 UPDATE，不再回读整行（含全文 content）。 */
    @Query("UPDATE books SET coverPath = :path WHERE id = :bookId")
    suspend fun updateCoverPath(bookId: Long, path: String)

    /** 删书前取文件路径：只读所需列，不搬运 content 全文。 */
    @Query("SELECT filePath FROM books WHERE id = :id")
    suspend fun getFilePath(id: Long): String?

    /** 定向删除：@Delete 需要携带全文字段的实体，纯浪费。 */
    @Query("DELETE FROM books WHERE id = :bookId")
    suspend fun deleteById(bookId: Long)

    // issue 10.6：先用 ESCAPE 转义查询里的 %/_/\\，LIKE 通配符不再被当元字符注入搜索词
    @Query("SELECT $LIST_COLUMNS FROM books WHERE (title LIKE '%' || :query || '%' ESCAPE '\\' OR author LIKE '%' || :query || '%' ESCAPE '\\') AND isArchived = 0 ORDER BY lastReadTime DESC")
    fun searchBooks(query: String): Flow<List<BookListItem>>

    @Query("SELECT COUNT(*) FROM books")
    suspend fun getBookCount(): Int
}
