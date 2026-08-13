import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:provider/provider.dart';
import '../providers/habit_provider.dart';
import '../models/habit.dart';
import '../theme/app_theme.dart';

class HabitsScreen extends StatefulWidget {
  const HabitsScreen({super.key});

  @override
  State<HabitsScreen> createState() => _HabitsScreenState();
}

class _HabitsScreenState extends State<HabitsScreen> {
  final _searchController = TextEditingController();
  String _query = '';

  @override
  void dispose() {
    _searchController.dispose();
    super.dispose();
  }

  List<Habit> _filter(List<Habit> habits) {
    if (_query.isEmpty) return habits;
    return habits
        .where((h) =>
            h.name.toLowerCase().contains(_query.toLowerCase()) ||
            (h.description?.toLowerCase().contains(_query.toLowerCase()) ??
                false))
        .toList();
  }

  @override
  Widget build(BuildContext context) {
    return Consumer<HabitProvider>(
      builder: (context, provider, _) {
        final activeHabits = provider.habits.where((h) => !h.archived).toList()
          ..sort((a, b) => a.order.compareTo(b.order));
        final archivedHabits =
            provider.habits.where((h) => h.archived).toList();

        final filteredActive = _filter(activeHabits);
        final filteredArchived = _filter(archivedHabits);

        return DefaultTabController(
          length: 2,
          child: Scaffold(
            appBar: AppBar(
              title: const Text('✨ 习惯管理'),
              bottom: PreferredSize(
                preferredSize: const Size.fromHeight(96),
                child: Column(
                  children: [
                    // 搜索框
                    Padding(
                      padding: const EdgeInsets.fromLTRB(12, 0, 12, 8),
                      child: TextField(
                        controller: _searchController,
                        onChanged: (v) => setState(() => _query = v),
                        decoration: InputDecoration(
                          hintText: '搜索习惯...',
                          hintStyle: TextStyle(
                              color: AppTheme.textSecondary.withOpacity(0.5)),
                          prefixIcon: const Icon(Icons.search, size: 20),
                          suffixIcon: _query.isNotEmpty
                              ? IconButton(
                                  icon: const Icon(Icons.clear, size: 18),
                                  onPressed: () {
                                    _searchController.clear();
                                    setState(() => _query = '');
                                  },
                                )
                              : null,
                          filled: true,
                          fillColor: AppTheme.bgElevated,
                          contentPadding: const EdgeInsets.symmetric(
                              vertical: 0, horizontal: 12),
                          border: OutlineInputBorder(
                            borderRadius: BorderRadius.circular(12),
                            borderSide: BorderSide.none,
                          ),
                        ),
                        style: const TextStyle(fontSize: 14),
                      ),
                    ),
                    TabBar(
                      tabs: [
                        Tab(text: '进行中 (${filteredActive.length})'),
                        Tab(text: '已暂停 (${filteredArchived.length})'),
                      ],
                    ),
                  ],
                ),
              ),
            ),
            body: TabBarView(
              children: [
                _HabitList(habits: filteredActive, isArchived: false),
                _HabitList(habits: filteredArchived, isArchived: true),
              ],
            ),
            floatingActionButton: FloatingActionButton.extended(
              onPressed: () => _showAddHabitDialog(context),
              backgroundColor: AppTheme.primary,
              icon: const Icon(Icons.add),
              label: const Text('添加'),
            ),
          ),
        );
      },
    );
  }

