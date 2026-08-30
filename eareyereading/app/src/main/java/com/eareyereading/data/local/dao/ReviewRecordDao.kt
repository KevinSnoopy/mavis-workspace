package com.eareyereading.data.local.dao

import androidx.room.*
import com.eareyereading.data.local.entity.ReviewRecordEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface ReviewRecordDao {
    // vocabularyId 唯一索引下用 IGNORE：竞态重复插入变成幂等空操作；
    // 绝不能用 REPLACE（会把已积累的 SM-2 进度整条抹掉重建）
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertReview(review: ReviewRecordEntity): Long

    @Update
    suspend fun updateReview(review: ReviewRecordEntity)

    @Delete
    suspend fun deleteReview(review: ReviewRecordEntity)

    @Query("DELETE FROM review_records WHERE vocabularyId = :vocabularyId")
    suspend fun deleteByVocabularyId(vocabularyId: Long)

    // 别名，方便调用
    suspend fun deleteReviewForVocab(vocabularyId: Long) = deleteByVocabularyId(vocabularyId)

    @Query("SELECT * FROM review_records WHERE vocabularyId = :vocabularyId LIMIT 1")
    suspend fun getReviewForVocab(vocabularyId: Long): ReviewRecordEntity?

    @Query("SELECT * FROM review_records WHERE nextReviewDate <= :now ORDER BY nextReviewDate ASC LIMIT :limit")
    fun getDueReviews(now: Long, limit: Int): Flow<List<ReviewRecordEntity>>

    @Query("SELECT COUNT(*) FROM review_records WHERE nextReviewDate <= :now")
    fun getDueReviewCount(now: Long): Flow<Int>

    @Query("SELECT * FROM review_records ORDER BY nextReviewDate ASC")
    fun getAllReviews(): Flow<List<ReviewRecordEntity>>
}
