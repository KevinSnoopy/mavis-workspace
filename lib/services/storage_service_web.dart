import 'package:shared_preferences/shared_preferences.dart';
import 'storage_service_interface.dart';

/// Web 端实现：SharedPreferences
/// 注意：Web 端数据存储于浏览器 localStorage，无系统级加密。
/// 适用于非敏感元数据（习惯名称、统计数据、偏好设置）。
/// 若需更高安全性，请评估 Web Crypto API 或第三方加密库。
class StorageServiceImpl implements StorageServiceInterface {
  SharedPreferences? _prefs;

  Future<SharedPreferences> get _preferences async {
    _prefs ??= await SharedPreferences.getInstance();
    return _prefs!;
  }

  @override
  Future<String?> getString(String key) async {
    final prefs = await _preferences;
    return prefs.getString(key);
  }

  @override
  Future<void> setString(String key, String value) async {
    final prefs = await _preferences;
    await prefs.setString(key, value);
  }

  @override
  Future<void> remove(String key) async {
    final prefs = await _preferences;
    await prefs.remove(key);
  }

  @override
  Future<void> clear() async {
    final prefs = await _preferences;
    await prefs.clear();
  }
}

/// 默认入口（Web 端）
StorageServiceInterface createStorageService() => StorageServiceImpl();
