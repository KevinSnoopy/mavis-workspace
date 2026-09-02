package com.eareyereading.domain.repository

import com.eareyereading.domain.model.*
import kotlinx.coroutines.flow.Flow

interface BookRepository {
    fun getAllBooks(): Flow<List<Book>>
    fun getArchivedBooks(): Flow<List<Book>>
    fun getBookById(id: Long): Flow<Book?>
    suspend fun addBook(book: Book): Long
    suspend fun updateBook(book: Book)
    suspend fun updateProgress(bookId: Long, progress: Float, position: Int)
    suspend fun setArchived(bookId: Long, archived: Boolean)
    suspend fun deleteBook(bookId: Long)
    fun searchBooks(query: String): Flow<List<Book>>
}

interface VocabularyRepository {
    fun getAllVocabulary(): Flow<List<Vocabulary>>
    fun getNewWords(): Flow<List<Vocabulary>>
    fun getLearnedWords(): Flow<List<Vocabulary>>
    /** issue 11.20：按书过滤生词（DAO 早已暴露 getWordsByBook，仓库层此前未接通）。 */
    fun getWordsByBook(bookId: Long): Flow<List<Vocabulary>>
    suspend fun getWord(word: String): Vocabulary?
    /** 按主键批量取词（复习会话组队用，避免逐词查询与字符串匹配歧义）。 */
    suspend fun getWordsByIds(ids: List<Long>): Map<Long, Vocabulary>
    /** 复习作答后同步词汇统计（复习次数/最后复习时间）。 */
    suspend fun recordReviewActivity(vocabularyId: Long, reviewTime: Long)
    suspend fun addWord(vocabulary: Vocabulary): Long
    suspend fun updateWord(vocabulary: Vocabulary)
    suspend fun deleteWord(word: Vocabulary)
    fun getTotalCount(): Flow<Int>
    fun getLearnedCount(): Flow<Int>
    suspend fun translateWord(word: String, context: String?): String?
    suspend fun translateParagraphs(paragraphs: List<String>): Map<Int, String>
    suspend fun translateSentence(sentence: String): String?
    suspend fun addWordToReview(vocabularyId: Long, word: String)
}

interface ReadingRepository {
    suspend fun getState(bookId: Long): ReadingState?
    fun getStateFlow(bookId: Long): Flow<ReadingState?>
    suspend fun saveState(state: ReadingState)
    suspend fun updatePosition(bookId: Long, paragraph: Int, position: Int)
    suspend fun updateMode(bookId: Long, mode: ReadingMode)
    suspend fun updateRsvpSpeed(bookId: Long, speed: Int)

    // ── issue 8.5：段落翻译缓存 ─────────────────────────
    /** 读取整本书某语言对的译文缓存（Map: paragraphIndex → translatedText）。 */
    suspend fun getTranslations(bookId: Long, langPair: String): Map<Int, String>
    /** 缓存某段的译文（同段 REPLACE 覆盖）。 */
    suspend fun saveTranslation(bookId: Long, langPair: String, paragraphIndex: Int, sourceText: String, translatedText: String)
    /** 删书时清空该书全部译文缓存。 */
    suspend fun deleteTranslations(bookId: Long)
}

interface SettingsRepository {
    fun getRsvpSpeed(): Flow<Int>
    fun getFontSize(): Flow<Int>
    fun getTheme(): Flow<ReadingTheme>
    fun getLanguage(): Flow<String>
    fun getTranslationAlpha(): Flow<Float>
    fun getRsvpStrength(): Flow<Int>
    fun getDarkMode(): Flow<Boolean>
    fun getNotifications(): Flow<Boolean>
    /** 通知偏好：TTS 下载进度提醒开关 */
    fun getNotificationDownloadProgress(): Flow<Boolean>
    /** 通知偏好：TTS 下载完成提醒开关 */
    fun getNotificationDownloadComplete(): Flow<Boolean>
    fun getCollinsHighlight(): Flow<Boolean>
    /** 普通阅读模式的翻页样式 */
    fun getPageTurningStyle(): Flow<PageTurningStyle>
    /** TTS 语速倍率（0.5 - 2.0），用于内置/系统 TTS 的 speak 调用 */
    fun getTtsSpeed(): Flow<Float>
    suspend fun setRsvpSpeed(speed: Int)
    suspend fun setFontSize(size: Int)
    suspend fun setTheme(theme: ReadingTheme)
    suspend fun setTranslationAlpha(alpha: Float)
    suspend fun setRsvpStrength(strength: Int)
    suspend fun setDarkMode(enabled: Boolean)
    suspend fun setNotifications(enabled: Boolean)
    suspend fun setNotificationDownloadProgress(enabled: Boolean)
    suspend fun setNotificationDownloadComplete(enabled: Boolean)
    suspend fun setCollinsHighlight(enabled: Boolean)
    suspend fun setPageTurningStyle(style: PageTurningStyle)
    suspend fun setTtsSpeed(speed: Float)
    suspend fun clearAll()
}
