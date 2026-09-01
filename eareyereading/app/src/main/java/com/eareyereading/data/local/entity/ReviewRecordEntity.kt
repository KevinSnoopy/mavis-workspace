package com.eareyereading.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 复习记录（SM-2 间隔重复）
 */
@Entity(
    tableName = "review_records",
    indices = [
        // 每个词只允许一条复习记录：check-then-insert 竞态由数据库兜底，
        // 配合 DAO 的 IGNORE 策略（迁移 5→6 创建，含存量去重）
        Index(value = ["vocabularyId"], unique = true),
        // getDueReviews / getDueReviewCount 都按 nextReviewDate 过滤 + 排序
        Index(value = ["nextReviewDate"]),
    ],
)
data class ReviewRecordEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val vocabularyId: Long,
    val word: String,
    val easeFactor: Float = 2.5f,
    val interval: Int = 1,
    val repetitions: Int = 0,
    val nextReviewDate: Long,
    // issue 11.18：字段改名 lastReviewDate → lastReviewedAt，与 VocabularyEntity.lastReviewTime
    // 的"最后复习"语义对齐；DB 列名保持不变（@ColumnInfo），无需 schema 迁移。
    // 备份导入导出（SettingsScreen）的 JSON 键仍叫 lastReviewDate，保持跨机兼容。
    @ColumnInfo(name = "lastReviewDate")
    val lastReviewedAt: Long,
    val lastQuality: Int = 0,
)
