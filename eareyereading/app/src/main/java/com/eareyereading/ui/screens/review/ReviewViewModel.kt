package com.eareyereading.ui.screens.review
@file:Suppress("WildcardImport", "FunctionNaming", "UnusedParameter", "MatchingDeclarationName", "EmptyFunctionBlock", "UnusedPrivateProperty")

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.eareyereading.data.local.dao.ReviewRecordDao
import com.eareyereading.data.local.entity.ReviewRecordEntity
import com.eareyereading.domain.model.Vocabulary
import com.eareyereading.domain.repository.VocabularyRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * SM-2 遗忘曲线复习算法
 * - quality: 0=完全不记得 1=错误 2=记得但困难 3=一般 4=良好 5=完美
 */
@HiltViewModel
class ReviewViewModel @Inject constructor(
    private val reviewRecordDao: ReviewRecordDao,
    private val vocabularyRepository: VocabularyRepository,
) : ViewModel() {

    data class ReviewCard(
        val record: ReviewRecordEntity,
        val vocabulary: Vocabulary?,
    )

    data class ReviewUiState(
        val dueCards: List<ReviewCard> = emptyList(),
        val currentIndex: Int = 0,
        val isShowingAnswer: Boolean = false,
        val isSessionComplete: Boolean = false,
        val totalReviewed: Int = 0,
        val correctCount: Int = 0,
    )

    private val _uiState = MutableStateFlow(ReviewUiState())
    val uiState: StateFlow<ReviewUiState> = _uiState.asStateFlow()

    val dueCount: StateFlow<Int> = reviewRecordDao
        .getDueReviewCount(System.currentTimeMillis())
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    fun loadDueReviews() {
        viewModelScope.launch {
            reviewRecordDao.getDueReviews(System.currentTimeMillis(), 50).collect { records ->
                val cards = records.map { record ->
                    val vocab = vocabularyRepository.getWord(record.word)
                    ReviewCard(record = record, vocabulary = vocab)
                }
                _uiState.update {
                    it.copy(
                        dueCards = cards,
                        currentIndex = 0,
                        isShowingAnswer = false,
                        isSessionComplete = cards.isEmpty(),
                    )
                }
            }
        }
    }

    fun revealAnswer() {
        _uiState.update { it.copy(isShowingAnswer = true) }
    }

    /**
     * SM-2 算法核心：
     * - q < 3: 重新从间隔1开始
     * - q >= 3: 按公式更新间隔
     */
    fun answerCard(quality: Int) {
        val state = _uiState.value
        if (state.currentIndex >= state.dueCards.size) return

        val card = state.dueCards[state.currentIndex]
        val record = card.record

        // SM-2 算法计算
        val q = quality.coerceIn(0, 5)
        val newEF = if (q < 3) {
            record.easeFactor  // 不记得了就降低 EF
        } else {
            max(1.3f, record.easeFactor + (0.1f - (5 - q) * (0.08f + (5 - q) * 0.02f)))
        }

        val (newInterval, newReps) = if (q < 3) {
            Pair(1, 0)  // 重新开始
        } else {
            val reps = record.repetitions + 1
            val interval = when (reps) {
                1 -> 1
                2 -> 6
                else -> (record.interval * newEF).roundToInt()
            }
            Pair(interval, reps)
        }

        val now = System.currentTimeMillis()
        val nextReview = now + newInterval * 24 * 60 * 60 * 1000L

        viewModelScope.launch {
            val updatedRecord = record.copy(
                easeFactor = newEF,
                interval = newInterval,
                repetitions = newReps,
                nextReviewDate = nextReview,
                lastReviewDate = now,
                lastQuality = q,
            )
            reviewRecordDao.updateReview(updatedRecord)
        }

        val isCorrect = q >= 3
        val nextIndex = state.currentIndex + 1
        val isComplete = nextIndex >= state.dueCards.size

        _uiState.update {
            it.copy(
                currentIndex = nextIndex,
                isShowingAnswer = false,
                isSessionComplete = isComplete,
                totalReviewed = it.totalReviewed + 1,
                correctCount = if (isCorrect) it.correctCount + 1 else it.correctCount,
            )
        }
    }

    fun addWordToReview(vocabularyId: Long, word: String) {
        viewModelScope.launch {
            val existing = reviewRecordDao.getReviewForVocab(vocabularyId)
            if (existing == null) {
                reviewRecordDao.insertReview(
                    ReviewRecordEntity(
                        vocabularyId = vocabularyId,
                        word = word,
                    )
                )
            }
        }
    }

    fun restartSession() {
        _uiState.update {
            it.copy(
                currentIndex = 0,
                isShowingAnswer = false,
                isSessionComplete = false,
                totalReviewed = 0,
                correctCount = 0,
            )
        }
        loadDueReviews()
    }
}
