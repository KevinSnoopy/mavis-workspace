package com.eareyereading.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.eareyereading.data.local.entity.ParagraphTranslationEntity

/** 译文投影：只取缓存命中所需的两列。
 *  sourceText 是整本书的原文副本，SELECT * 会把它整份拉进内存白白翻倍。 */
data class ParagraphTranslationEntry(
    val paragraphIndex: Int,
    val translatedText: String,
)

/**
 * issue 8.5：段落翻译缓存 DAO。
 * 缓存命中面（整书 + 语言对）走 getForBook；写入走 REPLACE 覆盖同段旧译文。
 */
@Dao
interface ParagraphTranslationDao {

    /** 拉取整本书某语言对的全部译文（缓存命中主查询，不搬运 sourceText）。 */
    @Query("SELECT paragraphIndex, translatedText FROM paragraph_translations WHERE bookId = :bookId AND langPair = :langPair")
    suspend fun getForBook(bookId: Long, langPair: String): List<ParagraphTranslationEntry>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(translation: ParagraphTranslationEntity)

    /** 批量落缓存：整本几百段旧路径逐条单事务写（每段一次 fsync 语义），改单事务批写。 */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(translations: List<ParagraphTranslationEntity>)

    /** 删书时清缓存，避免孤儿行常驻数据库。 */
    @Query("DELETE FROM paragraph_translations WHERE bookId = :bookId")
    suspend fun deleteForBook(bookId: Long)
}
