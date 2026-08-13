import 'package:flutter/material.dart';

/// 应用内通知管理
class NotificationProvider extends ChangeNotifier {
  final List<_NotificationItem> _items = [];

  List<_NotificationItem> get items => List.unmodifiable(_items);
  bool get hasUnread => _items.isNotEmpty;

  /// 添加一条通知
  void add({
    required String title,
    required String body,
    String? emoji,
    VoidCallback? onTap,
  }) {
    final id = DateTime.now().millisecondsSinceEpoch.toString();
    _items.insert(
        0,
        _NotificationItem(
          id: id,
          title: title,
          body: body,
          emoji: emoji,
          onTap: onTap,
          createdAt: DateTime.now(),
        ));
    notifyListeners();

    // 5 秒后自动消失（提示类通知）
    if (onTap == null) {
      Future.delayed(const Duration(seconds: 5), () => dismiss(id));
    }
  }

  /// 标记已读/删除
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