  void _showAddHabitDialog(BuildContext context) {
    final nameController = TextEditingController();
    final descController = TextEditingController();
    String selectedIcon = '🎯';
    int selectedColor = AppColors.habitColors[0];
    HabitFrequency selectedFrequency = HabitFrequency.daily;
    List<int> selectedWeekDays = [DateTime.now().weekday % 7]; // 默认今天
    List<int> selectedMonthDays = [DateTime.now().day];        // 默认今天

    showModalBottomSheet(
      context: context,
      isScrollControlled: true,
      backgroundColor: AppTheme.bgCard,
      shape: const RoundedRectangleBorder(
        borderRadius: BorderRadius.vertical(top: Radius.circular(24)),
      ),
      builder: (ctx) {
        return StatefulBuilder(
          builder: (ctx, setState) {
            return Padding(
              padding: EdgeInsets.only(
                left: 20,
                right: 20,
                top: 20,
                bottom: MediaQuery.of(ctx).viewInsets.bottom + 20,
              ),
              child: SingleChildScrollView(
                child: Column(
                  mainAxisSize: MainAxisSize.min,
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Row(
                      mainAxisAlignment: MainAxisAlignment.spaceBetween,
                      children: [
                        const Text('添加习惯',
                            style: TextStyle(
                                fontSize: 20, fontWeight: FontWeight.bold)),
                        IconButton(
                          icon: const Icon(Icons.close),
                          onPressed: () => Navigator.pop(ctx),
                        ),
                      ],
                    ),
                    const SizedBox(height: 16),
                    TextField(
                      controller: nameController,
                      autofocus: true,
                      decoration: const InputDecoration(
                        hintText: '习惯名称',
                        labelText: '习惯名称',
                      ),
                    ),
                    const SizedBox(height: 12),
                    TextField(
                      controller: descController,
                      decoration: const InputDecoration(
                        hintText: '简短描述（可选）',
                        labelText: '描述',
                      ),
                    ),
                    const SizedBox(height: 16),
                    const Text('图标', style: TextStyle(fontSize: 14)),
                    const SizedBox(height: 8),
                    Wrap(
                      spacing: 8,
                      runSpacing: 8,
                      children: AppColors.habitIcons.map((icon) {
                        final isSelected = selectedIcon == icon;
                        return GestureDetector(
                          onTap: () => setState(() => selectedIcon = icon),
                          child: Container(
                            width: 44,
                            height: 44,
                            decoration: BoxDecoration(
                              color: isSelected
                                  ? AppTheme.primary.withOpacity(0.2)
                                  : AppTheme.bgElevated,
                              borderRadius: BorderRadius.circular(8),
                              border: isSelected
                                  ? Border.all(color: AppTheme.primary)
                                  : null,
                            ),
                            child: Center(
                              child: Text(icon,
                                  style: const TextStyle(fontSize: 20)),
                            ),
                          ),
                        );
                      }).toList(),
                    ),
                    const SizedBox(height: 16),
                    const Text('颜色', style: TextStyle(fontSize: 14)),
                    const SizedBox(height: 8),
                    Wrap(
                      spacing: 8,
                      runSpacing: 8,
                      children: AppColors.habitColors.map((color) {
                        final isSelected = selectedColor == color;
                        return GestureDetector(
                          onTap: () => setState(() => selectedColor = color),
                          child: Container(
                            width: 36,
                            height: 36,
                            decoration: BoxDecoration(
                              color: Color(color),
                              shape: BoxShape.circle,
                              border: isSelected
                                  ? Border.all(color: Colors.white, width: 3)
                                  : null,
                            ),
                          ),
                        );
                      }).toList(),
                    ),
                    const SizedBox(height: 16),
                    const Text('频率', style: TextStyle(fontSize: 14)),
                    const SizedBox(height: 8),
                    _FrequencySelector(
                      frequency: selectedFrequency,
                      onChanged: (f, _) => setState(() => selectedFrequency = f),
                    ),
                    if (selectedFrequency == HabitFrequency.weekly) ...[
                      const SizedBox(height: 12),
                      _WeekDayPicker(
                        selectedDays: selectedWeekDays,
                        onChanged: (days) =>
                            setState(() => selectedWeekDays = days),
                      ),
                    ],
                    if (selectedFrequency == HabitFrequency.monthly) ...[
                      const SizedBox(height: 12),
                      _MonthDayPicker(
                        selectedDays: selectedMonthDays,
                        onChanged: (days) =>
                            setState(() => selectedMonthDays = days),
                      ),
                    ],
                    const SizedBox(height: 24),
                    SizedBox(
                      width: double.infinity,
                      child: ElevatedButton(
                        onPressed: () async {
                          if (nameController.text.trim().isEmpty) return;
                          HapticFeedback.lightImpact();
                          await context.read<HabitProvider>().addHabit(Habit(
                                id: '',
                                name: nameController.text.trim(),
                                description: descController.text.trim().isEmpty
                                    ? null
                                    : descController.text.trim(),
                                icon: selectedIcon,
                                colorValue: selectedColor,
                                frequency: selectedFrequency,
                                weekDays: selectedFrequency == HabitFrequency.weekly
                                    ? selectedWeekDays
                                    : null,
                                monthDays: selectedFrequency == HabitFrequency.monthly
                                    ? selectedMonthDays
                                    : null,
                                createdAt: DateTime.now(),
                              ));
                          if (ctx.mounted) Navigator.pop(ctx);
                        },
                        child: const Text('添加'),
                      ),
                    ),
                  ],
                ),
              ),
            );
          },
        );
      },
    );
  }
}

