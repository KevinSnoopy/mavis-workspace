package com.eareyereading.data.local.entity
@file:Suppress("WildcardImport", "FunctionNaming", "UnusedParameter", "MatchingDeclarationName", "EmptyFunctionBlock", "UnusedPrivateProperty")

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * 书签记录
 */
@Entity(tableName = "bookmarks")
data class BookmarkEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val bookId: Long,
    val paragraphIndex: Int,
    val note: String = "",
    val createdAt: Long = System.currentTimeMillis(),
)
