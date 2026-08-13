import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:provider/provider.dart';
import 'package:confetti/confetti.dart';
import '../models/habit.dart';
import '../providers/habit_provider.dart';
import '../providers/notification_provider.dart';
import '../theme/app_theme.dart';
import 'analyzer_screen.dart';
import 'settings_screen.dart';

class HomeScreen extends StatefulWidget {
  const HomeScreen({super.key});

  @override
  State<HomeScreen> createState() => _HomeScreenState();
}

class _HomeScreenState extends State<HomeScreen> {
  late final ConfettiController _confettiController;
  bool _hasShownConfettiToday = false;

  static final _emptyStats = HabitStats(
    currentStreak: 0,
    longestStreak: 0,
    totalCount: 0,
    completionRate: 0.0,
    checkInDates: [],
  );

  @override
  void initState() {
    super.initState();
    _confettiController =
        ConfettiController(duration: const Duration(seconds: 2));
    _checkDailyReminder();
  }

  void _checkDailyReminder() {
    WidgetsBinding.instance.addPostFrameCallback((_) {
      final provider = context.read<HabitProvider>();
      final notifProvider = context.read<NotificationProvider>();
      final todayHabits = provider.getTodayHabits();
      final notDone = todayHabits
          .where((h) => provider.getTodayCheckIn(h.id) == null)
          .toList();
      if (notDone.isNotEmpty && todayHabits.isNotEmpty) {
        notifProvider.add(
          title: '📅 今日打卡提醒',
          body: '还有 ${notDone.length} 个习惯待完成，加油！',
          emoji: '🔥',
        );
      }
    });
  }

  @override
  void dispose() {
    _confettiController.dispose();
    super.dispose();
  }

  String _getGreeting() {
    final hour = DateTime.now().hour;
    if (hour < 6) return '🌙 夜深了';
    if (hour < 9) return '🌅 早上好';
    if (hour < 12) return '☀️ 上午好';
    if (hour < 14) return '🌞 中午好';
    if (hour < 18) return '🌤️ 下午好';
    if (hour < 22) return '🌆 晚上好';
    return '🌙 夜深了';
  }

  void _onCheckIn(String habitId, bool wasAlreadyDone) {
    HapticFeedback.lightImpact();
    final provider = context.read<HabitProvider>();
    if (wasAlreadyDone) {
      provider.cancelCheckIn(habitId);
    } else {
      provider.checkIn(habitId);
      context.read<NotificationProvider>().add(
            title: '✅ 打卡成功',
            body: '继续保持！🔥',
            emoji: '🎉',
          );
    }
    final todayHabits = provider.getTodayHabits();
    final nowDone = todayHabits.where((h) {
      if (h.id == habitId) return true;
      return provider.getTodayCheckIn(h.id) != null;
    }).length;
    if (!wasAlreadyDone &&
        nowDone == todayHabits.length &&
        todayHabits.isNotEmpty &&
        !_hasShownConfettiToday) {
      _confettiController.play();
      _hasShownConfettiToday = true;
    }
  }

