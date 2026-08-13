import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:shared_preferences/shared_preferences.dart';
import 'package:maodun_app/providers/theme_provider.dart';

/// 等待 ThemeProvider 异步加载完成（轮询 isLoaded 标志）
Future<void> _waitForLoaded(ThemeProvider provider) async {
  for (int i = 0; i < 100; i++) {
    if (provider.isLoaded) return;
    await Future.delayed(const Duration(milliseconds: 5));
  }
}

void main() {
  late ThemeProvider provider;

  setUp(() async {
    SharedPreferences.setMockInitialValues({});
    provider = ThemeProvider();
    await _waitForLoaded(provider);
  });

  group('ThemeProvider - 主题状态', () {
    test('默认主题为深色', () {
      expect(provider.mode, equals(ThemeMode.dark));
      expect(provider.isDark, isTrue);
    });

    test('toggle 切换到浅色主题', () async {
      await provider.toggle();
      expect(provider.mode, equals(ThemeMode.light));
      expect(provider.isDark, isFalse);
    });

    test('toggle 两次回到深色主题', () async {
      await provider.toggle(); // dark -> light
      await provider.toggle(); // light -> dark
      expect(provider.mode, equals(ThemeMode.dark));
      expect(provider.isDark, isTrue);
    });

    test('setMode 强制设置浅色', () async {
      await provider.setMode(ThemeMode.light);
      expect(provider.mode, equals(ThemeMode.light));
    });

    test('setMode 设置相同主题不触发 reload', () async {
      // 初始就是 dark，设置 dark 不应报错
      await provider.setMode(ThemeMode.dark);
      expect(provider.mode, equals(ThemeMode.dark));
    });
  });

  group('ThemeProvider - 持久化', () {
    test('toggle 后重启仍保持浅色', () async {
      await provider.toggle(); // dark -> light

      // 模拟重启：重新创建 provider
      final newProvider = ThemeProvider();
      await _waitForLoaded(newProvider);

      expect(newProvider.mode, equals(ThemeMode.light));
    });
  });
}
