package com.eareyereading.di
@file:Suppress("WildcardImport", "FunctionNaming", "UnusedParameter", "MatchingDeclarationName")

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStore
import androidx.room.Room
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.eareyereading.data.local.dao.*
import com.eareyereading.data.local.database.AppDatabase
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): AppDatabase {
        return Room.databaseBuilder(
            context,
            AppDatabase::class.java,
            "eareyereading.db"
        )
            .addMigrations(
                object : Migration(1, 2) {
                    override fun migrate(db: SupportSQLiteDatabase) {
                        db.execSQL("""
                            CREATE TABLE IF NOT EXISTS review_records (
                                id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                                vocabularyId INTEGER NOT NULL,
                                word TEXT NOT NULL,
                                easeFactor REAL NOT NULL DEFAULT 2.5,
                                interval INTEGER NOT NULL DEFAULT 1,
                                repetitions INTEGER NOT NULL DEFAULT 0,
                                nextReviewDate INTEGER NOT NULL,
                                lastReviewDate INTEGER NOT NULL,
                                lastQuality INTEGER NOT NULL DEFAULT 0
                            )
                        """)
                        db.execSQL("""
                            CREATE TABLE IF NOT EXISTS word_frequencies (
                                id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                                bookId INTEGER NOT NULL,
                                word TEXT NOT NULL,
                                count INTEGER NOT NULL,
                                frequency REAL NOT NULL
                            )
                        """)
                    }
                },
                object : Migration(2, 3) {
                    override fun migrate(db: SupportSQLiteDatabase) {
                        db.execSQL("""
                            CREATE TABLE IF NOT EXISTS bookmarks (
                                id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                                bookId INTEGER NOT NULL,
                                paragraphIndex INTEGER NOT NULL,
                                note TEXT NOT NULL DEFAULT '',
                                createdAt INTEGER NOT NULL
                            )
                        """)
                        db.execSQL("""
                            CREATE TABLE IF NOT EXISTS highlights (
                                id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                                bookId INTEGER NOT NULL,
                                paragraphIndex INTEGER NOT NULL,
                                startOffset INTEGER NOT NULL,
                                endOffset INTEGER NOT NULL,
                                text TEXT NOT NULL,
                                color TEXT NOT NULL DEFAULT '#FFE082',
                                note TEXT NOT NULL DEFAULT '',
                                createdAt INTEGER NOT NULL
                            )
                        """)
                    }
                },
                object : Migration(3, 4) {
                    override fun migrate(db: SupportSQLiteDatabase) {
                        db.execSQL("ALTER TABLE vocabulary ADD COLUMN note TEXT")
                        db.execSQL("ALTER TABLE vocabulary ADD COLUMN example TEXT")
                    }
                },
            )
            .build()
    }

    @Provides
    fun provideBookDao(db: AppDatabase): BookDao = db.bookDao()

    @Provides
    fun provideVocabularyDao(db: AppDatabase): VocabularyDao = db.vocabularyDao()

    @Provides
    fun provideWordFrequencyDao(db: AppDatabase): WordFrequencyDao = db.wordFrequencyDao()

    @Provides
    fun provideReadingStateDao(db: AppDatabase): ReadingStateDao = db.readingStateDao()

    @Provides
    fun provideReadingStatsDao(db: AppDatabase): ReadingStatsDao = db.readingStatsDao()

    @Provides
    fun provideReviewRecordDao(db: AppDatabase): ReviewRecordDao = db.reviewRecordDao()

    @Provides
    fun provideBookmarkDao(db: AppDatabase): BookmarkDao = db.bookmarkDao()

    @Provides
    fun provideHighlightDao(db: AppDatabase): HighlightDao = db.highlightDao()

    @Provides
    @Singleton
    fun provideDataStore(@ApplicationContext context: Context): DataStore<Preferences> =
        context.dataStore
}

@Module
@InstallIn(SingletonComponent::class)
object RepositoryModule {
    // 使用 @Binds 的方式会需要更多设置，这里用显式注册
}
