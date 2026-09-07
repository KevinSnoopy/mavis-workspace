package com.eareyereading.data.repository

import androidx.room.withTransaction
import com.eareyereading.data.local.dao.BookDao
import com.eareyereading.data.local.dao.BookmarkDao
import com.eareyereading.data.local.dao.HighlightDao
import com.eareyereading.data.local.dao.ReadingStateDao
import com.eareyereading.data.local.dao.ReadingStatsDao
import com.eareyereading.data.local.dao.ReviewRecordDao
import com.eareyereading.data.local.dao.VocabularyDao
import com.eareyereading.data.local.dao.WordFrequencyDao
import com.eareyereading.data.local.database.AppDatabase
import javax.inject.Inject
import javax.inject.Singleton

/** 书籍级联删除：事务内删除所有关联数据 + 清理文件。
 *
 * 从 BookRepositoryImpl 拆出（SRP）：删书涉及 8 张表的级联删除 + 文件清理，
 * 删除顺序有依赖约束（复习记录靠 vocabulary 行定位），是独立的生命周期管理职责。 */
@Singleton
internal class BookDeleter @Inject constructor(
    private val bookDao: BookDao,
    private val bookmarkDao: BookmarkDao,
    private val highlightDao: HighlightDao,
    private val readingStateDao: ReadingStateDao,
    private val readingStatsDao: ReadingStatsDao,
    private val wordFrequencyDao: WordFrequencyDao,
    private val vocabularyDao: VocabularyDao,
    private val reviewRecordDao: ReviewRecordDao,
    private val database: AppDatabase,
    private val fileCleanup: BookFileCleanup,
) {

    /**
     * 删除一本书及其全部关联数据（事务原子性）+ 清理导入文件。
     *
     * 删除顺序：复习记录 → 词汇 → 书签 → 高亮 → 阅读状态 → 统计 → 词频 → 书籍。
     * 复习记录先删：它靠 vocabulary 行的 bookId 子查询定位，
     * 顺序反了会匹配不到，留下指向已删词汇的孤儿记录。
     */
    suspend fun deleteBook(bookId: Long) {
        // 删除前记下文件路径：事务成功后清理导入时拷贝的书籍文件，
        // 防孤儿文件无限累积（仅限应用 books 目录内的文件）
        val filePath = bookDao.getFilePath(bookId).orEmpty()
        // 在单个事务中级联删除，保证原子性：要么全部成功，要么全部回滚
        database.withTransaction {
            // 复习记录先删：它靠 vocabulary 行的 bookId 子查询定位，
            // 顺序反了会匹配不到，留下指向已删词汇的孤儿记录
            reviewRecordDao.deleteByBookId(bookId)
            vocabularyDao.deleteForBook(bookId)
            bookmarkDao.deleteAllForBook(bookId)
            highlightDao.deleteAllForBook(bookId)
            readingStateDao.deleteForBook(bookId)
            readingStatsDao.deleteForBook(bookId)
            wordFrequencyDao.deleteForBook(bookId)
            bookDao.deleteById(bookId)
        }
        fileCleanup.deleteBookFiles(filePath, bookId)
    }
}
