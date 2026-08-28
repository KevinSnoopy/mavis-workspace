package com.eareyereading.data.local.dao

import androidx.room.*
import com.eareyereading.data.local.entity.ReadingStatsEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface ReadingStatsDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertStat(stat: ReadingStatsEntity): Long

    // 按 bookId + date 合并：已有则替换（用于每日去重）
    @Query("DELETE FROM reading_stats WHERE bookId = :bookId AND date = :date")
    suspend fun deleteForBookAndDate(bookId: Long, date: String)

    @Query("SELECT * FROM reading_stats WHERE date = :date ORDER BY timestamp DESC")
    fun getStatsByDate(date: String): Flow<List<ReadingStatsEntity>>

    @Query("SELECT * FROM reading_stats WHERE date = :date ORDER BY timestamp DESC LIMIT 1")
    suspend fun getStatsForDate(date: String): ReadingStatsEntity?

    @Query("SELECT * FROM reading_stats ORDER BY timestamp DESC")
    suspend fun getAllStats(): List<ReadingStatsEntity>

    @Query("SELECT * FROM reading_stats ORDER BY timestamp DESC LIMIT :limit")
    fun getRecentStats(limit: Int): Flow<List<ReadingStatsEntity>>

    @Query("SELECT SUM(readingMinutes) FROM reading_stats WHERE date = :date")
    suspend fun getTotalMinutesForDate(date: String): Int?

    @Query("SELECT SUM(charsRead) FROM reading_stats WHERE date = :date")
    suspend fun getTotalCharsForDate(date: String): Int?

    @Query("DELETE FROM reading_stats WHERE date = :date")
    suspend fun deleteStatsForDate(date: String)
}
