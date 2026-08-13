import 'dart:convert';
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:provider/provider.dart';
import 'package:share_plus/share_plus.dart';
import '../providers/habit_provider.dart';
import '../providers/theme_provider.dart';
import '../theme/app_theme.dart';

class SettingsScreen extends StatelessWidget {
  const SettingsScreen({super.key});

  @override
  Widget build(BuildContext context) {
    final isDark = Theme.of(context).brightness == Brightness.dark;

    return Scaffold(
      appBar: AppBar(
        leading: IconButton(
          icon: const Icon(Icons.arrow_back),
          onPressed: () => Navigator.pop(context),
        ),
        title: const Text('⚙️ 设置'),
      ),
      body: ListView(
        padding: const EdgeInsets.all(16),
        children: [
          // ─── 外观 ───
          _Section(
            title: '外观',
            children: [
              Consumer<ThemeProvider>(
                builder: (context, theme, _) {
                  return _Tile(
                    icon: Icons.dark_mode,
                    title: '深色模式',
                    subtitle: theme.isDark ? '已开启' : '已关闭',
                    trailing: Switch.adaptive(
                      value: theme.isDark,
                      onChanged: (_) {
                        HapticFeedback.lightImpact();
                        theme.toggle();
                      },
                      activeColor: AppTheme.primary,
                    ),
                  );
                },
              ),
            ],
          ),

          const SizedBox(height: 16),

          // ─── 提醒 ───
          _Section(
            title: '提醒',
            children: [
              _Tile(
                icon: Icons.notifications,
                title: '推送通知',
                subtitle: '打卡提醒',
                trailing: Switch.adaptive(
                  value: true,
                  onChanged: (v) {
                    HapticFeedback.lightImpact();
                    _showNotificationInfo(context);
                  },
                  activeColor: AppTheme.primary,
                ),
              ),
              _Tile(
                icon: Icons.schedule,
                title: '每日提醒时间',
                subtitle: '09:00',
                onTap: () => _showTimePicker(context),
              ),
            ],
          ),

          const SizedBox(height: 16),

          // ─── 数据 ───
          _Section(
            title: '数据',
            children: [
              Consumer<HabitProvider>(
                builder: (context, provider, _) {
                  return _Tile(
                    icon: Icons.cloud_upload,
                    title: '导出数据',
                    subtitle: 'JSON 格式',
                    onTap: () => _exportData(context, provider),
                  );
                },
              ),
              Consumer<HabitProvider>(
                builder: (context, provider, _) {
                  return _Tile(
                    icon: Icons.cloud_download,
                    title: '导入数据',
                    subtitle: '从 JSON 恢复',
                    onTap: () => _showImportDialog(context, provider),
                  );
                },
              ),
              Consumer<HabitProvider>(
                builder: (context, provider, _) {
                  return _Tile(
                    icon: Icons.delete_forever,
                    title: '清除所有数据',
                    subtitle: '删除习惯、打卡、成就',
                    textColor: Colors.red,
                    onTap: () => _showClearDataDialog(context, provider),
                  );
                },
              ),
            ],
          ),

          const SizedBox(height: 16),

          // ─── 关于 ───
          _Section(
            title: '关于',
            children: [
              _Tile(
                icon: Icons.info,
                title: '版本',
                subtitle: 'v1.1.0',
              ),
              _Tile(
                icon: Icons.book,
                title: '关于「矛盾」',
                subtitle: '基于《矛盾论》与《实践论》',
                onTap: () => _showAboutDialog(context),
              ),
              _Tile(
                icon: Icons.code,
                title: '源码',
                subtitle: 'GitHub 开源',
                onTap: () {
                  ScaffoldMessenger.of(context).showSnackBar(
                    const SnackBar(
                      content: Text('github.com/KevinSnoopy/mavis-workspace'),
                    ),
                  );
                },
              ),
            ],
          ),

          const SizedBox(height: 32),

          // 底部标语
          Center(
            child: Text(
              '⚖️ 矛盾 — 在对立中寻找统一',
              style: TextStyle(
                fontSize: 12,
                color: isDark
                    ? AppTheme.textSecondary.withOpacity(0.5)
                    : Colors.grey,
              ),
            ),
          ),

          const SizedBox(height: 24),
        ],
      ),
    );
  }

