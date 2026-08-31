package com.eareyereading.ui.screens.review

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.room.withTransaction
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
    private val database: com.eareyereading.data.local.database.AppDatabase,
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
        // 评分写库进行中：防止答案按钮在过渡/写入期间再次命中（双击会把
        // 评分记到下一张未展示的卡片上，静默污染 SM-2 历史）
        val isSubmitting: Boolean = false,
        // 加载失败与"全部完成"必须可区分：此前 DB 异常被当成完成渲染庆祝页
        val errorMessage: String? = null,
    )

    private val _uiState = MutableStateFlow(ReviewUiState())
    val uiState: StateFlow<ReviewUiState> = _uiState.asStateFlow()

    // 到期数的查询基准时间不能冻结在 ViewModel 构造时刻：
    // 长会话中陆续到期的卡片要能被计入。每次加载/重开/答题后刷新时间戳，
    // flatMapLatest 用新时间戳重新起流
    private val dueCountTimestamp = MutableStateFlow(System.currentTimeMillis())
    val dueCount: StateFlow<Int> = dueCountTimestamp
        .flatMapLatest { now -> reviewRecordDao.getDueReviewCount(now) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    fun loadDueReviews() {
        viewModelScope.launch {
            try {
                // 刷新到期数的基准时间（见 dueCount 说明）
                dueCountTimestamp.value = System.currentTimeMillis()
                // 用 first() 而非 collect() — 一次拉取，不持续监听 DB 变化
                // answerCard() 会直接更新 _uiState，不依赖 Flow 重拉
                val records = reviewRecordDao.getDueReviews(System.currentTimeMillis(), 50).first()
                // 按 vocabularyId 一次批量取词：旧实现逐词 LOWER(word) 匹配，
                // 同名多行时取到任意一行（可能无上下文），且 50 次全表扫描
                val vocabById = vocabularyRepository.getWordsByIds(records.map { it.vocabularyId })
                val cards = records.map { record ->
                    ReviewCard(record = record, vocabulary = vocabById[record.vocabularyId])
                }
                _uiState.update {
                    it.copy(
                        dueCards = cards,
                        currentIndex = 0,
                        isShowingAnswer = false,
                        isSessionComplete = cards.isEmpty(),
                        errorMessage = null,
                    )
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                // DB 异常不再走 viewModelScope 未捕获处理器崩 App；
                // 与"全部完成"区分开，显示错误态供用户重试
                android.util.Log.e("ReviewViewModel", "loadDueReviews failed", e)
                _uiState.update {
                    it.copy(isSessionComplete = false, errorMessage = "复习数据加载失败，请重试")
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
     *
     * 评分写入与界面推进串行化：先落库成功再推进卡片。旧实现界面先推进、
     * 写库 fire-and-forget，写入失败/进程被杀时评分静默丢失（卡片原地复活），
     * 且过渡动画期间的二次点击会把评分记到下一张未展示的卡片上。
     */
    fun answerCard(quality: Int) {
        val state = _uiState.value
        // 未揭示答案/正在提交/队列已尽：一律不受理
        if (!state.isShowingAnswer || state.isSubmitting) return
        if (state.currentIndex >= state.dueCards.size) return

        val card = state.dueCards[state.currentIndex]
        val record = card.record

        // SM-2 算法计算
        val q = quality.coerceIn(0, 5)
        // EF 更新按原始论文无条件套公式（q<3 也更新，只是 reps/interval 复位），
        // 并夹在 [1.3, 2.5]：无上限时长期"完美"评分会让 EF 无限增长，
        // interval*EF 溢出 Int 后 nextReviewDate 算进过去 → 卡片永久 due
        val newEF = (record.easeFactor + (0.1f - (5 - q) * (0.08f + (5 - q) * 0.02f)))
            .coerceIn(1.3f, 2.5f)

        val (newInterval, newReps) = if (q < 3) {
            Pair(1, 0)  // 标准 SM-2：q<3 复位间隔，从头积累
        } else {
            val reps = record.repetitions + 1
            // 先转 Double 再乘并钳制到 Int 范围：Float 乘大数再 roundToInt
            // 溢出回负/Int.MAX 时 nextReviewDate 直接跳到过去
            val interval = when (reps) {
                1 -> 1
                2 -> 6
                else -> (record.interval.toDouble() * newEF.toDouble())
                    .coerceIn(1.0, Int.MAX_VALUE.toDouble())
                    .roundToInt()
            }
            Pair(interval, reps)
        }

        val now = System.currentTimeMillis()
        // 先转 Long 再乘：EF 无上限增长时 interval 可能很大，
        // Int 乘法溢出会把 nextReviewDate 算进过去，卡片永久"到期"
        val nextReview = now + newInterval.toLong() * 24L * 60L * 60L * 1000L

        _uiState.update { it.copy(isSubmitting = true) }
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
                // 两次写必须原子：SM-2 推进 + 词汇统计若只成功一半，
                // 重试会在已推进的记录上再套一次算法，间隔被错误放大
                database.withTransaction {
                    reviewRecordDao.updateReview(updatedRecord)
                    // 词汇侧统计同步更新（此前复习只写 review_records，
                    // 词汇页 reviewCount/lastReviewTime 永远为 0）
                    vocabularyRepository.recordReviewActivity(record.vocabularyId, now)
                }
                // 刷新到期数基准时间，会话中后续到期的卡片能计入角标
                dueCountTimestamp.value = System.currentTimeMillis()

                val isCorrect = q >= 3
                val nextIndex = state.currentIndex + 1
                val isComplete = nextIndex >= state.dueCards.size
                _uiState.update {
                    it.copy(
                        currentIndex = nextIndex,
                        isShowingAnswer = false,
                        isSubmitting = false,
                        isSessionComplete = isComplete,
                        totalReviewed = it.totalReviewed + 1,
                        correctCount = if (isCorrect) it.correctCount + 1 else it.correctCount,
                        // 重试成功后必须解除错误提示：否则旧文案会残留在
                        // 后续每张卡片顶部，误导用户以为后续作答也失败
                        errorMessage = null,
                    )
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                // 写库失败不得假装成功：保留在当前卡片并给出可感知的错误，
                // 用户可重试评分而不是丢失这次作答
                android.util.Log.e("ReviewViewModel", "Failed to update review record", e)
                _uiState.update {
                    it.copy(isSubmitting = false, errorMessage = "保存复习结果失败，请重试")
                }
            }
        }
    }

    fun clearError() {
        _uiState.update { it.copy(errorMessage = null) }
    }

    fun restartSession() {
        _uiState.update {
            it.copy(
                currentIndex = 0,
                isShowingAnswer = false,
                isSessionComplete = false,
                totalReviewed = 0,
                correctCount = 0,
                errorMessage = null,
            )
        }
        loadDueReviews()
    }
}
