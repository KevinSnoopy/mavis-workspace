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

    /**
     * 原子累计当日统计：已存在则叠加（段落取高水位），不存在则插入。
     * (bookId, date) 唯一索引 + @Transaction 保证并发/中断下不会
     * 产生重复行或丢累计（替代旧的 delete+insert 两步写）
     */
    @Transaction
    suspend fun accumulateDailyStat(
        bookId: Long,
        date: String,
        addMinutes: Int,
        addChars: Int,
        paragraphsHighWater: Int,
    ) {
        val existing = getStatForBookAndDate(bookId, date)
        if (existing != null) {
            updateAccumulated(
                bookId = bookId,
                date = date,
                minutes = existing.readingMinutes + addMinutes,
                chars = existing.charsRead + addChars,
                paragraphs = maxOf(existing.paragraphsRead, paragraphsHighWater),
            )
        } else {
            insertStat(
                ReadingStatsEntity(
                    bookId = bookId,
                    date = date,
                    readingMinutes = addMinutes,
                    charsRead = addChars,
                    paragraphsRead = paragraphsHighWater,
                )
            )
        }
    }

    @Query(
        "UPDATE reading_stats SET readingMinutes = :minutes, charsRead = :chars, " +
            "paragraphsRead = :paragraphs WHERE bookId = :bookId AND date = :date"
    )
    suspend fun updateAccumulated(bookId: Long, date: String, minutes: Int, chars: Int, paragraphs: Int)

    /** 删除某本书的全部统计（删书级联用）。 */
    @Query("DELETE FROM reading_stats WHERE bookId = :bookId")
    suspend fun deleteForBook(bookId: Long)

    @Query("SELECT * FROM reading_stats WHERE date = :date ORDER BY timestamp DESC")
    fun getStatsByDate(date: String): Flow<List<ReadingStatsEntity>>

    @Query("SELECT * FROM reading_stats ORDER BY timestamp DESC")
    suspend fun getAllStats(): List<ReadingStatsEntity>

    /** 连胜计算只需要去重日期：widget 高频刷新场景免拉全表实体。 */
    @Query("SELECT DISTINCT date FROM reading_stats")
    suspend fun getAllDates(): List<String>

    @Query("SELECT * FROM reading_stats ORDER BY timestamp DESC LIMIT :limit")
    fun getRecentStats(limit: Int): Flow<List<ReadingStatsEntity>>

    @Query("SELECT SUM(readingMinutes) FROM reading_stats WHERE date = :date")
    suspend fun getTotalMinutesForDate(date: String): Int?

    @Query("SELECT SUM(charsRead) FROM reading_stats WHERE date = :date")
    suspend fun getTotalCharsForDate(date: String): Int?

    @Query("DELETE FROM reading_stats WHERE date = :date")
    suspend fun deleteStatsForDate(date: String)
}
