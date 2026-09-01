package com.eareyereading.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "books",
    indices = [
        // getAllBooks / getArchivedBooks / searchBooks 都按 isArchived 过滤
        // 并按 lastReadTime 排序，首页每次启动都走
        Index(value = ["isArchived", "lastReadTime"]),
    ],
)
data class BookEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val title: String,
    val author: String,
    val coverPath: String? = null,
    val filePath: String,
    // issue 9.9：外部 content:// URI（SAF/ACTION_VIEW 导入），本地拷贝失效时回退读取
    val sourceUri: String? = null,
    val totalWords: Int = 0,
    val readProgress: Float = 0f,       // 0.0 ~ 1.0
    val lastReadPosition: Int = 0,
    val lastReadTime: Long = System.currentTimeMillis(),
    val dateAdded: Long = System.currentTimeMillis(),
    val language: String = "en",
    val isArchived: Boolean = false,
    val content: String = "",   // 文章正文（URL导入）
    val addedAt: String = "",   // 添加时间
)
