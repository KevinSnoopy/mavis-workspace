package com.eareyereading.data.local.entity
@file:Suppress("WildcardImport", "FunctionNaming", "UnusedParameter", "MatchingDeclarationName", "EmptyFunctionBlock", "UnusedPrivateProperty")

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * 阅读统计记录
 */
@Entity(tableName = "reading_stats")
data class ReadingStatsEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val bookId: Long,
    val date: String,          // yyyy-MM-dd 格式
    val readingMinutes: Int,  // 阅读分钟数
    val charsRead: Int,       // 本次阅读字数
    val paragraphsRead: Int,   // 本次阅读段落数
    val timestamp: Long = System.currentTimeMillis(),
)

/**
 * 单词复习记录（用于遗忘曲线）
 */
@Entity(tableName = "review_records")
data class ReviewRecordEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val vocabularyId: Long,    // 关联 VocabularyEntity.id
    val word: String,
    val easeFactor: Float = 2.5f,  // SM-2 遗忘曲线 Ease Factor
    val interval: Int = 1,          // 复习间隔（天）
    val repetitions: Int = 0,        // 连续正确次数
    val nextReviewDate: Long = System.currentTimeMillis(), // 下次复习时间戳
    val lastReviewDate: Long = System.currentTimeMillis(),
    val lastQuality: Int = 0,  // SM-2 评分 0-5
)
