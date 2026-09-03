package com.eareyereading.data.repository

import com.eareyereading.data.local.dao.VocabularyDao
import com.eareyereading.data.local.entity.VocabularyEntity
import com.eareyereading.domain.model.Vocabulary
import com.eareyereading.domain.repository.VocabularyRepository
import com.eareyereading.util.TranslationHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class VocabularyRepositoryImpl @Inject constructor(
    private val vocabularyDao: VocabularyDao,
    private val translationHelper: TranslationHelper,
) : VocabularyRepository {

    override fun getAllVocabulary(): Flow<List<Vocabulary>> =
        vocabularyDao.getAllVocabulary()
            .map { list -> list.map { it.toDomain() } }
            // 全量实体→领域对象重建跑在收集者上下文（主线程）；
            // 生词每次增删都会触发重发射，大词库时主线程拷贝整列表
            .flowOn(Dispatchers.Default)

    override fun getNewWords(): Flow<List<Vocabulary>> =
        vocabularyDao.getNewWords()
            .map { list -> list.map { it.toDomain() } }
            .flowOn(Dispatchers.Default)

    override fun getLearnedWords(): Flow<List<Vocabulary>> =
        vocabularyDao.getLearnedWords()
            .map { list -> list.map { it.toDomain() } }
            .flowOn(Dispatchers.Default)

    override fun getWordsByBook(bookId: Long): Flow<List<Vocabulary>> =
        vocabularyDao.getWordsByBook(bookId)
            .map { list -> list.map { it.toDomain() } }
            .flowOn(Dispatchers.Default)

    override suspend fun getWord(word: String): Vocabulary? =
        // 先走 word 索引的精确匹配（O(log n)），未命中再大小写不敏感兜底
        //（旧 LOWER() 查询索引彻底失效，点词每次全表扫描）
        (vocabularyDao.getWordExact(word) ?: vocabularyDao.getWordIgnoreCase(word))?.toDomain()

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
        // 两次网络往返并行化：旧实现串行执行，点词延迟直接翻倍
        return coroutineScope {
            val contextDeferred = context?.let { ctx -> async { translateSentence(ctx) } }
            val definitionDeferred = async { translationHelper.translateWord(word) }
            val translatedContext = contextDeferred?.await()
            val definition = definitionDeferred.await()
            listOfNotNull(translatedContext, definition).joinToString(" · ").ifBlank { definition }
        }
    }

    override suspend fun translateParagraphs(paragraphs: List<String>): Map<Int, String> {
        return translationHelper.translateParagraphs(paragraphs)
    }

    override suspend fun translateSentence(sentence: String): String? {
        return translationHelper.translateEnToZh(sentence)
    }

    override suspend fun addWordToReview(vocabularyId: Long, word: String) {
        // 不做 hasReviewRecord 预检查：插入本身是 IGNORE + vocabularyId 唯一索引，
        // 已幂等。预检查只是多一次查询往返，还留有 check-then-act 竞态窗口
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
