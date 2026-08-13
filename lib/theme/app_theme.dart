import 'package:flutter/material.dart';

class AppTheme {
  // ── 核心色 ─────────────────────────────────────────────
  static const Color primary = Color(0xFFE85D4C);
  static const Color primaryDark = Color(0xFFC94A3A);
  static const Color primaryLight = Color(0xFFFF7B6B);

  // 强调色 - 琥珀金
  static const Color accent = Color(0xFFF5A623);
  static const Color accentLight = Color(0xFFFFC857);

  // 成功色
  static const Color success = Color(0xFF4ADE80);
  static const Color successLight = Color(0xFF86EFAC);

  // 警告色
  static const Color warning = Color(0xFFFBBF24);

  // 背景色（深色）
  static const Color bgDark = Color(0xFF0F0F1A);
  static const Color bgCard = Color(0xFF1A1A2E);
  static const Color bgElevated = Color(0xFF252542);
  // 玻璃态：半透明卡片
  static const Color glassDark = Color(0x1AFFFFFF);
  static const Color glassBorderDark = Color(0x29FFFFFF);

  // 背景色（浅色）
  static const Color bgLight = Color(0xFFF5F5F7);
  static const Color bgCardLight = Color(0xFFFFFFFF);
  static const Color bgElevatedLight = Color(0xFFF0F0F2);

  // 文字色
  static const Color textPrimary = Color(0xFFFFFFFF);
  static const Color textSecondary = Color(0xFFA0A0B0);
  static const Color textTertiary = Color(0xFF6B6B7B);
  static const Color textPrimaryLight = Color(0xFF1A1A2E);
  static const Color textSecondaryLight = Color(0xFF6B6B7B);

  // ── 圆角 ───────────────────────────────────────────────
  static const double radiusXs = 6;
  static const double radiusSm = 10;
  static const double radiusMd = 14;
  static const double radiusLg = 20;
  static const double radiusXl = 28;
  static const double radiusFull = 999;

  // ── 阴影预设 ──────────────────────────────────────────
  static List<BoxShadow> shadowSm(BuildContext context) => [
        BoxShadow(
          color: _shadowColor(context).withOpacity(0.06),
          blurRadius: 8,
          offset: const Offset(0, 2),
        ),
      ];

  static List<BoxShadow> shadowMd(BuildContext context) => [
        BoxShadow(
          color: _shadowColor(context).withOpacity(0.08),
          blurRadius: 16,
          offset: const Offset(0, 4),
        ),
        BoxShadow(
          color: _shadowColor(context).withOpacity(0.04),
          blurRadius: 8,
          offset: const Offset(0, 2),
        ),
      ];

  static List<BoxShadow> shadowLg(BuildContext context) => [
        BoxShadow(
          color: _shadowColor(context).withOpacity(0.12),
          blurRadius: 32,
          offset: const Offset(0, 12),
        ),
        BoxShadow(
          color: _shadowColor(context).withOpacity(0.06),
          blurRadius: 12,
          offset: const Offset(0, 4),
        ),
      ];

  static List<BoxShadow> shadowPrimary(BuildContext context) => [
        BoxShadow(
          color: primary.withOpacity(0.25),
          blurRadius: 20,
          offset: const Offset(0, 8),
        ),
        BoxShadow(
          color: primary.withOpacity(0.12),
          blurRadius: 8,
          offset: const Offset(0, 3),
        ),
      ];

  static Color _shadowColor(BuildContext context) {
    final isDark = Theme.of(context).brightness == Brightness.dark;
    return isDark ? Colors.black : const Color(0xFF000000);
  }

  // ── 渐变预设 ───────────────────────────────────────────
  static const LinearGradient primaryGradient = LinearGradient(
    begin: Alignment.topLeft,
    end: Alignment.bottomRight,
    colors: [primary, primaryDark],
  );

  static const LinearGradient accentGradient = LinearGradient(
    begin: Alignment.topLeft,
    end: Alignment.bottomRight,
    colors: [accent, accentLight],
  );

  static const LinearGradient successGradient = LinearGradient(
    begin: Alignment.topLeft,
    end: Alignment.bottomRight,
    colors: [success, Color(0xFF22C55E)],
  );

