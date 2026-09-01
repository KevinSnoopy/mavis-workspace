package com.eareyereading.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * issue 12.5：大词典条目落库（按需查询，避免整份载入内存导致 OOM）。
 *
 * 小词典仍整份载内存；大词典（文件 >= 10MB）首次查询时按行写入本表，
 * 之后 lookup 走 (dictId, word) 单条查询，不再把全量词条读进内存。
 *
 * (dictId, word) 唯一 + INSERT REPLACE：同一词典重复导入就地覆盖，幂等。
 * 切换词典时按 dictId 区分，互不干扰；删除词典时按 dictId 清理孤儿行。
 */
@Entity(
    tableName = "dictionary_entries",
    indices = [
        Index(value = ["dictId", "word"], unique = true),
    ],
)
data class DictionaryEntryEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    /** 词典标识（当前使用 active 词典 id）。 */
    val dictId: String,
    /** 归一化后的单词键（lookup 传入的小写键）。 */
    val word: String,
    val definition: String,
)