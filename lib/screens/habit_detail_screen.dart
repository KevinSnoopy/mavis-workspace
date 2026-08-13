import 'package:flutter/material.dart';
import 'package:fl_chart/fl_chart.dart';
import 'package:provider/provider.dart';
import '../providers/habit_provider.dart';
import '../models/habit.dart';
import '../theme/app_theme.dart';

/// 习惯详情页：展示统计数据和打卡日历
class HabitDetailScreen extends StatelessWidget {
  final String habitId;

  const HabitDetailScreen({super.key, required this.habitId});

  @override
  Widget build(BuildContext context) {
    return Consumer<HabitProvider>(
      builder: (context, provider, _) {
        Habit? habit;
        try {
          habit = provider.habits.firstWhere((h) => h.id == habitId);
        } catch (_) {
          return Scaffold(
            appBar: AppBar(title: const Text('习惯详情')),
            body: const Center(child: Text('习惯不存在')),
          );
        }

        final stats = provider.getHabitStats(habitId);
        final habitCheckIns = provider.checkIns.where((c) => c.habitId == habitId).toList();
        final checkInSet = stats.checkInDates.toSet();

        // 最近7天的打卡数据（用于柱状图）
        final now = DateTime.now();
        final last7Days = List.generate(7, (i) {
          return now.subtract(Duration(days: 6 - i));
        });

        return Scaffold(
          appBar: AppBar(
            title: Row(
              mainAxisSize: MainAxisSize.min,
              children: [
                Text(habit.icon, style: const TextStyle(fontSize: 20)),
                const SizedBox(width: 8),
                Text(habit.name),
              ],
            ),
          ),
          body: SingleChildScrollView(
            physics: const BouncingScrollPhysics(),
            padding: const EdgeInsets.all(16),
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                // 核心指标
                Row(
                  children: [
                    _MetricCard(
                      icon: '🔥',
                      value: '${stats.currentStreak}',
                      label: '当前连胜',
                      color: const Color(0xFFFF6B35),
                    ),
                    const SizedBox(width: 10),
                    _MetricCard(
                      icon: '🏆',
                      value: '${stats.longestStreak}',
                      label: '最长连胜',
                      color: AppTheme.accent,
                    ),
                  ],
                ),
                const SizedBox(height: 10),
                Row(
                  children: [
                    _MetricCard(
                      icon: '✅',
                      value: '${stats.totalCount}',
                      label: '累计打卡',
                      color: AppTheme.success,
                    ),
                    const SizedBox(width: 10),
                    _MetricCard(
                      icon: '📊',
                      value: '${stats.completionRate.toStringAsFixed(0)}%',
                      label: '完成率',
                      color: Color(habit.colorValue),
                    ),
                  ],
                ),

                const SizedBox(height: 24),

                // 最近7天柱状图
                const Text(
                  '📈 近7天打卡',
                  style: TextStyle(fontSize: 16, fontWeight: FontWeight.bold),
                ),
                const SizedBox(height: 12),
                Container(
                  height: 180,
                  padding: const EdgeInsets.all(16),
                  decoration: BoxDecoration(
                    color: Theme.of(context).brightness == Brightness.dark
                        ? AppTheme.bgCard
                        : Colors.white,
                    borderRadius: BorderRadius.circular(AppTheme.radiusMd),
                  ),
                  child: BarChart(
                    BarChartData(
                      alignment: BarChartAlignment.spaceAround,
                      maxY: 2,
                      barTouchData: BarTouchData(
                        touchTooltipData: BarTouchTooltipData(
                          getTooltipItem: (group, groupIndex, rod, rodIndex) {
                            return BarTooltipItem(
                              rod.toY.round() > 0 ? '已打卡' : '未打卡',
                              const TextStyle(color: Colors.white, fontSize: 12),
                            );
                          },
                        ),
                      ),
                      titlesData: FlTitlesData(
                        show: true,
                        bottomTitles: AxisTitles(
                          sideTitles: SideTitles(
                            showTitles: true,
                            getTitlesWidget: (value, meta) {
                              final day = last7Days[value.toInt()];
                              return Padding(
                                padding: const EdgeInsets.only(top: 8),
                                child: Text(
                                  '${day.day}',
                                  style: TextStyle(
                                    fontSize: 11,
                                    color: AppTheme.textSecondary,
                                  ),
                                ),
                              );
                            },
                          ),
                        ),
                        leftTitles: const AxisTitles(
                          sideTitles: SideTitles(showTitles: false),
                        ),
                        topTitles: const AxisTitles(
                          sideTitles: SideTitles(showTitles: false),
                        ),
                        rightTitles: const AxisTitles(
                          sideTitles: SideTitles(showTitles: false),
                        ),
                      ),
                      borderData: FlBorderData(show: false),
                      gridData: const FlGridData(show: false),
                      barGroups: last7Days.asMap().entries.map((entry) {
                        final idx = entry.key;
                        final day = entry.value;
                        final dateStr =
                            '${day.year}-${day.month.toString().padLeft(2, '0')}-${day.day.toString().padLeft(2, '0')}';
                        final checked = checkInSet.contains(dateStr);
                        return BarChartGroupData(
                          x: idx,
                          barRods: [
                            BarChartRodData(
                              toY: checked ? 1 : 0,
                              color: checked
                                  ? Color(habit!.colorValue)
                                  : AppTheme.bgElevated,
                              width: 24,
                              borderRadius: const BorderRadius.vertical(
                                top: Radius.circular(6),
                              ),
                            ),
                          ],
                        );
                      }).toList(),
                    ),
                  ),
                ),

                const SizedBox(height: 24),

                // 本月日历
                const Text(
                  '📅 本月打卡',
                  style: TextStyle(fontSize: 16, fontWeight: FontWeight.bold),
                ),
                const SizedBox(height: 12),
                _MonthCalendar(
                  habitColor: Color(habit.colorValue),
                  checkInDates: checkInSet,
                  year: now.year,
                  month: now.month,
                ),

                const SizedBox(height: 24),

                // 打卡记录
                if (habitCheckIns.isNotEmpty) ...[
                  const Text(
                    '📝 打卡记录',
                    style: TextStyle(fontSize: 16, fontWeight: FontWeight.bold),
                  ),
                  const SizedBox(height: 12),
                  Container(
                    decoration: BoxDecoration(
                      color: Theme.of(context).brightness == Brightness.dark
                          ? AppTheme.bgCard
                          : Colors.white,
                      borderRadius: BorderRadius.circular(AppTheme.radiusMd),
                    ),
                    child: Column(
                      children: habitCheckIns
                          .toList()
                          .reversed
                          .take(10)
                          .map((c) {
                        final date = DateTime.parse(c.date);
                        return ListTile(
                          leading: Icon(
                            Icons.check_circle,
                            color: Color(habit!.colorValue),
                            size: 20,
                          ),
                          title: Text('${date.month}月${date.day}日'),
                          subtitle: Text(c.note ?? ''),
                          trailing: Text(
                            '×${c.count}',
                            style: TextStyle(
                              color: AppTheme.textSecondary,
                              fontSize: 13,
                            ),
                          ),
                        );
                      }).toList(),
                    ),
                  ),
                ],

                const SizedBox(height: 32),
              ],
            ),
          ),
        );
      },
    );
  }
}

