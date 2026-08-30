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
        // 每日每书一条的硬约束（迁移 6→7）：累计落库改事务化后，
        // 数据库层兜底保证不会再出现同书同日多行
        Index(value = ["bookId", "date"], unique = true),
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
