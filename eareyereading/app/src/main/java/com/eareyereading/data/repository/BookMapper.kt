package com.eareyereading.data.repository

import com.eareyereading.data.local.dao.BookListItem
import com.eareyereading.data.local.entity.BookEntity
import com.eareyereading.domain.model.Book
import com.eareyereading.util.TocCodec

/** 书籍实体 ↔ 领域对象的双向映射。
 *
 * 从 BookRepositoryImpl 拆出（SRP）：原实现把映射逻辑内联为 private 扩展函数，
 * 拆分后各 internal 协作类都需要复用同一套映射，集中到此避免散落重复。 */
internal object BookMapper {

    /** 列表投影 → 领域对象（content 不进列表：需要正文走 getBookById）。 */
    fun toDomain(item: BookListItem) = Book(
        id = item.id, title = item.title, author = item.author, coverPath = item.coverPath,
        filePath = item.filePath, sourceUri = item.sourceUri, identifier = item.identifier,
        isTruncated = item.isTruncated, originalCharCount = item.originalCharCount,
        totalWords = item.totalWords, readProgress = item.readProgress,
        lastReadPosition = item.lastReadPosition, lastReadTime = item.lastReadTime,
        dateAdded = item.dateAdded, language = item.language, isArchived = item.isArchived,
        category = item.category, addedAt = item.addedAt,
    )

    /** 全字段实体 → 领域对象（含 content 正文与 coverStyle）。 */
    fun toDomain(entity: BookEntity) = Book(
        id = entity.id, title = entity.title, author = entity.author, coverPath = entity.coverPath,
        filePath = entity.filePath, sourceUri = entity.sourceUri, identifier = entity.identifier,
        isTruncated = entity.isTruncated, originalCharCount = entity.originalCharCount,
        totalWords = entity.totalWords, readProgress = entity.readProgress,
        lastReadPosition = entity.lastReadPosition, lastReadTime = entity.lastReadTime,
        dateAdded = entity.dateAdded, language = entity.language, isArchived = entity.isArchived,
        category = entity.category, coverStyle = entity.coverStyle, content = entity.content, addedAt = entity.addedAt,
        toc = TocCodec.decode(entity.tocJson),
    )

    /** 领域对象 → 全字段实体（入库/更新用）。 */
    fun toEntity(book: Book) = BookEntity(
        id = book.id, title = book.title, author = book.author, coverPath = book.coverPath,
        filePath = book.filePath, sourceUri = book.sourceUri, identifier = book.identifier,
        isTruncated = book.isTruncated, originalCharCount = book.originalCharCount,
        totalWords = book.totalWords, readProgress = book.readProgress,
        lastReadPosition = book.lastReadPosition, lastReadTime = book.lastReadTime,
        dateAdded = book.dateAdded, language = book.language, isArchived = book.isArchived,
        category = book.category, coverStyle = book.coverStyle, content = book.content, addedAt = book.addedAt,
        tocJson = TocCodec.encode(book.toc),
    )
}
