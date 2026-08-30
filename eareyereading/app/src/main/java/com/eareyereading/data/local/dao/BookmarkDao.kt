package com.eareyereading.data.local.dao

import androidx.room.*
import com.eareyereading.data.local.entity.BookmarkEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface BookmarkDao {
    // (bookId, paragraphIndex) 唯一索引下，竞态插入用 IGNORE 兜底为幂等；
    // 不用 REPLACE（会静默删旧行换 id/创建时间）
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(bookmark: BookmarkEntity): Long

    @Query("SELECT * FROM bookmarks WHERE bookId = :bookId ORDER BY paragraphIndex ASC")
    fun getBookmarksForBook(bookId: Long): Flow<List<BookmarkEntity>>

    @Query("SELECT * FROM bookmarks WHERE bookId = :bookId AND paragraphIndex = :paraIndex LIMIT 1")
    suspend fun getBookmarkAt(bookId: Long, paraIndex: Int): BookmarkEntity?

    @Delete
    suspend fun delete(bookmark: BookmarkEntity)

    @Query("DELETE FROM bookmarks WHERE bookId = :bookId")
    suspend fun deleteAllForBook(bookId: Long)
}