  @override
  Widget build(BuildContext context) {
    return Consumer2<HabitProvider, NotificationProvider>(
      builder: (context, provider, notifProvider, _) {
        if (provider.isLoading) {
          return const Center(child: CircularProgressIndicator());
        }

        final todayHabits = provider.getTodayHabits();
        final completedCount = todayHabits.where((h) {
          final check = provider.getTodayCheckIn(h.id);
          return check != null && check.count >= h.targetPerDay;
        }).length;

        final isAllDone =
            completedCount == todayHabits.length && todayHabits.isNotEmpty;
        final isPartiallyDone =
            completedCount > 0 && completedCount < todayHabits.length;
        final globalStreak = provider.globalStreak;

        // 重置彩屑标记（新的一天）
        final today = _todayString();
        final lastShown = _lastConfettiDate();
        if (lastShown != today) {
          _hasShownConfettiToday = false;
        }

        return Stack(
          children: [
            RefreshIndicator(
              onRefresh: () {
                provider.refresh();
                return Future.delayed(const Duration(milliseconds: 500));
              },
              child: CustomScrollView(
                physics: const BouncingScrollPhysics(),
                slivers: [
                  // ─── 问候语 ───
                  SliverToBoxAdapter(
                    child: _HomeHeader(
                      greeting: _getGreeting(),
                      isAllDone: isAllDone,
                      isPartiallyDone: isPartiallyDone,
                    ),
                  ),

                  // ─── 连胜卡片 ───
                  if (globalStreak > 0)
                    SliverToBoxAdapter(
                      child: _StreakCard(streak: globalStreak),
                    ),

                  // ─── 今日进度卡片 ───
                  SliverToBoxAdapter(
                    child: Padding(
                      padding: const EdgeInsets.fromLTRB(16, 0, 16, 8),
                      child: Container(
                        padding: const EdgeInsets.all(20),
                        decoration: BoxDecoration(
                          color: AppTheme.bgCard,
                          borderRadius:
                              BorderRadius.circular(AppTheme.radiusLg),
                          border:
                              Border.all(color: Colors.white.withOpacity(0.08)),
                        ),
                        child: Column(
                          children: [
                            Row(
                              mainAxisAlignment: MainAxisAlignment.spaceBetween,
                              children: [
                                const Text(
                                  '今日行动',
                                  style: TextStyle(
                                    fontSize: 16,
                                    fontWeight: FontWeight.w600,
                                  ),
                                ),
                                Text(
                                  '$completedCount/${todayHabits.length}',
                                  style: TextStyle(
                                    fontSize: 14,
                                    color: AppTheme.textSecondary,
                                  ),
                                ),
                              ],
                            ),
                            const SizedBox(height: 16),
                            SizedBox(
                              height: 120,
                              child: Center(
                                child: Stack(
                                  alignment: Alignment.center,
                                  children: [
                                    SizedBox(
                                      width: 120,
                                      height: 120,
                                      child: CircularProgressIndicator(
                                        value: todayHabits.isEmpty
                                            ? 0
                                            : completedCount /
                                                todayHabits.length,
                                        strokeWidth: 8,
                                        backgroundColor: AppTheme.bgElevated,
                                        valueColor: AlwaysStoppedAnimation(
                                          isAllDone
                                              ? AppTheme.success
                                              : AppTheme.primary,
                                        ),
                                      ),
                                    ),
                                    if (isAllDone)
                                      const Text('🎉',
                                          style: TextStyle(fontSize: 36))
                                    else
                                      Column(
                                        mainAxisSize: MainAxisSize.min,
                                        children: [
                                          Text(
                                            todayHabits.isEmpty
                                                ? '0%'
                                                : '${((completedCount / todayHabits.length) * 100).round()}%',
                                            style: const TextStyle(
                                              fontSize: 28,
                                              fontWeight: FontWeight.bold,
                                            ),
                                          ),
                                          Text(
                                            '完成',
                                            style: TextStyle(
                                              fontSize: 12,
                                              color: AppTheme.textSecondary,
                                            ),
                                          ),
                                        ],
                                      ),
                                  ],
                                ),
                              ),
                            ),
                          ],
                        ),
                      ),
                    ),
                  ),

                  // ─── 今日习惯列表 ───
                  if (todayHabits.isNotEmpty)
                    SliverPadding(
                      padding: const EdgeInsets.fromLTRB(16, 8, 16, 8),
                      sliver: SliverList(
                        delegate: SliverChildBuilderDelegate(
                          (context, index) {
                            final habit = todayHabits[index];
                            final check = provider.getTodayCheckIn(habit.id);
                            final isDone = check != null &&
                                check.count >= habit.targetPerDay;
                            final stats = provider.getHabitStats(habit.id) ?? _emptyStats;

                            return Padding(
                              padding: const EdgeInsets.only(bottom: 10),
                              child: _HabitCheckInTile(
                                habit: habit,
                                isDone: isDone,
                                stats: stats,
                                onTap: () => _onCheckIn(habit.id, isDone),
                              ),
                            );
                          },
                          childCount: todayHabits.length,
                        ),
                      ),
                    )
                  else
                    SliverToBoxAdapter(
                      child: Padding(
                        padding: const EdgeInsets.all(32),
                        child: Column(
                          children: [
                            const Text('🌱', style: TextStyle(fontSize: 48)),
                            const SizedBox(height: 16),
                            Text(
                              '还没有习惯',
                              style: TextStyle(
                                fontSize: 16,
                                color: AppTheme.textSecondary,
                              ),
                            ),
                            const SizedBox(height: 8),
                            Text(
                              '通过矛盾分析，帮你把困惑转化为行动',
                              style: TextStyle(
                                fontSize: 13,
                                color: AppTheme.textSecondary.withOpacity(0.7),
                              ),
                              textAlign: TextAlign.center,
                            ),
                            const SizedBox(height: 20),
                            ElevatedButton.icon(
                              onPressed: () => Navigator.push(
                                context,
                                MaterialPageRoute(
                                    builder: (_) => const AnalyzerScreen()),
                              ),
                              icon: const Icon(Icons.psychology, size: 18),
                              label: const Text('开始分析'),
                            ),
                          ],
                        ),
                      ),
                    ),

                  // ─── 矛盾分析入口 ───
                  SliverToBoxAdapter(
                    child: Padding(
                      padding: const EdgeInsets.fromLTRB(16, 8, 16, 24),
                      child: GestureDetector(
                        onTap: () => Navigator.push(
                          context,
                          MaterialPageRoute(
                              builder: (_) => const AnalyzerScreen()),
                        ),
                        child: Container(
                          padding: const EdgeInsets.all(20),
                          decoration: BoxDecoration(
                            gradient: const LinearGradient(
                              colors: [AppTheme.primary, AppTheme.primaryDark],
                            ),
                            borderRadius:
                                BorderRadius.circular(AppTheme.radiusLg),
                            boxShadow: [
                              BoxShadow(
                                color: AppTheme.primary.withOpacity(0.3),
                                blurRadius: 20,
                                offset: const Offset(0, 8),
                              ),
                            ],
                          ),
                          child: Row(
                            children: [
                              Container(
                                width: 56,
                                height: 56,
                                decoration: BoxDecoration(
                                  color: Colors.white.withOpacity(0.2),
                                  borderRadius: BorderRadius.circular(12),
                                ),
                                child: const Center(
                                  child: Text('⚖️',
                                      style: TextStyle(fontSize: 28)),
                                ),
                              ),
                              const SizedBox(width: 16),
                              Expanded(
                                child: Column(
                                  crossAxisAlignment: CrossAxisAlignment.start,
                                  children: [
                                    const Text(
                                      '矛盾分析器',
                                      style: TextStyle(
                                        fontSize: 18,
                                        fontWeight: FontWeight.bold,
                                      ),
                                    ),
                                    const SizedBox(height: 4),
                                    Text(
                                      '找到核心问题，转化为行动',
                                      style: TextStyle(
                                        fontSize: 14,
                                        color: Colors.white.withOpacity(0.8),
                                      ),
                                    ),
                                  ],
                                ),
                              ),
                              const Icon(Icons.arrow_forward_ios,
                                  color: Colors.white, size: 20),
                            ],
                          ),
                        ),
                      ),
                    ),
                  ),

                  // 底部安全间距
                  const SliverToBoxAdapter(child: SizedBox(height: 80)),
                ],
              ),
            ),

            // 彩屑动画
            Align(
              alignment: Alignment.topCenter,
              child: ConfettiWidget(
                confettiController: _confettiController,
                blastDirectionality: BlastDirectionality.explosive,
                particleDrag: 0.05,
                emissionFrequency: 0.05,
                numberOfParticles: 30,
                gravity: 0.1,
                colors: AppColors.confettiColors,
              ),
            ),
          ],
        );
      },
    );
  }

