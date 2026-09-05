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
        // issue 9.7：OPF dc:identifier 唯一——重复导入同一本 EPUB（SAF 拷贝路径每次不同）
        // 时按此去重复用旧 id，而非无限插入
        Index(value = ["identifier"], unique = true),
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
    // issue 9.7：EPUB OPF `<dc:identifier>`（书籍唯一标识，跨导入去重）
    val identifier: String? = null,
    // issue 9.2：是否因 MAX_TOTAL_CHARS 被截断 + 截断前原文累计字符数（书库卡片提示）
    val isTruncated: Boolean = false,
    val originalCharCount: Int = 0,
    val totalWords: Int = 0,
    val readProgress: Float = 0f,       // 0.0 ~ 1.0
    val lastReadPosition: Int = 0,
    val lastReadTime: Long = System.currentTimeMillis(),
    val dateAdded: Long = System.currentTimeMillis(),
    val language: String = "en",
    val isArchived: Boolean = false,
    // 书架分类：导入来源自动预设（文章/经典名著/未分类），用户可在书卡菜单修改
    val category: String = "未分类",
    val content: String = "",   // 文章正文（URL导入）
    val addedAt: String = "",   // 添加时间
)
