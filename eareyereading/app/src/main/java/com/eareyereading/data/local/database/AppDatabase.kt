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
    ],
    version = 4,
    exportSchema = false
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
}
