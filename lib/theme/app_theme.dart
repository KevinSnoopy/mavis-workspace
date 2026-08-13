import 'package:flutter/material.dart';

class AppTheme {
  // 主色调 - 珊瑚红
  static const Color primary = Color(0xFFE85D4C);
  static const Color primaryDark = Color(0xFFC94A3A);
  static const Color primaryLight = Color(0xFFFF7B6B);
  
  // 强调色 - 琥珀金
  static const Color accent = Color(0xFFF5A623);
  
  // 成功色
  static const Color success = Color(0xFF4ADE80);
  
  // 背景色
  static const Color bgDark = Color(0xFF0F0F1A);
  static const Color bgCard = Color(0xFF1A1A2E);
  static const Color bgElevated = Color(0xFF252542);
  
  // 文字色（深色主题默认）
  static const Color textPrimary = Color(0xFFFFFFFF);
  static const Color textSecondary = Color(0xFFA0A0B0);

  // 浅色主题文字色
  static const Color textPrimaryLight = Color(0xFF1A1A2E);
  static const Color textSecondaryLight = Color(0xFF6B6B7B);

  // 浅色主题背景色
  static const Color bgLight = Color(0xFFF5F5F7);
  static const Color bgCardLight = Color(0xFFFFFFFF);
  static const Color bgElevatedLight = Color(0xFFF0F0F2);
  
  // 圆角
  static const double radiusSm = 12;
  static const double radiusMd = 16;
  static const double radiusLg = 24;
  static const double radiusFull = 999;

  static ThemeData get darkTheme {
    return ThemeData(
      useMaterial3: true,
      brightness: Brightness.dark,
      primaryColor: primary,
      scaffoldBackgroundColor: bgDark,
      pageTransitionsTheme: const PageTransitionsTheme(
        builders: {
          TargetPlatform.android: FadeUpwardsPageTransitionsBuilder(),
          TargetPlatform.iOS: CupertinoPageTransitionsBuilder(),
          TargetPlatform.linux: FadeUpwardsPageTransitionsBuilder(),
          TargetPlatform.macOS: CupertinoPageTransitionsBuilder(),
          TargetPlatform.windows: FadeUpwardsPageTransitionsBuilder(),
        },
      ),
      colorScheme: const ColorScheme.dark(
        primary: primary,
        secondary: accent,
        surface: bgCard,
        error: Color(0xFFEF4444),
      ),
      appBarTheme: const AppBarTheme(
        backgroundColor: bgDark,
        elevation: 0,
        centerTitle: true,
        titleTextStyle: TextStyle(
          color: textPrimary,
          fontSize: 18,
          fontWeight: FontWeight.w600,
        ),
      ),
      cardTheme: CardTheme(
        color: bgCard,
        elevation: 0,
        shape: RoundedRectangleBorder(
          borderRadius: BorderRadius.circular(radiusMd),
          side: BorderSide(color: Colors.white.withOpacity(0.08)),
        ),
      ),
      elevatedButtonTheme: ElevatedButtonThemeData(
        style: ElevatedButton.styleFrom(
          backgroundColor: primary,
          foregroundColor: textPrimary,
          elevation: 0,
          padding: const EdgeInsets.symmetric(horizontal: 24, vertical: 14),
          shape: RoundedRectangleBorder(
            borderRadius: BorderRadius.circular(radiusFull),
          ),
          textStyle: const TextStyle(
            fontSize: 16,
            fontWeight: FontWeight.w600,
          ),
        ),
      ),
      inputDecorationTheme: InputDecorationTheme(
        filled: true,
        fillColor: bgElevated,
        border: OutlineInputBorder(
          borderRadius: BorderRadius.circular(radiusMd),
          borderSide: BorderSide.none,
        ),
        contentPadding: const EdgeInsets.all(16),
      ),
      bottomNavigationBarTheme: const BottomNavigationBarThemeData(
        backgroundColor: bgCard,
        selectedItemColor: primary,
        unselectedItemColor: textSecondary,
        type: BottomNavigationBarType.fixed,
        elevation: 0,
      ),
    );
  }

