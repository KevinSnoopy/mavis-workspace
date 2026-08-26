package com.eareyereading.data.local.entity
@file:Suppress("WildcardImport", "FunctionNaming", "UnusedParameter", "MatchingDeclarationName", "EmptyFunctionBlock", "UnusedPrivateProperty")

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * 复习记录（SM-2 间隔重复）
 */
@Entity(tableName = "review_records")
data class ReviewRecordEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val vocabularyId: Long,
    val word: String,
    val easeFactor: Float = 2.5f,
    val interval: Int = 1,
    val repetitions: Int = 0,
    val nextReviewDate: Long,
    val lastReviewDate: Long,
    val lastQuality: Int = 0,
)
