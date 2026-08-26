package com.eareyereading.data.local.dao
@file:Suppress("WildcardImport", "FunctionNaming", "UnusedParameter", "MatchingDeclarationName", "EmptyFunctionBlock", "UnusedPrivateProperty")

import androidx.room.*
import com.eareyereading.data.local.entity.VocabularyEntity
import com.eareyereading.data.local.entity.WordFrequencyEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface VocabularyDao {
    @Query("SELECT * FROM vocabulary ORDER BY dateAdded DESC")
    fun getAllVocabulary(): Flow<List<VocabularyEntity>>

    @Query("SELECT * FROM vocabulary WHERE isLearned = 0 ORDER BY dateAdded DESC")
    fun getNewWords(): Flow<List<VocabularyEntity>>

    @Query("SELECT * FROM vocabulary WHERE isLearned = 1 ORDER BY lastReviewTime DESC")
    fun getLearnedWords(): Flow<List<VocabularyEntity>>

    @Query("SELECT * FROM vocabulary WHERE word = :word LIMIT 1")
    suspend fun getWord(word: String): VocabularyEntity?

    @Query("SELECT * FROM vocabulary WHERE bookId = :bookId")
    fun getWordsByBook(bookId: Long): Flow<List<VocabularyEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(word: VocabularyEntity): Long

    @Update
    suspend fun update(word: VocabularyEntity)

    @Delete
    suspend fun delete(word: VocabularyEntity)

    @Query("SELECT COUNT(*) FROM vocabulary")
    fun getTotalWordCount(): Flow<Int>

    @Query("SELECT COUNT(*) FROM vocabulary WHERE isLearned = 1")
    fun getLearnedWordCount(): Flow<Int>
}

@Dao
interface WordFrequencyDao {
    @Query("SELECT * FROM word_frequency WHERE bookId = :bookId ORDER BY count DESC")
    fun getWordFrequencies(bookId: Long): Flow<List<WordFrequencyEntity>>

    @Query("SELECT * FROM word_frequency WHERE bookId = :bookId ORDER BY count DESC LIMIT :limit")
    fun getTopFrequencies(bookId: Long, limit: Int): Flow<List<WordFrequencyEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(words: List<WordFrequencyEntity>)

    @Query("DELETE FROM word_frequency WHERE bookId = :bookId")
    suspend fun deleteByBook(bookId: Long)
}
