package com.eareyereading.domain.repository
@file:Suppress("WildcardImport", "FunctionNaming", "UnusedParameter", "MatchingDeclarationName", "EmptyFunctionBlock", "UnusedPrivateProperty")

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
    suspend fun getWord(word: String): Vocabulary?
    suspend fun addWord(vocabulary: Vocabulary): Long
    suspend fun updateWord(vocabulary: Vocabulary)
    suspend fun deleteWord(word: Vocabulary)
    fun getTotalCount(): Flow<Int>
    fun getLearnedCount(): Flow<Int>
    suspend fun translateWord(word: String, context: String?): String?
    suspend fun translateParagraphs(paragraphs: List<String>): Map<Int, String>
    suspend fun translateSentence(sentence: String): String?
}

interface WordFrequencyRepository {
    fun getFrequencies(bookId: Long): Flow<List<WordFrequency>>
    fun getTopFrequencies(bookId: Long, limit: Int): Flow<List<WordFrequency>>
    suspend fun calculateFrequencies(bookId: Long, paragraphs: List<String>)
}

interface ReadingRepository {
    suspend fun getState(bookId: Long): ReadingState?
    fun getStateFlow(bookId: Long): Flow<ReadingState?>
    suspend fun saveState(state: ReadingState)
    suspend fun updatePosition(bookId: Long, position: Int)
    suspend fun updateMode(bookId: Long, mode: ReadingMode)
    suspend fun updateRsvpSpeed(bookId: Long, speed: Int)
}

interface SettingsRepository {
    fun getRsvpSpeed(): Flow<Int>
    fun getFontSize(): Flow<Int>
    fun getTheme(): Flow<ReadingTheme>
    fun getLanguage(): Flow<String>
    fun getTranslationAlpha(): Flow<Float>
    fun getRsvpStrength(): Flow<Int>
    fun getRsvpInterval(): Flow<Int>
    suspend fun setRsvpSpeed(speed: Int)
    suspend fun setFontSize(size: Int)
    suspend fun setTheme(theme: ReadingTheme)
    suspend fun setTranslationAlpha(alpha: Float)
    suspend fun setRsvpStrength(strength: Int)
    suspend fun setRsvpInterval(interval: Int)
}
