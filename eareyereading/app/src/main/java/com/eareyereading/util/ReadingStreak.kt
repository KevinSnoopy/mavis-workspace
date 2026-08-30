package com.eareyereading.util

import com.eareyereading.data.local.entity.ReadingStatsEntity
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

/**
 * 阅读连续天数（连胜）计算。
 *
 * 从三个 ViewModel（Home/Library/Settings）逐字复制的算法收敛为单一实现：
 * 口径修正（时区/起始日/断连规则）只需改这一处，三页不再可能显示不一致。
 *
 * 按日历日回溯，不用毫秒差除以 86_400_000：
 * - 夏令时切换日长度为 23/25 小时，毫秒除法会把"漏读一天"误算成连续；
 * - 未来日期的记录（时钟漂移/恢复数据）也会混入。
 * 容差语义保留：今天还没读不算断，从昨天开始检查。
 */
object ReadingStreak {

    fun calculate(stats: List<ReadingStatsEntity>): Int {
        if (stats.isEmpty()) return 0
        val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
        val presentDates = stats.mapNotNull { stat ->
            try { dateFormat.parse(stat.date) } catch (_: java.text.ParseException) { null }
        }.map { dateFormat.format(it) }.toSet()

        val calendar = Calendar.getInstance()
        if (dateFormat.format(calendar.time) !in presentDates) {
            calendar.add(Calendar.DAY_OF_YEAR, -1)
        }
        var streak = 0
        while (dateFormat.format(calendar.time) in presentDates) {
            streak++
            calendar.add(Calendar.DAY_OF_YEAR, -1)
        }
        return streak
    }
}
