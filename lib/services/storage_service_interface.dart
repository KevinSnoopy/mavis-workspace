/// 存储服务接口
/// 所有平台实现均遵循此接口。
abstract class StorageServiceInterface {
  Future<void> setString(String key, String value);
  Future<String?> getString(String key);
  Future<void> remove(String key);
  Future<void> clear();
}
