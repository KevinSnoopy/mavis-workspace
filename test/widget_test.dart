import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:provider/provider.dart';
import 'package:shared_preferences/shared_preferences.dart';
import 'package:maodun_app/models/habit.dart';
import 'package:maodun_app/providers/habit_provider.dart';
import 'package:maodun_app/providers/theme_provider.dart';
import 'package:maodun_app/providers/notification_provider.dart';
import 'package:maodun_app/services/storage_service.dart';
import 'package:maodun_app/theme/app_theme.dart';

/// 测试用内存存储适配层（不需要 native 平台）
class _InMemoryStorage implements StorageServiceInterface {
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

void main() {
  setUp(() {
    SharedPreferences.setMockInitialValues({});
  });

  Widget buildTestApp({required Widget child, HabitProvider? habitProvider}) {
    return MultiProvider(
      providers: [
        ChangeNotifierProvider<HabitProvider>(
          create: (_) => habitProvider ?? HabitProvider.forTesting(_InMemoryStorage()),
        ),
        ChangeNotifierProvider(create: (_) => ThemeProvider()),
        ChangeNotifierProvider(create: (_) => NotificationProvider()),
      ],
      child: MaterialApp(
        theme: AppTheme.lightTheme,
        darkTheme: AppTheme.darkTheme,
        themeMode: ThemeMode.dark,
        home: child,
      ),
    );
  }

  group('Widget 测试 - 打卡流程', () {
    testWidgets('点击打卡按钮触发打卡', (WidgetTester tester) async {
      await tester.pumpWidget(buildTestApp(
        child: _TestCheckInWidget(),
      ));
      await tester.pumpAndSettle();

      // 初始显示"未打卡"
      expect(find.text('未打卡'), findsOneWidget);

      // 点击打卡按钮
      await tester.tap(find.byIcon(Icons.add));
      await tester.pumpAndSettle();

      // 应该显示"已打卡"
      expect(find.text('已打卡'), findsOneWidget);
    });

    testWidgets('再次点击取消打卡', (WidgetTester tester) async {
      await tester.pumpWidget(buildTestApp(
        child: _TestCheckInWidget(),
      ));
      await tester.pumpAndSettle();

      // 打卡
      await tester.tap(find.byIcon(Icons.add));
      await tester.pumpAndSettle();
      expect(find.text('已打卡'), findsOneWidget);

      // 取消打卡
      await tester.tap(find.byIcon(Icons.check));
      await tester.pumpAndSettle();
      expect(find.text('未打卡'), findsOneWidget);
    });
  });

  group('Widget 测试 - 主题切换', () {
    testWidgets('主题切换按钮存在', (WidgetTester tester) async {
      await tester.pumpWidget(buildTestApp(
        child: Scaffold(
          body: Consumer<ThemeProvider>(
            builder: (context, theme, _) {
              return ElevatedButton(
                onPressed: () => theme.toggle(),
                child: Text(theme.isDark ? '浅色' : '深色'),
              );
            },
          ),
        ),
      ));
      await tester.pumpAndSettle();

      expect(find.text('浅色'), findsOneWidget);

      await tester.tap(find.byType(ElevatedButton));
      await tester.pumpAndSettle();

      expect(find.text('深色'), findsOneWidget);
    });
  });

  group('Widget 测试 - 习惯卡片', () {
    testWidgets('习惯卡片正确渲染', (WidgetTester tester) async {
      await tester.pumpWidget(buildTestApp(
        child: _TestHabitCard(
          habit: Habit(
            id: 'card-test',
            name: '测试习惯',
            icon: '📚',
            colorValue: 0xFFEF4444,
            frequency: HabitFrequency.daily,
            createdAt: DateTime.now(),
          ),
        ),
      ));
      await tester.pumpAndSettle();

      expect(find.text('测试习惯'), findsOneWidget);
      expect(find.text('📚'), findsOneWidget);
    });
  });
}

// ─── 测试用 Widget ─────────────────────────────────────────────

/// 简单打卡测试 widget
class _TestCheckInWidget extends StatelessWidget {
  @override
  Widget build(BuildContext context) {
    final provider = context.watch<HabitProvider>();
    final today = provider.getTodayCheckIn('test-habit');
    final isDone = today != null;

    return Scaffold(
      body: Center(
        child: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            Text(isDone ? '已打卡' : '未打卡'),
            IconButton(
              icon: Icon(isDone ? Icons.check : Icons.add),
              onPressed: () {
                if (isDone) {
                  provider.cancelCheckIn('test-habit');
                } else {
                  provider.checkIn('test-habit');
                }
              },
            ),
          ],
        ),
      ),
    );
  }
}

/// 习惯卡片测试 widget
class _TestHabitCard extends StatelessWidget {
  final Habit habit;

  const _TestHabitCard({required this.habit});

  @override
  Widget build(BuildContext context) {
    return Card(
      child: ListTile(
        leading: Text(habit.icon, style: const TextStyle(fontSize: 24)),
        title: Text(habit.name),
      ),
    );
  }
}
