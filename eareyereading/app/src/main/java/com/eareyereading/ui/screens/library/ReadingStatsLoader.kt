@file:Suppress("SwallowedException", "TooGenericExceptionCaught")

package com.eareyereading.ui.screens.library

import com.eareyereading.data.local.dao.ReadingStatsDao
import com.eareyereading.data.local.dao.ReviewRecordDao
import com.eareyereading.util.ReadingStreak
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 阅读统计与待复习数加载的单一职责控制器。
 *
 * 独立维护待复习数查询基准时间（长停留页面时新到期卡片能计入），
 * 并按需刷新今日/累计阅读统计。统计结果写入 [LibraryStateController]。
 */
internal class ReadingStatsLoader(
    private val stateController: LibraryStateController,
    private val reviewRecordDao: ReviewRecordDao,
    private val readingStatsDao: ReadingStatsDao,
) {

    /** 待复习数查询基准时间：不能冻结在 init 时刻，长时间停留本页面时
     * 陆续到期的卡片要能计入（与 ReviewViewModel 同款方案） */
    private val dueCountTimestamp = MutableStateFlow(System.currentTimeMillis())

    /** 启动待复习数流收集（独立更新，避免 timestamp 变化干扰 combine） */
    @OptIn(ExperimentalCoroutinesApi::class)
    fun startDueCountCollection(scope: CoroutineScope) {
        scope.launch {
            try {
                dueCountTimestamp
                    .flatMapLatest { now -> reviewRecordDao.getDueReviewCount(now) }
                    .collect { count ->
                        stateController.update { it.copy(dueReviewCount = count) }
                    }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                android.util.Log.e("LibraryViewModel", "due count collect failed", e)
            }
        }
    }

    /** 刷新到期数基准时间（切回书库 tab 或导入/删除后调用） */
    fun refreshDueTimestamp() {
        dueCountTimestamp.value = System.currentTimeMillis()
    }

    /** 加载阅读统计（切回书库 tab 时刷新） */
    fun loadReadingStats(scope: CoroutineScope) {
        scope.launch {
            try {
                val todayDate = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
                    .format(Date())
                // reading_stats 每日每书一行：必须用 SUM 聚合，
                // 取单行会在用户一天读多本书时少报
                val todayMinutes = readingStatsDao.getTotalMinutesForDate(todayDate) ?: 0
                val todayChars = readingStatsDao.getTotalCharsForDate(todayDate) ?: 0
                val allStats = readingStatsDao.getAllStats()
                val totalMinutes = allStats.sumOf { it.readingMinutes }
                val streakDays = ReadingStreak.calculate(allStats)
                stateController.update {
                    it.copy(
                        readingStats = ReadingStatsSummary(
                            todayMinutes = todayMinutes,
                            todayChars = todayChars,
                            totalBooks = allStats.distinctBy { s -> s.bookId }.size,
                            totalMinutes = totalMinutes,
                            streakDays = streakDays,
                        )
                    )
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: java.lang.RuntimeException) {
                // DB may not have records yet - use default stats
                android.util.Log.d("LibraryViewModel", "Stats not available yet", e)
            }
        }
    }
}
