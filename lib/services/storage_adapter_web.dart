import 'package:shared_preferences/shared_preferences.dart';
import 'storage_adapter_interface.dart';

/// Web 端实现：SharedPreferences（浏览器 localStorage）
StorageAdapter createPlatformStorageAdapter() => SharedPrefsStorageAdapter();

class SharedPrefsStorageAdapter implements StorageAdapter {
  SharedPreferences? _prefs;

  Future<SharedPreferences> get _p async {
    _prefs ??= await SharedPreferences.getInstance();
    return _prefs!;
  }

  @override
  Future<void> setString(String key, String value) async {
    (await _p).setString(key, value);
  }

  @override
  Future<String?> getString(String key) async {
    return (await _p).getString(key);
  }

  @override
  Future<void> remove(String key) async {
    (await _p).remove(key);
  }

  @override
  Future<void> clear() async {
    (await _p).clear();
  }
}
