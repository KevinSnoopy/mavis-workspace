import { create } from 'zustand'
import { persist } from 'zustand/middleware'
import type { Habit, CheckIn, HabitStats, Achievement } from './types'

// 日期工具函数
const getToday = () => new Date().toISOString().split('T')[0]

interface HabitStore {
  habits: Habit[]
  checkIns: CheckIn[]
  achievements: Achievement[]
  
  // 矛盾分析与习惯的关联
  analysisInsights: AnalysisInsight[]
  
  // 习惯操作
  addHabit: (habit: Omit<Habit, 'id' | 'createdAt' | 'order'>) => void
  updateHabit: (id: string, updates: Partial<Habit>) => void
  deleteHabit: (id: string) => void
  archiveHabit: (id: string) => void
  reorderHabits: (habits: Habit[]) => void
  
  // 打卡操作
  checkIn: (habitId: string, date: string, count?: number, note?: string) => void
  cancelCheckIn: (habitId: string, date: string) => void
  getCheckIn: (habitId: string, date: string) => CheckIn | undefined
  
  // 统计
  getHabitStats: (habitId: string) => HabitStats
  getTodayHabits: () => Habit[]
  
  // 成就
  checkAchievements: (habitId: string) => Achievement[]
  
  // 矛盾分析关联
  addAnalysisInsight: (insight: Omit<AnalysisInsight, 'id' | 'createdAt'>) => void
}

export interface AnalysisInsight {
  id: string
  mainConflict: string
  suggestedHabits: string[]
  createdAt: string
}

