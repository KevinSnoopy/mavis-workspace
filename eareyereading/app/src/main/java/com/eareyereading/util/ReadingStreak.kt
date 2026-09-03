package com.eareyereading.util

import com.eareyereading.data.local.entity.ReadingStatsEntity
import java.time.LocalDate

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
 *
 * 日期只做格式校验（\d{4}-\d{2}-\d{2}）：旧实现对每条记录做
 * SimpleDateFormat.parse + format 往返（内部分配 Calendar + 同步块），
 * 一年 365 条记录 = 730 次解析，widget 每 30 分钟刷一遍纯属浪费。
 * minSdk 26，java.time 直接可用。
 */
object ReadingStreak {

    private val DATE_PATTERN = Regex("""\d{4}-\d{2}-\d{2}""")

    fun calculate(stats: List<ReadingStatsEntity>): Int =
        calculateFromDates(stats.map { it.date })

    /** 只需要日期字符串的调用方（widget）走这里，免拉全表实体。 */
    fun calculateFromDates(dates: List<String>): Int {
        val presentDates = HashSet<String>(dates.size)
        for (date in dates) {
            if (DATE_PATTERN.matches(date)) presentDates.add(date)
        }
        if (presentDates.isEmpty()) return 0

        var day = LocalDate.now()
        if (day.toString() !in presentDates) {
            day = day.minusDays(1)
        }
        var streak = 0
        while (day.toString() in presentDates) {
            streak++
            day = day.minusDays(1)
        }
        return streak
    }
}
