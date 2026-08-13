// 习惯模型
import 'package:flutter/foundation.dart';

/// 解析 required 字段，缺失时抛出具体错误
T _req<T>(Map<String, dynamic> json, String key) =>
    json[key] as T? ??
    (throw FormatException('缺少必填字段 "$key"'));

class Habit {
  final String id;
  final String name;
  final String? description;
  final String icon;
  final int colorValue;
  final HabitFrequency frequency;
  final List<int>? weekDays;
  final List<int>? monthDays;
  final int targetPerDay;
  final String? reminderTime;
  final DateTime createdAt;
  final bool archived;
  final int order;

  Habit({
    required this.id,
    required this.name,
    this.description,
    required this.icon,
    required this.colorValue,
    required this.frequency,
    this.weekDays,
    this.monthDays,
    this.targetPerDay = 1,
    this.reminderTime,
    required this.createdAt,
    this.archived = false,
    this.order = 0,
  });

  Habit copyWith({
    String? id,
    String? name,
    String? description,
    String? icon,
    int? colorValue,
    HabitFrequency? frequency,
    List<int>? weekDays,
    List<int>? monthDays,
    int? targetPerDay,
    String? reminderTime,
    DateTime? createdAt,
    bool? archived,
    int? order,
  }) {
    return Habit(
      id: id ?? this.id,
      name: name ?? this.name,
      description: description ?? this.description,
      icon: icon ?? this.icon,
      colorValue: colorValue ?? this.colorValue,
      frequency: frequency ?? this.frequency,
      weekDays: weekDays ?? this.weekDays,
      monthDays: monthDays ?? this.monthDays,
      targetPerDay: targetPerDay ?? this.targetPerDay,
      reminderTime: reminderTime ?? this.reminderTime,
      createdAt: createdAt ?? this.createdAt,
      archived: archived ?? this.archived,
      order: order ?? this.order,
    );
  }

  Map<String, dynamic> toJson() {
    return {
      'id': id,
      'name': name,
      'description': description,
      'icon': icon,
      'colorValue': colorValue,
      'frequency': frequency.name,
      'weekDays': weekDays,
      'monthDays': monthDays,
      'targetPerDay': targetPerDay,
      'reminderTime': reminderTime,
      'createdAt': createdAt.toIso8601String(),
      'archived': archived,
      'order': order,
    };
  }

  factory Habit.fromJson(Map<String, dynamic> json) {
    final freqStr = json['frequency'] as String?;
    final knownFreq = HabitFrequency.values.where((e) => e.name == freqStr).firstOrNull;
    if (knownFreq == null && freqStr != null) {
      // ignore: avoid_print
      debugPrint('[Habit.fromJson] 未知 frequency "$freqStr"，回退为 daily');
    }
    return Habit(
      id: _req(json, 'id'),
      name: _req(json, 'name'),
      description: json['description'],
      icon: json['icon'] as String? ?? '🎯',
      colorValue: json['colorValue'] as int? ?? 0xFFE85D4C,
      frequency: knownFreq ?? HabitFrequency.daily,
      weekDays:
          json['weekDays'] != null ? List<int>.from(json['weekDays']) : null,
      monthDays:
          json['monthDays'] != null ? List<int>.from(json['monthDays']) : null,
      targetPerDay: json['targetPerDay'] as int? ?? 1,
      reminderTime: json['reminderTime'],
      createdAt: DateTime.parse(_req(json, 'createdAt')),
      archived: json['archived'] as bool? ?? false,
      order: json['order'] as int? ?? 0,
    );
  }
}

enum HabitFrequency { daily, weekly, monthly }

// 打卡记录
class CheckIn {
  final String id;
  final String habitId;
  final String date;
  final int count;
  final String? note;
  final DateTime createdAt;

  CheckIn({
    required this.id,
    required this.habitId,
    required this.date,
    required this.count,
    this.note,
    required this.createdAt,
  });

  Map<String, dynamic> toJson() {
    return {
      'id': id,
      'habitId': habitId,
      'date': date,
      'count': count,
      'note': note,
      'createdAt': createdAt.toIso8601String(),
    };
  }

  factory CheckIn.fromJson(Map<String, dynamic> json) {
    return CheckIn(
      id: _req(json, 'id'),
      habitId: _req(json, 'habitId'),
      date: _req(json, 'date'),
      count: _req(json, 'count'),
      note: json['note'],
      createdAt: DateTime.parse(_req(json, 'createdAt')),
    );
  }
}

// 成就
class Achievement {
  final String id;
  final String habitId;
  final String name;
  final String description;
  final String icon;
  final DateTime unlockedAt;

  Achievement({
    required this.id,
    required this.habitId,
    required this.name,
    required this.description,
    required this.icon,
    required this.unlockedAt,
  });

  Map<String, dynamic> toJson() {
    return {
      'id': id,
      'habitId': habitId,
      'name': name,
      'description': description,
      'icon': icon,
      'unlockedAt': unlockedAt.toIso8601String(),
    };
  }

  factory Achievement.fromJson(Map<String, dynamic> json) {
    return Achievement(
      id: _req(json, 'id'),
      habitId: _req(json, 'habitId'),
      name: _req(json, 'name'),
      description: json['description'] as String? ?? '',
      icon: json['icon'] as String? ?? '🏆',
      unlockedAt: DateTime.parse(_req(json, 'unlockedAt')),
    );
  }
}

// 矛盾分析洞察
class AnalysisInsight {
  final String id;
  final String mainConflict;
  final List<String> suggestedHabits;
  final DateTime createdAt;

  AnalysisInsight({
    required this.id,
    required this.mainConflict,
    required this.suggestedHabits,
    required this.createdAt,
  });

  Map<String, dynamic> toJson() {
    return {
      'id': id,
      'mainConflict': mainConflict,
      'suggestedHabits': suggestedHabits,
      'createdAt': createdAt.toIso8601String(),
    };
  }

  factory AnalysisInsight.fromJson(Map<String, dynamic> json) {
    return AnalysisInsight(
      id: json['id'],
      mainConflict: json['mainConflict'],
      suggestedHabits: List<String>.from(json['suggestedHabits']),
      createdAt: DateTime.parse(json['createdAt']),
    );
  }
}

// 统计数据
class HabitStats {
  final int currentStreak;
  final int longestStreak;
  final int totalCount;
  final double completionRate;
  final List<String> checkInDates;

  HabitStats({
    required this.currentStreak,
    required this.longestStreak,
    required this.totalCount,
    required this.completionRate,
    required this.checkInDates,
  });
}
