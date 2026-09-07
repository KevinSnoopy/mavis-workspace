@file:Suppress("WildcardImport", "FunctionNaming", "UnusedParameter", "MatchingDeclarationName")

package com.eareyereading.di

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStore
import androidx.room.Room
import com.eareyereading.data.local.dao.*
import com.eareyereading.data.local.database.AppDatabase
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

/**
 * 数据库与 DataStore 的 Hilt DI 装配。
 *
 * 重构说明：Room 迁移链（v1→v14）已拆到 [AppDatabaseMigrations]，
 * 本模块只保留 DI 绑定（SRP / CCP：DI 装配与迁移 SQL 是不同的变更轴）。
 */
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
            .addMigrations(*AppDatabaseMigrations.ALL)
            .build()
    }

    // DAO 提供方统一 @Singleton：DB 与 Repository 均为单例，
    // DAO 不加作用域会让每次注入产生新包装实例，作用域意图不一致
    @Singleton
    @Provides
    fun provideBookDao(db: AppDatabase): BookDao = db.bookDao()

    @Singleton
    @Provides
    fun provideVocabularyDao(db: AppDatabase): VocabularyDao = db.vocabularyDao()

    @Singleton
    @Provides
    fun provideWordFrequencyDao(db: AppDatabase): WordFrequencyDao = db.wordFrequencyDao()

    @Singleton
    @Provides
    fun provideReadingStateDao(db: AppDatabase): ReadingStateDao = db.readingStateDao()

    @Singleton
    @Provides
    fun provideReadingStatsDao(db: AppDatabase): ReadingStatsDao = db.readingStatsDao()

    @Singleton
    @Provides
    fun provideReviewRecordDao(db: AppDatabase): ReviewRecordDao = db.reviewRecordDao()

    @Singleton
    @Provides
    fun provideBookmarkDao(db: AppDatabase): BookmarkDao = db.bookmarkDao()

    @Singleton
    @Provides
    fun provideHighlightDao(db: AppDatabase): HighlightDao = db.highlightDao()

    @Singleton
    @Provides
    fun provideParagraphTranslationDao(db: AppDatabase): ParagraphTranslationDao = db.paragraphTranslationDao()

    @Singleton
    @Provides
    fun provideDictionaryEntryDao(db: AppDatabase): DictionaryEntryDao = db.dictionaryEntryDao()

    @Provides
    @Singleton
    fun provideDataStore(@ApplicationContext context: Context): DataStore<Preferences> =
        context.dataStore
}
