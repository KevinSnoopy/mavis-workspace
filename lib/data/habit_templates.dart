import '../models/habit.dart';

/// 习惯模板库
class HabitTemplate {
  final String name;
  final String? description;
  final String icon;
  final int colorValue;
  final HabitFrequency frequency;
  final List<int>? weekDays;
  final List<int>? monthDays;
  final String? reminderTime;

  const HabitTemplate({
    required this.name,
    this.description,
    required this.icon,
    required this.colorValue,
    this.frequency = HabitFrequency.daily,
    this.weekDays,
    this.monthDays,
    this.reminderTime,
  });

  Habit toHabit() => Habit(
        id: '',
        name: name,
        description: description,
        icon: icon,
        colorValue: colorValue,
        frequency: frequency,
        weekDays: weekDays,
        monthDays: monthDays,
        reminderTime: reminderTime,
        createdAt: DateTime.now(),
      );
}

/// 预设模板
const habitTemplates = <HabitTemplate>[
  // ── 健康 ──
  HabitTemplate(
    name: '早起打卡',
    description: '每天按时起床，迎接新的一天',
    icon: '🌅',
    colorValue: 0xFFFF9500,
    frequency: HabitFrequency.daily,
    reminderTime: '07:00',
  ),
  HabitTemplate(
    name: '每日运动',
    description: '跑步、健身、瑜伽，保持活力',
    icon: '🏃',
    colorValue: 0xFF34C759,
    frequency: HabitFrequency.daily,
    reminderTime: '19:00',
  ),
  HabitTemplate(
    name: '喝水提醒',
    description: '每天喝够 8 杯水',
    icon: '💧',
    colorValue: 0xFF007AFF,
    frequency: HabitFrequency.daily,
  ),
  HabitTemplate(
    name: '冥想 10 分钟',
    description: '正念冥想，放空身心',
    icon: '🧘',
    colorValue: 0xFF5856D6,
    frequency: HabitFrequency.daily,
    reminderTime: '22:00',
  ),
  HabitTemplate(
    name: '充足睡眠',
    description: '早睡早起，保证 7-8 小时睡眠',
    icon: '😴',
    colorValue: 0xFFAF52DE,
    frequency: HabitFrequency.daily,
    reminderTime: '23:00',
  ),

  // ── 学习 ──
  HabitTemplate(
    name: '每日阅读',
    description: '每天读书 30 分钟',
    icon: '📖',
    colorValue: 0xFFFF2D55,
    frequency: HabitFrequency.daily,
    reminderTime: '21:00',
  ),
  HabitTemplate(
    name: '写作/日记',
    description: '记录生活，复盘成长',
    icon: '✍️',
    colorValue: 0xFFFF3B30,
    frequency: HabitFrequency.daily,
    reminderTime: '22:30',
  ),
  HabitTemplate(
    name: '背单词',
    description: '每天学习 20 个新单词',
    icon: '📝',
    colorValue: 0xFFFF9500,
    frequency: HabitFrequency.daily,
    reminderTime: '08:00',
  ),
  HabitTemplate(
    name: '周末复盘',
    description: '回顾本周计划执行情况',
    icon: '📊',
    colorValue: 0xFF007AFF,
    frequency: HabitFrequency.weekly,
    weekDays: [6], // 周日
    reminderTime: '20:00',
  ),

  // ── 效率 ──
  HabitTemplate(
    name: '晨间计划',
    description: '每天早上列出今日三件要事',
    icon: '📋',
    colorValue: 0xFF34C759,
    frequency: HabitFrequency.daily,
    reminderTime: '07:30',
  ),
  HabitTemplate(
    name: '番茄工作法',
    description: '专注工作 25 分钟，休息 5 分钟',
    icon: '🍅',
    colorValue: 0xFFFF3B30,
    frequency: HabitFrequency.daily,
  ),
  HabitTemplate(
    name: '日复盘',
    description: '回顾今日完成情况，记录收获',
    icon: '🔍',
    colorValue: 0xFF5856D6,
    frequency: HabitFrequency.daily,
    reminderTime: '22:00',
  ),

  // ── 生活方式 ──
  HabitTemplate(
    name: '健康饮食',
    description: '少油少糖，营养均衡',
    icon: '🥗',
    colorValue: 0xFFFF2D55,
    frequency: HabitFrequency.daily,
  ),
  HabitTemplate(
    name: '理财记账',
    description: '记录每日收支，培养财商',
    icon: '💰',
    colorValue: 0xFF34C759,
    frequency: HabitFrequency.daily,
    reminderTime: '21:00',
  ),
  HabitTemplate(
    name: '联系家人',
    description: '给父母/家人打电话或视频',
    icon: '📞',
    colorValue: 0xFFFF9500,
    frequency: HabitFrequency.weekly,
    weekDays: [0, 6], // 周末
  ),
  HabitTemplate(
    name: '整理房间',
    description: '保持居住环境整洁有序',
    icon: '🧹',
    colorValue: 0xFF007AFF,
    frequency: HabitFrequency.weekly,
    weekDays: [6], // 周日
  ),
  HabitTemplate(
    name: '学习新技能',
    description: '每月掌握一项小技能',
    icon: '🚀',
    colorValue: 0xFF5856D6,
    frequency: HabitFrequency.daily,
    reminderTime: '20:00',
  ),
];
