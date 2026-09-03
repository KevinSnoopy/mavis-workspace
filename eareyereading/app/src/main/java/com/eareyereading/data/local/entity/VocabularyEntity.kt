package com.eareyereading.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "vocabulary",
    indices = [
        // getWordsByBook / 按书删词场景的过滤列
        Index(value = ["bookId"]),
        // 点词查词（getWordExact）：阅读界面高频热路径，无索引时全表扫描
        Index(value = ["word"]),
        // 生词/已学列表：isLearned 过滤 + dateAdded/lastReviewTime 排序
        Index(value = ["isLearned", "dateAdded"]),
        Index(value = ["isLearned", "lastReviewTime"]),
    ],
)
data class VocabularyEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val word: String,
    val phonetic: String? = null,
    val definition: String? = null,
    val bookId: Long? = null,
    val bookTitle: String? = null,
    val context: String? = null,          // 单词出现的原句
    val translation: String? = null,       // 中文翻译
    val isLearned: Boolean = false,
    val reviewCount: Int = 0,
    val lastReviewTime: Long? = null,
    val dateAdded: Long = System.currentTimeMillis(),
    val note: String? = null,             // 用户笔记
    val example: String? = null,           // 例句
    // Collins 词频等级
    val level: Int = 0,
)