class _HabitList extends StatelessWidget {
  final List<Habit> habits;
  final bool isArchived;

  const _HabitList({required this.habits, required this.isArchived});

  static final _emptyStats = HabitStats(
    currentStreak: 0,
    longestStreak: 0,
    totalCount: 0,
    completionRate: 0.0,
    checkInDates: [],
  );

  @override
  Widget build(BuildContext context) {
    if (habits.isEmpty) {
      return Center(
        child: Column(
          mainAxisAlignment: MainAxisAlignment.center,
          children: [
            Text(isArchived ? '📦' : '🌱',
                style: const TextStyle(fontSize: 48)),
            const SizedBox(height: 16),
            Text(
              isArchived ? '没有暂停的习惯' : '还没有习惯',
              style: TextStyle(color: AppTheme.textSecondary),
            ),
            if (!isArchived) ...[
              const SizedBox(height: 8),
              Text(
                '点击底部 + 添加第一个习惯',
                style: TextStyle(
                  fontSize: 13,
                  color: AppTheme.textSecondary.withOpacity(0.7),
                ),
              ),
            ],
          ],
        ),
      );
    }

    return ListView.builder(
      padding: const EdgeInsets.all(16),
      physics: const BouncingScrollPhysics(),
      itemCount: habits.length,
      itemBuilder: (context, index) {
        final habit = habits[index];
        return _HabitTile(habit: habit, isArchived: isArchived);
      },
    );
  }
}

class _HabitTile extends StatelessWidget {
  final Habit habit;
  final bool isArchived;

  const _HabitTile({required this.habit, required this.isArchived});