  static LinearGradient glassGradient(BuildContext context) {
    final isDark = Theme.of(context).brightness == Brightness.dark;
    if (isDark) {
      return LinearGradient(
        begin: Alignment.topLeft,
        end: Alignment.bottomRight,
        colors: [
          Colors.white.withOpacity(0.12),
          Colors.white.withOpacity(0.04),
        ],
      );
    } else {
      return LinearGradient(
        begin: Alignment.topLeft,
        end: Alignment.bottomRight,
        colors: [
          Colors.white.withOpacity(0.9),
          Colors.white.withOpacity(0.7),
        ],
      );
    }
  }

  // ── 主题 Data ──────────────────────────────────────────

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
      colorScheme: ColorScheme.dark(
        primary: primary,
        secondary: accent,
        surface: bgCard,
        surfaceContainerHighest: bgElevated,
        error: const Color(0xFFEF4444),
        onPrimary: textPrimary,
        onSecondary: textPrimary,
        onSurface: textPrimary,
        outline: Colors.white.withOpacity(0.12),
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
            letterSpacing: 0.2,
          ),
        ),
      ),
      outlinedButtonTheme: OutlinedButtonThemeData(
        style: OutlinedButton.styleFrom(
          foregroundColor: textPrimary,
          side: BorderSide(color: Colors.white.withOpacity(0.15)),
          padding: const EdgeInsets.symmetric(horizontal: 20, vertical: 12),
          shape: RoundedRectangleBorder(
            borderRadius: BorderRadius.circular(radiusFull),
          ),
          textStyle: const TextStyle(
            fontSize: 14,
            fontWeight: FontWeight.w500,
          ),
        ),
      ),
      textButtonTheme: TextButtonThemeData(
        style: TextButton.styleFrom(
          foregroundColor: primary,
          textStyle: const TextStyle(
            fontSize: 14,
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
        hintStyle: TextStyle(color: textSecondary.withOpacity(0.6)),
      ),
      bottomNavigationBarTheme: const BottomNavigationBarThemeData(
        backgroundColor: bgCard,
        selectedItemColor: primary,
        unselectedItemColor: textSecondary,
        type: BottomNavigationBarType.fixed,
        elevation: 0,
      ),
      snackBarTheme: SnackBarThemeData(
        backgroundColor: bgCard,
        contentTextStyle: const TextStyle(color: textPrimary),
        shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(radiusMd)),
        behavior: SnackBarBehavior.floating,
      ),
      dialogTheme: DialogTheme(
        backgroundColor: bgCard,
        shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(radiusLg)),
        titleTextStyle: const TextStyle(
          color: textPrimary,
          fontSize: 18,
          fontWeight: FontWeight.w600,
        ),
      ),
      bottomSheetTheme: BottomSheetThemeData(
        backgroundColor: bgCard,
        shape: const RoundedRectangleBorder(
          borderRadius: BorderRadius.vertical(top: Radius.circular(radiusXl)),
        ),
      ),
      tabBarTheme: TabBarTheme(
        labelColor: primary,
        unselectedLabelColor: textSecondary,
        indicatorColor: primary,
        indicatorSize: TabBarIndicatorSize.label,
        dividerColor: Colors.transparent,
        labelStyle: const TextStyle(fontSize: 13, fontWeight: FontWeight.w600),
        unselectedLabelStyle: const TextStyle(fontSize: 13, fontWeight: FontWeight.w500),
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
      colorScheme: ColorScheme.light(
        primary: primary,
        secondary: accent,
        surface: bgCardLight,
        surfaceContainerHighest: bgElevatedLight,
        error: const Color(0xFFEF4444),
        onPrimary: textPrimary,
        onSecondary: textPrimary,
        onSurface: textPrimaryLight,
        outline: Colors.black.withOpacity(0.1),
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
            letterSpacing: 0.2,
          ),
        ),
      ),
      outlinedButtonTheme: OutlinedButtonThemeData(
        style: OutlinedButton.styleFrom(
          foregroundColor: primary,
          side: BorderSide(color: primary.withOpacity(0.3)),
          padding: const EdgeInsets.symmetric(horizontal: 20, vertical: 12),
          shape: RoundedRectangleBorder(
            borderRadius: BorderRadius.circular(radiusFull),
          ),
          textStyle: const TextStyle(
            fontSize: 14,
            fontWeight: FontWeight.w500,
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
        hintStyle: TextStyle(color: textSecondaryLight.withOpacity(0.6)),
      ),
      bottomNavigationBarTheme: const BottomNavigationBarThemeData(
        backgroundColor: bgCardLight,
        selectedItemColor: primary,
        unselectedItemColor: textSecondaryLight,
        type: BottomNavigationBarType.fixed,
        elevation: 0,
      ),
      snackBarTheme: SnackBarThemeData(
        backgroundColor: bgCardLight,
        contentTextStyle: const TextStyle(color: textPrimaryLight),
        shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(radiusMd)),
        behavior: SnackBarBehavior.floating,
      ),
      dialogTheme: DialogTheme(
        backgroundColor: bgCardLight,
        shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(radiusLg)),
        titleTextStyle: const TextStyle(
          color: textPrimaryLight,
          fontSize: 18,
          fontWeight: FontWeight.w600,
        ),
      ),
      bottomSheetTheme: BottomSheetThemeData(
        backgroundColor: bgCardLight,
        shape: const RoundedRectangleBorder(
          borderRadius: BorderRadius.vertical(top: Radius.circular(radiusXl)),
        ),
      ),
      tabBarTheme: TabBarTheme(
        labelColor: primary,
        unselectedLabelColor: textSecondaryLight,
        indicatorColor: primary,
        indicatorSize: TabBarIndicatorSize.label,
        dividerColor: Colors.transparent,
        labelStyle: const TextStyle(fontSize: 13, fontWeight: FontWeight.w600),
        unselectedLabelStyle: const TextStyle(fontSize: 13, fontWeight: FontWeight.w500),
      ),
    );
  }
}

