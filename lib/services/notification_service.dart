import 'dart:async';
import 'package:flutter/foundation.dart';
import 'package:shared_preferences/shared_preferences.dart';

import 'web_notification.dart';

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

  // 提醒触发回调（通知 UI 显示）
  void Function()? onReminderDue;

  String get permissionStatus => _permissionStatus;

  /// 初始化通知服务
  Future<void> init() async {
    if (_initialized) return;
    _initialized = true;

    if (kIsWeb) {
      await _initWeb();
    } else {
      await _initNative();
    }

    await _loadReminderSettings();
    _scheduleReminder();
  }

  Future<void> _initWeb() async {
    try {
      _permissionStatus = await requestWebPermission();
    } catch (_) {
      _permissionStatus = 'denied';
    }
  }

  Future<void> _initNative() async {
    // flutter_local_notifications 在 main.dart 中单独初始化
  }

  // ---- 提醒调度 ----

  /// 安排每日打卡提醒
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

  /// 取消每日提醒
  void cancelDailyReminder() {
    _cancelTimers();
    SharedPreferences.getInstance().then((prefs) {
      prefs.remove('reminder_hour');
      prefs.remove('reminder_minute');
    }).catchError((_) {});
  }

  /// 获取已保存的提醒时间（null 表示未设置）
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

    // 一次性定时器：到点触发
    _reminderTimer?.cancel();
    _reminderTimer = Timer(delay, _onReminderFired);

    // 每分钟检查一次（防止应用常驻时错过提醒）
    _periodicCheck?.cancel();
    _periodicCheck = Timer.periodic(const Duration(minutes: 1), (_) => _checkReminder());
  }

  void _checkReminder() {
    final now = DateTime.now();
    if (now.hour == _reminderHour && now.minute == _reminderMinute) {
      _onReminderFired();
    }
  }

  void _onReminderFired() {
    // 重新调度明天的提醒
    _scheduleReminder();

    // Web 浏览器通知
    if (kIsWeb && _permissionStatus == 'granted') {
      showWebNotification(
        title: '矛盾 · 每日打卡',
        body: '新的一天开始了，今天的习惯完成了吗？',
      );
    }

    // 通知 UI
    onReminderDue?.call();
  }

  void _cancelTimers() {
    _reminderTimer?.cancel();
    _reminderTimer = null;
    _periodicCheck?.cancel();
    _periodicCheck = null;
  }

  void dispose() {
    _cancelTimers();
  }
}
