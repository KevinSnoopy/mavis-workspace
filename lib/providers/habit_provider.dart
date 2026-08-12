import 'dart:convert';
import 'package:flutter/material.dart';
import 'package:shared_preferences/shared_preferences.dart';
import '../models/habit.dart';

class HabitProvider extends ChangeNotifier {
  List<Habit> _habits = [];
  List<CheckIn> _checkIns = [];
  List<Achievement> _achievements = [];
  List<AnalysisInsight> _analysisInsights = [];
  bool _isLoading = true;

  List<Habit> get habits => _habits;
  List<CheckIn> get checkIns => _checkIns;
  List<Achievement> get achievements => _achievements;
  List<AnalysisInsight> get analysisInsights => _analysisInsights;
  bool get isLoading => _isLoading;

  HabitProvider() {
    _loadData();
  }

  // 加载数据
  Future<void> _loadData() async {
    final prefs = await SharedPreferences.getInstance();
    
    final habitsJson = prefs.getString('habits');
    if (habitsJson != null) {
      final List<dynamic> habitsList = jsonDecode(habitsJson);
      _habits = habitsList.map((e) => Habit.fromJson(e)).toList();
    }
    
    final checkInsJson = prefs.getString('checkIns');
    if (checkInsJson != null) {
      final List<dynamic> checkInsList = jsonDecode(checkInsJson);
      _checkIns = checkInsList.map((e) => CheckIn.fromJson(e)).toList();
    }
    
    final achievementsJson = prefs.getString('achievements');
    if (achievementsJson != null) {
      final List<dynamic> achievementsList = jsonDecode(achievementsJson);
      _achievements = achievementsList.map((e) => Achievement.fromJson(e)).toList();
    }
    
    final insightsJson = prefs.getString('analysisInsights');
    if (insightsJson != null) {
      final List<dynamic> insightsList = jsonDecode(insightsJson);
      _analysisInsights = insightsList.map((e) => AnalysisInsight.fromJson(e)).toList();
    }
    
    _isLoading = false;
    notifyListeners();
  }

  // 保存数据
  Future<void> _saveData() async {
    final prefs = await SharedPreferences.getInstance();
    await prefs.setString('habits', jsonEncode(_habits.map((e) => e.toJson()).toList()));
    await prefs.setString('checkIns', jsonEncode(_checkIns.map((e) => e.toJson()).toList()));
    await prefs.setString('achievements', jsonEncode(_achievements.map((e) => e.toJson()).toList()));
    await prefs.setString('analysisInsights', jsonEncode(_analysisInsights.map((e) => e.toJson()).toList()));
  }

  // 获取今日习惯
  List<Habit> getTodayHabits() {
    final now = DateTime.now();
    final dayOfWeek = now.weekday % 7; // 0=周日
    final dayOfMonth = now.day;
    
    return _habits.where((h) {
      if (h.archived) return false;
      
      switch (h.frequency) {
        case HabitFrequency.daily:
          return true;
        case HabitFrequency.weekly:
          return h.weekDays?.contains(dayOfWeek) ?? false;
        case HabitFrequency.monthly:
          return h.monthDays?.contains(dayOfMonth) ?? false;
      }
    }).toList();
  }

  // 获取今日打卡
  CheckIn? getTodayCheckIn(String habitId) {
    final today = _getTodayString();
    return _checkIns.where((c) => c.habitId == habitId && c.date == today).firstOrNull;
  }

  // 打卡
  Future<void> checkIn(String habitId, {int count = 1, String? note}) async {
    final today = _getTodayString();
    final existing = _checkIns.where((c) => c.habitId == habitId && c.date == today).firstOrNull;
    
    if (existing != null) {
      // 更新
      final index = _checkIns.indexOf(existing);
      _checkIns[index] = CheckIn(
        id: existing.id,
        habitId: habitId,
        date: today,
        count: existing.count + count,
        note: note ?? existing.note,
        createdAt: existing.createdAt,
      );
    } else {
      // 新增
      _checkIns.add(CheckIn(
        id: DateTime.now().millisecondsSinceEpoch.toString(),
        habitId: habitId,
        date: today,
        count: count,
        note: note,
        createdAt: DateTime.now(),
      ));
    }
    
    await _checkAchievements(habitId);
    await _saveData();
    notifyListeners();
  }

  // 取消打卡
  Future<void> cancelCheckIn(String habitId) async {
    final today = _getTodayString();
    _checkIns.removeWhere((c) => c.habitId == habitId && c.date == today);
    await _saveData();
    notifyListeners();
  }