export const useHabitStore = create<HabitStore>()(
  persist(
    (set, get) => ({
      habits: [],
      checkIns: [],
      achievements: [],
      analysisInsights: [],
      
      addHabit: (habitData) => {
        const habit: Habit = {
          ...habitData,
          id: Date.now().toString(),
          createdAt: new Date().toISOString(),
          order: get().habits.length,
        }
        set((state) => ({ habits: [...state.habits, habit] }))
      },
      
      updateHabit: (id, updates) => {
        set((state) => ({
          habits: state.habits.map((h) => (h.id === id ? { ...h, ...updates } : h)),
        }))
      },
      
      deleteHabit: (id) => {
        set((state) => ({
          habits: state.habits.filter((h) => h.id !== id),
          checkIns: state.checkIns.filter((c) => c.habitId !== id),
        }))
      },
      
      archiveHabit: (id) => {
        set((state) => ({
          habits: state.habits.map((h) => (h.id === id ? { ...h, archived: !h.archived } : h)),
        }))
      },
      
      reorderHabits: (habits) => {
        set({ habits })
      },
      
      checkIn: (habitId, date, count = 1, note) => {
        const existing = get().checkIns.find((c) => c.habitId === habitId && c.date === date)
        if (existing) {
          set((state) => ({
            checkIns: state.checkIns.map((c) =>
              c.id === existing.id ? { ...c, count: c.count + count, note: note || c.note } : c
            ),
          }))
        } else {
          const checkIn: CheckIn = {
            id: Date.now().toString(),
            habitId,
            date,
            count,
            note,
            createdAt: new Date().toISOString(),
          }
          set((state) => ({ checkIns: [...state.checkIns, checkIn] }))
        }
        // 检查成就
        get().checkAchievements(habitId)
      },
      
      cancelCheckIn: (habitId, date) => {
        set((state) => ({
          checkIns: state.checkIns.filter((c) => !(c.habitId === habitId && c.date === date)),
        }))
      },
      
      getCheckIn: (habitId, date) => {
        return get().checkIns.find((c) => c.habitId === habitId && c.date === date)
      },
      
      getHabitStats: (habitId) => {
        const habit = get().habits.find((h) => h.id === habitId)
        if (!habit) {
          return { habitId, currentStreak: 0, longestStreak: 0, totalCount: 0, completionRate: 0, checkInDates: [] }
        }
        
        const habitCheckIns = get().checkIns.filter((c) => c.habitId === habitId)
        const checkInDates = habitCheckIns.map((c) => c.date).sort()
        
        // 计算连续天数
        let currentStreak = 0
        let longestStreak = 0
        let tempStreak = 0
        
        const today = getToday()
        const sortedDates = [...checkInDates].sort()
        
        for (let i = 0; i < sortedDates.length; i++) {
          if (i === 0) {
            tempStreak = 1
          } else {
            const prev = new Date(sortedDates[i - 1])
            const curr = new Date(sortedDates[i])
            const diffDays = Math.round((curr.getTime() - prev.getTime()) / (1000 * 60 * 60 * 24))
            
            if (diffDays === 1) {
              tempStreak++
            } else {
              tempStreak = 1
            }
          }
          longestStreak = Math.max(longestStreak, tempStreak)
        }
        
        // 当前连续
        if (sortedDates.length > 0) {
          const lastDate = sortedDates[sortedDates.length - 1]
          const lastDateObj = new Date(lastDate)
          const todayObj = new Date(today)
          const daysSinceLast = Math.round((todayObj.getTime() - lastDateObj.getTime()) / (1000 * 60 * 60 * 24))
          
          if (daysSinceLast <= 1) {
            currentStreak = tempStreak
          }
        }
        
        // 完成率
        const daysSinceCreated = Math.max(1, Math.round((new Date(today).getTime() - new Date(habit.createdAt).getTime()) / (1000 * 60 * 60 * 24)))
        const completionRate = Math.round((checkInDates.length / daysSinceCreated) * 100)
        
        return {
          habitId,
          currentStreak,
          longestStreak,
          totalCount: habitCheckIns.reduce((sum, c) => sum + c.count, 0),
          completionRate: Math.min(100, completionRate),
          checkInDates,
        }
      },
      
      getTodayHabits: () => {
        const today = new Date()
        const dayOfWeek = today.getDay() as 0 | 1 | 2 | 3 | 4 | 5 | 6
        const dayOfMonth = today.getDate()
        
        return get().habits.filter((h) => {
          if (h.archived) return false
          
          switch (h.frequency) {
            case 'daily':
              return true
            case 'weekly':
              return h.weekDays?.includes(dayOfWeek)
            case 'monthly':
              return h.monthDays?.includes(dayOfMonth)
            default:
              return false
          }
        })
      },
      
      checkAchievements: (habitId) => {
        const stats = get().getHabitStats(habitId)
        const newAchievements: Achievement[] = []
        
        // 检查里程碑
        const milestones = [
          { count: 7, name: '坚持一周', description: '连续打卡7天', icon: '🏆' },
          { count: 30, name: '坚持一月', description: '连续打卡30天', icon: '🏆' },
          { count: 100, name: '百次达人', description: '累计打卡100次', icon: '🌟' },
        ]
        
        milestones.forEach((m) => {
          const alreadyHas = get().achievements.some(
            (a) => a.habitId === habitId && a.type === 'milestone' && a.name === m.name
          )
          
          if (!alreadyHas) {
            if (m.count === 7 && stats.currentStreak >= 7) {
              newAchievements.push({
                id: Date.now().toString(),
                habitId,
                type: 'streak',
                name: m.name,
                description: m.description,
                unlockedAt: new Date().toISOString(),
              })
            } else if (m.count === 30 && stats.currentStreak >= 30) {
              newAchievements.push({
                id: Date.now().toString(),
                habitId,
                type: 'streak',
                name: m.name,
                description: m.description,
                unlockedAt: new Date().toISOString(),
              })
            } else if (m.count === 100 && stats.totalCount >= 100) {
              newAchievements.push({
                id: Date.now().toString(),
                habitId,
                type: 'total',
                name: m.name,
                description: m.description,
                unlockedAt: new Date().toISOString(),
              })
            }
          }
        })
        
        if (newAchievements.length > 0) {
          set((state) => ({ achievements: [...state.achievements, ...newAchievements] }))
        }
        
        return newAchievements
      },
      
      addAnalysisInsight: (insight) => {
        const newInsight: AnalysisInsight = {
          ...insight,
          id: Date.now().toString(),
          createdAt: new Date().toISOString(),
        }
        set((state) => ({ analysisInsights: [newInsight, ...state.analysisInsights] }))
      },
    }),
    {
      name: 'habitflow-storage',
    }
  )
)
