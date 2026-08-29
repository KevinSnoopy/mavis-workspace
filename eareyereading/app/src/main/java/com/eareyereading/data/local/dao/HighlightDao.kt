package com.eareyereading.data.local.dao

import androidx.room.*
import com.eareyereading.data.local.entity.HighlightEntity
import kotlinx.coroutines.flow.Flow

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