  @override
  Widget build(BuildContext context) {
    final provider = context.watch<HabitProvider>();
    final stats = provider.getHabitStats(habit.id) ?? _HabitList._emptyStats;

    return Padding(
      padding: const EdgeInsets.only(bottom: 10),
      child: Dismissible(
        key: Key(habit.id),
        direction: DismissDirection.endToStart,
        background: Container(
          alignment: Alignment.centerRight,
          padding: const EdgeInsets.only(right: 20),
          decoration: BoxDecoration(
            color: Colors.red.withOpacity(0.2),
            borderRadius: BorderRadius.circular(AppTheme.radiusMd),
          ),
          child: const Icon(Icons.delete, color: Colors.red),
        ),
        confirmDismiss: (_) async {
          return await showDialog<bool>(
            context: context,
            builder: (ctx) => AlertDialog(
              backgroundColor: AppTheme.bgCard,
              title: const Text('确认删除？'),
              content: Text('删除「${habit.name}」及其所有打卡记录'),
              actions: [
                TextButton(
                  onPressed: () => Navigator.pop(ctx, false),
                  child: const Text('取消'),
                ),
                TextButton(
                  onPressed: () => Navigator.pop(ctx, true),
                  child: const Text('删除', style: TextStyle(color: Colors.red)),
                ),
              ],
            ),
          );
        },
        onDismissed: (_) => provider.deleteHabit(habit.id),
        child: GestureDetector(
          onTap: () => _showEditSheet(context, habit),
          child: Container(
            padding: const EdgeInsets.all(16),
            decoration: BoxDecoration(
              color: AppTheme.bgCard,
              borderRadius: BorderRadius.circular(AppTheme.radiusMd),
              border: Border.all(color: Colors.white.withOpacity(0.05)),
            ),
            child: Row(
              children: [
                Container(
                  width: 48,
                  height: 48,
                  decoration: BoxDecoration(
                    color: Color(habit.colorValue).withOpacity(0.2),
                    borderRadius: BorderRadius.circular(12),
                  ),
                  child: Center(
                    child:
                        Text(habit.icon, style: const TextStyle(fontSize: 24)),
                  ),
                ),
                const SizedBox(width: 12),
                Expanded(
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      Text(
                        habit.name,
                        style: const TextStyle(
                          fontSize: 16,
                          fontWeight: FontWeight.w500,
                        ),
                      ),
                      const SizedBox(height: 4),
                      Row(
                        children: [
                          _StatChip('🔥 ${stats.currentStreak}天'),
                          const SizedBox(width: 8),
                          _StatChip('📊 ${stats.totalCount}次'),
                          const SizedBox(width: 8),
                          _StatChip(
                              '${stats.completionRate.toStringAsFixed(0)}%'),
                        ],
                      ),
                    ],
                  ),
                ),
                if (isArchived)
                  Container(
                    padding:
                        const EdgeInsets.symmetric(horizontal: 8, vertical: 4),
                    decoration: BoxDecoration(
                      color: AppTheme.textSecondary.withOpacity(0.2),
                      borderRadius: BorderRadius.circular(8),
                    ),
                    child: Text(
                      '已暂停',
                      style: TextStyle(
                          fontSize: 10, color: AppTheme.textSecondary),
                    ),
                  ),
                const SizedBox(width: 8),
                Icon(Icons.chevron_right, color: AppTheme.textSecondary),
              ],
            ),
          ),
        ),
      ),
    );
  }

  void _showEditSheet(BuildContext context, Habit habit) {
    final nameController = TextEditingController(text: habit.name);
    final descController = TextEditingController(text: habit.description ?? '');
    String selectedIcon = habit.icon;
    int selectedColor = habit.colorValue;
    HabitFrequency selectedFrequency = habit.frequency;
    List<int> selectedWeekDays = habit.weekDays ?? [DateTime.now().weekday % 7];
    List<int> selectedMonthDays = habit.monthDays ?? [DateTime.now().day];

    showModalBottomSheet(
      context: context,
      isScrollControlled: true,
      backgroundColor: AppTheme.bgCard,
      shape: const RoundedRectangleBorder(
        borderRadius: BorderRadius.vertical(top: Radius.circular(24)),
      ),
      builder: (ctx) {
        return StatefulBuilder(
          builder: (ctx, setState) {
            return Padding(
              padding: EdgeInsets.only(
                left: 20,
                right: 20,
                top: 20,
                bottom: MediaQuery.of(ctx).viewInsets.bottom + 20,
              ),
              child: SingleChildScrollView(
                child: Column(
                  mainAxisSize: MainAxisSize.min,
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Row(
                      mainAxisAlignment: MainAxisAlignment.spaceBetween,
                      children: [
                        const Text('编辑习惯',
                            style: TextStyle(
                                fontSize: 20, fontWeight: FontWeight.bold)),
                        IconButton(
                          icon: const Icon(Icons.close),
                          onPressed: () => Navigator.pop(ctx),
                        ),
                      ],
                    ),
                    const SizedBox(height: 16),
                    TextField(
                      controller: nameController,
                      decoration: const InputDecoration(
                        labelText: '习惯名称',
                      ),
                    ),
                    const SizedBox(height: 12),
                    TextField(
                      controller: descController,
                      decoration: const InputDecoration(
                        labelText: '描述（可选）',
                      ),
                    ),
                    const SizedBox(height: 16),
                    const Text('图标', style: TextStyle(fontSize: 14)),
                    const SizedBox(height: 8),
                    Wrap(
                      spacing: 8,
                      runSpacing: 8,
                      children: AppColors.habitIcons.map((icon) {
                        final isSelected = selectedIcon == icon;
                        return GestureDetector(
                          onTap: () => setState(() => selectedIcon = icon),
                          child: Container(
                            width: 44,
                            height: 44,
                            decoration: BoxDecoration(
                              color: isSelected
                                  ? AppTheme.primary.withOpacity(0.2)
                                  : AppTheme.bgElevated,
                              borderRadius: BorderRadius.circular(8),
                              border: isSelected
                                  ? Border.all(color: AppTheme.primary)
                                  : null,
                            ),
                            child: Center(
                              child: Text(icon,
                                  style: const TextStyle(fontSize: 20)),
                            ),
                          ),
                        );
                      }).toList(),
                    ),
                    const SizedBox(height: 16),
                    const Text('颜色', style: TextStyle(fontSize: 14)),
                    const SizedBox(height: 8),
                    Wrap(
                      spacing: 8,
                      runSpacing: 8,
                      children: AppColors.habitColors.map((color) {
                        final isSelected = selectedColor == color;
                        return GestureDetector(
                          onTap: () => setState(() => selectedColor = color),
                          child: Container(
                            width: 36,
                            height: 36,
                            decoration: BoxDecoration(
                              color: Color(color),
                              shape: BoxShape.circle,
                              border: isSelected
                                  ? Border.all(color: Colors.white, width: 3)
                                  : null,
                            ),
                          ),
                        );
                      }).toList(),
                    ),
                    const SizedBox(height: 16),
                    const Text('频率', style: TextStyle(fontSize: 14)),
                    const SizedBox(height: 8),
                    _FrequencySelector(
                      frequency: selectedFrequency,
                      onChanged: (f, _) => setState(() => selectedFrequency = f),
                    ),
                    if (selectedFrequency == HabitFrequency.weekly) ...[
                      const SizedBox(height: 12),
                      _WeekDayPicker(
                        selectedDays: selectedWeekDays,
                        onChanged: (days) =>
                            setState(() => selectedWeekDays = days),
                      ),
                    ],
                    if (selectedFrequency == HabitFrequency.monthly) ...[
                      const SizedBox(height: 12),
                      _MonthDayPicker(
                        selectedDays: selectedMonthDays,
                        onChanged: (days) =>
                            setState(() => selectedMonthDays = days),
                      ),
                    ],
                    const SizedBox(height: 24),
                    Row(
                      children: [
                        Expanded(
                          child: OutlinedButton(
                            onPressed: () async {
                              await context
                                  .read<HabitProvider>()
                                  .toggleArchive(habit.id);
                              if (ctx.mounted) Navigator.pop(ctx);
                            },
                            style: OutlinedButton.styleFrom(
                              side: BorderSide(
                                  color:
                                      AppTheme.textSecondary.withOpacity(0.3)),
                            ),
                            child: Text(
                              habit.archived ? '恢复习惯' : '暂停习惯',
                              style: TextStyle(color: AppTheme.textSecondary),
                            ),
                          ),
                        ),
                        const SizedBox(width: 12),
                        Expanded(
                          child: ElevatedButton(
                            onPressed: () async {
                              if (nameController.text.trim().isEmpty) return;
                              HapticFeedback.lightImpact();
                              await context.read<HabitProvider>().updateHabit(
                                    habit.copyWith(
                                      name: nameController.text.trim(),
                                      description:
                                          descController.text.trim().isEmpty
                                              ? null
                                              : descController.text.trim(),
                                      icon: selectedIcon,
                                      colorValue: selectedColor,
                                      frequency: selectedFrequency,
                                      weekDays: selectedFrequency == HabitFrequency.weekly
                                          ? selectedWeekDays
                                          : null,
                                      monthDays: selectedFrequency == HabitFrequency.monthly
                                          ? selectedMonthDays
                                          : null,
                                    ),
                                  );
                              if (ctx.mounted) Navigator.pop(ctx);
                            },
                            child: const Text('保存'),
                          ),
                        ),
                      ],
                    ),
                  ],
                ),
              ),
            );
          },
        );
      },
    );
  }
}

