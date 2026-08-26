package com.eareyereading.data.local.dao
@file:Suppress("WildcardImport", "FunctionNaming", "UnusedParameter", "MatchingDeclarationName", "EmptyFunctionBlock", "UnusedPrivateProperty")

import androidx.room.*
import com.eareyereading.data.local.entity.BookmarkEntity
import com.eareyereading.data.local.entity.HighlightEntity
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

@Dao
interface HighlightDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(highlight: HighlightEntity): Long

    @Query("SELECT * FROM highlights WHERE bookId = :bookId ORDER BY paragraphIndex ASC, startOffset ASC")
    fun getHighlightsForBook(bookId: Long): Flow<List<HighlightEntity>>

    @Query("SELECT * FROM highlights WHERE bookId = :bookId AND paragraphIndex = :paraIndex ORDER BY startOffset ASC")
    fun getHighlightsForParagraph(bookId: Long, paraIndex: Int): Flow<List<HighlightEntity>>

    @Delete
    suspend fun delete(highlight: HighlightEntity)

    @Query("DELETE FROM highlights WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("DELETE FROM highlights WHERE bookId = :bookId")
    suspend fun deleteAllForBook(bookId: Long)
}
