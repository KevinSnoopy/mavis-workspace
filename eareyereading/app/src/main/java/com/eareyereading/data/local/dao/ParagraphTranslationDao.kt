package com.eareyereading.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.eareyereading.data.local.entity.ParagraphTranslationEntity

/**
 * issue 8.5：段落翻译缓存 DAO。
 * 缓存命中面（整书 + 语言对）走 getForBook；写入走 REPLACE 覆盖同段旧译文。
 */
@Dao
interface ParagraphTranslationDao {

    /** 拉取整本书某语言对的全部译文（缓存命中主查询）。 */
    @Query("SELECT * FROM paragraph_translations WHERE bookId = :bookId AND langPair = :langPair")
    suspend fun getForBook(bookId: Long, langPair: String): List<ParagraphTranslationEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(translation: ParagraphTranslationEntity)

    /** 删书时清缓存，避免孤儿行常驻数据库。 */
    @Query("DELETE FROM paragraph_translations WHERE bookId = :bookId")
    suspend fun deleteForBook(bookId: Long)
}