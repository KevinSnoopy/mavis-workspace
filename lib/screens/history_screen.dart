import 'package:flutter/material.dart';
import 'package:provider/provider.dart';
import '../providers/habit_provider.dart';
import '../models/habit.dart';
import '../theme/app_theme.dart';
import 'settings_screen.dart';
import 'habit_detail_screen.dart';

class HistoryScreen extends StatelessWidget {
  const HistoryScreen({super.key});

  @override
  Widget build(BuildContext context) {
    return DefaultTabController(
      length: 4,
      child: Scaffold(
        appBar: AppBar(
          title: const Text('📊 记录'),
          actions: [
            IconButton(
              icon: const Icon(Icons.settings),
              onPressed: () => Navigator.push(
                context,
                MaterialPageRoute(builder: (_) => const SettingsScreen()),
              ),
            ),
          ],
          bottom: const TabBar(
            tabs: [
              Tab(text: '概览'),
              Tab(text: '成就'),
              Tab(text: '分析'),
              Tab(text: '日历'),
            ],
          ),
        ),
        body: const TabBarView(
          children: [
            _OverviewTab(),
            _AchievementsTab(),
            _InsightsTab(),
            _CalendarTab(),
          ],
        ),
      ),
    );
  }
}

// ─────────────────────────────────────────────
// 概览 Tab
// ─────────────────────────────────────────────
class _OverviewTab extends StatelessWidget {
  const _OverviewTab();

  static final _emptyStats = HabitStats(
    currentStreak: 0,
    longestStreak: 0,
    totalCount: 0,
    completionRate: 0.0,
    checkInDates: [],
  );

