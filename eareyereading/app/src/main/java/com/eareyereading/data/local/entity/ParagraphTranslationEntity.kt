package com.eareyereading.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * issue 8.5：段落翻译结果本地缓存。
 * 全文翻译（回译 BACK_TRANSLATION / 分栏 SPLIT 模式）首次翻译后落库，
 * 下次打开同一本书、同一语言对时直接读缓存，不再重复下载/调用 ML Kit，
 * 也避免回译模式每次进入都要重跑整本（此前 paragraphTranslations 只在
 * 会话内存，换书/重开即丢）。
 *
 * (bookId, paragraphIndex) 唯一 + REPLACE 覆盖：内容重导入/重切分后重译
 * 会就地覆盖旧译文。sourceText 留档，将来可做"源文变了才重译"比对。
 */
@Entity(
    tableName = "paragraph_translations",
    indices = [
        // 同一段只保留最新一条译文（重译 REPLACE 覆盖）
        Index(value = ["bookId", "paragraphIndex"], unique = true),
        // 整本拉取 + 语言对过滤的热查索引
        Index(value = ["bookId", "langPair"]),
    ],
)
data class ParagraphTranslationEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val bookId: Long,
    val paragraphIndex: Int,
    val sourceText: String,
    val translatedText: String,
    /** 语言对标识，如 "en>zh" / "fr>zh"，区分不同译文 */
    val langPair: String,
    val translatedAt: Long,
)