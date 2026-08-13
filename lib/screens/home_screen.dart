import 'dart:math' as math;
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:provider/provider.dart';
import 'package:confetti/confetti.dart';
import 'package:shared_preferences/shared_preferences.dart';
import '../models/habit.dart';
import '../providers/habit_provider.dart';
import '../providers/notification_provider.dart';
import '../theme/app_theme.dart';
import 'analyzer_screen.dart';
import 'habits_screen.dart';
import 'settings_screen.dart';

/// 渐变圆弧绘制器
class _GradientArcPainter extends CustomPainter {
  final double progress;
  final Gradient gradient;
  final Color bgColor;
  final double strokeWidth;

  _GradientArcPainter({
    required this.progress,
    required this.gradient,
    required this.bgColor,
    required this.strokeWidth,
  });

  @override
  void paint(Canvas canvas, Size size) {
    final center = Offset(size.width / 2, size.height / 2);
    final radius = (size.width - strokeWidth) / 2;
    const startAngle = -math.pi / 2;
    final sweepAngle = 2 * math.pi * progress;

    // 背景弧
    final bgPaint = Paint()
      ..color = bgColor
      ..style = PaintingStyle.stroke
      ..strokeWidth = strokeWidth
      ..strokeCap = StrokeCap.round;
    canvas.drawCircle(center, radius, bgPaint);

    // 渐变前景弧
    final rect = Rect.fromCircle(center: center, radius: radius);
    final paint = Paint()
      ..shader = gradient.createShader(rect)
      ..style = PaintingStyle.stroke
      ..strokeWidth = strokeWidth
      ..strokeCap = StrokeCap.round;
    canvas.drawArc(rect, startAngle, sweepAngle, false, paint);
  }

  @override
  bool shouldRepaint(covariant _GradientArcPainter old) =>
      old.progress != progress;
}

/// 动画 FractionallySizedBox（用于进度条宽度动画）
class AnimatedFractionallySizedBox extends ImplicitlyAnimatedWidget {
  final double widthFactor;
  final Widget child;

  const AnimatedFractionallySizedBox({
    super.key,
    required this.widthFactor,
    required this.child,
    required super.duration,
    super.curve,
  });

  @override
  ImplicitlyAnimatedWidgetState<AnimatedFractionallySizedBox> createState() =>
      _AnimatedFractionallySizedBoxState();
}

class _AnimatedFractionallySizedBoxState
    extends ImplicitlyAnimatedWidgetState<AnimatedFractionallySizedBox> {
  Tween<double>? _widthFactor;

  @override
  void forEachTween(TweenVisitor<dynamic> visitor) {
    _widthFactor = visitor(
      _widthFactor,
      widget.widthFactor,
      (dynamic v) => Tween<double>(begin: v as double),
    ) as Tween<double>?;
  }

  @override
  Widget build(BuildContext context) {
    return LayoutBuilder(
      builder: (context, constraints) {
        return SizedBox(
          width: constraints.maxWidth * (_widthFactor?.evaluate(animation) ?? 0),
          child: widget.child,
        );
      },
    );
  }
}

class HomeScreen extends StatefulWidget {
  const HomeScreen({super.key});

  @override
  State<HomeScreen> createState() => _HomeScreenState();
}

