package com.eareyereading.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 阅读高亮记录
 */
@Entity(
    tableName = "highlights",
    indices = [
        // getHighlightsForBook（ORDER BY paragraphIndex, startOffset）与
        // getHighlightsForParagraph（ORDER BY startOffset）全覆盖
        Index(value = ["bookId", "paragraphIndex", "startOffset"]),
    ],
)
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
