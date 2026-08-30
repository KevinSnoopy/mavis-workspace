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

    /** 某本书某天的统计行（每日每书一条的写入约定下，用于累计更新）。 */
    @Query("SELECT * FROM reading_stats WHERE bookId = :bookId AND date = :date LIMIT 1")
    suspend fun getStatForBookAndDate(bookId: Long, date: String): ReadingStatsEntity?

    /** 删除某本书的全部统计（删书级联用）。 */
    @Query("DELETE FROM reading_stats WHERE bookId = :bookId")
    suspend fun deleteForBook(bookId: Long)

    @Query("SELECT * FROM reading_stats WHERE date = :date ORDER BY timestamp DESC")
    fun getStatsByDate(date: String): Flow<List<ReadingStatsEntity>>

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
