@file:Suppress("WildcardImport", "FunctionNaming", "UnusedParameter", "MatchingDeclarationName")

package com.eareyereading.di

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
                object : Migration(4, 5) {
                    override fun migrate(db: SupportSQLiteDatabase) {
                        db.execSQL("ALTER TABLE vocabulary ADD COLUMN level INTEGER NOT NULL DEFAULT 0")
                    }
                },
                object : Migration(5, 6) {
                    override fun migrate(db: SupportSQLiteDatabase) {
                        // 存量去重：历史竞态可能已产生同词多条复习记录。
                        // 必须在建唯一索引前执行，否则 CREATE UNIQUE INDEX 抛
                        // SQLiteConstraintException，数据库打不开。
                        // 保留每词"最近复习过"的一条（按最后复习时间/重复次数/ id 决胜），
                        // 相关子查询写法兼容 minSdk 26 的 SQLite（不用窗口函数）
                        db.execSQL(
                            """
                            DELETE FROM review_records
                            WHERE id NOT IN (
                                SELECT r.id FROM review_records r
                                WHERE r.id = (
                                    SELECT r2.id FROM review_records r2
                                    WHERE r2.vocabularyId = r.vocabularyId
                                    ORDER BY r2.lastReviewDate DESC, r2.repetitions DESC, r2.id DESC
                                    LIMIT 1
                                )
                            )
                            """
                        )
                        // 书签去重：双击竞态历史可能插入同段多条。
                        // note 无写入路径，保留最早一条无信息损失
                        db.execSQL(
                            """
                            DELETE FROM bookmarks
                            WHERE id NOT IN (
                                SELECT MIN(id) FROM bookmarks GROUP BY bookId, paragraphIndex
                            )
                            """
                        )
                        // reading_stats 历史竞态可能产生同书同日多行：
                        // 先把各组汇总到将保留的行（分钟/字数求和、段落取最大），
                        // 再删冗余行。不合并直接删会丢失阅读时长数据
                        db.execSQL(
                            """
                            UPDATE reading_stats SET
                                readingMinutes = (
                                    SELECT SUM(s2.readingMinutes) FROM reading_stats s2
                                    WHERE s2.bookId = reading_stats.bookId AND s2.date = reading_stats.date),
                                charsRead = (
                                    SELECT SUM(s2.charsRead) FROM reading_stats s2
                                    WHERE s2.bookId = reading_stats.bookId AND s2.date = reading_stats.date),
                                paragraphsRead = (
                                    SELECT MAX(s2.paragraphsRead) FROM reading_stats s2
                                    WHERE s2.bookId = reading_stats.bookId AND s2.date = reading_stats.date)
                            WHERE id IN (SELECT MAX(id) FROM reading_stats GROUP BY bookId, date)
                            """
                        )
                        db.execSQL(
                            """
                            DELETE FROM reading_stats
                            WHERE id NOT IN (SELECT MAX(id) FROM reading_stats GROUP BY bookId, date)
                            """
                        )
                        // 唯一约束：与 DAO 的 IGNORE 插入策略配合，竞态写入变幂等
                        db.execSQL(
                            "CREATE UNIQUE INDEX IF NOT EXISTS `index_review_records_vocabularyId` " +
                                "ON `review_records` (`vocabularyId`)"
                        )
                        db.execSQL(
                            "CREATE UNIQUE INDEX IF NOT EXISTS `index_bookmarks_bookId_paragraphIndex` " +
                                "ON `bookmarks` (`bookId`, `paragraphIndex`)"
                        )
                        // 热查二级索引（与各 DAO 查询逐条对照过）：
                        // 到期复习过滤+排序 / 每日统计聚合 / 词频排序 / 高亮全覆盖 / 书库列表
                        db.execSQL(
                            "CREATE INDEX IF NOT EXISTS `index_review_records_nextReviewDate` " +
                                "ON `review_records` (`nextReviewDate`)"
                        )
                        db.execSQL(
                            "CREATE INDEX IF NOT EXISTS `index_reading_stats_date` " +
                                "ON `reading_stats` (`date`)"
                        )
                        db.execSQL(
                            "CREATE INDEX IF NOT EXISTS `index_reading_stats_bookId_date` " +
                                "ON `reading_stats` (`bookId`, `date`)"
                        )
                        db.execSQL(
                            "CREATE INDEX IF NOT EXISTS `index_word_frequencies_bookId_count` " +
                                "ON `word_frequencies` (`bookId`, `count`)"
                        )
                        db.execSQL(
                            "CREATE INDEX IF NOT EXISTS `index_vocabulary_bookId` " +
                                "ON `vocabulary` (`bookId`)"
                        )
                        db.execSQL(
                            "CREATE INDEX IF NOT EXISTS `index_highlights_bookId_paragraphIndex_startOffset` " +
                                "ON `highlights` (`bookId`, `paragraphIndex`, `startOffset`)"
                        )
                        db.execSQL(
                            "CREATE INDEX IF NOT EXISTS `index_books_isArchived_lastReadTime` " +
                                "ON `books` (`isArchived`, `lastReadTime`)"
                        )
                    }
                },
                object : Migration(6, 7) {
                    override fun migrate(db: SupportSQLiteDatabase) {
                        // reading_stats 升级为 (bookId, date) 唯一。
                        // 先防御性再合并一次（v6 已合并、写入路径也已单飞，
                        // 但约束创建前兜底，保证迁移绝不在存量重复上失败）：
                        // 分钟/字数求和、段落取最大，汇总到将保留的行后删冗余
                        db.execSQL(
                            """
                            UPDATE reading_stats SET
                                readingMinutes = (
                                    SELECT SUM(s2.readingMinutes) FROM reading_stats s2
                                    WHERE s2.bookId = reading_stats.bookId AND s2.date = reading_stats.date),
                                charsRead = (
                                    SELECT SUM(s2.charsRead) FROM reading_stats s2
                                    WHERE s2.bookId = reading_stats.bookId AND s2.date = reading_stats.date),
                                paragraphsRead = (
                                    SELECT MAX(s2.paragraphsRead) FROM reading_stats s2
                                    WHERE s2.bookId = reading_stats.bookId AND s2.date = reading_stats.date)
                            WHERE id IN (SELECT MAX(id) FROM reading_stats GROUP BY bookId, date)
                            """
                        )
                        db.execSQL(
                            """
                            DELETE FROM reading_stats
                            WHERE id NOT IN (SELECT MAX(id) FROM reading_stats GROUP BY bookId, date)
                            """
                        )
                        // 旧的非唯一索引与唯一索引同名，必须先删再建：
                        // IF NOT EXISTS 遇到同名索引会直接跳过，留下非唯一版本，
                        // Room 打开库时校验唯一性不匹配即崩
                        db.execSQL("DROP INDEX IF EXISTS `index_reading_stats_bookId_date`")
                        db.execSQL(
                            "CREATE UNIQUE INDEX IF NOT EXISTS `index_reading_stats_bookId_date` " +
                                "ON `reading_stats` (`bookId`, `date`)"
                        )
                    }
                },
                object : Migration(7, 8) {
                    // issue 8.5：段落翻译缓存表。全新表，无存量改造，
                    // 建表 SQL 与 ParagraphTranslationEntity 的 schema 严格对齐。
                    override fun migrate(db: SupportSQLiteDatabase) {
                        db.execSQL(
                            """
                            CREATE TABLE IF NOT EXISTS `paragraph_translations` (
                                `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                                `bookId` INTEGER NOT NULL,
                                `paragraphIndex` INTEGER NOT NULL,
                                `sourceText` TEXT NOT NULL,
                                `translatedText` TEXT NOT NULL,
                                `langPair` TEXT NOT NULL,
                                `translatedAt` INTEGER NOT NULL
                            )
                            """
                        )
                        db.execSQL(
                            "CREATE UNIQUE INDEX IF NOT EXISTS `index_paragraph_translations_bookId_paragraphIndex` " +
                                "ON `paragraph_translations` (`bookId`, `paragraphIndex`)"
                        )
                        db.execSQL(
                            "CREATE INDEX IF NOT EXISTS `index_paragraph_translations_bookId_langPair` " +
                                "ON `paragraph_translations` (`bookId`, `langPair`)"
                        )
                    }
                },
                object : Migration(8, 9) {
                    // issue 9.9：books 增加 sourceUri 列（外部 content:// URI 的回退读取源）。
                    // 存量书无外部 URI，列默认 NULL；新导入的 SAF/ACTION_VIEW content:// 书写入此列
                    override fun migrate(db: SupportSQLiteDatabase) {
                        // 可空 TEXT 列，不加 DEFAULT：Room 校验会把存储的 "NULL" 默认识别为
                        // null 之外的字符串，导致 schema 校验失败（与 Migration(3,4) 同款写法）
                        db.execSQL("ALTER TABLE books ADD COLUMN `sourceUri` TEXT")
                    }
                },
                object : Migration(9, 10) {
                    // issue 12.5：大词典条目落库。全新表，无存量改造，建表 SQL 与
                    // DictionaryEntryEntity 的 schema 严格对齐（含唯一索引）。
                    override fun migrate(db: SupportSQLiteDatabase) {
                        db.execSQL(
                            """
                            CREATE TABLE IF NOT EXISTS `dictionary_entries` (
                                `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                                `dictId` TEXT NOT NULL,
                                `word` TEXT NOT NULL,
                                `definition` TEXT NOT NULL
                            )
                            """
                        )
                        db.execSQL(
                            "CREATE UNIQUE INDEX IF NOT EXISTS `index_dictionary_entries_dictId_word` " +
                                "ON `dictionary_entries` (`dictId`, `word`)"
                        )
                    }
                },
                object : Migration(10, 11) {
                    // issue 9.7 + 9.2：books 表新增 identifier（OPF dc:identifier，唯一索引做跨导入去重）、
                    // isTruncated / originalCharCount（截断标记与原文规模，书库卡片提示）。
                    // 加列顺序与 BookEntity 的 schema 严格对齐；identifier 用可空 TEXT（不加 DEFAULT，
                    // 与 Migration(3,4)/(8,9) 同款写法，避免 Room 把存储的 NULL 识别成字符串导致校验失败）。
                    override fun migrate(db: SupportSQLiteDatabase) {
                        db.execSQL("ALTER TABLE books ADD COLUMN `identifier` TEXT")
                        db.execSQL("ALTER TABLE books ADD COLUMN `isTruncated` INTEGER NOT NULL DEFAULT 0")
                        db.execSQL("ALTER TABLE books ADD COLUMN `originalCharCount` INTEGER NOT NULL DEFAULT 0")
                        // 存量行 identifier 全为 NULL，SQLite 唯一索引允许多个 NULL，不会因存量冲突失败。
                        // 索引名与 BookEntity `Index(["identifier"], unique=true)` 的自动命名一致。
                        db.execSQL(
                            "CREATE UNIQUE INDEX IF NOT EXISTS `index_books_identifier` " +
                                "ON `books` (`identifier`)"
                        )
                    }
                },
                object : Migration(11, 12) {
                    // 性能：vocabulary 补热查索引——word（点词查词 getWordExact，
                    // 无索引时阅读界面每次点词全表扫描）、isLearned+排序列
                    // （生词/已学列表过滤排序）。索引名与 VocabularyEntity
                    // 声明式索引的 Room 自动命名严格一致，保证 schema 校验通过。
                    override fun migrate(db: SupportSQLiteDatabase) {
                        db.execSQL(
                            "CREATE INDEX IF NOT EXISTS `index_vocabulary_word` " +
                                "ON `vocabulary` (`word`)"
                        )
                        db.execSQL(
                            "CREATE INDEX IF NOT EXISTS `index_vocabulary_isLearned_dateAdded` " +
                                "ON `vocabulary` (`isLearned`, `dateAdded`)"
                        )
                        db.execSQL(
                            "CREATE INDEX IF NOT EXISTS `index_vocabulary_isLearned_lastReviewTime` " +
                                "ON `vocabulary` (`isLearned`, `lastReviewTime`)"
                        )
                    }
                },
            )
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


