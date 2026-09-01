package com.eareyereading.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.eareyereading.data.local.entity.DictionaryEntryEntity

/**
 * issue 12.5：大词典按需查询 DAO。
 * lookup 走单条 (dictId, word) 查询；批量导入用 REPLACE 覆盖去重；
 * 切换/删除词典按 dictId 清理。
 */
@Dao
interface DictionaryEntryDao {

    /** 查询某词典中单词的释义，未命中返回 null。 */
    @Query("SELECT definition FROM dictionary_entries WHERE dictId = :dictId AND word = :word")
    suspend fun getDefinition(dictId: String, word: String): String?

    /** 批量写入（(dictId, word) 唯一，REPLACE 覆盖旧行）。 */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(entries: List<DictionaryEntryEntity>)

    /** 删除某词典的全部条目（切换/删除词典时清理）。 */
    @Query("DELETE FROM dictionary_entries WHERE dictId = :dictId")
    suspend fun deleteByDictId(dictId: String)

    /** 某词典已入库条数，用于判断是否已导入、避免重复扫描文件。 */
    @Query("SELECT COUNT(*) FROM dictionary_entries WHERE dictId = :dictId")
    suspend fun countByDictId(dictId: String): Long
}