  void _showNotificationInfo(BuildContext context) {
    ScaffoldMessenger.of(context).showSnackBar(
      const SnackBar(
        content: Text('通知功能需要设备权限，将在后续版本完善'),
        duration: Duration(seconds: 2),
      ),
    );
  }

  void _showTimePicker(BuildContext context) async {
    final time = await showTimePicker(
      context: context,
      initialTime: const TimeOfDay(hour: 9, minute: 0),
      builder: (context, child) {
        return Theme(
          data: Theme.of(context).copyWith(
            colorScheme: ColorScheme.dark(
              primary: AppTheme.primary,
              surface: AppTheme.bgCard,
            ),
          ),
          child: child!,
        );
      },
    );
    if (time != null && context.mounted) {
      ScaffoldMessenger.of(context).showSnackBar(
        SnackBar(content: Text('已设置为 ${time.hour.toString().padLeft(2, '0')}:${time.minute.toString().padLeft(2, '0')}')),
      );
    }
  }

  void _exportData(BuildContext context, HabitProvider provider) async {
    HapticFeedback.lightImpact();

    final data = {
      'version': '1.1.0',
      'exportedAt': DateTime.now().toIso8601String(),
      'habits': provider.habits.map((h) => h.toJson()).toList(),
      'checkIns': provider.checkIns.map((c) => c.toJson()).toList(),
      'achievements': provider.achievements.map((a) => a.toJson()).toList(),
      'analysisInsights': provider.analysisInsights.map((i) => i.toJson()).toList(),
    };

    final jsonStr = const JsonEncoder.withIndent('  ').convert(data);

    await Share.share(
      jsonStr,
      subject: '矛盾 App 数据导出',
    );
  }

  void _showClearDataDialog(BuildContext context, HabitProvider provider) {
    showDialog(
      context: context,
      builder: (ctx) => AlertDialog(
        backgroundColor: AppTheme.bgCard,
        title: const Text('确认清除所有数据？'),
        content: const Text(
          '此操作不可恢复。\n\n将删除：\n• 所有习惯\n• 所有打卡记录\n• 所有成就\n• 所有分析记录',
        ),
        actions: [
          TextButton(
            onPressed: () => Navigator.pop(ctx),
            child: const Text('取消'),
          ),
          TextButton(
            onPressed: () async {
              Navigator.pop(ctx);
              HapticFeedback.heavyImpact();
              await provider.clearAll();
              if (context.mounted) {
                ScaffoldMessenger.of(context).showSnackBar(
                  const SnackBar(
                    content: Text('所有数据已清除'),
                    backgroundColor: Colors.red,
                  ),
                );
              }
            },
            child: const Text('确认删除', style: TextStyle(color: Colors.red)),
          ),
        ],
      ),
    );
  }