class _HomeScreenState extends State<HomeScreen> {
  late final ConfettiController _confettiController;
  bool _hasShownConfettiToday = false;
  String _lastConfettiDate = '';

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
    _loadLastConfettiDate();
  }

  Future<void> _loadLastConfettiDate() async {
    try {
      final prefs = await SharedPreferences.getInstance();
      final saved = prefs.getString('_last_confetti_date') ?? '';
      if (mounted) {
        setState(() => _lastConfettiDate = saved);
      }
    } catch (_) {}
  }

  Future<void> _saveLastConfettiDate() async {
    try {
      final prefs = await SharedPreferences.getInstance();
      await prefs.setString('_last_confetti_date', _todayString());
    } catch (_) {}
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
      _saveLastConfettiDate();
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
        if (_lastConfettiDate != today) {
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

                  // ─── 连胜卡片（永远显示）───
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
                        child: Row(
                          children: [
                            Expanded(
                              child: Column(
                                crossAxisAlignment: CrossAxisAlignment.start,
                                children: [
                                  Row(
                                    mainAxisAlignment:
                                        MainAxisAlignment.spaceBetween,
                                    children: [
                                      const Text(
                                        '今日行动',
                                        style: TextStyle(
                                          fontSize: 15,
                                          fontWeight: FontWeight.w600,
                                        ),
                                      ),
                                      Text(
                                        '$completedCount/${todayHabits.length}',
                                        style: TextStyle(
                                          fontSize: 13,
                                          fontWeight: FontWeight.w500,
                                          color: isAllDone
                                              ? AppTheme.success
                                              : AppTheme.textSecondary,
                                        ),
                                      ),
                                    ],
                                  ),
                                  const SizedBox(height: 4),
                                  Text(
                                    isAllDone
                                        ? '全部完成，太棒了！'
                                        : isPartiallyDone
                                            ? '继续坚持，马上就完成了'
                                            : '开始今天的第一个习惯吧',
                                    style: TextStyle(
                                      fontSize: 12,
                                      color: AppTheme.textSecondary,
                                    ),
                                  ),
                                  const SizedBox(height: 16),
                                  // 进度条版本
                                  ClipRRect(
                                    borderRadius: BorderRadius.circular(6),
                                    child: SizedBox(
                                      height: 8,
                                      child: Stack(
                                        children: [
                                          Container(
                                            decoration: BoxDecoration(
                                              color: AppTheme.bgElevated,
                                              borderRadius:
                                                  BorderRadius.circular(6),
                                            ),
                                          ),
                                          AnimatedFractionallySizedBox(
                                            duration: const Duration(
                                                milliseconds: 500),
                                            curve: Curves.easeOutCubic,
                                            widthFactor: todayHabits.isEmpty
                                                ? 0
                                                : completedCount /
                                                    todayHabits.length,
                                            child: Container(
                                              decoration: BoxDecoration(
                                                gradient:
                                                    isAllDone || completedCount > 0
                                                        ? AppTheme
                                                            .successGradient
                                                        : AppTheme.primaryGradient,
                                                borderRadius:
                                                    BorderRadius.circular(6),
                                              ),
                                            ),
                                          ),
                                        ],
                                      ),
                                    ),
                                  ),
                                ],
                              ),
                            ),
                            const SizedBox(width: 24),
                            // 环形进度
                            SizedBox(
                              width: 80,
                              height: 80,
                              child: TweenAnimationBuilder<double>(
                                tween: Tween(
                                  begin: 0,
                                  end: todayHabits.isEmpty
                                      ? 0.0
                                      : completedCount / todayHabits.length,
                                ),
                                duration:
                                    const Duration(milliseconds: 800),
                                curve: Curves.easeOutCubic,
                                builder: (context, value, child) {
                                  return CustomPaint(
                                    painter: _GradientArcPainter(
                                      progress: value,
                                      gradient: isAllDone
                                          ? AppTheme.successGradient
                                          : AppTheme.primaryGradient,
                                      bgColor: AppTheme.bgElevated,
                                      strokeWidth: 6,
                                    ),
                                    child: Center(
                                      child: isAllDone
                                          ? const Text('🎉',
                                              style: TextStyle(fontSize: 28))
                                          : TweenAnimationBuilder<int>(
                                              tween: IntTween(
                                                begin: 0,
                                                end: todayHabits.isEmpty
                                                    ? 0
                                                    : (value * 100).round(),
                                              ),
                                              duration: const Duration(
                                                  milliseconds: 800),
                                              curve: Curves.easeOutCubic,
                                              builder: (context, val, __) =>
                                                  Text(
                                                '$val%',
                                                style: TextStyle(
                                                  fontSize: 17,
                                                  fontWeight: FontWeight.bold,
                                                  color: isAllDone
                                                      ? AppTheme.success
                                                      : null,
                                                ),
                                              ),
                                            ),
                                    ),
                                  );
                                },
                              ),
                            ),
                          ],
                        ),
                      ),
                    ),
                  ),

                  // ─── 90天热力图 ───
                  SliverToBoxAdapter(
                    child: _HeatmapCard(
                      checkIns: provider.checkIns,
                      habits: provider.habits,
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
                            // 装饰性图标
                            Container(
                              width: 80,
                              height: 80,
                              decoration: BoxDecoration(
                                gradient: AppTheme.primaryGradient,
                                borderRadius: BorderRadius.circular(24),
                                boxShadow: [
                                  BoxShadow(
                                    color: AppTheme.primary.withOpacity(0.3),
                                    blurRadius: 24,
                                    offset: const Offset(0, 8),
                                  ),
                                ],
                              ),
                              child: const Center(
                                child: Text('⚡',
                                    style: TextStyle(fontSize: 36)),
                              ),
                            ),
                            const SizedBox(height: 24),
                            const Text(
                              '从一个小习惯开始',
                              style: TextStyle(
                                fontSize: 20,
                                fontWeight: FontWeight.bold,
                              ),
                            ),
                            const SizedBox(height: 8),
                            Text(
                              '好的习惯，会在时间里开花结果',
                              style: TextStyle(
                                fontSize: 14,
                                color: AppTheme.textSecondary,
                              ),
                              textAlign: TextAlign.center,
                            ),
                            const SizedBox(height: 28),
                            // 行动按钮
                            Row(
                              mainAxisAlignment: MainAxisAlignment.center,
                              children: [
                                ElevatedButton.icon(
                                  onPressed: () => Navigator.push(
                                    context,
                                    MaterialPageRoute(
                                        builder: (_) => const HabitsScreen()),
                                  ),
                                  icon: const Icon(Icons.add, size: 18),
                                  label: const Text('创建习惯'),
                                ),
                                const SizedBox(width: 12),
                                OutlinedButton.icon(
                                  onPressed: () => Navigator.push(
                                    context,
                                    MaterialPageRoute(
                                        builder: (_) => const AnalyzerScreen()),
                                  ),
                                  icon: const Icon(Icons.psychology, size: 18),
                                  label: const Text('矛盾分析'),
                                ),
                              ],
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
}

// ─────────────────────────────────────────────
// 习惯打卡卡片（抽出来，减少 build 方法复杂度）
// ─────────────────────────────────────────────
class _HabitCheckInTile extends StatefulWidget {
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
  State<_HabitCheckInTile> createState() => _HabitCheckInTileState();
}

class _HabitCheckInTileState extends State<_HabitCheckInTile>
    with SingleTickerProviderStateMixin {
  late final AnimationController _ctrl;
  late final Animation<double> _scale;
  bool _isPressed = false;

  @override
  void initState() {
    super.initState();
    _ctrl = AnimationController(
      duration: const Duration(milliseconds: 150),
      vsync: this,
    );
    _scale = Tween<double>(begin: 1.0, end: 0.96).animate(
      CurvedAnimation(parent: _ctrl, curve: Curves.easeInOut),
    );
  }

  @override
  void dispose() {
    _ctrl.dispose();
    super.dispose();
  }

  @override
  void didUpdateWidget(covariant _HabitCheckInTile old) {
    super.didUpdateWidget(old);
    // 完成打卡时触发动画
    if (!old.isDone && widget.isDone) {
      _ctrl.forward().then((_) => _ctrl.reverse());
    }
  }

  @override
  Widget build(BuildContext context) {
    final habitColor = Color(widget.habit.colorValue as int);

    return GestureDetector(
      onTapDown: (_) => setState(() => _isPressed = true),
      onTapUp: (_) {
        setState(() => _isPressed = false);
        widget.onTap();
      },
      onTapCancel: () => setState(() => _isPressed = false),
      child: AnimatedBuilder(
        animation: _scale,
        builder: (context, child) {
          return Transform.scale(
            scale: _isPressed ? _scale.value : 1.0,
            child: child,
          );
        },
        child: AnimatedContainer(
          duration: const Duration(milliseconds: 200),
          curve: Curves.easeInOut,
          padding: const EdgeInsets.all(14),
          decoration: BoxDecoration(
            color: widget.isDone
                ? AppTheme.success.withOpacity(0.08)
                : AppTheme.bgElevated,
            borderRadius: BorderRadius.circular(AppTheme.radiusMd),
            border: Border.all(
              color: widget.isDone
                  ? AppTheme.success.withOpacity(0.25)
                  : Colors.transparent,
            ),
          ),
          child: Row(
            children: [
              // 图标背景
              AnimatedContainer(
                duration: const Duration(milliseconds: 200),
                width: 44,
                height: 44,
                decoration: BoxDecoration(
                  color: widget.isDone
                      ? AppTheme.success.withOpacity(0.2)
                      : habitColor.withOpacity(0.15),
                  borderRadius: BorderRadius.circular(12),
                ),
                child: Center(
                  child: widget.isDone
                      ? const Icon(Icons.check, color: Colors.white, size: 22)
                      : Text(widget.habit.icon,
                          style: const TextStyle(fontSize: 22)),
                ),
              ),
              const SizedBox(width: 12),
              // 名称 + 连胜
              Expanded(
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Text(
                      widget.habit.name,
                      style: TextStyle(
                        fontSize: 15,
                        fontWeight: FontWeight.w600,
                        decoration:
                            widget.isDone ? TextDecoration.lineThrough : null,
                        color: widget.isDone
                            ? AppTheme.textSecondary
                            : null,
                      ),
                    ),
                    const SizedBox(height: 3),
                    Text(
                      widget.stats.currentStreak > 0
                          ? '🔥 ${widget.stats.currentStreak}天'
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
              AnimatedContainer(
                duration: const Duration(milliseconds: 200),
                width: 36,
                height: 36,
                decoration: BoxDecoration(
                  gradient: widget.isDone ? AppTheme.successGradient : null,
                  color: widget.isDone ? null : AppTheme.bgCard,
                  shape: BoxShape.circle,
                  border: widget.isDone
                      ? null
                      : Border.all(
                          color: habitColor.withOpacity(0.4),
                        ),
                  boxShadow: widget.isDone
                      ? [
                          BoxShadow(
                            color: AppTheme.success.withOpacity(0.3),
                            blurRadius: 8,
                            offset: const Offset(0, 2),
                          ),
                        ]
                      : null,
                ),
                child: Icon(
                  widget.isDone ? Icons.check : Icons.add,
                  color: widget.isDone ? Colors.white : habitColor,
                  size: 18,
                ),
              ),
            ],
          ),
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
      padding: const EdgeInsets.fromLTRB(16, 16, 16, 4),
      child: Row(
        crossAxisAlignment: CrossAxisAlignment.center,
        children: [
          // 品牌 logo
          Container(
            width: 40,
            height: 40,
            decoration: BoxDecoration(
              gradient: AppTheme.primaryGradient,
              borderRadius: BorderRadius.circular(10),
              boxShadow: [
                BoxShadow(
                  color: AppTheme.primary.withOpacity(0.3),
                  blurRadius: 12,
                  offset: const Offset(0, 4),
                ),
              ],
            ),
            child: const Center(
              child: Text('矛', style: TextStyle(fontSize: 20, fontWeight: FontWeight.bold, color: Colors.white)),
            ),
          ),
          const SizedBox(width: 12),
          Expanded(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Text(
                  greeting,
                  style: TextStyle(
                    fontSize: 13,
                    color: AppTheme.textSecondary,
                  ),
                ),
                const SizedBox(height: 2),
                Text(
                  isAllDone
                      ? '太棒了！今天全部完成 🎉'
                      : isPartiallyDone
                          ? '继续加油 💪'
                          : '开始今天的行动吧',
                  style: const TextStyle(
                    fontSize: 22,
                    fontWeight: FontWeight.bold,
                  ),
                ),
              ],
            ),
          ),
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
    );
  }
}

/// 连胜卡片（玻璃态设计，激励文案）
class _StreakCard extends StatelessWidget {
  final int streak;

  const _StreakCard({required this.streak});

  @override
  Widget build(BuildContext context) {
    final isDark = Theme.of(context).brightness == Brightness.dark;

    return Padding(
      padding: const EdgeInsets.fromLTRB(16, 0, 16, 12),
      child: Container(
        padding: const EdgeInsets.all(18),
        decoration: BoxDecoration(
          gradient: streak > 0
              ? LinearGradient(
                  begin: Alignment.topLeft,
                  end: Alignment.bottomRight,
                  colors: streak >= 7
                      ? [
                          AppTheme.primary.withOpacity(isDark ? 0.3 : 0.15),
                          AppTheme.accent.withOpacity(isDark ? 0.2 : 0.1),
                        ]
                      : [
                          Colors.white.withOpacity(isDark ? 0.1 : 0.7),
                          Colors.white.withOpacity(isDark ? 0.04 : 0.4),
                        ],
                )
              : LinearGradient(
                  colors: [
                    Colors.white.withOpacity(isDark ? 0.06 : 0.6),
                    Colors.white.withOpacity(isDark ? 0.02 : 0.3),
                  ],
                ),
          borderRadius: BorderRadius.circular(AppTheme.radiusLg),
          border: Border.all(
            color: streak > 0
                ? (isDark ? Colors.white.withOpacity(0.12) : Colors.white.withOpacity(0.5))
                : (isDark ? Colors.white.withOpacity(0.08) : Colors.white.withOpacity(0.4)),
          ),
          boxShadow: streak > 3
              ? [
                  BoxShadow(
                    color: (streak >= 7 ? AppTheme.primary : AppTheme.accent)
                        .withOpacity(0.15),
                    blurRadius: 20,
                    offset: const Offset(0, 6),
                  ),
                ]
              : null,
        ),
        child: Row(
          children: [
            // 火焰图标
            Container(
              width: 52,
              height: 52,
              decoration: BoxDecoration(
                color: streak > 0
                    ? (streak >= 7
                        ? AppTheme.primary.withOpacity(0.2)
                        : AppTheme.accent.withOpacity(0.2))
                    : (isDark ? Colors.white.withOpacity(0.08) : Colors.grey.withOpacity(0.1)),
                borderRadius: BorderRadius.circular(14),
              ),
              child: Center(
                child: streak > 0
                    ? ShaderMask(
                        shaderCallback: (bounds) => LinearGradient(
                          colors: streak >= 7
                              ? [AppTheme.primary, AppTheme.primaryLight]
                              : [AppTheme.accent, AppTheme.accentLight],
                        ).createShader(bounds),
                        child: Text(
                          streak >= 30
                              ? '🔥🔥'
                              : streak >= 7
                                  ? '🔥'
                                  : '✨',
                          style: const TextStyle(fontSize: 26),
                        ),
                      )
                    : Text(
                        '🌱',
                        style: TextStyle(
                          fontSize: 26,
                          color: AppTheme.textSecondary.withOpacity(0.5),
                        ),
                      ),
              ),
            ),
            const SizedBox(width: 14),
            Expanded(
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Text(
                    streak > 0 ? '当前连胜' : '开始连胜',
                    style: TextStyle(
                      fontSize: 13,
                      color: AppTheme.textSecondary,
                    ),
                  ),
                  const SizedBox(height: 2),
                  Row(
                    crossAxisAlignment: CrossAxisAlignment.end,
                    children: [
                      ShaderMask(
                        shaderCallback: (bounds) => LinearGradient(
                          colors: streak >= 7
                              ? [AppTheme.primary, AppTheme.primaryLight]
                              : streak > 0
                                  ? [AppTheme.accent, AppTheme.accentLight]
                                  : [
                                      AppTheme.textSecondary,
                                      AppTheme.textSecondary
                                    ],
                        ).createShader(bounds),
                        child: Text(
                          streak > 0 ? '$streak' : '0',
                          style: TextStyle(
                            fontSize: 36,
                            fontWeight: FontWeight.bold,
                            color: Colors.white,
                          ),
                        ),
                      ),
                      Padding(
                        padding: const EdgeInsets.only(bottom: 6, left: 4),
                        child: Text(
                          '天',
                          style: TextStyle(
                            fontSize: 15,
                            color: AppTheme.textSecondary,
                          ),
                        ),
                      ),
                    ],
                  ),
                ],
              ),
            ),
            // 激励文案
            if (streak > 0)
              Container(
                padding: const EdgeInsets.symmetric(horizontal: 10, vertical: 5),
                decoration: BoxDecoration(
                  color: streak >= 7
                      ? AppTheme.primary.withOpacity(0.15)
                      : AppTheme.accent.withOpacity(0.15),
                  borderRadius: BorderRadius.circular(AppTheme.radiusFull),
                ),
                child: Text(
                  streak >= 30
                      ? '传奇 🏆'
                      : streak >= 7
                          ? '习惯养成中'
                          : '坚持就是胜利',
                  style: TextStyle(
                    fontSize: 11,
                    fontWeight: FontWeight.w500,
                    color: streak >= 7 ? AppTheme.primary : AppTheme.accent,
                  ),
                ),
              )
            else
              Text(
                '今天开始',
                style: TextStyle(
                  fontSize: 12,
                  color: AppTheme.textSecondary.withOpacity(0.6),
                ),
              ),
          ],
        ),
      ),
    );
  }
}

/// 90天打卡热力图卡片（GitHub 风格）
class _HeatmapCard extends StatelessWidget {
  final List<CheckIn> checkIns;
  final List<Habit> habits;

  const _HeatmapCard({
    required this.checkIns,
    required this.habits,
  });

  /// 计算指定日期应打卡的活跃习惯
  List<Habit> _habitsForDay(List<Habit> all, DateTime day) {
    final dayOfWeek = day.weekday % 7;
    final dayOfMonth = day.day;
    final lastDayOfMonth = DateTime(day.year, day.month + 1, 0).day;

    return all.where((h) {
      if (h.archived) return false;
      switch (h.frequency) {
        case HabitFrequency.daily:
          return true;
        case HabitFrequency.weekly:
          return h.weekDays?.contains(dayOfWeek) ?? false;
        case HabitFrequency.monthly:
          if (h.monthDays == null || h.monthDays!.isEmpty) return false;
          return h.monthDays!.contains(dayOfMonth) ||
              (dayOfMonth == lastDayOfMonth &&
                  h.monthDays!.any((d) => d > lastDayOfMonth));
      }
    }).toList();
  }

  /// 计算指定日期的打卡完成率
  double _completionForDay(DateTime day) {
    final dateStr = _dateStr(day);
    final scheduled = _habitsForDay(habits, day);
    if (scheduled.isEmpty) return -1; // 无应打卡习惯
    final done = scheduled.where((h) {
      return checkIns.any((c) => c.habitId == h.id && c.date == dateStr);
    }).length;
    return done / scheduled.length;
  }

  String _dateStr(DateTime d) =>
      '${d.year}-${d.month.toString().padLeft(2, '0')}-${d.day.toString().padLeft(2, '0')}';

  Color _cellColor(double rate) {
    if (rate < 0) return AppTheme.bgElevated;
    if (rate == 0) return AppTheme.bgElevated;
    if (rate < 0.25) return AppTheme.success.withOpacity(0.3);
    if (rate < 0.5) return AppTheme.success.withOpacity(0.5);
    if (rate < 0.75) return AppTheme.success.withOpacity(0.75);
    return AppTheme.success;
  }

  @override
  Widget build(BuildContext context) {
    final now = DateTime.now();
    // 从今天往前推13周（约91天），从周日开始对齐
    final today = DateTime(now.year, now.month, now.day);
    // 找到上一个周日作为起点
    final startDow = today.weekday % 7; // 0=周日
    final start = today.subtract(Duration(days: startDow + 90));

    // 13行(周) × 7列(天)，共91天
    const weeks = 13;
    const daysPerWeek = 7;
    const cellSize = 12.0;
    const cellSpacing = 3.0;

    // 构建每周的7天数据
    final weekData = <List<double>>[];
    for (int w = 0; w < weeks; w++) {
      final week = <double>[];
      for (int d = 0; d < daysPerWeek; d++) {
        final day = start.add(Duration(days: w * 7 + d));
        if (day.isAfter(today)) {
          week.add(-1); // 未来日期
        } else {
          week.add(_completionForDay(day));
        }
      }
      weekData.add(week);
    }

    final weekLabels = ['', '一', '', '三', '', '五', ''];

    return Container(
      margin: const EdgeInsets.fromLTRB(16, 4, 16, 8),
      padding: const EdgeInsets.all(16),
      decoration: BoxDecoration(
        color: AppTheme.bgCard,
        borderRadius: BorderRadius.circular(AppTheme.radiusLg),
        border: Border.all(color: Colors.white.withOpacity(0.08)),
      ),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Row(
            children: [
              ShaderMask(
                shaderCallback: (bounds) =>
                    AppTheme.primaryGradient.createShader(bounds),
                child: const Icon(Icons.grid_view_rounded,
                    size: 16, color: Colors.white),
              ),
              const SizedBox(width: 6),
              const Text('近三月打卡热力图',
                  style: TextStyle(fontSize: 14, fontWeight: FontWeight.w600)),
              const Spacer(),
              // 图例
              _legendItem(AppTheme.bgElevated, '无'),
              const SizedBox(width: 8),
              _legendItem(AppTheme.success.withOpacity(0.3), '25%'),
              const SizedBox(width: 8),
              _legendItem(AppTheme.success.withOpacity(0.5), '50%'),
              const SizedBox(width: 8),
              _legendItem(AppTheme.success, '100%'),
            ],
          ),
          const SizedBox(height: 14),
          SingleChildScrollView(
            scrollDirection: Axis.horizontal,
            child: Row(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                // 星期标签
                Column(
                  children: weekLabels.map((l) {
                    return SizedBox(
                      width: 16,
                      height: cellSize + cellSpacing,
                      child: l.isNotEmpty
                          ? Align(
                              alignment: Alignment.centerRight,
                              child: Text(
                                l,
                                style: TextStyle(
                                  fontSize: 9,
                                  color: AppTheme.textSecondary,
                                ),
                              ),
                            )
                          : null,
                    );
                  }).toList(),
                ),
                const SizedBox(width: 4),
                // 热力网格
                Row(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: List.generate(weeks, (w) {
                    return Column(
                      children: List.generate(daysPerWeek, (d) {
                        final rate = weekData[w][d];
                        return Tooltip(
                          message: rate < 0
                              ? ''
                              : '${start.add(Duration(days: w * 7 + d)).month}/${start.add(Duration(days: w * 7 + d)).day}: ${(rate * 100).round()}%',
                          child: Container(
                            width: cellSize,
                            height: cellSize,
                            margin: EdgeInsets.only(
                              right: d < daysPerWeek - 1 ? cellSpacing : 0,
                              bottom: cellSpacing,
                            ),
                            decoration: BoxDecoration(
                              color: _cellColor(rate),
                              borderRadius: BorderRadius.circular(2),
                            ),
                          ),
                        );
                      }),
                    );
                  }),
                ),
              ],
            ),
          ),
        ],
      ),
    );
  }

  Widget _legendItem(Color color, String label) {
    return Row(
      mainAxisSize: MainAxisSize.min,
      children: [
        Container(
          width: 10,
          height: 10,
          decoration: BoxDecoration(
            color: color,
            borderRadius: BorderRadius.circular(2),
          ),
        ),
        const SizedBox(width: 3),
        Text(label,
            style: TextStyle(fontSize: 9, color: AppTheme.textSecondary)),
      ],
    );
  }
}
