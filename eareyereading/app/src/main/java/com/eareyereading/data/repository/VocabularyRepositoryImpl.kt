package com.eareyereading.data.repository

import com.eareyereading.data.local.dao.VocabularyDao
import com.eareyereading.data.local.entity.VocabularyEntity
import com.eareyereading.domain.model.Vocabulary
import com.eareyereading.domain.repository.VocabularyRepository
import com.eareyereading.util.TranslationHelper
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class VocabularyRepositoryImpl @Inject constructor(
    private val vocabularyDao: VocabularyDao,
    private val translationHelper: TranslationHelper,
) : VocabularyRepository {

    override fun getAllVocabulary(): Flow<List<Vocabulary>> =
        vocabularyDao.getAllVocabulary().map { list -> list.map { it.toDomain() } }

    override fun getNewWords(): Flow<List<Vocabulary>> =
        vocabularyDao.getNewWords().map { list -> list.map { it.toDomain() } }

    override fun getLearnedWords(): Flow<List<Vocabulary>> =
        vocabularyDao.getLearnedWords().map { list -> list.map { it.toDomain() } }

    override fun getWordsByBook(bookId: Long): Flow<List<Vocabulary>> =
        vocabularyDao.getWordsByBook(bookId).map { list -> list.map { it.toDomain() } }

    override suspend fun getWord(word: String): Vocabulary? =
        vocabularyDao.getWord(word)?.toDomain()

    override suspend fun getWordsByIds(ids: List<Long>): Map<Long, Vocabulary> =
        vocabularyDao.getWordsByIds(ids).associate { it.id to it.toDomain() }

    override suspend fun recordReviewActivity(vocabularyId: Long, reviewTime: Long) {
        vocabularyDao.bumpReviewStats(vocabularyId, reviewTime)
    }

    override suspend fun addWord(vocabulary: Vocabulary): Long =
        vocabularyDao.insert(vocabulary.toEntity())

    override suspend fun updateWord(vocabulary: Vocabulary) {
        vocabularyDao.update(vocabulary.toEntity())
    }

    override suspend fun deleteWord(word: Vocabulary) {
        vocabularyDao.deleteVocabularyWithReview(word.id, word.toEntity())
    }

    override fun getTotalCount(): Flow<Int> = vocabularyDao.getTotalWordCount()

    override fun getLearnedCount(): Flow<Int> = vocabularyDao.getLearnedWordCount()

    override suspend fun translateWord(word: String, context: String?): String? {
        val context = context?.let { translateSentence(it) }
        val definition = translationHelper.translateWord(word)
        return listOfNotNull(context, definition).joinToString(" · ").ifBlank { definition }
    }

    override suspend fun translateParagraphs(paragraphs: List<String>): Map<Int, String> {
        return translationHelper.translateParagraphs(paragraphs)
    }

    override suspend fun translateSentence(sentence: String): String? {
        return translationHelper.translateEnToZh(sentence)
    }

    override suspend fun addWordToReview(vocabularyId: Long, word: String) {
        if (!vocabularyDao.hasReviewRecord(vocabularyId)) {
            val now = System.currentTimeMillis()
            vocabularyDao.insertReviewRecord(
                com.eareyereading.data.local.entity.ReviewRecordEntity(
                    vocabularyId = vocabularyId,
                    word = word,
                    nextReviewDate = now,
                    lastReviewedAt = now,
                )
            )
        }
    }

    private fun VocabularyEntity.toDomain() = Vocabulary(
        id = id, word = word, phonetic = phonetic, definition = definition,
        bookId = bookId, bookTitle = bookTitle, context = context,
        translation = translation, isLearned = isLearned,
        reviewCount = reviewCount, lastReviewTime = lastReviewTime,
        dateAdded = dateAdded,
        note = note,
        example = example,
        level = level,
    )

    private fun Vocabulary.toEntity() = VocabularyEntity(
        id = id, word = word, phonetic = phonetic, definition = definition,
        bookId = bookId, bookTitle = bookTitle, context = context,
        translation = translation, isLearned = isLearned,
        reviewCount = reviewCount, lastReviewTime = lastReviewTime,
        dateAdded = dateAdded,
        note = note,
        example = example,
        level = level,
    )
}
