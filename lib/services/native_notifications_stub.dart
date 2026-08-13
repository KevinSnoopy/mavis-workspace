// Web 端 stub：flutter_local_notifications 在 Web 不可用
// 条件导入：当 dart.library.io 可用时导入 native_notifications.dart，否则本文件生效

Future<bool> initializeNativeNotifications() async => false;
Future<bool> requestNotificationPermission() async => false;
Future<void> showNativeNotification({
  required String title,
  required String body,
  String? payload,
}) async {}