class _StatChip extends StatelessWidget {
  final String text;
  const _StatChip(this.text);

  @override
  Widget build(BuildContext context) {
    return Text(
      text,
      style: TextStyle(fontSize: 12, color: AppTheme.textSecondary),
    );
  }
}

/// 频率选择器：每日 / 每周 / 每月
class _FrequencySelector extends StatelessWidget {
  final HabitFrequency frequency;
  final void Function(HabitFrequency f, List<int>? days) onChanged;

  const _FrequencySelector({
    required this.frequency,
    required this.onChanged,
  });

  @override
  Widget build(BuildContext context) {
    final options = [
      (HabitFrequency.daily, '每日'),
      (HabitFrequency.weekly, '每周'),
      (HabitFrequency.monthly, '每月'),
    ];

    return Wrap(
      spacing: 8,
      children: options.map((opt) {
        final isSelected = frequency == opt.$1;
        return GestureDetector(
          onTap: () => onChanged(opt.$1, null),
          child: Container(
            padding: const EdgeInsets.symmetric(horizontal: 14, vertical: 8),
            decoration: BoxDecoration(
              color: isSelected
                  ? AppTheme.primary.withOpacity(0.15)
                  : AppTheme.bgElevated,
              borderRadius: BorderRadius.circular(20),
              border: isSelected
                  ? Border.all(color: AppTheme.primary)
                  : Border.all(color: Colors.transparent),
            ),
            child: Text(
              opt.$2,
              style: TextStyle(
                fontSize: 13,
                color: isSelected ? AppTheme.primary : AppTheme.textSecondary,
                fontWeight: isSelected ? FontWeight.w600 : FontWeight.normal,
              ),
            ),
          ),
        );
      }).toList(),
    );
  }
}

