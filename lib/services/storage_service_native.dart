import 'package:flutter_secure_storage/flutter_secure_storage.dart';
import 'storage_service_interface.dart';

/// 原生端实现：flutter_secure_storage
/// iOS：Keychain 加密存储
/// Android：EncryptedSharedPreferences（API 23+）或 Keystore
/// macOS：Keychain
/// Windows：DPAPI
class StorageServiceImpl implements StorageServiceInterface {
  final FlutterSecureStorage _secure = const FlutterSecureStorage(
    aOptions: AndroidOptions(
      encryptedSharedPreferences: true,
    ),
    iOptions: IOSOptions(
      accessibility: KeychainAccessibility.first_unlock_this_device,
    ),
  );

  @override
  Future<String?> getString(String key) async {
    return _secure.read(key: key);
  }

  @override
  Future<void> setString(String key, String value) async {
    await _secure.write(key: key, value: value);
  }

  @override
  Future<void> remove(String key) async {
    await _secure.delete(key: key);
  }

  @override
  Future<void> clear() async {
    await _secure.deleteAll();
  }
}

/// 默认入口（原生端）
StorageServiceInterface createStorageService() => StorageServiceImpl();