  // 添加习惯
  Future<void> addHabit(Habit habit) async {
    final newHabit = habit.copyWith(
      id: DateTime.now().millisecondsSinceEpoch.toString(),
      createdAt: DateTime.now(),
      order: _habits.length,
    );
    _habits.add(newHabit);
    await _saveData();
    notifyListeners();
  }

  // 更新习惯
  Future<void> updateHabit(Habit habit) async {
    final index = _habits.indexWhere((h) => h.id == habit.id);
    if (index != -1) {
      _habits[index] = habit;
      await _saveData();
      notifyListeners();
    }
  }

  // 删除习惯
  Future<void> deleteHabit(String habitId) async {
    _habits.removeWhere((h) => h.id == habitId);
    _checkIns.removeWhere((c) => c.habitId == habitId);
    await _saveData();
    notifyListeners();
  }

  // 归档习惯
  Future<void> toggleArchive(String habitId) async {
    final index = _habits.indexWhere((h) => h.id == habitId);
    if (index != -1) {
      _habits[index] = _habits[index].copyWith(archived: !_habits[index].archived);
      await _saveData();
      notifyListeners();
    }
  }

  // 获取习惯统计
  HabitStats getHabitStats(String habitId) {
    final habit = _habits.firstWhere((h) => h.id == habitId, orElse: () => throw Exception('Habit not found'));
    final habitCheckIns = _checkIns.where((c) => c.habitId == habitId).toList();
    final dates = habitCheckIns.map((c) => c.date).toList()..sort();
    
    int currentStreak = 0;
    int longestStreak = 0;
    int tempStreak = 0;
    
    for (int i = 0; i < dates.length; i++) {
      if (i == 0) {
        tempStreak = 1;
      } else {
        final prev = DateTime.parse(dates[i - 1]);
        final curr = DateTime.parse(dates[i]);
        if (curr.difference(prev).inDays == 1) {
          tempStreak++;
        } else {
          tempStreak = 1;
        }
      }
      longestStreak = tempStreak > longestStreak ? tempStreak : longestStreak;
    }
    
    // 当前连续
    if (dates.isNotEmpty) {
      final lastDate = DateTime.parse(dates.last);
      final today = DateTime.now();
      if (today.difference(lastDate).inDays <= 1) {
        currentStreak = tempStreak;
      }
    }
    
    final daysSinceCreated = DateTime.now().difference(habit.createdAt).inDays + 1;
    final completionRate = (dates.length / daysSinceCreated * 100).clamp(0, 100);
    
    return HabitStats(
      currentStreak: currentStreak,
      longestStreak: longestStreak,
      totalCount: habitCheckIns.fold(0, (sum, c) => sum + c.count),
      completionRate: completionRate.toDouble(),
      checkInDates: dates,
    );
  }

  // 全局连胜
  int get globalStreak {
    if (_habits.isEmpty) return 0;
    int maxStreak = 0;
    for (final habit in _habits.where((h) => !h.archived)) {
      final stats = getHabitStats(habit.id);
      if (stats.currentStreak > maxStreak) {
        maxStreak = stats.currentStreak;
      }
    }
    return maxStreak;
  }

  // 添加分析洞察
  Future<void> addAnalysisInsight(AnalysisInsight insight) async {
    _analysisInsights.insert(0, insight);
    await _saveData();
    notifyListeners();
  }

  // 检查成就
  Future<void> _checkAchievements(String habitId) async {
    final stats = getHabitStats(habitId);
    
    final milestones = [
      {'count': 7, 'name': '坚持一周', 'description': '连续打卡7天', 'icon': '🏆'},
      {'count': 30, 'name': '坚持一月', 'description': '连续打卡30天', 'icon': '🏆'},
      {'count': 100, 'name': '百次达人', 'description': '累计打卡100次', 'icon': '🌟'},
    ];
    
    for (final m in milestones) {
      final alreadyHas = _achievements.any((a) => a.habitId == habitId && a.name == m['name']);
      if (alreadyHas) continue;
      
      bool unlocked = false;
      if (m['count'] == 7 && stats.currentStreak >= 7) unlocked = true;
      if (m['count'] == 30 && stats.currentStreak >= 30) unlocked = true;
      if (m['count'] == 100 && stats.totalCount >= 100) unlocked = true;
      
      if (unlocked) {
        _achievements.add(Achievement(
          id: DateTime.now().millisecondsSinceEpoch.toString(),
          habitId: habitId,
          name: m['name'] as String,
          description: m['description'] as String,
          icon: m['icon'] as String,
          unlockedAt: DateTime.now(),
        ));
      }
    }
  }

  String _getTodayString() {
    final now = DateTime.now();
    return '${now.year}-${now.month.toString().padLeft(2, '0')}-${now.day.toString().padLeft(2, '0')}';
  }
}