  @override
  Widget build(BuildContext context) {
    return Consumer<HabitProvider>(
      builder: (context, provider, _) {
        final activeHabits = provider.habits.where((h) => !h.archived).toList();
        final totalCheckIns = provider.checkIns.length;
        final totalAchievements = provider.achievements.length;
        final todayHabits = provider.getTodayHabits();
        final completedToday = todayHabits.where((h) {
          return provider.getTodayCheckIn(h.id) != null;
        }).length;
        final globalStreak = provider.globalStreak;

        return SingleChildScrollView(
          padding: const EdgeInsets.all(16),
          physics: const BouncingScrollPhysics(),
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              // 关键指标卡片
              Row(
                children: [
                  Expanded(
                    child: _StatCard(
                      icon: '🔥',
                      value: '$globalStreak',
                      label: '当前连胜',
                      color: AppTheme.accent,
                    ),
                  ),
                  const SizedBox(width: 12),
                  Expanded(
                    child: _StatCard(
                      icon: '✅',
                      value: '$totalCheckIns',
                      label: '累计打卡',
                      color: AppTheme.success,
                    ),
                  ),
                ],
              ),
              const SizedBox(height: 12),
              Row(
                children: [
                  Expanded(
                    child: _StatCard(
                      icon: '✨',
                      value: '${activeHabits.length}',
                      label: '进行中习惯',
                      color: AppTheme.primary,
                    ),
                  ),
                  const SizedBox(width: 12),
                  Expanded(
                    child: _StatCard(
                      icon: '🏆',
                      value: '$totalAchievements',
                      label: '已获成就',
                      color: AppTheme.accent,
                    ),
                  ),
                ],
              ),

              const SizedBox(height: 24),

              // 今日进度
              if (todayHabits.isNotEmpty) ...[
                const Text(
                  '📅 今日进度',
                  style: TextStyle(
                    fontSize: 16,
                    fontWeight: FontWeight.bold,
                  ),
                ),
                const SizedBox(height: 12),
                Container(
                  padding: const EdgeInsets.all(20),
                  decoration: BoxDecoration(
                    color: Theme.of(context).brightness == Brightness.dark
                        ? AppTheme.bgCard
                        : Colors.white,
                    borderRadius: BorderRadius.circular(AppTheme.radiusMd),
                  ),
                  child: Row(
                    children: [
                      Expanded(
                        child: Column(
                          crossAxisAlignment: CrossAxisAlignment.start,
                          children: [
                            Text(
                              '$completedToday / ${todayHabits.length}',
                              style: const TextStyle(
                                fontSize: 28,
                                fontWeight: FontWeight.bold,
                              ),
                            ),
                            Text(
                              '今日完成',
                              style: TextStyle(
                                fontSize: 13,
                                color: AppTheme.textSecondary,
                              ),
                            ),
                          ],
                        ),
                      ),
                      SizedBox(
                        width: 80,
                        height: 80,
                        child: Stack(
                          alignment: Alignment.center,
                          children: [
                            CircularProgressIndicator(
                              value: completedToday / todayHabits.length,
                              strokeWidth: 6,
                              backgroundColor: AppTheme.bgElevated,
                              valueColor: AlwaysStoppedAnimation(
                                completedToday == todayHabits.length
                                    ? AppTheme.success
                                    : AppTheme.primary,
                              ),
                            ),
                            Text(
                              '${((completedToday / todayHabits.length) * 100).round()}%',
                              style: const TextStyle(
                                fontSize: 16,
                                fontWeight: FontWeight.bold,
                              ),
                            ),
                          ],
                        ),
                      ),
                    ],
                  ),
                ),
                const SizedBox(height: 24),
              ],

              // 习惯进度图
              if (activeHabits.isNotEmpty) ...[
                const Text(
                  '📈 习惯完成率',
                  style: TextStyle(
                    fontSize: 16,
                    fontWeight: FontWeight.bold,
                  ),
                ),
                const SizedBox(height: 12),
                Container(
                  padding: const EdgeInsets.all(16),
                  decoration: BoxDecoration(
                    color: Theme.of(context).brightness == Brightness.dark
                        ? AppTheme.bgCard
                        : Colors.white,
                    borderRadius: BorderRadius.circular(AppTheme.radiusMd),
                  ),
                  child: Column(
                    children: activeHabits.take(5).map((habit) {
                      final stats = provider.getHabitStats(habit.id) ?? _emptyStats;
                      return Padding(
                        padding: const EdgeInsets.only(bottom: 12),
                        child: GestureDetector(
                          onTap: () => Navigator.push(
                            context,
                            MaterialPageRoute(
                              builder: (_) =>
                                  HabitDetailScreen(habitId: habit.id),
                            ),
                          ),
                          child: Row(
                            children: [
                              Text(habit.icon,
                                  style: const TextStyle(fontSize: 18)),
                              const SizedBox(width: 8),
                              Expanded(
                                child: Column(
                                  crossAxisAlignment: CrossAxisAlignment.start,
                                  children: [
                                    Row(
                                      mainAxisAlignment:
                                          MainAxisAlignment.spaceBetween,
                                      children: [
                                        Text(
                                          habit.name,
                                          style: const TextStyle(
                                            fontSize: 13,
                                            fontWeight: FontWeight.w500,
                                          ),
                                        ),
                                        Text(
                                          '${stats.completionRate.toStringAsFixed(0)}%',
                                          style: TextStyle(
                                            fontSize: 13,
                                            color: AppTheme.textSecondary,
                                          ),
                                        ),
                                      ],
                                    ),
                                    const SizedBox(height: 4),
                                    ClipRRect(
                                      borderRadius: BorderRadius.circular(4),
                                      child: LinearProgressIndicator(
                                        value: stats.completionRate / 100,
                                        backgroundColor: AppTheme.bgElevated,
                                        valueColor: AlwaysStoppedAnimation(
                                          Color(habit.colorValue),
                                        ),
                                        minHeight: 6,
                                      ),
                                    ),
                                  ],
                                ),
                              ),
                            ],
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
        );
      },
    );
  }
}

// ─────────────────────────────────────────────
// 成就 Tab
// ─────────────────────────────────────────────
class _AchievementsTab extends StatelessWidget {
  const _AchievementsTab();

  @override
  Widget build(BuildContext context) {
    return Consumer<HabitProvider>(
      builder: (context, provider, _) {
        final achievements = provider.achievements;

        if (achievements.isEmpty) {
          return Center(
            child: Column(
              mainAxisAlignment: MainAxisAlignment.center,
              children: [
                const Text('🏆', style: TextStyle(fontSize: 56)),
                const SizedBox(height: 16),
                Text(
                  '还没有成就',
                  style: TextStyle(
                    fontSize: 16,
                    color: AppTheme.textSecondary,
                  ),
                ),
                const SizedBox(height: 8),
                Text(
                  '连续打卡7天解锁第一个成就',
                  style: TextStyle(
                    fontSize: 13,
                    color: AppTheme.textSecondary.withOpacity(0.7),
                  ),
                ),
              ],
            ),
          );
        }

        // 按时间分组（每个分组 = header + N 个成就卡片）
        final groups = <_AchievementGroup>[];
        final grouped = <String, List<dynamic>>{};
        for (final a in achievements) {
          final key = _formatDateGroup(a.unlockedAt);
          grouped.putIfAbsent(key, () => []).add(a);
        }
        for (final entry in grouped.entries) {
          groups.add(_AchievementGroup(dateLabel: entry.key, items: entry.value));
        }

        return ListView.builder(
          padding: const EdgeInsets.all(16),
          physics: const BouncingScrollPhysics(),
          itemCount: groups.length,
          itemBuilder: (context, groupIdx) {
            final group = groups[groupIdx];
            return Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Padding(
                  padding: const EdgeInsets.symmetric(vertical: 8),
                  child: Text(
                    group.dateLabel,
                    style: TextStyle(
                      fontSize: 13,
                      fontWeight: FontWeight.w600,
                      color: AppTheme.textSecondary,
                    ),
                  ),
                ),
                ...group.items.map((a) => Container(
                      margin: const EdgeInsets.only(bottom: 10),
                      padding: const EdgeInsets.all(16),
                      decoration: BoxDecoration(
                        color: Theme.of(context).brightness == Brightness.dark
                            ? AppTheme.bgCard
                            : Colors.white,
                        borderRadius: BorderRadius.circular(AppTheme.radiusMd),
                        border: Border.all(
                          color: AppTheme.accent.withOpacity(0.2),
                        ),
                      ),
                      child: Row(
                        children: [
                          Text(a.icon, style: const TextStyle(fontSize: 32)),
                          const SizedBox(width: 12),
                          Expanded(
                            child: Column(
                              crossAxisAlignment: CrossAxisAlignment.start,
                              children: [
                                Text(
                                  a.name,
                                  style: const TextStyle(
                                    fontSize: 16,
                                    fontWeight: FontWeight.bold,
                                  ),
                                ),
                                Text(
                                  a.description,
                                  style: TextStyle(
                                    fontSize: 13,
                                    color: AppTheme.textSecondary,
                                  ),
                                ),
                              ],
                            ),
                          ),
                        ],
                      ),
                    )),
              ],
            );
          },
        );
      },
    );
  }

  String _formatDateGroup(DateTime date) {
    final now = DateTime.now();
    final diff = now.difference(date).inDays;
    if (diff == 0) return '今天';
    if (diff == 1) return '昨天';
    if (diff < 7) return '$diff天前';
    return '${date.month}月${date.day}日';
  }
}

/// 成就分组（用于 ListView.builder 懒加载）
class _AchievementGroup {
  final String dateLabel;
  final List<dynamic> items;
  _AchievementGroup({required this.dateLabel, required this.items});
}

// ─────────────────────────────────────────────
// 分析记录 Tab
// ─────────────────────────────────────────────
class _InsightsTab extends StatelessWidget {
  const _InsightsTab();

  @override
  Widget build(BuildContext context) {
    return Consumer<HabitProvider>(
      builder: (context, provider, _) {
        final insights = provider.analysisInsights;

        if (insights.isEmpty) {
          return Center(
            child: Column(
              mainAxisAlignment: MainAxisAlignment.center,
              children: [
                const Text('⚖️', style: TextStyle(fontSize: 56)),
                const SizedBox(height: 16),
                Text(
                  '还没有分析记录',
                  style: TextStyle(
                    fontSize: 16,
                    color: AppTheme.textSecondary,
                  ),
                ),
                const SizedBox(height: 8),
                Text(
                  '去「分析」页描述你的困惑',
                  style: TextStyle(
                    fontSize: 13,
                    color: AppTheme.textSecondary.withOpacity(0.7),
                  ),
                ),
              ],
            ),
          );
        }

        return ListView.builder(
          padding: const EdgeInsets.all(16),
          physics: const BouncingScrollPhysics(),
          itemCount: insights.length,
          itemBuilder: (context, index) {
            final insight = insights[index];
            return Container(
              margin: const EdgeInsets.only(bottom: 12),
              padding: const EdgeInsets.all(16),
              decoration: BoxDecoration(
                color: Theme.of(context).brightness == Brightness.dark
                    ? AppTheme.bgCard
                    : Colors.white,
                borderRadius: BorderRadius.circular(AppTheme.radiusMd),
                border: const Border(
                  left: BorderSide(color: AppTheme.primary, width: 3),
                ),
              ),
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Row(
                    children: [
                      Container(
                        padding: const EdgeInsets.symmetric(
                          horizontal: 8,
                          vertical: 4,
                        ),
                        decoration: BoxDecoration(
                          color: AppTheme.primary.withOpacity(0.15),
                          borderRadius: BorderRadius.circular(8),
                        ),
                        child: const Text(
                          '主要矛盾',
                          style: TextStyle(
                            fontSize: 12,
                            color: AppTheme.primary,
                            fontWeight: FontWeight.w500,
                          ),
                        ),
                      ),
                      const Spacer(),
                      Text(
                        _formatDate(insight.createdAt),
                        style: TextStyle(
                          fontSize: 12,
                          color: AppTheme.textSecondary,
                        ),
                      ),
                    ],
                  ),
                  const SizedBox(height: 10),
                  Text(
                    insight.mainConflict,
                    style: const TextStyle(
                      fontSize: 17,
                      fontWeight: FontWeight.bold,
                    ),
                  ),
                  const SizedBox(height: 8),
                  Text(
                    '${insight.suggestedHabits.length} 个行动建议',
                    style: TextStyle(
                      fontSize: 13,
                      color: AppTheme.textSecondary,
                    ),
                  ),
                  if (insight.suggestedHabits.isNotEmpty) ...[
                    const SizedBox(height: 8),
                    Wrap(
                      spacing: 6,
                      runSpacing: 6,
                      children: insight.suggestedHabits
                          .map((h) => Container(
                                padding: const EdgeInsets.symmetric(
                                  horizontal: 8,
                                  vertical: 4,
                                ),
                                decoration: BoxDecoration(
                                  color: AppTheme.bgElevated,
                                  borderRadius: BorderRadius.circular(12),
                                ),
                                child: Text(
                                  h,
                                  style: const TextStyle(fontSize: 12),
                                ),
                              ))
                          .toList(),
                    ),
                  ],
                ],
              ),
            );
          },
        );
      },
    );
  }

  String _formatDate(DateTime date) {
    final now = DateTime.now();
    final diff = now.difference(date).inDays;
    if (diff == 0) return '今天';
    if (diff == 1) return '昨天';
    if (diff < 7) return '$diff天前';
    return '${date.month}/${date.day}';
  }
}

// ─────────────────────────────────────────────
// 统计卡片
// ─────────────────────────────────────────────
class _StatCard extends StatelessWidget {
  final String icon;
  final String value;
  final String label;
  final Color color;

  const _StatCard({
    required this.icon,
    required this.value,
    required this.label,
    required this.color,
  });

  @override
  Widget build(BuildContext context) {
    final isDark = Theme.of(context).brightness == Brightness.dark;
    return Container(
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
          Text(icon, style: const TextStyle(fontSize: 24)),
          const SizedBox(height: 8),
          Text(
            value,
            style: TextStyle(
              fontSize: 24,
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
    );
  }
}

/// 全局日历热力图 Tab
class _CalendarTab extends StatefulWidget {
  const _CalendarTab();

  @override
  State<_CalendarTab> createState() => _CalendarTabState();
}

class _CalendarTabState extends State<_CalendarTab> {
  late DateTime _currentMonth;

  @override
  void initState() {
    super.initState();
    _currentMonth = DateTime(DateTime.now().year, DateTime.now().month);
  }

  void _prevMonth() {
    setState(() {
      _currentMonth = DateTime(_currentMonth.year, _currentMonth.month - 1);
    });
  }

  void _nextMonth() {
    setState(() {
      _currentMonth = DateTime(_currentMonth.year, _currentMonth.month + 1);
    });
  }

  @override
  Widget build(BuildContext context) {
    return Consumer<HabitProvider>(
      builder: (context, provider, _) {
        final allDates = provider.checkIns.map((c) => c.date).toSet();

        return ListView(
          padding: const EdgeInsets.all(16),
          children: [
            // 月份导航
            Row(
              mainAxisAlignment: MainAxisAlignment.spaceBetween,
              children: [
                IconButton(
                  icon: const Icon(Icons.chevron_left),
                  onPressed: _prevMonth,
                ),
                Text(
                  '${_currentMonth.year}年${_currentMonth.month}月',
                  style: const TextStyle(
                      fontSize: 16, fontWeight: FontWeight.w600),
                ),
                IconButton(
                  icon: const Icon(Icons.chevron_right),
                  onPressed: _nextMonth,
                ),
              ],
            ),
            const SizedBox(height: 12),

            // 星期标题
            Row(
              children: ['一', '二', '三', '四', '五', '六', '日']
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

            // 日历网格
            _buildCalendarGrid(_currentMonth, allDates),
            const SizedBox(height: 20),

            // 图例
            Row(
              mainAxisAlignment: MainAxisAlignment.center,
              children: [
                _legendItem(AppTheme.bgElevated, '未打卡'),
                const SizedBox(width: 16),
                _legendItem(AppTheme.primary.withOpacity(0.3), '有打卡'),
                const SizedBox(width: 16),
                _legendItem(AppTheme.primary, '全部完成'),
              ],
            ),
          ],
        );
      },
    );
  }

  Widget _buildCalendarGrid(DateTime month, Set<String> checkInDates) {
    final firstDay = DateTime(month.year, month.month, 1);
    // 周一=1，周日=7
    int startWeekday = firstDay.weekday; // 1=Mon, 7=Sun
    final daysInMonth = DateTime(month.year, month.month + 1, 0).day;
    final today = DateTime.now();
    final todayStr =
        '${today.year}-${today.month.toString().padLeft(2, '0')}-${today.day.toString().padLeft(2, '0')}';

    // 计算网格行数
    final totalCells = startWeekday - 1 + daysInMonth;
    final rows = (totalCells / 7).ceil();

    return Column(
      children: List.generate(rows, (row) {
        return Padding(
          padding: const EdgeInsets.only(bottom: 4),
          child: Row(
            children: List.generate(7, (col) {
              final cellIndex = row * 7 + col;
              final dayNum = cellIndex - (startWeekday - 1) + 1;

              if (dayNum < 1 || dayNum > daysInMonth) {
                return const Expanded(child: SizedBox(height: 40));
              }

              final dateStr =
                  '${month.year}-${month.month.toString().padLeft(2, '0')}-${dayNum.toString().padLeft(2, '0')}';
              final hasCheckIn = checkInDates.contains(dateStr);
              final isToday = dateStr == todayStr;
              final isFuture = dateStr.compareTo(todayStr) > 0;

              // 计算该天有几个习惯
              final habitsOnDay = _getHabitsForDay(dateStr, context);

              return Expanded(
                child: GestureDetector(
                  onTap: isFuture
                      ? null
                      : () => _showDayDetail(dateStr, habitsOnDay),
                  child: Container(
                    height: 40,
                    margin: const EdgeInsets.all(1),
                    decoration: BoxDecoration(
                      color: isFuture
                          ? AppTheme.bgElevated.withOpacity(0.3)
                          : hasCheckIn
                              ? AppTheme.primary.withOpacity(0.25)
                              : AppTheme.bgElevated,
                      borderRadius: BorderRadius.circular(6),
                      border: isToday
                          ? Border.all(color: AppTheme.primary, width: 1.5)
                          : null,
                    ),
                    child: Center(
                      child: Column(
                        mainAxisAlignment: MainAxisAlignment.center,
                        children: [
                          Text(
                            '$dayNum',
                            style: TextStyle(
                              fontSize: 12,
                              color: isFuture
                                  ? AppTheme.textSecondary.withOpacity(0.4)
                                  : isToday
                                      ? AppTheme.primary
                                      : AppTheme.textSecondary,
                              fontWeight:
                                  isToday ? FontWeight.bold : FontWeight.normal,
                            ),
                          ),
                          if (hasCheckIn && !isFuture)
                            Container(
                              width: 4,
                              height: 4,
                              decoration: BoxDecoration(
                                color: AppTheme.primary,
                                shape: BoxShape.circle,
                              ),
                            ),
                        ],
                      ),
                    ),
                  ),
                ),
              );
            }),
          ),
        );
      }),
    );
  }

  List<Habit> _getHabitsForDay(String dateStr, BuildContext context) {
    final provider = context.read<HabitProvider>();
    return provider.habits.where((h) {
      return provider.checkIns
          .any((c) => c.habitId == h.id && c.date == dateStr);
    }).toList();
  }

  void _showDayDetail(String dateStr, List<Habit> habits) {
    final provider = context.read<HabitProvider>();
    final allHabits = provider.habits;
    final checkedIds = habits.map((h) => h.id).toSet();
    final notDone = allHabits.where((h) => !checkedIds.contains(h.id)).toList();

    showModalBottomSheet(
      context: context,
      backgroundColor: AppTheme.bgCard,
      shape: const RoundedRectangleBorder(
        borderRadius: BorderRadius.vertical(top: Radius.circular(20)),
      ),
      builder: (ctx) => Padding(
        padding: const EdgeInsets.all(20),
        child: Column(
          mainAxisSize: MainAxisSize.min,
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Text(
              dateStr,
              style: const TextStyle(fontSize: 18, fontWeight: FontWeight.bold),
            ),
            const SizedBox(height: 16),
            if (habits.isNotEmpty) ...[
              const Text('✅ 已完成',
                  style: TextStyle(fontWeight: FontWeight.w600)),
              const SizedBox(height: 8),
              ...habits.map((h) => Padding(
                    padding: const EdgeInsets.only(bottom: 4),
                    child: Row(children: [
                      Text(h.icon, style: const TextStyle(fontSize: 16)),
                      const SizedBox(width: 8),
                      Text(h.name),
                    ]),
                  )),
              const SizedBox(height: 12),
            ],
            if (notDone.isNotEmpty) ...[
              const Text('❌ 未完成',
                  style: TextStyle(fontWeight: FontWeight.w600)),
              const SizedBox(height: 8),
              ...notDone.map((h) => Padding(
                    padding: const EdgeInsets.only(bottom: 4),
                    child: Row(children: [
                      Text(h.icon, style: const TextStyle(fontSize: 16)),
                      const SizedBox(width: 8),
                      Text(h.name,
                          style: TextStyle(color: AppTheme.textSecondary)),
                    ]),
                  )),
            ],
            if (habits.isEmpty && notDone.isEmpty)
              const Text('暂无数据', style: TextStyle(color: Colors.grey)),
            const SizedBox(height: 16),
          ],
        ),
      ),
    );
  }

  Widget _legendItem(Color color, String label) {
    return Row(
      children: [
        Container(
          width: 12,
          height: 12,
          decoration: BoxDecoration(
            color: color,
            borderRadius: BorderRadius.circular(3),
          ),
        ),
        const SizedBox(width: 4),
        Text(
          label,
          style: TextStyle(fontSize: 11, color: AppTheme.textSecondary),
        ),
      ],
    );
  }
}
