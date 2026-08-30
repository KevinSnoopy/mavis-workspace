package com.eareyereading.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "word_frequencies",
    indices = [
        // getTopFrequencies 按 bookId 过滤 + count DESC 排序，
        // 复合索引让排序走索引反向扫描
        Index(value = ["bookId", "count"]),
    ],
)
data class WordFrequencyEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val bookId: Long,
    val word: String,
    val count: Int,
    val frequency: Float,
)
