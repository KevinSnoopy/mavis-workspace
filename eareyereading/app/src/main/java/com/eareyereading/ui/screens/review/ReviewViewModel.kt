package com.eareyereading.ui.screens.review

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
            try {
                // 用 first() 而非 collect() — 一次拉取，不持续监听 DB 变化
                // answerCard() 会直接更新 _uiState，不依赖 Flow 重拉
                val records = reviewRecordDao.getDueReviews(System.currentTimeMillis(), 50).first()
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
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                // DB 异常不再走 viewModelScope 未捕获处理器崩 App：
                // 显示空会话，用户可重试
                android.util.Log.e("ReviewViewModel", "loadDueReviews failed", e)
                _uiState.update { it.copy(isSessionComplete = true) }
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
            record.easeFactor  // 标准 SM-2：q<3 时 EF 不变，interval/reps 复位
        } else {
            max(1.3f, record.easeFactor + (0.1f - (5 - q) * (0.08f + (5 - q) * 0.02f)))
        }

        val (newInterval, newReps) = if (q < 3) {
            Pair(1, 0)  // 标准 SM-2：q<3 复位间隔，从头积累
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
        // 先转 Long 再乘：EF 无上限增长时 interval 可能很大，
        // Int 乘法溢出会把 nextReviewDate 算进过去，卡片永久"到期"
        val nextReview = now + newInterval.toLong() * 24L * 60L * 60L * 1000L

        viewModelScope.launch {
            try {
                val updatedRecord = record.copy(
                    easeFactor = newEF,
                    interval = newInterval,
                    repetitions = newReps,
                    nextReviewDate = nextReview,
                    lastReviewDate = now,
                    lastQuality = q,
                )
                reviewRecordDao.updateReview(updatedRecord)
            } catch (e: Exception) {
                android.util.Log.e("ReviewViewModel", "Failed to update review record", e)
            }
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
            try {
                val existing = reviewRecordDao.getReviewForVocab(vocabularyId)
                if (existing == null) {
                    val now = System.currentTimeMillis()
                    reviewRecordDao.insertReview(
                        ReviewRecordEntity(
                            vocabularyId = vocabularyId,
                            word = word,
                            nextReviewDate = now,
                            lastReviewDate = now,
                        )
                    )
                }
            } catch (e: Exception) {
                android.util.Log.e("ReviewViewModel", "Failed to add word to review", e)
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
