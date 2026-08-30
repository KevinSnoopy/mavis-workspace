package com.eareyereading.data.local.dao

import androidx.room.*
import com.eareyereading.data.local.entity.ReviewRecordEntity
import com.eareyereading.data.local.entity.VocabularyEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface VocabularyDao {
    @Query("SELECT * FROM vocabulary ORDER BY dateAdded DESC")
    fun getAllVocabulary(): Flow<List<VocabularyEntity>>

    @Query("SELECT * FROM vocabulary WHERE isLearned = 0 ORDER BY dateAdded DESC")
    fun getNewWords(): Flow<List<VocabularyEntity>>

    @Query("SELECT * FROM vocabulary WHERE isLearned = 1 ORDER BY lastReviewTime DESC")
    fun getLearnedWords(): Flow<List<VocabularyEntity>>

    @Query("SELECT * FROM vocabulary WHERE LOWER(word) = LOWER(:word) LIMIT 1")
    suspend fun getWord(word: String): VocabularyEntity?

    @Query("SELECT * FROM vocabulary WHERE bookId = :bookId")
    fun getWordsByBook(bookId: Long): Flow<List<VocabularyEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(word: VocabularyEntity): Long

    @Update
    suspend fun update(word: VocabularyEntity)

    @Delete
    suspend fun delete(word: VocabularyEntity)

    @Query("SELECT COUNT(*) FROM vocabulary")
    fun getTotalWordCount(): Flow<Int>

    @Query("SELECT COUNT(*) FROM vocabulary WHERE isLearned = 1")
    fun getLearnedWordCount(): Flow<Int>

    // 原子删除：先删复习记录，再删词汇条目，防止孤儿记录
    @Transaction
    suspend fun deleteVocabularyWithReview(vocabId: Long, word: VocabularyEntity) {
        deleteReviewRecord(vocabId)
        deleteEntity(word)
    }

    @Query("DELETE FROM review_records WHERE vocabularyId = :vocabId")
    suspend fun deleteReviewRecord(vocabId: Long)

    // vocabularyId 唯一索引下用 IGNORE：与 ReviewRecordDao.insertReview 同理，
    // 保护已积累的 SM-2 进度不被竞态插入重建
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertReviewRecord(review: ReviewRecordEntity)

    @Query("SELECT EXISTS(SELECT 1 FROM review_records WHERE vocabularyId = :vocabularyId LIMIT 1)")
    suspend fun hasReviewRecord(vocabularyId: Long): Boolean

    @Delete
    suspend fun deleteEntity(word: VocabularyEntity)
}
