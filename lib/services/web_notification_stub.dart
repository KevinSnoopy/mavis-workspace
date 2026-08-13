// Web 通知桩实现在非 Web 平台为空操作
Future<String> requestWebPermission() async => 'denied';
void showWebNotification({required String title, required String body}) {}