class _MetricCard extends StatelessWidget {
  final String icon;
  final String value;
  final String label;
  final Color color;

  const _MetricCard({
    required this.icon,
    required this.value,
    required this.label,
    required this.color,
  });

  @override
  Widget build(BuildContext context) {
    final isDark = Theme.of(context).brightness == Brightness.dark;
    return Expanded(
      child: Container(
        padding: const EdgeInsets.all(16),
        decoration: BoxDecoration(
          color: isDark ? AppTheme.bgCard : Colors.white,
          borderRadius: BorderRadius.circular(AppTheme.radiusMd),
          border: Border.all(
            color: isDark
                ? Colors.white.withOpacity(0.05)
                : Colors.black.withOpacity(0.05),
          ),
        ),
        child: Column(
          children: [
            Text(icon, style: const TextStyle(fontSize: 20)),
            const SizedBox(height: 6),
            Text(
              value,
              style: TextStyle(
                fontSize: 22,
                fontWeight: FontWeight.bold,
                color: color,
              ),
            ),
            Text(
              label,
              style: TextStyle(
                fontSize: 12,
                color: AppTheme.textSecondary,
              ),
            ),
          ],
        ),
      ),
    );
  }
}

/// 月份日历视图
class _MonthCalendar extends StatelessWidget {
  final Color habitColor;
  final Set<String> checkInDates;
  final int year;
  final int month;

  const _MonthCalendar({
    required this.habitColor,
    required this.checkInDates,
    required this.year,
    required this.month,
  });

  @override
  Widget build(BuildContext context) {
    final firstDay = DateTime(year, month, 1);
    final daysInMonth = DateTime(year, month + 1, 0).day;
    final startWeekday = firstDay.weekday % 7; // 0=周日

    return Container(
      padding: const EdgeInsets.all(16),
      decoration: BoxDecoration(
        color: Theme.of(context).brightness == Brightness.dark
            ? AppTheme.bgCard
            : Colors.white,
        borderRadius: BorderRadius.circular(AppTheme.radiusMd),
      ),
      child: Column(
        children: [
          // 星期标题
          Row(
            children: ['日', '一', '二', '三', '四', '五', '六']
                .map((d) => Expanded(
                      child: Center(
                        child: Text(
                          d,
                          style: TextStyle(
                            fontSize: 12,
                            color: AppTheme.textSecondary,
                            fontWeight: FontWeight.w500,
                          ),
                        ),
                      ),
                    ))
                .toList(),
          ),
          const SizedBox(height: 8),
          // 日期网格
          GridView.builder(
            shrinkWrap: true,
            physics: const NeverScrollableScrollPhysics(),
            gridDelegate: const SliverGridDelegateWithFixedCrossAxisCount(
              crossAxisCount: 7,
              childAspectRatio: 1,
            ),
            itemCount: startWeekday + daysInMonth,
            itemBuilder: (context, index) {
              if (index < startWeekday) {
                return const SizedBox();
              }
              final day = index - startWeekday + 1;
              final dateStr =
                  '$year-${month.toString().padLeft(2, '0')}-${day.toString().padLeft(2, '0')}';
              final checked = checkInDates.contains(dateStr);
              final isToday = _isToday(day);

              return Center(
                child: Container(
                  width: 32,
                  height: 32,
                  decoration: BoxDecoration(
                    color: checked
                        ? habitColor
                        : isToday
                            ? habitColor.withOpacity(0.2)
                            : Colors.transparent,
                    shape: BoxShape.circle,
                    border: isToday && !checked
                        ? Border.all(color: habitColor, width: 2)
                        : null,
                  ),
                  child: Center(
                    child: Text(
                      '$day',
                      style: TextStyle(
                        fontSize: 12,
                        fontWeight: checked || isToday
                            ? FontWeight.bold
                            : FontWeight.normal,
                        color: checked
                            ? Colors.white
                            : isToday
                                ? habitColor
                                : AppTheme.textSecondary,
                      ),
                    ),
                  ),
                ),
              );
            },
          ),
        ],
      ),
    );
  }

  bool _isToday(int day) {
    final now = DateTime.now();
    return now.year == year && now.month == month && now.day == day;
  }
}
