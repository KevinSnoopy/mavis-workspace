// 原生端通知初始化（flutter_local_notifications）
// 条件导入入口：
//   - 原生平台：导出 native_notifications_impl.dart
//   - Web 平台：导出 native_notifications_stub.dart（空实现）
export 'native_notifications_stub.dart'
    if (dart.library.io) 'native_notifications_impl.dart';
