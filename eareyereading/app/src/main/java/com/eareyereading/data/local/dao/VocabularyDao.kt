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

    /** 精确匹配：走 word 二级索引 O(log n)，绝大多数点词查询命中于此。 */
    @Query("SELECT * FROM vocabulary WHERE word = :word LIMIT 1")
    suspend fun getWordExact(word: String): VocabularyEntity?

    /** 大小写不敏感兜底：旧查询 LOWER(word)=LOWER(:word) 对任何索引都失效，
     *  点词热路径每次全表扫描。先精确后兜底，索引命中的快路径零扫描。 */
    @Query("SELECT * FROM vocabulary WHERE LOWER(word) = LOWER(:word) LIMIT 1")
    suspend fun getWordIgnoreCase(word: String): VocabularyEntity?

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

    /** 删书级联清理：词汇行本身（复习记录由 ReviewRecordDao 按子查询先删）。 */
    @Query("DELETE FROM vocabulary WHERE bookId = :bookId")
    suspend fun deleteForBook(bookId: Long)

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

    @Delete
    suspend fun deleteEntity(word: VocabularyEntity)
}
