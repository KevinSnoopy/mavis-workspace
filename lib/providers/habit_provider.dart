import 'dart:convert';
import 'package:flutter/foundation.dart';
import '../models/habit.dart';
import '../services/storage_service.dart';

/// 习惯状态管理
/// 使用缓存避免重复计算统计数据
///
/// 存储：
/// - Web 端：SharedPreferences（浏览器 localStorage）
/// - 原生端：flutter_secure_storage（Keychain / EncryptedSharedPreferences）
class HabitProvider extends ChangeNotifier {
  final StorageServiceInterface _storage;
  List<Habit> _habits = [];
  List<CheckIn> _checkIns = [];
  List<Achievement> _achievements = [];
  List<AnalysisInsight> _analysisInsights = [];
  bool _isLoading = true;
  bool _saveFailed = false;

  // 统计缓存，避免每次 rebuild 都重算
  final Map<String, HabitStats> _statsCache = {};
  int? _cachedGlobalStreak;

  /// 是否有未解决的保存失败（用于 UI 显示警告）
  bool get hasSaveFailure => _saveFailed;

  /// 清除保存失败标志（用户确认后调用）
  void clearSaveFailure() {
    _saveFailed = false;
    notifyListeners();
  }

  List<Habit> get habits => _habits;
  List<CheckIn> get checkIns => _checkIns;
  List<Achievement> get achievements => _achievements;
  List<AnalysisInsight> get analysisInsights => _analysisInsights;
  bool get isLoading => _isLoading;

  /// 默认构造器：使用平台存储适配层（Web→SharedPreferences, Native→flutter_secure_storage）
  HabitProvider() : _storage = createStorageService() {
    _loadData();
  }

  /// 测试构造器：注入自定义存储适配层
  HabitProvider.forTesting(StorageServiceInterface storage) : _storage = storage {
    _loadData();
  }

  // ──────────────────────── 清空数据 ────────────────────────

  Future<void> clearAll() async {
    try {
      _habits.clear();
      _checkIns.clear();
      _achievements.clear();
      _analysisInsights.clear();
      _invalidateCache();
      await _saveData();
      notifyListeners();
    } catch (e) {
      // 数据清空失败时仍然重置内存状态，保持 UI 正常
      _invalidateCache();
      notifyListeners();
    }
  }

  /// 导入数据，返回导入的记录数
  Future<_ImportResult> importData(String jsonStr) async {
    try {
      final data = jsonDecode(jsonStr) as Map<String, dynamic>;
      int habitsAdded = 0;
      int checkInsAdded = 0;

      if (data['habits'] != null) {
        final list = data['habits'] as List<dynamic>;
        for (final h in list) {
          try {
            _habits.add(Habit.fromJson(h as Map<String, dynamic>));
            habitsAdded++;
          } catch (e) {
            // ignore: avoid_print
            debugPrint('[import] 跳过无效习惯记录: $e');
          }
        }
      }

      if (data['checkIns'] != null) {
        final list = data['checkIns'] as List<dynamic>;
        for (final c in list) {
          try {
            _checkIns.add(CheckIn.fromJson(c as Map<String, dynamic>));
            checkInsAdded++;
          } catch (e) {
            // ignore: avoid_print
            debugPrint('[import] 跳过无效打卡记录: $e');
          }
        }
      }

      _invalidateCache();
      await _saveData();
      notifyListeners();
      return _ImportResult(habitsAdded, checkInsAdded);
    } catch (e) {
      throw Exception('导入数据格式错误：${e.toString()}');
    }
  }

  // ──────────────────────── 数据加载 ────────────────────────

