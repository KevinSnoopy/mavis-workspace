package com.eareyereading.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * 保存每个阅读会话的进度
 */
@Entity(tableName = "reading_state")
data class ReadingStateEntity(
    @PrimaryKey
    val bookId: Long,
    val currentPosition: Int = 0,        // 当前字符位置
    val currentParagraph: Int = 0,        // 当前段落索引
    val totalCharacters: Int = 0,
    val totalParagraphs: Int = 0,
    val readingMode: String = "normal",   // normal | rsvp | speed | cloze | fuzzy
    val rsvpSpeed: Int = 300,            // 字/分钟
    val fontSize: Int = 18,
    val theme: String = "light",          // light | dark | sepia
    // 全文翻译开关（每本书独立）：重进书时恢复，避免排版从"带译文"回落到
    // "无译文"造成整书重新分页、阅读位置漂移
    val showTranslation: Boolean = false,
    val lastUpdated: Long = System.currentTimeMillis(),
)