// ── 主题感知颜色辅助 ─────────────────────────────────────────
class AppColors {
  // 习惯颜色盘
  static const List<int> habitColors = [
    0xFFEF4444, 0xFFF97316, 0xFFF59E0B, 0xFF22C55E,
    0xFF14B8A6, 0xFF06B6D4, 0xFF3B82F6, 0xFF8B5CF6,
    0xFFA855F7, 0xFFEC4899, 0xFFF43F5E, 0xFF64748B,
  ];

  // 习惯图标
  static const List<String> habitIcons = [
    '🏃', '📚', '💧', '🧘', '🌙', '📝', '💪', '🗣️',
    '✍️', '🎯', '💼', '🍎', '🧠', '⭐', '🔥', '🌟',
    '💡', '🎨', '🎵', '🏋️',
  ];

  // 彩屑动画颜色
  static const List<Color> confettiColors = [
    Color(0xFFFF6B6B),
    Color(0xFFFFD93D),
    Color(0xFF6BCB77),
    Color(0xFF4D96FF),
    Color(0xFFFF6B35),
    Color(0xFFE85D4C),
    Color(0xFFA855F7),
    Color(0xFFFFC857),
  ];
}

// ── 主题感知颜色 ─────────────────────────────────────────────
class AppThemeColors {
  static Color card(BuildContext context) =>
      Theme.of(context).brightness == Brightness.dark
          ? AppTheme.bgCard
          : AppTheme.bgCardLight;

  static Color elevated(BuildContext context) =>
      Theme.of(context).brightness == Brightness.dark
          ? AppTheme.bgElevated
          : AppTheme.bgElevatedLight;

  static Color textPrimary(BuildContext context) =>
      Theme.of(context).brightness == Brightness.dark
          ? AppTheme.textPrimary
          : AppTheme.textPrimaryLight;

  static Color textSecondary(BuildContext context) =>
      Theme.of(context).brightness == Brightness.dark
          ? AppTheme.textSecondary
          : AppTheme.textSecondaryLight;

  static Color scaffoldBg(BuildContext context) =>
      Theme.of(context).brightness == Brightness.dark
          ? AppTheme.bgDark
          : AppTheme.bgLight;

  static Color glassBorder(BuildContext context) =>
      Theme.of(context).brightness == Brightness.dark
          ? AppTheme.glassBorderDark
          : Colors.white.withOpacity(0.3);

  static Color glassBg(BuildContext context) =>
      Theme.of(context).brightness == Brightness.dark
          ? AppTheme.glassDark
          : Colors.white.withOpacity(0.7);
}