  Future<void> _loadData() async {
    try {
      _isLoading = true;
      notifyListeners();

      final habitsJson = await _storage.getString('habits');
      if (habitsJson != null) {
        final List<dynamic> list = jsonDecode(habitsJson);
        _habits =
            list.map((e) => Habit.fromJson(e as Map<String, dynamic>)).toList();
      }

      final checkInsJson = await _storage.getString('checkIns');
      if (checkInsJson != null) {
        final List<dynamic> list = jsonDecode(checkInsJson);
        _checkIns = list
            .map((e) => CheckIn.fromJson(e as Map<String, dynamic>))
            .toList();
      }

      final achievementsJson = await _storage.getString('achievements');
      if (achievementsJson != null) {
        final List<dynamic> list = jsonDecode(achievementsJson);
        _achievements = list
            .map((e) => Achievement.fromJson(e as Map<String, dynamic>))
            .toList();
      }

      final insightsJson = await _storage.getString('analysisInsights');
      if (insightsJson != null) {
        final List<dynamic> list = jsonDecode(insightsJson);
        _analysisInsights = list
            .map((e) => AnalysisInsight.fromJson(e as Map<String, dynamic>))
            .toList();
      }
    } catch (e) {
      // 数据加载失败，使用空状态
    } finally {
      // 启动时对所有已有习惯检查成就（防止应用关闭期间已满足条件的习惯漏解锁）
      for (final habit in _habits) {
        await _checkAchievements(habit.id);
      }
      _isLoading = false;
      notifyListeners();
    }
  }

  Future<void> _saveData() async {
    try {
      // 串行写入以避免竞态
      await _storage.setString(
          'habits', jsonEncode(_habits.map((e) => e.toJson()).toList()));
      await _storage.setString(
          'checkIns', jsonEncode(_checkIns.map((e) => e.toJson()).toList()));
      await _storage.setString('achievements',
          jsonEncode(_achievements.map((e) => e.toJson()).toList()));
      await _storage.setString('analysisInsights',
          jsonEncode(_analysisInsights.map((e) => e.toJson()).toList()));
      _saveFailed = false;
    } catch (e) {
      _saveFailed = true;
    }
  }

  // ──────────────────────── 缓存失效 ────────────────────────

  void _invalidateCache([String? habitId]) {
    if (habitId != null) {
      _statsCache.remove(habitId);
    } else {
      _statsCache.clear();
    }
    // globalStreak 取决于哪些习惯未被归档，必须失效
    _cachedGlobalStreak = null;
  }

  // ──────────────────────── 今日习惯 ────────────────────────

  List<Habit> getTodayHabits() {
    final now = DateTime.now();
    final dayOfWeek = now.weekday % 7;
    final dayOfMonth = now.day;
    // 月末回退：当月天数不足时，设置的日期视为该月最后一天
    final lastDayOfMonth = DateTime(now.year, now.month + 1, 0).day;

    return _habits.where((h) {
      if (h.archived) return false;
      switch (h.frequency) {
        case HabitFrequency.daily:
          return true;
        case HabitFrequency.weekly:
          return h.weekDays?.contains(dayOfWeek) ?? false;
        case HabitFrequency.monthly:
          if (h.monthDays == null || h.monthDays!.isEmpty) return false;
          // 如果用户设置了 31 日但本月不足 31 天，视为最后一天
          return h.monthDays!.contains(dayOfMonth) ||
              (dayOfMonth == lastDayOfMonth &&
                  h.monthDays!.any((d) => d > lastDayOfMonth));
      }
    }).toList();
  }

  CheckIn? getTodayCheckIn(String habitId) {
    final today = _todayString();
    return _checkIns
        .where((c) => c.habitId == habitId && c.date == today)
        .firstOrNull;
  }

  /// 触发 UI 刷新（下拉刷新时调用）
  void refresh() {
    notifyListeners();
  }

  // ──────────────────────── 打卡操作 ────────────────────────

  Future<void> checkIn(String habitId, {int count = 1, String? note}) async {
    try {
      final today = _todayString();
      final existingIndex =
          _checkIns.indexWhere((c) => c.habitId == habitId && c.date == today);

      if (existingIndex != -1) {
        final existing = _checkIns[existingIndex];
        _checkIns[existingIndex] = CheckIn(
          id: existing.id,
          habitId: habitId,
          date: today,
          count: existing.count + count,
          note: note ?? existing.note,
          createdAt: existing.createdAt,
        );
      } else {
        _checkIns.add(CheckIn(
          id: '${DateTime.now().millisecondsSinceEpoch}',
          habitId: habitId,
          date: today,
          count: count,
          note: note,
          createdAt: DateTime.now(),
        ));
      }

      _invalidateCache(habitId);
      await _checkAchievements(habitId);
      await _saveData();
      notifyListeners();
    } catch (e) {
      // 打卡失败，通知 UI
      notifyListeners();
    }
  }

