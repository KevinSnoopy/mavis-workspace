import 'package:flutter/material.dart';
import '../theme/app_theme.dart';

class SettingsScreen extends StatefulWidget {
  const SettingsScreen({super.key});

  @override
  State<SettingsScreen> createState() => _SettingsScreenState();
}

class _SettingsScreenState extends State<SettingsScreen> {
  bool _notifications = true;
  bool _darkMode = true;

  @override
  Widget build(BuildContext context) {
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
          // 通知设置
          _SettingsSection(
            title: '提醒',
            children: [
              _SettingsTile(
                icon: Icons.notifications,
                title: '推送通知',
                trailing: Switch(
                  value: _notifications,
                  onChanged: (v) => setState(() => _notifications = v),
                  activeColor: AppTheme.primary,
                ),
              ),
              _SettingsTile(
                icon: Icons.alarm,
                title: '提醒时间',
                subtitle: '每天 09:00',
                onTap: () {},
              ),
            ],
          ),

          const SizedBox(height: 16),

          // 外观
          _SettingsSection(
            title: '外观',
            children: [
              _SettingsTile(
                icon: Icons.dark_mode,
                title: '深色模式',
                trailing: Switch(
                  value: _darkMode,
                  onChanged: (v) => setState(() => _darkMode = v),
                  activeColor: AppTheme.primary,
                ),
              ),
            ],
          ),

          const SizedBox(height: 16),

          // 数据
          _SettingsSection(
            title: '数据',
            children: [
              _SettingsTile(
                icon: Icons.cloud_upload,
                title: '导出数据',
                subtitle: '导出JSON格式',
                onTap: () {
                  ScaffoldMessenger.of(context).showSnackBar(
                    const SnackBar(content: Text('导出功能开发中')),
                  );
                },
              ),
              _SettingsTile(
                icon: Icons.delete_forever,
                title: '清除数据',
                subtitle: '删除所有习惯和记录',
                textColor: Colors.red,
                onTap: () => _showClearDataDialog(),
              ),
            ],
          ),

          const SizedBox(height: 16),

          // 关于
          _SettingsSection(
            title: '关于',
            children: [
              _SettingsTile(
                icon: Icons.info,
                title: '版本',
                subtitle: '1.0.0',
              ),
              _SettingsTile(
                icon: Icons.book,
                title: '关于「矛盾」',
                subtitle: '基于《矛盾论》与《实践论》',
                onTap: () => _showAboutDialog(),
              ),
            ],
          ),

          const SizedBox(height: 32),

          // 底部
          Center(
            child: Text(
              '⚖️ 矛盾 — 在对立中寻找统一',
              style: TextStyle(
                fontSize: 12,
                color: AppTheme.textSecondary,
              ),
            ),
          ),
        ],
      ),
    );
  }

  void _showClearDataDialog() {
    showDialog(
      context: context,
      builder: (context) => AlertDialog(
        backgroundColor: AppTheme.bgCard,
        title: const Text('确认清除数据？'),
        content: const Text('此操作不可恢复，所有习惯、打卡记录和成就都将被删除。'),
        actions: [
          TextButton(
            onPressed: () => Navigator.pop(context),
            child: const Text('取消'),
          ),
          TextButton(
            onPressed: () {
              // TODO: 实现清除数据
              Navigator.pop(context);
              ScaffoldMessenger.of(context).showSnackBar(
                const SnackBar(content: Text('数据已清除')),
              );
            },
            child: const Text('确认删除', style: TextStyle(color: Colors.red)),
          ),
        ],
      ),
    );
  }

  void _showAboutDialog() {
    showDialog(
      context: context,
      builder: (context) => AlertDialog(
        backgroundColor: AppTheme.bgCard,
        title: Row(
          children: const [
            Text('⚖️ ', style: TextStyle(fontSize: 24)),
            Text('矛盾'),
          ],
        ),
        content: const Column(
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
              '核心原理：',
              style: TextStyle(fontWeight: FontWeight.bold),
            ),
            Text('• 矛盾具有普遍性和特殊性'),
            Text('• 抓住主要矛盾是解决问题的关键'),
            Text('• 实践是检验认识的唯一标准'),
            Text('• 从实践中来，到实践中去'),
          ],
        ),
        actions: [
          TextButton(
            onPressed: () => Navigator.pop(context),
            child: const Text('明白了'),
          ),
        ],
      ),
    );
  }
}

class _SettingsSection extends StatelessWidget {
  final String title;
  final List<Widget> children;

  const _SettingsSection({
    required this.title,
    required this.children,
  });

  @override
  Widget build(BuildContext context) {
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Padding(
          padding: const EdgeInsets.only(left: 4, bottom: 8),
          child: Text(
            title,
            style: TextStyle(
              fontSize: 12,
              color: AppTheme.textSecondary,
              fontWeight: FontWeight.w500,
            ),
          ),
        ),
        Container(
          decoration: BoxDecoration(
            color: AppTheme.bgCard,
            borderRadius: BorderRadius.circular(AppTheme.radiusMd),
          ),
          child: Column(children: children),
        ),
      ],
    );
  }
}

class _SettingsTile extends StatelessWidget {
  final IconData icon;
  final String title;
  final String? subtitle;
  final Widget? trailing;
  final VoidCallback? onTap;
  final Color? textColor;

  const _SettingsTile({
    required this.icon,
    required this.title,
    this.subtitle,
    this.trailing,
    this.onTap,
    this.textColor,
  });

  @override
  Widget build(BuildContext context) {
    return ListTile(
      leading: Icon(icon, size: 20),
      title: Text(
        title,
        style: TextStyle(color: textColor),
      ),
      subtitle: subtitle != null
          ? Text(
              subtitle!,
              style: TextStyle(
                fontSize: 12,
                color: AppTheme.textSecondary,
              ),
            )
          : null,
      trailing: trailing ?? (onTap != null ? const Icon(Icons.chevron_right, size: 20) : null),
      onTap: onTap,
    );
  }
}