  static ThemeData get lightTheme {
    return ThemeData(
      useMaterial3: true,
      brightness: Brightness.light,
      primaryColor: primary,
      scaffoldBackgroundColor: bgLight,
      pageTransitionsTheme: const PageTransitionsTheme(
        builders: {
          TargetPlatform.android: FadeUpwardsPageTransitionsBuilder(),
          TargetPlatform.iOS: CupertinoPageTransitionsBuilder(),
          TargetPlatform.linux: FadeUpwardsPageTransitionsBuilder(),
          TargetPlatform.macOS: CupertinoPageTransitionsBuilder(),
          TargetPlatform.windows: FadeUpwardsPageTransitionsBuilder(),
        },
      ),
      colorScheme: const ColorScheme.light(
        primary: primary,
        secondary: accent,
        surface: bgCardLight,
        error: Color(0xFFEF4444),
      ),
      appBarTheme: const AppBarTheme(
        backgroundColor: bgLight,
        elevation: 0,
        centerTitle: true,
        titleTextStyle: TextStyle(
          color: textPrimaryLight,
          fontSize: 18,
          fontWeight: FontWeight.w600,
        ),
        iconTheme: IconThemeData(color: textPrimaryLight),
      ),
      cardTheme: CardTheme(
        color: bgCardLight,
        elevation: 0,
        shape: RoundedRectangleBorder(
          borderRadius: BorderRadius.circular(radiusMd),
          side: BorderSide(color: Colors.black.withOpacity(0.08)),
        ),
      ),
      dividerTheme: DividerThemeData(
        color: Colors.black.withOpacity(0.06),
        thickness: 1,
      ),
      elevatedButtonTheme: ElevatedButtonThemeData(
        style: ElevatedButton.styleFrom(
          backgroundColor: primary,
          foregroundColor: textPrimary,
          elevation: 0,
          padding: const EdgeInsets.symmetric(horizontal: 24, vertical: 14),
          shape: RoundedRectangleBorder(
            borderRadius: BorderRadius.circular(radiusFull),
          ),
        ),
      ),
      inputDecorationTheme: InputDecorationTheme(
        filled: true,
        fillColor: bgElevatedLight,
        border: OutlineInputBorder(
          borderRadius: BorderRadius.circular(radiusMd),
          borderSide: BorderSide.none,
        ),
        contentPadding: const EdgeInsets.all(16),
      ),
      bottomNavigationBarTheme: const BottomNavigationBarThemeData(
        backgroundColor: bgCardLight,
        selectedItemColor: primary,
        unselectedItemColor: textSecondaryLight,
        type: BottomNavigationBarType.fixed,
        elevation: 0,
      ),
      tabBarTheme: TabBarTheme(
        labelColor: primary,
        unselectedLabelColor: textSecondaryLight,
        indicatorColor: primary,
        indicatorSize: TabBarIndicatorSize.label,
        dividerColor: Colors.transparent,
      ),
    );
  }
}

// 主题感知颜色辅助
class AppThemeColors {
  /// 根据当前主题返回卡片背景色
  static Color card(BuildContext context) {
    return Theme.of(context).brightness == Brightness.dark
        ? AppTheme.bgCard
        : AppTheme.bgCardLight;
  }

  /// 根据当前主题返回提升层背景色
  static Color elevated(BuildContext context) {
    return Theme.of(context).brightness == Brightness.dark
        ? AppTheme.bgElevated
        : AppTheme.bgElevatedLight;
  }

  /// 根据当前主题返回主文字色
  static Color textPrimary(BuildContext context) {
    return Theme.of(context).brightness == Brightness.dark
        ? AppTheme.textPrimary
        : AppTheme.textPrimaryLight;
  }

  /// 根据当前主题返回次要文字色
  static Color textSecondary(BuildContext context) {
    return Theme.of(context).brightness == Brightness.dark
        ? AppTheme.textSecondary
        : AppTheme.textSecondaryLight;
  }

  /// 根据当前主题返回脚手架背景色
  static Color scaffoldBg(BuildContext context) {
    return Theme.of(context).brightness == Brightness.dark
        ? AppTheme.bgDark
        : AppTheme.bgLight;
  }
}

// 常用颜色
class AppColors {
  static const List<int> habitColors = [
    0xFFEF4444, // 红
    0xFFF97316, // 橙
    0xFFF59E0B, // 黄
    0xFF22C55E, // 绿
    0xFF14B8A6, // 青
    0xFF06B6D4, // 蓝
    0xFF3B82F6, // 蓝
    0xFF8B5CF6, // 紫
    0xFFA855F7, // 紫
    0xFFEC4899, // 粉
    0xFFF43F5E, // 红
    0xFF64748B, // 灰
  ];

  static const List<String> habitIcons = [
    '🏃', '📚', '💧', '🧘', '🌙', '📝', '💪', '🗣️', '✍️', '🎯',
    '💼', '🍎', '🧠', '⭐', '🔥', '🌟', '💡', '🎨', '🎵', '🏋️',
  ];
}
