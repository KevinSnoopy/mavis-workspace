import 'package:flutter/foundation.dart';

/// 通知服务
/// Web 端：使用浏览器 Notification API
/// 原生端：使用 flutter_local_notifications
class NotificationService {
  static final NotificationService _instance = NotificationService._();
  factory NotificationService() => _instance;
  NotificationService._();

  bool _initialized = false;
  final List<_PendingNotification> _pending = [];

  /// 初始化通知服务
  Future<void> init() async {
    if (_initialized) return;
    _initialized = true;

    if (kIsWeb) {
      await _initWeb();
    } else {
      await _initNative();
    }
  }

  Future<void> _initWeb() async {
    // Web: 请求通知权限
    // 注意：浏览器会弹出权限请求
    try {
      // ignore: avoid_dynamic_calls
      // ignore: undefined_identifier
    } catch (_) {}
  }

  Future<void> _initNative() async {
    // 原生端由 flutter_local_notifications 处理
    // 在 main.dart 中初始化
  }

  /// 安排每日打卡提醒
  Future<void> scheduleDailyReminder({
    required int hour,
    required int minute,
  }) async {
    // 保存提醒时间
    // 在首页加载时检查并显示
  }

  /// 显示应用内通知（所有平台通用）
  void showInApp({
    required String title,
    required String body,
    VoidCallback? onTap,
  }) {
    _pending.add(_PendingNotification(
      title: title,
      body: body,
      onTap: onTap,
      createdAt: DateTime.now(),
    ));
    // 通过 NotificationProvider 通知 UI
    if (_onNotificationAdded != null) {
      _onNotificationAdded!();
    }
  }

  void Function()? _onNotificationAdded;

  void setOnNotificationAdded(void Function()? callback) {
    _onNotificationAdded = callback;
  }

  List<_PendingNotification> get pending => List.unmodifiable(_pending);

  void clearPending() {
    _pending.clear();
    _onNotificationAdded?.call();
  }

  void dismiss(int index) {
    if (index >= 0 && index < _pending.length) {
      _pending.removeAt(index);
      _onNotificationAdded?.call();
    }
  }
}

class _PendingNotification {
  final String title;
  final String body;
  final VoidCallback? onTap;
  final DateTime createdAt;

  _PendingNotification({
    required this.title,
    required this.body,
    this.onTap,
    required this.createdAt,
  });
}
