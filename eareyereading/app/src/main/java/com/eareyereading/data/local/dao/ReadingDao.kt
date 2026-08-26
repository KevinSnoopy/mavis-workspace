package com.eareyereading.data.local.dao
@file:Suppress("WildcardImport", "FunctionNaming", "UnusedParameter", "MatchingDeclarationName", "EmptyFunctionBlock", "UnusedPrivateProperty")

import androidx.room.*
import com.eareyereading.data.local.entity.ReadingStateEntity
import com.eareyereading.data.local.entity.ReadingStatsEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface ReadingStateDao {
    @Query("SELECT * FROM reading_state WHERE bookId = :bookId")
    suspend fun getState(bookId: Long): ReadingStateEntity?

    @Query("SELECT * FROM reading_state WHERE bookId = :bookId")
    fun getStateFlow(bookId: Long): Flow<ReadingStateEntity?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun saveState(state: ReadingStateEntity)

    @Query("UPDATE reading_state SET currentPosition = :position, lastUpdated = :time WHERE bookId = :bookId")
    suspend fun updatePosition(bookId: Long, position: Int, time: Long = System.currentTimeMillis())

    @Query("UPDATE reading_state SET readingMode = :mode WHERE bookId = :bookId")
    suspend fun updateMode(bookId: Long, mode: String)

    @Query("UPDATE reading_state SET rsvpSpeed = :speed WHERE bookId = :bookId")
    suspend fun updateRsvpSpeed(bookId: Long, speed: Int)
}

@Dao
interface ReadingStatsDao {
    @Query("SELECT * FROM reading_stats WHERE date = :date")
    suspend fun getStats(date: String): ReadingStatsEntity?

    @Query("SELECT * FROM reading_stats ORDER BY date DESC LIMIT :days")
    fun getRecentStats(days: Int): Flow<List<ReadingStatsEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(stats: ReadingStatsEntity)

    @Query("SELECT SUM(totalReadingTimeMinutes) FROM reading_stats")
    suspend fun getTotalReadingMinutes(): Int?

    @Query("SELECT SUM(wordsRead) FROM reading_stats")
    suspend fun getTotalWordsRead(): Int?
}
