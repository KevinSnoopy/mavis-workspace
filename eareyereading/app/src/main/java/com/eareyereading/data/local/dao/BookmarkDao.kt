package com.eareyereading.data.local.dao

import androidx.room.*
import com.eareyereading.data.local.entity.BookmarkEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface BookmarkDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
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
