// 原生端通知实现（Android/iOS/macOS/Windows）
// 仅在原生平台编译，Web 不包含此文件

import 'package:flutter/foundation.dart';
import 'package:flutter_local_notifications/flutter_local_notifications.dart';

/// 原生端通知插件实例（单例）
final FlutterLocalNotificationsPlugin _plugin =
    FlutterLocalNotificationsPlugin();

/// 初始化原生端推送通知
Future<bool> initializeNativeNotifications() async {
  try {
    const androidSettings = AndroidInitializationSettings('@mipmap/ic_launcher');

    const darwinSettings = DarwinInitializationSettings(
      requestAlertPermission: true,
      requestBadgePermission: true,
      requestSoundPermission: true,
    );

    const initSettings = InitializationSettings(
      android: androidSettings,
      iOS: darwinSettings,
      macOS: darwinSettings,
    );

    final initialized = await _plugin.initialize(
      initSettings,
      onDidReceiveNotificationResponse: _onNotificationTap,
    );

    if (initialized == true) {
      await _createAndroidChannel();
    }

    return initialized ?? false;
  } catch (e) {
    return false;
  }
}

Future<void> _createAndroidChannel() async {
  const androidChannel = AndroidNotificationChannel(
    'maodun_daily_reminder',
    '每日打卡提醒',
    description: '提醒您完成每日习惯打卡',
    importance: Importance.high,
    playSound: true,
    enableVibration: true,
  );

  await _plugin
      .resolvePlatformSpecificImplementation<
          AndroidFlutterLocalNotificationsPlugin>()
      ?.createNotificationChannel(androidChannel);
}

void _onNotificationTap(NotificationResponse response) {
  debugPrint('[Notification] Tapped: ${response.payload}');
}

/// 请求通知权限（Android 13+ / iOS）
Future<bool> requestNotificationPermission() async {
  final androidGranted = await _plugin
          .resolvePlatformSpecificImplementation<
              AndroidFlutterLocalNotificationsPlugin>()
          ?.requestNotificationsPermission() ??
      false;

  final iosGranted = await _plugin
          .resolvePlatformSpecificImplementation<
              IOSFlutterLocalNotificationsPlugin>()
          ?.requestPermissions(alert: true, badge: true, sound: true) ??
      false;

  return androidGranted || iosGranted;
}

/// 显示一条原生通知
Future<void> showNativeNotification({
  required String title,
  required String body,
  String? payload,
}) async {
  const androidDetails = AndroidNotificationDetails(
    'maodun_daily_reminder',
    '每日打卡提醒',
    channelDescription: '提醒您完成每日习惯打卡',
    importance: Importance.high,
    priority: Priority.high,
    icon: '@mipmap/ic_launcher',
  );

  const darwinDetails = DarwinNotificationDetails(
    presentAlert: true,
    presentBadge: true,
    presentSound: true,
  );

  const details = NotificationDetails(
    android: androidDetails,
    iOS: darwinDetails,
    macOS: darwinDetails,
  );

  await _plugin.show(
    DateTime.now().millisecondsSinceEpoch.remainder(100000),
    title,
    body,
    details,
    payload: payload,
  );
}
