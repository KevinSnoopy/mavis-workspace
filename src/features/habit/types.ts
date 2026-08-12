// ============================================
// HabitFlow - 数据模型定义
// ============================================

export type Frequency = 'daily' | 'weekly' | 'monthly'
export type WeekDay = 0 | 1 | 2 | 3 | 4 | 5 | 6 // 0=周日

export interface Habit {
  id: string
  name: string
  description?: string
  icon: string
  color: string
  frequency: Frequency
  // 每周几 (仅 weekly 模式)
  weekDays?: WeekDay[]
  // 每月几号 (仅 monthly 模式)
  monthDays?: number[]
  // 目标次数 (默认1)
  targetPerDay: number
  // 提醒时间 (HH:mm 格式)
  reminderTimes?: string[]
  // 创建时间
  createdAt: string
  // 是否归档
  archived: boolean
  // 排序
  order: number
}

export interface CheckIn {
  id: string
  habitId: string
  date: string // YYYY-MM-DD
  count: number // 完成次数
  note?: string
  createdAt: string
}

export interface Achievement {
  id: string
  habitId: string
  type: 'streak' | 'total' | 'milestone'
  name: string
  description: string
  unlockedAt: string
}

// 统计数据
export interface HabitStats {
  habitId: string
  currentStreak: number
  longestStreak: number
  totalCount: number
  completionRate: number // 0-100
  checkInDates: string[] // 所有打卡日期
}

// 预设习惯模板
export const HABIT_TEMPLATES = [
  { name: '晨跑', icon: '🏃', color: '#22c55e', frequency: 'daily' as Frequency },
  { name: '阅读30分钟', icon: '📚', color: '#3b82f6', frequency: 'daily' as Frequency },
  { name: '喝水8杯', icon: '💧', color: '#06b6d4', frequency: 'daily' as Frequency },
  { name: '冥想10分钟', icon: '🧘', color: '#8b5cf6', frequency: 'daily' as Frequency },
  { name: '早睡早起', icon: '🌙', color: '#f59e0b', frequency: 'daily' as Frequency },
  { name: '背单词', icon: '📝', color: '#ec4899', frequency: 'daily' as Frequency },
  { name: '健身', icon: '💪', color: '#ef4444', frequency: 'weekly' as Frequency, weekDays: [1, 3, 5] as WeekDay[] },
  { name: '学英语', icon: '🗣️', color: '#14b8a6', frequency: 'daily' as Frequency },
  { name: '写日记', icon: '✍️', color: '#f97316', frequency: 'daily' as Frequency },
  { name: '瑜伽', icon: '🧘‍♀️', color: '#a855f7', frequency: 'weekly' as Frequency, weekDays: [2, 4, 6] as WeekDay[] },
]

// 可选图标
export const ICONS = ['🏃', '📚', '💧', '🧘', '🌙', '📝', '💪', '🗣️', '✍️', '🧘‍♀️', '🎯', '💼', '🍎', '🧠', '⭐', '🔥', '🌟', '💡', '🎨', '🎵']

// 可选颜色
export const COLORS = [
  '#ef4444', '#f97316', '#f59e0b', '#22c55e', 
  '#14b8a6', '#06b6d4', '#3b82f6', '#8b5cf6', 
  '#a855f7', '#ec4899', '#f43f5e', '#64748b'
]
