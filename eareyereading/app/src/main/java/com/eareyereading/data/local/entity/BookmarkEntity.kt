package com.eareyereading.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 书签记录
 */
@Entity(
    tableName = "bookmarks",
    indices = [
        // 同一段落只允许一个书签：双击竞态由数据库兜底（迁移 5→6，含存量去重）。
        // 复合索引同时覆盖按 bookId 的列表/级联删除查询
        Index(value = ["bookId", "paragraphIndex"], unique = true),
    ],
)
data class BookmarkEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val bookId: Long,
    val paragraphIndex: Int,
    val note: String = "",
    val createdAt: Long = System.currentTimeMillis(),
)