  void _showImportDialog(BuildContext context, HabitProvider provider) {
    final controller = TextEditingController();
    showDialog(
      context: context,
      builder: (ctx) => AlertDialog(
        backgroundColor: AppTheme.bgCard,
        title: const Text('导入数据'),
        content: SingleChildScrollView(
          child: Column(
            mainAxisSize: MainAxisSize.min,
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              const Text(
                '将之前导出的 JSON 数据粘贴到下方：',
                style: TextStyle(fontSize: 13),
              ),
              const SizedBox(height: 12),
              TextField(
                controller: controller,
                maxLines: 8,
                style: const TextStyle(fontSize: 12, fontFamily: 'monospace'),
                decoration: InputDecoration(
                  hintText: '{"habits": [...], "checkIns": [...]}',
                  hintStyle: TextStyle(
                    fontSize: 11,
                    color: AppTheme.textSecondary.withOpacity(0.5),
                  ),
                  filled: true,
                  fillColor: AppTheme.bgElevated,
                  border: OutlineInputBorder(
                    borderRadius: BorderRadius.circular(8),
                    borderSide: BorderSide.none,
                  ),
                ),
              ),
            ],
          ),
        ),
        actions: [
          TextButton(
            onPressed: () => Navigator.pop(ctx),
            child: const Text('取消'),
          ),
          TextButton(
            onPressed: () async {
              final jsonStr = controller.text.trim();
              if (jsonStr.isEmpty) return;
              try {
                final imported = await provider.importData(jsonStr);
                if (ctx.mounted) {
                  Navigator.pop(ctx);
                  ScaffoldMessenger.of(context).showSnackBar(
                    SnackBar(
                      content: Text(
                        '成功导入 ${imported.habits} 个习惯、${imported.checkIns} 条打卡',
                      ),
                      backgroundColor: AppTheme.success,
                    ),
                  );
                }
              } catch (e) {
                if (ctx.mounted) {
                  ScaffoldMessenger.of(context).showSnackBar(
                    SnackBar(
                      content: Text('导入失败：${e.toString()}'),
                      backgroundColor: Colors.red,
                    ),
                  );
                }
              }
            },
            child: const Text('导入'),
          ),
        ],
      ),
    );
  }

  void _showAboutDialog(BuildContext context) {
    showDialog(
      context: context,
      builder: (ctx) => AlertDialog(
        backgroundColor: AppTheme.bgCard,
        title: Row(
          children: const [
            Text('⚖️ ', style: TextStyle(fontSize: 24)),
            Text('矛盾'),
          ],
        ),
        content: const SingleChildScrollView(
          child: Column(
            mainAxisSize: MainAxisSize.min,
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Text(
                '「矛盾」App 基于毛泽东的《矛盾论》和《实践论》哲学思想，'
                '帮助你在复杂的人生问题中找到主要矛盾，'
                '通过建立微小习惯，在实践中不断前进。',
              ),
              SizedBox(height: 16),
              Text(
                '核心原理',
                style: TextStyle(fontWeight: FontWeight.bold),
              ),
              SizedBox(height: 8),
              Text('• 矛盾具有普遍性和特殊性'),
              Text('• 抓住主要矛盾是解决问题的关键'),
              Text('• 实践是检验认识的唯一标准'),
              Text('• 从实践中来，到实践中去'),
              SizedBox(height: 16),
              Text(
                '版本：v1.1.0',
                style: TextStyle(fontSize: 12),
              ),
            ],
          ),
        ),
        actions: [
          TextButton(
            onPressed: () => Navigator.pop(ctx),
            child: const Text('明白了'),
          ),
        ],
      ),
    );
  }
}

class _Section extends StatelessWidget {
  final String title;
  final List<Widget> children;

  const _Section({required this.title, required this.children});

  @override
  Widget build(BuildContext context) {
    final isDark = Theme.of(context).brightness == Brightness.dark;
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Padding(
          padding: const EdgeInsets.only(left: 4, bottom: 8),
          child: Text(
            title,
            style: TextStyle(
              fontSize: 12,
              color: isDark ? AppTheme.textSecondary : Colors.grey,
              fontWeight: FontWeight.w500,
            ),
          ),
        ),
        Container(
          decoration: BoxDecoration(
            color: isDark ? AppTheme.bgCard : Colors.white,
            borderRadius: BorderRadius.circular(AppTheme.radiusMd),
            border: Border.all(
              color: isDark
                  ? Colors.white.withOpacity(0.05)
                  : Colors.black.withOpacity(0.05),
            ),
          ),
          child: Column(children: children),
        ),
      ],
    );
  }
}

class _Tile extends StatelessWidget {
  final IconData icon;
  final String title;
  final String? subtitle;
  final Widget? trailing;
  final VoidCallback? onTap;
  final Color? textColor;

  const _Tile({
    required this.icon,
    required this.title,
    this.subtitle,
    this.trailing,
    this.onTap,
    this.textColor,
  });

  @override
  Widget build(BuildContext context) {
    final isDark = Theme.of(context).brightness == Brightness.dark;
    return ListTile(
      leading: Icon(icon, size: 20),
      title: Text(title, style: textColor != null ? TextStyle(color: textColor) : null),
      subtitle: subtitle != null
          ? Text(
              subtitle!,
              style: TextStyle(
                fontSize: 12,
                color: textColor ?? (isDark ? AppTheme.textSecondary : Colors.grey),
              ),
            )
          : null,
      trailing: trailing ?? (onTap != null ? Icon(Icons.chevron_right, size: 20, color: isDark ? AppTheme.textSecondary : Colors.grey) : null),
      onTap: onTap,
    );
  }
}