  String _todayString() {
    final now = DateTime.now();
    return '${now.year}-${now.month.toString().padLeft(2, '0')}-${now.day.toString().padLeft(2, '0')}';
  }

  String _lastConfettiDate() {
    // 简单用 SharedPreferences 存一下日期（懒加载，不用改 provider）
    return '';
  }
}

// ─────────────────────────────────────────────
// 习惯打卡卡片（抽出来，减少 build 方法复杂度）
// ─────────────────────────────────────────────
class _HabitCheckInTile extends StatelessWidget {
  final dynamic habit;
  final bool isDone;
  final dynamic stats;
  final VoidCallback onTap;

  const _HabitCheckInTile({
    required this.habit,
    required this.isDone,
    required this.stats,
    required this.onTap,
  });

  @override
  Widget build(BuildContext context) {
    return GestureDetector(
      onTap: onTap,
      child: AnimatedContainer(
        duration: const Duration(milliseconds: 200),
        curve: Curves.easeInOut,
        padding: const EdgeInsets.all(16),
        decoration: BoxDecoration(
          color:
              isDone ? AppTheme.success.withOpacity(0.1) : AppTheme.bgElevated,
          borderRadius: BorderRadius.circular(AppTheme.radiusMd),
          border: Border.all(
            color:
                isDone ? AppTheme.success.withOpacity(0.3) : Colors.transparent,
          ),
        ),
        child: Row(
          children: [
            // 图标
            Container(
              width: 48,
              height: 48,
              decoration: BoxDecoration(
                color: Color(habit.colorValue).withOpacity(0.2),
                borderRadius: BorderRadius.circular(12),
              ),
              child: Center(
                child: isDone
                    ? const Icon(Icons.check, color: Colors.white, size: 24)
                    : Text(habit.icon, style: const TextStyle(fontSize: 24)),
              ),
            ),
            const SizedBox(width: 12),
            // 名称 + 连胜
            Expanded(
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Text(
                    habit.name,
                    style: TextStyle(
                      fontSize: 16,
                      fontWeight: FontWeight.w500,
                      decoration: isDone ? TextDecoration.lineThrough : null,
                      color: isDone ? AppTheme.textSecondary : null,
                    ),
                  ),
                  const SizedBox(height: 4),
                  Text(
                    stats.currentStreak > 0
                        ? '🔥 ${stats.currentStreak}天'
                        : '开始你的第一次',
                    style: TextStyle(
                      fontSize: 12,
                      color: AppTheme.textSecondary,
                    ),
                  ),
                ],
              ),
            ),
            // 打卡按钮
            Container(
              width: 40,
              height: 40,
              decoration: BoxDecoration(
                color: isDone ? AppTheme.success : AppTheme.bgCard,
                shape: BoxShape.circle,
                border: isDone
                    ? null
                    : Border.all(color: Colors.white.withOpacity(0.2)),
              ),
              child: Icon(
                isDone ? Icons.check : Icons.add,
                color: isDone ? Colors.white : Color(habit.colorValue),
                size: 20,
              ),
            ),
          ],
        ),
      ),
    );
  }
}

