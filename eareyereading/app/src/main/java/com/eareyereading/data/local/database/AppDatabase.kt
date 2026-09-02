package com.eareyereading.data.local.database

import androidx.room.Database
import androidx.room.RoomDatabase
import com.eareyereading.data.local.dao.*
import com.eareyereading.data.local.entity.*

@Database(
    entities = [
        BookEntity::class,
        VocabularyEntity::class,
        WordFrequencyEntity::class,
        ReadingStateEntity::class,
        ReadingStatsEntity::class,
        ReviewRecordEntity::class,
        BookmarkEntity::class,
        HighlightEntity::class,
        // issue 8.5：段落翻译本地缓存（migration 7→8 建表）
        ParagraphTranslationEntity::class,
        // issue 12.5：大词典条目落库（migration 9→10 建表 + 唯一索引）
        DictionaryEntryEntity::class,
    ],
    version = 11,
    // 导出 schema 到 app/schemas/：手写 migration 可以与 Room 期望的表结构
    // 逐版本对照，杜绝"迁移后 schema 校验失败 → 升级用户启动即崩"的漂移
    exportSchema = true
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun bookDao(): BookDao
    abstract fun vocabularyDao(): VocabularyDao
    abstract fun wordFrequencyDao(): WordFrequencyDao
    abstract fun readingStateDao(): ReadingStateDao
    abstract fun readingStatsDao(): ReadingStatsDao
    abstract fun reviewRecordDao(): ReviewRecordDao
    abstract fun bookmarkDao(): BookmarkDao
    abstract fun highlightDao(): HighlightDao
    abstract fun paragraphTranslationDao(): ParagraphTranslationDao
    abstract fun dictionaryEntryDao(): DictionaryEntryDao
}
