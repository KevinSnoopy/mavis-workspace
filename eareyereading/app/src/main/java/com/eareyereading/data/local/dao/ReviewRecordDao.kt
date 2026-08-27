package com.eareyereading.data.local.dao

import androidx.room.*
import com.eareyereading.data.local.entity.ReviewRecordEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface ReviewRecordDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertReview(review: ReviewRecordEntity): Long

    @Update
    suspend fun updateReview(review: ReviewRecordEntity)

    @Delete
    suspend fun deleteReview(review: ReviewRecordEntity)

    @Query("DELETE FROM review_records WHERE vocabularyId = :vocabularyId")
    suspend fun deleteByVocabularyId(vocabularyId: Long)

    @Query("SELECT * FROM review_records WHERE vocabularyId = :vocabularyId LIMIT 1")
    suspend fun getReviewForVocab(vocabularyId: Long): ReviewRecordEntity?

    @Query("SELECT * FROM review_records WHERE nextReviewDate <= :now ORDER BY nextReviewDate ASC")
    fun getDueReviews(now: Long): Flow<List<ReviewRecordEntity>>

    @Query("SELECT COUNT(*) FROM review_records WHERE nextReviewDate <= :now")
    fun getDueReviewCount(now: Long): Flow<Int>

    @Query("SELECT * FROM review_records ORDER BY nextReviewDate ASC")
    fun getAllReviews(): Flow<List<ReviewRecordEntity>>
}
