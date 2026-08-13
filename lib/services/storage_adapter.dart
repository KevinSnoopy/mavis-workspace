// 存储适配层 — 复用已有的 storage_service 条件导出
//
// storage_service.dart 已正确配置条件导出：
//   - Web：storage_service_web.dart（SharedPreferences）
//   - Native：storage_service_native.dart（flutter_secure_storage）
//
// 本文件兼容两个入口：
//   import 'storage_adapter.dart';     ← HabitProvider/ThemeProvider 使用
//   import 'storage_service.dart';      ← 其他服务直接使用
//
export 'storage_service.dart';
