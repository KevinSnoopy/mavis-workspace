package com.eareyereading.data.local.dao

import androidx.room.*
import com.eareyereading.data.local.entity.ReadingStatsEntity
import com.eareyereading.data.local.entity.ReviewRecordEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface ReadingStatsDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertStat(stat: ReadingStatsEntity): Long

    @Query("SELECT * FROM reading_stats WHERE date = :date ORDER BY timestamp DESC")
    fun getStatsByDate(date: String): Flow<List<ReadingStatsEntity>>

    @Query("SELECT * FROM reading_stats WHERE date = :date ORDER BY timestamp DESC LIMIT 1")
    suspend fun getStatsForDate(date: String): ReadingStatsEntity?

    @Query("SELECT * FROM reading_stats ORDER BY timestamp DESC")
    suspend fun getAllStats(): List<ReadingStatsEntity>

    @Query("SELECT * FROM reading_stats ORDER BY timestamp DESC LIMIT :limit")
    fun getRecentStats(limit: Int = 30): Flow<List<ReadingStatsEntity>>

    @Query("SELECT SUM(readingMinutes) FROM reading_stats WHERE date = :date")
    suspend fun getTotalMinutesForDate(date: String): Int?

    @Query("SELECT SUM(charsRead) FROM reading_stats WHERE date = :date")
    suspend fun getTotalCharsForDate(date: String): Int?

    @Query("DELETE FROM reading_stats WHERE bookId = :bookId")
    suspend fun deleteStatsForBook(bookId: Long)
}

@Dao
interface ReviewRecordDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertReview(record: ReviewRecordEntity): Long

    @Update
    suspend fun updateReview(record: ReviewRecordEntity)

    @Query("SELECT * FROM review_records WHERE nextReviewDate <= :now ORDER BY nextReviewDate ASC LIMIT :limit")
    fun getDueReviews(now: Long, limit: Int = 20): Flow<List<ReviewRecordEntity>>

    @Query("SELECT COUNT(*) FROM review_records WHERE nextReviewDate <= :now")
    fun getDueReviewCount(now: Long): Flow<Int>

    @Query("SELECT * FROM review_records WHERE vocabularyId = :vocabId LIMIT 1")
    suspend fun getReviewForVocab(vocabId: Long): ReviewRecordEntity?

    @Query("DELETE FROM review_records WHERE vocabularyId = :vocabId")
    suspend fun deleteReviewForVocab(vocabId: Long)
}
