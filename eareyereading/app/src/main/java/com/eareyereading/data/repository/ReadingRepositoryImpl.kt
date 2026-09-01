package com.eareyereading.data.repository

import com.eareyereading.data.local.dao.ParagraphTranslationDao
import com.eareyereading.data.local.dao.ReadingStateDao
import com.eareyereading.data.local.entity.ParagraphTranslationEntity
import com.eareyereading.data.local.entity.ReadingStateEntity
import com.eareyereading.domain.model.ReadingMode
import com.eareyereading.domain.model.ReadingState
import com.eareyereading.domain.model.ReadingTheme
import com.eareyereading.domain.repository.ReadingRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ReadingRepositoryImpl @Inject constructor(
    private val readingStateDao: ReadingStateDao,
    private val paragraphTranslationDao: ParagraphTranslationDao,
) : ReadingRepository {

    override suspend fun getState(bookId: Long): ReadingState? =
        readingStateDao.getForBook(bookId)?.toDomain()

    override fun getStateFlow(bookId: Long): Flow<ReadingState?> =
        readingStateDao.observeForBook(bookId).map { it?.toDomain() }

    override suspend fun saveState(state: ReadingState) {
        readingStateDao.upsert(state.toEntity())
    }

    override suspend fun updatePosition(bookId: Long, paragraph: Int, position: Int) {
        // 不再硬编码 paragraph = 0：原实现会把已保存的段落进度抹掉
        readingStateDao.updateProgress(bookId, paragraph, position)
    }

    override suspend fun updateMode(bookId: Long, mode: ReadingMode) {
        readingStateDao.updateMode(bookId, mode.value)
    }

    override suspend fun updateRsvpSpeed(bookId: Long, speed: Int) {
        readingStateDao.updateRsvpSpeed(bookId, speed)
    }

    // ── issue 8.5：段落翻译缓存 ─────────────────────────
    override suspend fun getTranslations(bookId: Long, langPair: String): Map<Int, String> =
        paragraphTranslationDao.getForBook(bookId, langPair)
            .associate { it.paragraphIndex to it.translatedText }

    override suspend fun saveTranslation(
        bookId: Long,
        langPair: String,
        paragraphIndex: Int,
        sourceText: String,
        translatedText: String,
    ) {
        paragraphTranslationDao.upsert(
            ParagraphTranslationEntity(
                bookId = bookId,
                paragraphIndex = paragraphIndex,
                sourceText = sourceText,
                translatedText = translatedText,
                langPair = langPair,
                translatedAt = System.currentTimeMillis(),
            )
        )
    }

    override suspend fun deleteTranslations(bookId: Long) =
        paragraphTranslationDao.deleteForBook(bookId)

    private fun ReadingStateEntity.toDomain() = ReadingState(
        bookId = bookId,
        currentPosition = currentPosition,
        currentParagraph = currentParagraph,
        totalCharacters = totalCharacters,
        totalParagraphs = totalParagraphs,
        readingMode = ReadingMode.entries.find { it.value == readingMode } ?: ReadingMode.NORMAL,
        rsvpSpeed = rsvpSpeed,
        fontSize = fontSize,
        theme = ReadingTheme.entries.find { it.value == theme } ?: ReadingTheme.LIGHT,
    )

    private fun ReadingState.toEntity() = ReadingStateEntity(
        bookId = bookId,
        currentPosition = currentPosition,
        currentParagraph = currentParagraph,
        totalCharacters = totalCharacters,
        totalParagraphs = totalParagraphs,
        readingMode = readingMode.value,
        rsvpSpeed = rsvpSpeed,
        fontSize = fontSize,
        theme = theme.value,
        lastUpdated = System.currentTimeMillis(),
    )
}
