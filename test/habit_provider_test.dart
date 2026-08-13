import 'package:flutter_test/flutter_test.dart';
import 'package:shared_preferences/shared_preferences.dart';
import 'package:maodun_app/models/habit.dart';
import 'package:maodun_app/providers/habit_provider.dart';

void main() {
  late HabitProvider provider;

  setUp(() async {
    // 每次测试前重置 Mock SharedPreferences
    SharedPreferences.setMockInitialValues({});
    provider = HabitProvider();
    // 等待首次数据加载完成
    await Future.delayed(const Duration(milliseconds: 100));
  });

  group('HabitProvider - 习惯 CRUD', () {
    test('addHabit 后 habits 列表包含该习惯', () async {
      final habit = Habit(
        id: 'test-1',
        name: '每日阅读',
        icon: '📚',
        colorValue: 0xFFEF4444,
        frequency: HabitFrequency.daily,
        createdAt: DateTime.now(),
      );

      await provider.addHabit(habit);

      expect(provider.habits.any((h) => h.name == '每日阅读'), isTrue);
    });

    test('deleteHabit 后习惯从列表移除', () async {
      final habit = Habit(
        id: 'del-test',
        name: '待删除习惯',
        icon: '🎯',
        colorValue: 0xFF22C55E,
        frequency: HabitFrequency.daily,
        createdAt: DateTime.now(),
      );

      await provider.addHabit(habit);
      final added = provider.habits.firstWhere((h) => h.name == '待删除习惯');
      await provider.deleteHabit(added.id);

      expect(provider.habits.any((h) => h.id == added.id), isFalse);
    });

    test('toggleArchive 切换归档状态', () async {
      final habit = Habit(
        id: 'archive-test',
        name: '归档测试',
        icon: '📦',
        colorValue: 0xFF3B82F6,
        frequency: HabitFrequency.daily,
        createdAt: DateTime.now(),
      );

      await provider.addHabit(habit);
      final added = provider.habits.first;
      expect(added.archived, isFalse);

      await provider.toggleArchive(added.id);
      final toggled = provider.habits.firstWhere((h) => h.id == added.id);
      expect(toggled.archived, isTrue);

      await provider.toggleArchive(added.id);
      final restored = provider.habits.firstWhere((h) => h.id == added.id);
      expect(restored.archived, isFalse);
    });
  });

  group('HabitProvider - 打卡', () {
    test('checkIn 后 getTodayCheckIn 返回打卡记录', () async {
      final habit = Habit(
        id: 'checkin-test',
        name: '打卡测试',
        icon: '✅',
        colorValue: 0xFFEF4444,
        frequency: HabitFrequency.daily,
        createdAt: DateTime.now(),
      );

      await provider.addHabit(habit);
      final added = provider.habits.first;

      await provider.checkIn(added.id);

      final checkIn = provider.getTodayCheckIn(added.id);
      expect(checkIn, isNotNull);
      expect(checkIn!.habitId, equals(added.id));
    });

    test('cancelCheckIn 移除当日打卡记录', () async {
      final habit = Habit(
        id: 'cancel-test',
        name: '取消测试',
        icon: '❌',
        colorValue: 0xFFF59E0B,
        frequency: HabitFrequency.daily,
        createdAt: DateTime.now(),
      );

      await provider.addHabit(habit);
      final added = provider.habits.first;

      await provider.checkIn(added.id);
      expect(provider.getTodayCheckIn(added.id), isNotNull);

      await provider.cancelCheckIn(added.id);
      expect(provider.getTodayCheckIn(added.id), isNull);
    });

    test('重复 checkIn 累加 count', () async {
      final habit = Habit(
        id: 'repeat-checkin',
        name: '重复打卡',
        icon: '🔢',
        colorValue: 0xFF22C55E,
        frequency: HabitFrequency.daily,
        createdAt: DateTime.now(),
      );

      await provider.addHabit(habit);
      final added = provider.habits.first;

      await provider.checkIn(added.id, count: 1);
      await provider.checkIn(added.id, count: 1);

      final checkIn = provider.getTodayCheckIn(added.id);
      expect(checkIn!.count, equals(2));
    });
  });

  group('HabitProvider - 统计', () {
    test('globalStreak 初始为 0', () {
      expect(provider.globalStreak, equals(0));
    });

    test('getHabitStats 返回非空统计', () async {
      final habit = Habit(
        id: 'stats-test',
        name: '统计测试',
        icon: '📊',
        colorValue: 0xFF8B5CF6,
        frequency: HabitFrequency.daily,
        createdAt: DateTime.now(),
      );

      await provider.addHabit(habit);
      final added = provider.habits.first;
      final stats = provider.getHabitStats(added.id);

      expect(stats, isNotNull);
      expect(stats.currentStreak, equals(0));
      expect(stats.totalCount, equals(0));
      expect(stats.completionRate, equals(0.0));
    });

    test('打卡后 currentStreak 为 1', () async {
      final habit = Habit(
        id: 'streak-test',
        name: '连胜测试',
        icon: '🔥',
        colorValue: 0xFFFF6B35,
        frequency: HabitFrequency.daily,
        createdAt: DateTime.now(),
      );

      await provider.addHabit(habit);
      final added = provider.habits.first;

      await provider.checkIn(added.id);
      final stats = provider.getHabitStats(added.id);

      expect(stats.currentStreak, equals(1));
    });
  });

  group('HabitProvider - 成就', () {
    test('首次打卡解锁"首次打卡"成就', () async {
      final habit = Habit(
        id: 'achieve-test',
        name: '成就测试',
        icon: '🏆',
        colorValue: 0xFFF5A623,
        frequency: HabitFrequency.daily,
        createdAt: DateTime.now(),
      );

      await provider.addHabit(habit);
      final added = provider.habits.first;

      await provider.checkIn(added.id);

      expect(provider.achievements.any((a) => a.name == '首次打卡'), isTrue);
    });
  });

  group('HabitProvider - 数据持久化', () {
    test('clearAll 清空所有数据', () async {
      final habit = Habit(
        id: 'clear-test',
        name: '清除测试',
        icon: '🗑️',
        colorValue: 0xFFEF4444,
        frequency: HabitFrequency.daily,
        createdAt: DateTime.now(),
      );

      await provider.addHabit(habit);
      await provider.checkIn(provider.habits.first.id);

      expect(provider.habits.isNotEmpty, isTrue);

      await provider.clearAll();

      expect(provider.habits.isEmpty, isTrue);
      expect(provider.checkIns.isEmpty, isTrue);
      expect(provider.achievements.isEmpty, isTrue);
    });
  });

  group('HabitProvider - 导入导出', () {
    test('importData 正确解析 JSON', () async {
      const json = '''
      {
        "habits": [
          {
            "id": "import-1",
            "name": "导入习惯",
            "icon": "📥",
            "colorValue": 4285562854,
            "frequency": "daily",
            "createdAt": "2026-01-01T00:00:00.000",
            "archived": false,
            "order": 0,
            "targetPerDay": 1
          }
        ],
        "checkIns": []
      }
      ''';

      await provider.importData(json);

      expect(provider.habits.any((h) => h.name == '导入习惯'), isTrue);
    });
  });
}
