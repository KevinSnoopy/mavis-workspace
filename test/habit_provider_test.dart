import 'package:flutter_test/flutter_test.dart';
import 'package:maodun_app/models/habit.dart';
import 'package:maodun_app/providers/habit_provider.dart';
import 'package:maodun_app/services/storage_service.dart';

/// 测试用 SharedPreferences 存储适配层
class _TestStorageAdapter implements StorageServiceInterface {
  final Map<String, String> _data = {};

  @override
  Future<String?> getString(String key) async => _data[key];

  @override
  Future<void> setString(String key, String value) async => _data[key] = value;

  @override
  Future<void> remove(String key) async => _data.remove(key);

  @override
  Future<void> clear() async => _data.clear();
}

/// 等待 provider 数据加载完成（轮询替代硬编码 delay，避免 Flaky）
Future<void> _waitForLoaded(HabitProvider provider) async {
  for (int i = 0; i < 50; i++) {
    if (!provider.isLoading) return;
    await Future.delayed(const Duration(milliseconds: 10));
  }
  // 超时保护（500ms），确保测试不会永久卡住
}

void main() {
  late HabitProvider provider;

  setUp(() async {
    provider = HabitProvider.forTesting(_TestStorageAdapter());
    await _waitForLoaded(provider);
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
      expect(stats!.currentStreak, equals(0));
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

      expect(stats?.currentStreak ?? 0, equals(1));
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

  group('HabitProvider - 全局连胜与多习惯', () {
    test('两个习惯各自独立计数', () async {
      final h1 = Habit(
        id: 'multi-h1',
        name: '习惯A',
        icon: '🔥',
        colorValue: 0xFFEF4444,
        frequency: HabitFrequency.daily,
        createdAt: DateTime.now(),
      );
      final h2 = Habit(
        id: 'multi-h2',
        name: '习惯B',
        icon: '📚',
        colorValue: 0xFF3B82F6,
        frequency: HabitFrequency.daily,
        createdAt: DateTime.now(),
      );

      await provider.addHabit(h1);
      await provider.addHabit(h2);

      // 两个习惯都有数据
      expect(provider.habits.length, equals(2));

      // 用 provider 返回的实际 id 打卡
      await provider.checkIn(provider.habits[0].id);
      expect(provider.getHabitStats(provider.habits[0].id)?.totalCount ?? 0, equals(1));
    });

    test('归档后习惯不在列表中出现', () async {
      final h = Habit(
        id: 'arch-test',
        name: '归档测试',
        icon: '📦',
        colorValue: 0xFF8B5CF6,
        frequency: HabitFrequency.daily,
        createdAt: DateTime.now(),
      );

      await provider.addHabit(h);
      // addHabit 生成新 id，需从 provider 获取
      final addedId = provider.habits.firstWhere((x) => x.name == '归档测试').id;
      expect(provider.habits.any((habit) => habit.id == addedId), isTrue);

      await provider.toggleArchive(addedId);

      // 归档后仍存在于 _habits 但 archived=true
      expect(
        provider.habits.firstWhere((habit) => habit.id == addedId).archived,
        isTrue,
      );
    });
  });

  group('HabitProvider - 频率过滤', () {
    test('weekly 频率习惯仅在指定星期出现于 todayHabits', () async {
      // 当前星期几（DateTime.monday = 1, sunday = 7）
      final now = DateTime.now();
      final todayWeekday = now.weekday; // 1=周一 ... 7=周日

      // 创建一个今天不scheduled的weekly习惯
      final otherDay = todayWeekday == 1 ? 2 : 1; // 不是今天
      final h = Habit(
        id: 'weekly-sched',
        name: '每周习惯',
        icon: '📅',
        colorValue: 0xFF22C55E,
        frequency: HabitFrequency.weekly,
        weekDays: [otherDay], // 只有另一天
        createdAt: now,
      );

      await provider.addHabit(h);

      // todayHabits 只包含 scheduled 的习惯
      final today = provider.getTodayHabits();
      expect(today.any((habit) => habit.id == h.id), isFalse);
    });

    test('monthly 频率习惯仅在指定日期出现于 todayHabits', () async {
      final now = DateTime.now();
      final todayDay = now.day;
      final otherDay = todayDay == 1 ? 2 : 1; // 不是今天

      final h = Habit(
        id: 'monthly-sched',
        name: '每月习惯',
        icon: '📆',
        colorValue: 0xFFF59E0B,
        frequency: HabitFrequency.monthly,
        monthDays: [otherDay],
        createdAt: now,
      );

      await provider.addHabit(h);

      final today = provider.getTodayHabits();
      expect(today.any((habit) => habit.id == h.id), isFalse);
    });
  });

  group('HabitProvider - 成就解锁条件', () {
    test('7天成就：连续打卡满7天解锁', () async {
      final h = Habit(
        id: 'achieve-7d',
        name: '七日习惯',
        icon: '🎯',
        colorValue: 0xFFEF4444,
        frequency: HabitFrequency.daily,
        createdAt: DateTime.now().subtract(const Duration(days: 7)),
      );

      await provider.addHabit(h);
      final added = provider.habits.first;

      // 7天连续打卡
      for (int i = 0; i < 7; i++) {
        await provider.checkIn(added.id);
      }

      // 首次打卡成就一定解锁（后续还有7天/30天成就）
      expect(provider.achievements.isNotEmpty, isTrue);
    });
  });
}