  Future<void> cancelCheckIn(String habitId) async {
    try {
      final today = _todayString();
      _checkIns.removeWhere((c) => c.habitId == habitId && c.date == today);
      _invalidateCache(habitId);
      await _saveData();
      notifyListeners();
    } catch (e) {
      notifyListeners();
    }
  }

  Future<void> addHabit(Habit habit) async {
    try {
      final newHabit = habit.copyWith(
        id: '${DateTime.now().millisecondsSinceEpoch}',
        createdAt: DateTime.now(),
        order: _habits.length,
      );
      _habits.add(newHabit);
      await _saveData();
      notifyListeners();
    } catch (e) {
      notifyListeners();
    }
  }

  Future<void> updateHabit(Habit habit) async {
    try {
      final index = _habits.indexWhere((h) => h.id == habit.id);
      if (index != -1) {
        _habits[index] = habit;
        await _saveData();
        notifyListeners();
      }
    } catch (e) {
      notifyListeners();
    }
  }

  Future<void> deleteHabit(String habitId) async {
    try {
      _habits.removeWhere((h) => h.id == habitId);
      _checkIns.removeWhere((c) => c.habitId == habitId);
      _invalidateCache(habitId);
      await _saveData();
      notifyListeners();
    } catch (e) {
      notifyListeners();
    }
  }

  Future<void> toggleArchive(String habitId) async {
    try {
      final index = _habits.indexWhere((h) => h.id == habitId);
      if (index != -1) {
        _habits[index] =
            _habits[index].copyWith(archived: !_habits[index].archived);
        // 归档改变会影响 globalStreak（排除归档习惯），必须失效缓存
        _invalidateCache(habitId);
        await _saveData();
        notifyListeners();
      }
    } catch (e) {
      notifyListeners();
    }
  }

  // ──────────────────────── 统计数据 ────────────────────────

  HabitStats? getHabitStats(String habitId) {
    // 先检查习惯是否存在，不存在则直接返回 null
    if (!_habits.any((h) => h.id == habitId)) return null;
    return _statsCache.putIfAbsent(habitId, () => _computeStats(habitId));
  }

  HabitStats _computeStats(String habitId) {
    // 此处习惯必然存在（由 getHabitStats 保证）
    final habit = _habits.firstWhere((h) => h.id == habitId);

    final habitCheckIns = _checkIns.where((c) => c.habitId == habitId).toList();
    if (habitCheckIns.isEmpty) {
      return HabitStats(
        currentStreak: 0,
        longestStreak: 0,
        totalCount: 0,
        completionRate: 0.0,
        checkInDates: [],
      );
    }

    final dates = habitCheckIns.map((c) => c.date).toList()..sort();
    final totalCount = habitCheckIns.fold<int>(0, (sum, c) => sum + c.count);

    // 计算最长连续和当前连续
    int longestStreak = 1;
    int currentStreak = 0;
    int tempStreak = 1;

    for (int i = 1; i < dates.length; i++) {
      final prev = DateTime.parse(dates[i - 1]);
      final curr = DateTime.parse(dates[i]);
      if (curr.difference(prev).inDays == 1) {
        tempStreak++;
      } else {
        if (tempStreak > longestStreak) longestStreak = tempStreak;
        tempStreak = 1;
      }
    }
    if (tempStreak > longestStreak) longestStreak = tempStreak;

    // 当前连续：最后一天在今天或昨天
    if (dates.isNotEmpty) {
      final lastDate = DateTime.parse(dates.last);
      final today = DateTime.now();
      final diff = today.difference(lastDate).inDays;
      if (diff <= 1) {
        // 重新计算当前连续
        currentStreak = 1;
        for (int i = dates.length - 2; i >= 0; i--) {
          final prev = DateTime.parse(dates[i]);
          final curr = DateTime.parse(dates[i + 1]);
          if (curr.difference(prev).inDays == 1) {
            currentStreak++;
          } else {
            break;
          }
        }
      }
    }

    // 基准日期取首次打卡（而非习惯创建日期），避免创建后未打卡期间拉低完成率
    final firstCheckIn = DateTime.parse(dates.first);
    int totalExpected;
    switch (habit.frequency) {
      case HabitFrequency.daily:
        totalExpected = DateTime.now().difference(firstCheckIn).inDays + 1;
        break;
      case HabitFrequency.weekly:
        totalExpected =
            ((DateTime.now().difference(firstCheckIn).inDays) / 7).ceil() + 1;
        break;
      case HabitFrequency.monthly:
        int months = (DateTime.now().year - firstCheckIn.year) * 12 +
            DateTime.now().month -
            firstCheckIn.month;
        if (DateTime.now().day >= firstCheckIn.day) months++;
        totalExpected = months.clamp(1, 9999);
        break;
    }
    final completionRate =
        (dates.length / totalExpected * 100).clamp(0.0, 100.0);

    return HabitStats(
      currentStreak: currentStreak,
      longestStreak: longestStreak,
      totalCount: totalCount,
      completionRate: completionRate,
      checkInDates: dates,
    );
  }