/// 首页头部：问候语 + 通知铃铛 + 设置按钮
class _HomeHeader extends StatelessWidget {
  final String greeting;
  final bool isAllDone;
  final bool isPartiallyDone;

  const _HomeHeader({
    required this.greeting,
    required this.isAllDone,
    required this.isPartiallyDone,
  });

  @override
  Widget build(BuildContext context) {
    return Padding(
      padding: const EdgeInsets.fromLTRB(16, 16, 16, 8),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Row(
            mainAxisAlignment: MainAxisAlignment.spaceBetween,
            children: [
              Text(
                greeting,
                style: TextStyle(
                  fontSize: 14,
                  color: AppTheme.textSecondary,
                ),
              ),
              Row(
                children: [
                  // 通知铃铛
                  Consumer<NotificationProvider>(
                    builder: (ctx, notif, _) {
                      return IconButton(
                        icon: Stack(
                          clipBehavior: Clip.none,
                          children: [
                            Icon(
                              notif.hasUnread
                                  ? Icons.notifications
                                  : Icons.notifications_outlined,
                              color: AppTheme.textSecondary,
                            ),
                            if (notif.hasUnread)
                              Positioned(
                                right: -2,
                                top: -2,
                                child: Container(
                                  width: 8,
                                  height: 8,
                                  decoration: const BoxDecoration(
                                    color: AppTheme.primary,
                                    shape: BoxShape.circle,
                                  ),
                                ),
                              ),
                          ],
                        ),
                        onPressed: () {
                          notif.clearAll();
                          ScaffoldMessenger.of(context).showSnackBar(
                            const SnackBar(
                              content: Text('通知已清空'),
                              duration: Duration(seconds: 1),
                            ),
                          );
                        },
                      );
                    },
                  ),
                  // 设置
                  IconButton(
                    icon: const Icon(Icons.settings_outlined,
                        color: AppTheme.textSecondary),
                    onPressed: () => Navigator.push(
                      context,
                      MaterialPageRoute(builder: (_) => const SettingsScreen()),
                    ),
                  ),
                ],
              ),
            ],
          ),
          const SizedBox(height: 4),
          Text(
            isAllDone
                ? '太棒了！今天全部完成 🎉'
                : isPartiallyDone
                    ? '继续加油 💪'
                    : '开始今天的行动吧',
            style: const TextStyle(
              fontSize: 24,
              fontWeight: FontWeight.bold,
            ),
          ),
        ],
      ),
    );
  }
}

