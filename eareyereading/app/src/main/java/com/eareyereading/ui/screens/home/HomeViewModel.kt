package com.eareyereading.ui.screens.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.eareyereading.data.local.dao.ReviewRecordDao
import com.eareyereading.data.local.dao.ReadingStatsDao
import com.eareyereading.domain.model.Book
import com.eareyereading.domain.repository.BookRepository
import com.eareyereading.domain.repository.VocabularyRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*
import javax.inject.Inject

data class HomeUiState(
    val greeting: String = "",
    val todayMinutes: Int = 0,
    val todayChars: Int = 0,
    val streakDays: Int = 0,
    val totalBooks: Int = 0,
    val totalVocabulary: Int = 0,
    val learnedVocabulary: Int = 0,
    val dueReviewCount: Int = 0,
    val recentBooks: List<Book> = emptyList(),
    val weeklyData: List<DayReadingData> = emptyList(),
    /** 近 12 周学习热力图：按周一开头逐日排列（最旧在前），未来日期为 -1。 */
    val heatmapData: List<Int> = emptyList(),
    val isLoading: Boolean = true,
)

data class DayReadingData(
    val dayLabel: String,  // "周一" etc.
    val minutes: Int,
)

@HiltViewModel
class HomeViewModel @Inject constructor(
    private val bookRepository: BookRepository,
    private val vocabularyRepository: VocabularyRepository,
    private val readingStatsDao: ReadingStatsDao,
    private val reviewRecordDao: ReviewRecordDao,
) : ViewModel() {

    private val _uiState = MutableStateFlow(HomeUiState())
    val uiState: StateFlow<HomeUiState> = _uiState.asStateFlow()
    private var loadDataJob: Job? = null
    private var statsJob: Job? = null

    // 到期数的查询基准时间不能冻结在加载时刻：长时间停留首页时
    // 陆续到期的卡片要能计入（与 ReviewViewModel 同款方案）
    private val dueCountTimestamp = MutableStateFlow(System.currentTimeMillis())

    init {
        updateGreeting()
        loadData()
    }

    private fun updateGreeting() {
        val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        val greeting = when {
            hour < 6 -> "夜深了"
            hour < 9 -> "早上好"
            hour < 12 -> "上午好"
            hour < 14 -> "中午好"
            hour < 18 -> "下午好"
            hour < 22 -> "晚上好"
            else -> "夜深了"
        }
        _uiState.update { it.copy(greeting = greeting) }
    }

    private fun loadData() {
        // 每次加载刷新到期数基准时间，避免用旧时间戳过滤
        dueCountTimestamp.value = System.currentTimeMillis()
        loadDataJob?.cancel()
        loadDataJob = viewModelScope.launch {
            try {
                // 加载词汇统计
                combine(
                    vocabularyRepository.getTotalCount(),
                    vocabularyRepository.getLearnedCount(),
                    dueCountTimestamp.flatMapLatest { now ->
                        reviewRecordDao.getDueReviewCount(now)
                    },
                    bookRepository.getAllBooks(),
                ) { total, learned, due, books ->
                    _uiState.update { state ->
                        state.copy(
                            totalVocabulary = total,
                            learnedVocabulary = learned,
                            dueReviewCount = due,
                            recentBooks = books.take(3),
                            isLoading = false,
                        )
                    }
                }.collect()
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                // 数据层异常不再炸掉启动页：降级为可交互的空状态
                android.util.Log.e("HomeViewModel", "loadData failed", e)
                _uiState.update { it.copy(isLoading = false) }
            }
        }

        // 加载今日阅读统计
        statsJob?.cancel()
        statsJob = viewModelScope.launch {
            try {
                val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
                val today = dateFormat.format(Date())
                // reading_stats 每日每书一行：必须用 SUM 聚合，
                // 取单行会在用户一天读多本书时少报
                val todayMinutes = readingStatsDao.getTotalMinutesForDate(today) ?: 0
                val todayChars = readingStatsDao.getTotalCharsForDate(today) ?: 0
                val allStats = readingStatsDao.getAllStats()
                val streakDays = calculateStreak(allStats)

                _uiState.update {
                    it.copy(
                        todayMinutes = todayMinutes,
                        todayChars = todayChars,
                        streakDays = streakDays,
                        totalBooks = allStats.distinctBy { s -> s.bookId }.size,
                    )
                }

                // 周数据（最近7天）
                val weeklyData = loadWeeklyData(dateFormat)
                _uiState.update { it.copy(weeklyData = weeklyData) }

                // 近 12 周热力图数据（allStats 已在上面取过，直接聚合）
                val heatmapData = buildHeatmapData(allStats, dateFormat)
                _uiState.update { it.copy(heatmapData = heatmapData) }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                android.util.Log.e("HomeViewModel", "loadStats failed", e)
                _uiState.update { it.copy(isLoading = false) }
            }
        }
    }

    private suspend fun loadWeeklyData(dateFormat: SimpleDateFormat): List<DayReadingData> {
        val calendar = Calendar.getInstance()
        val dayLabels = listOf("周日", "周一", "周二", "周三", "周四", "周五", "周六")
        return (6 downTo 0).map { daysAgo ->
            calendar.time = Date()
            calendar.add(Calendar.DAY_OF_YEAR, -daysAgo)
            val dateStr = dateFormat.format(calendar.time)
            val dayOfWeek = calendar.get(Calendar.DAY_OF_WEEK) - 1
            // SUM 聚合：一天读多本书时图表不再少报
            val minutes = readingStatsDao.getTotalMinutesForDate(dateStr) ?: 0
            DayReadingData(
                dayLabel = dayLabels[dayOfWeek],
                minutes = minutes,
            )
        }
    }

    /**
     * 近 12 周热力图数据：以"11 周前的周一"为起点逐日推进到今天，
     * 每天的分钟数从全量统计按日期聚合（一天读多本书时相加）。
     * 今天之后的格子（本周未来几天）用 -1 标记，UI 渲染为透明占位。
     */
    private fun buildHeatmapData(
        allStats: List<com.eareyereading.data.local.entity.ReadingStatsEntity>,
        dateFormat: SimpleDateFormat,
    ): List<Int> {
        val minutesByDate = HashMap<String, Int>(allStats.size)
        allStats.forEach { s ->
            // HashMap.merge 比分组再求和少一轮遍历；readingMinutes 均非负
            minutesByDate.merge(s.date, s.readingMinutes, Int::plus)
        }
        val cal = Calendar.getInstance().apply {
            firstDayOfWeek = Calendar.MONDAY
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
            // 先回到本周，再定位到周一（firstDayOfWeek=MONDAY 时 set 不会跨周）；
            // 回退 11 周用天数差而不是 WEEK_OF_YEAR（年初跨年时周字段语义不稳）
            set(Calendar.DAY_OF_WEEK, Calendar.MONDAY)
            add(Calendar.DAY_OF_YEAR, -77)
        }
        val today = dateFormat.format(Date())
        return buildList {
            repeat(84) {
                val dateStr = dateFormat.format(cal.time)
                add(if (dateStr > today) -1 else (minutesByDate[dateStr] ?: 0))
                cal.add(Calendar.DAY_OF_YEAR, 1)
            }
        }
    }

    /** Streak calc converged into ReadingStreak: single-source-of-truth for the
     * calendar-day rule shared by Home/Library/Settings. */
    private fun calculateStreak(stats: List<com.eareyereading.data.local.entity.ReadingStatsEntity>): Int =
        com.eareyereading.util.ReadingStreak.calculate(stats)

    fun refresh() {
        _uiState.update { it.copy(isLoading = true) }
        loadData()
    }
}
