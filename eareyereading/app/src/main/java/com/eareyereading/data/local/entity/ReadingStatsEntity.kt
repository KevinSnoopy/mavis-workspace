package com.eareyereading.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 阅读统计记录
 */
@Entity(
    tableName = "reading_stats",
    indices = [
        // 首页/书库按日期做 SUM 聚合与列表查询（每日加载的热路径）
        Index(value = ["date"]),
        // getStatForBookAndDate / deleteForBookAndDate / deleteForBook 的 bookId 前缀
        Index(value = ["bookId", "date"]),
    ],
)
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