/// 星期选择器：周一~周日（0=周日，6=周六，与 DateTime.weekday%7 一致）
class _WeekDayPicker extends StatelessWidget {
  final List<int> selectedDays;
  final void Function(List<int>) onChanged;

  const _WeekDayPicker({required this.selectedDays, required this.onChanged});

  static const _dayLabels = ['周日', '周一', '周二', '周三', '周四', '周五', '周六'];

  @override
  Widget build(BuildContext context) {
    return Wrap(
      spacing: 6,
      runSpacing: 6,
      children: List.generate(7, (i) {
        final selected = selectedDays.contains(i);
        return GestureDetector(
          onTap: () {
            final next = List<int>.from(selectedDays);
            if (selected) {
              next.remove(i);
            } else {
              next.add(i);
            }
            if (next.isNotEmpty) onChanged(next);
          },
          child: Container(
            width: 44,
            height: 36,
            decoration: BoxDecoration(
              color: selected ? AppTheme.accent.withOpacity(0.2) : AppTheme.bgElevated,
              borderRadius: BorderRadius.circular(8),
              border: selected ? Border.all(color: AppTheme.accent) : null,
            ),
            child: Center(
              child: Text(
                _dayLabels[i],
                style: TextStyle(
                  fontSize: 12,
                  color: selected ? AppTheme.accent : AppTheme.textSecondary,
                  fontWeight: selected ? FontWeight.w600 : FontWeight.normal,
                ),
              ),
            ),
          ),
        );
      }),
    );
  }
}

/// 月份日期选择器：1~31 日
class _MonthDayPicker extends StatelessWidget {
  final List<int> selectedDays;
  final void Function(List<int>) onChanged;

  const _MonthDayPicker({required this.selectedDays, required this.onChanged});

  @override
  Widget build(BuildContext context) {
    return Wrap(
      spacing: 6,
      runSpacing: 6,
      children: List.generate(31, (i) {
        final day = i + 1;
        final selected = selectedDays.contains(day);
        return GestureDetector(
          onTap: () {
            final next = List<int>.from(selectedDays);
            if (selected) {
              next.remove(day);
            } else {
              next.add(day);
            }
            if (next.isNotEmpty) onChanged(next);
          },
          child: Container(
            width: 36,
            height: 36,
            decoration: BoxDecoration(
              color: selected ? AppTheme.accent.withOpacity(0.2) : AppTheme.bgElevated,
              borderRadius: BorderRadius.circular(8),
              border: selected ? Border.all(color: AppTheme.accent) : null,
            ),
            child: Center(
              child: Text(
                '$day',
                style: TextStyle(
                  fontSize: 12,
                  color: selected ? AppTheme.accent : AppTheme.textSecondary,
                  fontWeight: selected ? FontWeight.w600 : FontWeight.normal,
                ),
              ),
            ),
          ),
        );
      }),
    );
  }
}
