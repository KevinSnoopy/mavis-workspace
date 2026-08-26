package com.eareyereading.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * 阅读高亮记录
 */
@Entity(tableName = "highlights")
data class HighlightEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val bookId: Long,
    val paragraphIndex: Int,
    val startOffset: Int,
    val endOffset: Int,
    val text: String,
    val color: String = "#FFE082", // yellow default
    val note: String = "",
    val createdAt: Long = System.currentTimeMillis(),
)
