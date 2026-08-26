package com.eareyereading.data.local.entity
@file:Suppress("WildcardImport", "FunctionNaming", "UnusedParameter", "MatchingDeclarationName", "EmptyFunctionBlock", "UnusedPrivateProperty")

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "vocabulary")
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
)

@Entity(tableName = "word_frequency")
data class WordFrequencyEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val bookId: Long,
    val word: String,
    val count: Int,
    val frequency: Float,    // count / totalWords
)