/// 连胜卡片
class _StreakCard extends StatelessWidget {
  final int streak;

  const _StreakCard({required this.streak});

  @override
  Widget build(BuildContext context) {
    return Padding(
      padding: const EdgeInsets.fromLTRB(16, 0, 16, 16),
      child: Container(
        padding: const EdgeInsets.all(20),
        decoration: BoxDecoration(
          gradient: LinearGradient(
            colors: [
              Colors.white.withOpacity(0.08),
              Colors.white.withOpacity(0.03),
            ],
          ),
          borderRadius: BorderRadius.circular(AppTheme.radiusLg),
          border: Border.all(color: Colors.white.withOpacity(0.1)),
        ),
        child: Row(
          children: [
            const Text('🔥', style: TextStyle(fontSize: 48)),
            const SizedBox(width: 16),
            Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Text(
                  '当前连胜',
                  style: TextStyle(
                    fontSize: 14,
                    color: AppTheme.textSecondary,
                  ),
                ),
                Row(
                  crossAxisAlignment: CrossAxisAlignment.end,
                  children: [
                    ShaderMask(
                      shaderCallback: (bounds) => const LinearGradient(
                        colors: [Color(0xFFE85D4C), Color(0xFFC94A3A)],
                      ).createShader(bounds),
                      child: Text(
                        '$streak',
                        style: const TextStyle(
                          fontSize: 40,
                          fontWeight: FontWeight.bold,
                          color: Colors.white,
                        ),
                      ),
                    ),
                    Padding(
                      padding: const EdgeInsets.only(bottom: 8, left: 4),
                      child: Text(
                        '天',
                        style: TextStyle(
                          fontSize: 16,
                          color: AppTheme.textSecondary,
                        ),
                      ),
                    ),
                  ],
                ),
              ],
            ),
          ],
        ),
      ),
    );
  }
}
