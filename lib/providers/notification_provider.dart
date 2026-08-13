import 'package:flutter/material.dart';

/// 应用内通知管理
class NotificationProvider extends ChangeNotifier {
  final List<_NotificationItem> _items = [];
  int _seq = 0; // 递增序列号，配合时间戳保证唯一性

  List<_NotificationItem> get items => List.unmodifiable(_items);
  bool get hasUnread => _items.isNotEmpty;

  /// 添加一条通知（相同标题+内容在 10 秒内去重，最多保留 50 条）
  void add({
    required String title,
    required String body,
    String? emoji,
    VoidCallback? onTap,
  }) {
    final now = DateTime.now();
    // 去重：10 秒内相同标题+内容不重复添加
    final isDuplicate = _items.any((i) =>
        i.title == title &&
        i.body == body &&
        now.difference(i.createdAt).inSeconds < 10);
    if (isDuplicate) return;

    _seq++;
    final id = '${now.millisecondsSinceEpoch}_$_seq';
    _items.insert(
        0,
        _NotificationItem(
          id: id,
          title: title,
          body: body,
          emoji: emoji,
          onTap: onTap,
          createdAt: now,
        ));

    // 容量限制：最多 50 条，删除最老的
    if (_items.length > 50) {
      _items.removeLast();
    }

    // 清理 7 天前的旧通知
    _items.removeWhere(
        (i) => now.difference(i.createdAt).inDays >= 7);

    notifyListeners();

    // 5 秒后自动消失（提示类通知）
    if (onTap == null) {
      Future.delayed(const Duration(seconds: 5), () => dismiss(id));
    }
  }

  /// 标记已读/删除（只删第一条匹配的）
  void dismiss(String id) {
    _items.removeWhere((i) => i.id == id);
    notifyListeners();
  }

  /// 全部清除
  void clearAll() {
    _items.clear();
    notifyListeners();
  }
}

class _NotificationItem {
  final String id;
  final String title;
  final String body;
  final String? emoji;
  final VoidCallback? onTap;
  final DateTime createdAt;

  _NotificationItem({
    required this.id,
    required this.title,
    required this.body,
    this.emoji,
    this.onTap,
    required this.createdAt,
  });
}
