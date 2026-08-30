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

    /** 备份导入判存预加载用：一次性取全部现存词的小写形态，
     * 避免循环内逐词 LOWER 全表扫描的 O(n²) 行访问。 */
    @Query("SELECT LOWER(word) FROM vocabulary")
    suspend fun getAllWordsLowercase(): List<String>

    /** 按主键批量取词：复习会话组队的唯一查询（替代逐词 LOWER 全表扫描）。 */
    @Query("SELECT * FROM vocabulary WHERE id IN (:ids)")
    suspend fun getWordsByIds(ids: List<Long>): List<VocabularyEntity>

    /** 复习作答后同步词汇侧统计：复习次数 +1、最后复习时间刷新。
     * 此前复习流只写 review_records，词汇页统计永远为 0。 */
    @Query("UPDATE vocabulary SET reviewCount = reviewCount + 1, lastReviewTime = :reviewTime WHERE id = :vocabId")
    suspend fun bumpReviewStats(vocabId: Long, reviewTime: Long)

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