  /// 全局连胜（所有习惯中最长的当前连续）
  int get globalStreak {
    if (_cachedGlobalStreak != null) return _cachedGlobalStreak!;

    int maxStreak = 0;
    for (final habit in _habits.where((h) => !h.archived)) {
      final stats = getHabitStats(habit.id);
      if (stats != null && stats.currentStreak > maxStreak) {
        maxStreak = stats.currentStreak;
      }
    }
    _cachedGlobalStreak = maxStreak;
    return maxStreak;
  }

  // ──────────────────────── 分析洞察 ────────────────────────

  Future<void> addAnalysisInsight(AnalysisInsight insight) async {
    _analysisInsights.insert(0, insight);
    await _saveData();
    notifyListeners();
  }

  // ──────────────────────── 成就检查 ────────────────────────

  Future<void> _checkAchievements(String habitId) async {
    // checkIn 调用时 habitId 必然有效
    final stats = getHabitStats(habitId) ?? HabitStats(
      currentStreak: 0,
      longestStreak: 0,
      totalCount: 0,
      completionRate: 0.0,
      checkInDates: [],
    );

    // F5.1 首次打卡成就
    final hasFirst =
        _achievements.any((a) => a.habitId == habitId && a.name == '首次打卡');
    if (!hasFirst && stats.totalCount >= 1) {
      _achievements.add(Achievement(
        id: '${DateTime.now().millisecondsSinceEpoch}',
        habitId: habitId,
        name: '首次打卡',
        description: '完成第一次打卡！',
        icon: '🎉',
        unlockedAt: DateTime.now(),
      ));
    }

    final milestones = [
      _Milestone(count: 7, name: '坚持一周', description: '连续打卡7天', icon: '🏆'),
      _Milestone(count: 30, name: '坚持一月', description: '连续打卡30天', icon: '🏆'),
      _Milestone(count: 100, name: '百次达人', description: '累计打卡100次', icon: '🌟'),
    ];

    for (final m in milestones) {
      final alreadyHas =
          _achievements.any((a) => a.habitId == habitId && a.name == m.name);
      if (alreadyHas) continue;

      bool unlocked = false;
      if (m.count == 7 && stats.currentStreak >= 7) unlocked = true;
      if (m.count == 30 && stats.currentStreak >= 30) unlocked = true;
      if (m.count == 100 && stats.totalCount >= 100) unlocked = true;

      if (unlocked) {
        _achievements.add(Achievement(
          id: '${DateTime.now().millisecondsSinceEpoch}',
          habitId: habitId,
          name: m.name,
          description: m.description,
          icon: m.icon,
          unlockedAt: DateTime.now(),
        ));
      }
    }
  }

  String _todayString() {
    final now = DateTime.now();
    return '${now.year}-${now.month.toString().padLeft(2, '0')}-${now.day.toString().padLeft(2, '0')}';
  }
}

class _Milestone {
  final int count;
  final String name;
  final String description;
  final String icon;

  const _Milestone({
    required this.count,
    required this.name,
    required this.description,
    required this.icon,
  });
}

class _ImportResult {
  final int habits;
  final int checkIns;

  _ImportResult(this.habits, this.checkIns);
}
