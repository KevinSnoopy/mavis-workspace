import 'dart:async';
import 'package:flutter/foundation.dart';
import 'package:shared_preferences/shared_preferences.dart';

import 'web_notification_stub.dart'
    if (dart.library.js_interop) 'web_notification.dart';

/// 通知服务
/// Web 端：浏览器 Notification API + SharedPreferences 定时器
/// 原生端：flutter_local_notifications
class NotificationService {
  static final NotificationService _instance = NotificationService._();
  factory NotificationService() => _instance;
  NotificationService._();

  bool _initialized = false;
  Timer? _reminderTimer;
  Timer? _periodicCheck;
  int _reminderHour = 9;
  int _reminderMinute = 0;
  String _permissionStatus = 'default';

  /// 习惯提醒：habitId → "HH:mm"
  final Map<String, String> _habitReminders = {};
  /// 每分钟检查一次，触发对应的 per-habit 通知
  Timer? _habitReminderTimer;

  // 提醒触发回调（通知 UI 显示）
  void Function()? onReminderDue;
  /// Per-habit 提醒回调：habitId → void
  void Function(String habitId)? onHabitReminderDue;

  String get permissionStatus => _permissionStatus;

  /// 更新 Web 通知权限状态（运行时用户操作后调用，同时持久化）
  Future<void> setPermissionStatus(String status) async {
    _permissionStatus = status;
    try {
      final prefs = await SharedPreferences.getInstance();
      await prefs.setString('notification_permission', status);
    } catch (_) {}
  }

  /// 初始化通知服务
  Future<void> init() async {
    if (_initialized) {
      await _loadReminderSettings();
      _scheduleReminder();
      _startHabitReminderTimer();
      return;
    }
    _initialized = true;

    if (kIsWeb) {
      await _initWeb();
    } else {
      await _initNative();
    }

    await _loadReminderSettings();
    _scheduleReminder();
    _startHabitReminderTimer();
  }

  Future<void> _initWeb() async {
    try {
      final prefs = await SharedPreferences.getInstance();
      final saved = prefs.getString('notification_permission');
      if (saved == 'granted' || saved == 'denied') {
        _permissionStatus = saved as String;
        return;
      }
      _permissionStatus = await requestWebPermission();
      await prefs.setString('notification_permission', _permissionStatus);
    } catch (_) {
      _permissionStatus = 'denied';
    }
  }

  Future<void> _initNative() async {}

  // ──────────────────── 全局每日提醒 ────────────────────

  /// 安排全局每日打卡提醒
  Future<void> scheduleDailyReminder({
    required int hour,
    required int minute,
  }) async {
    _reminderHour = hour;
    _reminderMinute = minute;

    try {
      final prefs = await SharedPreferences.getInstance();
      await prefs.setInt('reminder_hour', hour);
      await prefs.setInt('reminder_minute', minute);
    } catch (_) {}

    _cancelTimers();
    _scheduleReminder();
  }

  /// 取消全局每日提醒
  void cancelDailyReminder() {
    _cancelTimers();
    SharedPreferences.getInstance().then((prefs) {
      prefs.remove('reminder_hour');
      prefs.remove('reminder_minute');
    }).catchError((_) {});
  }

  /// 获取已保存的全局提醒时间（null 表示未设置）
  Future<(int, int)?> getReminderTime() async {
    try {
      final prefs = await SharedPreferences.getInstance();
      final hour = prefs.getInt('reminder_hour');
      final minute = prefs.getInt('reminder_minute');
      if (hour != null && minute != null) return (hour, minute);
    } catch (_) {}
    return null;
  }

  Future<void> _loadReminderSettings() async {
    try {
      final prefs = await SharedPreferences.getInstance();
      final hour = prefs.getInt('reminder_hour');
      final minute = prefs.getInt('reminder_minute');
      if (hour != null && minute != null) {
        _reminderHour = hour;
        _reminderMinute = minute;
      }
    } catch (_) {}
  }

  void _scheduleReminder() {
    final now = DateTime.now();
    var next = DateTime(now.year, now.month, now.day, _reminderHour, _reminderMinute);

    if (next.isBefore(now) || next.isAtSameMomentAs(now)) {
      next = next.add(const Duration(days: 1));
    }

    final delay = next.difference(now);

    _reminderTimer?.cancel();
    _reminderTimer = Timer(delay, _onReminderFired);

    _periodicCheck?.cancel();
    _periodicCheck = Timer.periodic(const Duration(minutes: 1), (_) => _checkReminder());
  }

  void _checkReminder() {
    final now = DateTime.now();
    if (now.hour == _reminderHour && now.minute == _reminderMinute) {
      _onReminderFired();
    }
    // 同时检查 per-habit 提醒
    _checkHabitReminders();
  }

  void _onReminderFired() {
    _scheduleReminder();

    if (kIsWeb && _permissionStatus == 'granted') {
      showWebNotification(
        title: '矛盾 · 每日打卡',
        body: '新的一天开始了，今天的习惯完成了吗？',
      );
    }

    onReminderDue?.call();
  }

  // ──────────────────── Per-habit 提醒 ────────────────────

  /// 更新所有习惯提醒（HabitProvider 在数据加载/变更时调用）
  /// habitReminders: { habitId: "HH:mm", ... }
  void updateHabitReminders(Map<String, String> habitReminders) {
    _habitReminders.clear();
    _habitReminders.addAll(habitReminders);
    _startHabitReminderTimer();
  }

  /// 启动每分钟检查，触发所有到点的 per-habit 通知
  void _startHabitReminderTimer() {
    _habitReminderTimer?.cancel();
    _habitReminderTimer = Timer.periodic(const Duration(minutes: 1), (_) {
      _checkHabitReminders();
    });
    // 立即检查一次（处理刚添加的情况）
    _checkHabitReminders();
  }

  void _checkHabitReminders() {
    if (_habitReminders.isEmpty) return;
    final now = DateTime.now();
    final nowStr = '${now.hour.toString().padLeft(2, '0')}:${now.minute.toString().padLeft(2, '0')}';

    for (final entry in _habitReminders.entries) {
      if (entry.value == nowStr) {
        _fireHabitReminder(entry.key);
      }
    }
  }

  void _fireHabitReminder(String habitId) {
    if (kIsWeb && _permissionStatus == 'granted') {
      showWebNotification(
        title: '📌 该打卡了',
        body: '来「矛盾」完成今日打卡，保持连胜！',
      );
    }
    onHabitReminderDue?.call(habitId);
  }

  void _cancelTimers() {
    _reminderTimer?.cancel();
    _reminderTimer = null;
    _periodicCheck?.cancel();
    _periodicCheck = null;
  }

  void dispose() {
    _cancelTimers();
    _habitReminderTimer?.cancel();
    _habitReminderTimer = null;
  }
}
