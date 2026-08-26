package com.eareyereading.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "word_frequencies")
data class WordFrequencyEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val bookId: Long,
    val word: String,
    val count: Int,
    val frequency: Float,
)
