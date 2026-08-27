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
    val lastUpdated: Long = System.currentTimeMillis(),
)

/**
 * 阅读统计
 */
@Entity(tableName = "reading_stats")
data class ReadingStatsEntity(
    @PrimaryKey
    val date: String,                     // "yyyy-MM-dd"
    val readingMinutes: Int = 0,          // 阅读分钟数（今日）
    val charsRead: Int = 0,               // 今日阅读字符数
    val wordsRead: Int = 0,               // 累计阅读词数
    val booksStarted: Int = 0,
    val booksFinished: Int = 0,
    val timestamp: Long = System.currentTimeMillis(),
)
