// 存储服务 — 条件导入入口
//
// 根据平台加载对应实现：
// - Web 端：SharedPreferences（浏览器本地存储）
// - 原生端：flutter_secure_storage（Keychain / Keystore 加密）
//
// 各平台实现均实现同一接口 StorageServiceInterface。
export 'storage_service_interface.dart';
export 'storage_service_web.dart'
    if (dart.library.io) 'storage_service_native.dart';
