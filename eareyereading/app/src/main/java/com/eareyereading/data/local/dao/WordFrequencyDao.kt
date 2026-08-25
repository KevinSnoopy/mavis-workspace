package com.eareyereading.data.local.dao

import androidx.room.*
import com.eareyereading.data.local.entity.WordFrequencyEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface WordFrequencyDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(frequencies: List<WordFrequencyEntity>)

    @Query("SELECT * FROM word_frequencies WHERE bookId = :bookId ORDER BY count DESC LIMIT :limit")
    fun getTopFrequencies(bookId: Long, limit: Int = 100): Flow<List<WordFrequencyEntity>>

    @Query("DELETE FROM word_frequencies WHERE bookId = :bookId")
    suspend fun deleteForBook(bookId: Long)
}
