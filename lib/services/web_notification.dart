// Web 浏览器通知 API
// 使用 dart:js_interop 接入浏览器 Notification API
// 仅在 Web 平台使用，非 Web 端不执行任何操作

import 'dart:js_interop';
import 'package:web/web.dart' as web;

/// 请求浏览器通知权限
/// 返回权限状态：'granted' | 'denied' | 'default'
Future<String> requestWebPermission() async {
  try {
    final permission = web.Notification.permission;
    if (permission == 'granted') return 'granted';
    if (permission == 'denied') return 'denied';

    // 请求权限，await JSPromise<JSString> 并转为 Dart String
    final jsResult = await web.Notification.requestPermission().toDart;
    return jsResult.toDart;
  } catch (e) {
    return 'denied';
  }
}

/// 显示 Web 浏览器通知
void showWebNotification({
  required String title,
  required String body,
  String? icon,
}) {
  try {
    if (web.Notification.permission != 'granted') return;

    final options = web.NotificationOptions(
      body: body,
      icon: icon ?? '/icons/icon-192.png',
      tag: 'maodun-$title-${DateTime.now().millisecondsSinceEpoch}',
    );
    web.Notification(title, options);
  } catch (e) {
    // 通知失败，静默忽略
  }
}
