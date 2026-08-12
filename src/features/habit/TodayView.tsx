import { useState } from 'react'
import { useHabitStore } from './store'
import { HabitCard } from './HabitCard'
import { HabitForm } from './HabitForm'
import type { Habit } from './types'

export function TodayView() {
  const { getTodayHabits, checkIn, cancelCheckIn, getCheckIn } = useHabitStore()
  const [showForm, setShowForm] = useState(false)
  const [editingHabit, setEditingHabit] = useState<Habit | undefined>()
  const [selectedDate, setSelectedDate] = useState(new Date().toISOString().split('T')[0])
  
  const todayHabits = getTodayHabits()
  const completedCount = todayHabits.filter((h) => {
    const check = getCheckIn(h.id, selectedDate)
    return check && check.count >= h.targetPerDay
  }).length
  
  const getDateLabel = (dateStr: string) => {
    const date = new Date(dateStr)
    const today = new Date()
    const yesterday = new Date(today)
    yesterday.setDate(yesterday.getDate() - 1)
    
    if (dateStr === today.toISOString().split('T')[0]) return '今天'
    if (dateStr === yesterday.toISOString().split('T')[0]) return '昨天'
    return date.toLocaleDateString('zh-CN', { month: 'numeric', day: 'numeric' })
  }
  
  const changeDate = (delta: number) => {
    const date = new Date(selectedDate)
    date.setDate(date.getDate() + delta)
    setSelectedDate(date.toISOString().split('T')[0])
  }
  
  return (
    <div className="animate-fadeIn">
      {/* 日期选择器 */}
      <div className="flex items-center justify-between mb-6">
        <button onClick={() => changeDate(-1)} className="text-2xl text-[#a0a0b0] p-2">←</button>
        <div className="text-center">
          <p className="text-xl font-bold">{getDateLabel(selectedDate)}</p>
          <p className="text-xs text-[#a0a0b0]">
            {new Date(selectedDate).toLocaleDateString('zh-CN', { weekday: 'long' })}
          </p>
        </div>
        <button 
          onClick={() => changeDate(1)} 
          className="text-2xl text-[#a0a0b0] p-2"
          disabled={selectedDate >= new Date().toISOString().split('T')[0]}
        >
          →
        </button>
      </div>
      
      {/* 进度概览 */}
      {selectedDate === new Date().toISOString().split('T')[0] && (
        <div className="bg-gradient-to-r from-[#c41e3a] to-[#8b0000] rounded-2xl p-5 mb-6">
          <div className="flex items-center justify-between">
            <div>
              <p className="text-white/80 text-sm">今日完成</p>
              <p className="text-3xl font-bold mt-1">
                {completedCount} <span className="text-lg font-normal">/ {todayHabits.length}</span>
              </p>
            </div>
            <div className="text-5xl">
              {completedCount === todayHabits.length && todayHabits.length > 0 ? '🎉' : '💪'}
            </div>
          </div>
          <div className="mt-3 h-2 bg-white/20 rounded-full overflow-hidden">
            <div 
              className="h-full bg-white rounded-full transition-all duration-500"
              style={{ width: todayHabits.length > 0 ? `${(completedCount / todayHabits.length) * 100}%` : '0%' }}
            />
          </div>
        </div>
      )}
      
      {/* 习惯列表 */}
      {todayHabits.length > 0 ? (
        <div className="space-y-3">
          {todayHabits.map((habit) => (
            <HabitCard
              key={habit.id}
              habit={habit}
              checkIn={getCheckIn(habit.id, selectedDate)}
              onCheckIn={() => checkIn(habit.id, selectedDate)}
              onCancelCheckIn={() => cancelCheckIn(habit.id, selectedDate)}
              onEdit={() => setEditingHabit(habit)}
              showStats={selectedDate === new Date().toISOString().split('T')[0]}
            />
          ))}
        </div>
      ) : (
        <div className="text-center py-12">
          <p className="text-5xl mb-4">✨</p>
          <p className="text-[#a0a0b0]">今天没有待完成的习惯</p>
          <p className="text-[#a0a0b0] text-sm mt-1">点击下方添加新习惯吧</p>
        </div>
      )}
      
      {/* 添加按钮 */}
      <button
        onClick={() => { setEditingHabit(undefined); setShowForm(true) }}
        className="fixed bottom-24 right-4 w-14 h-14 bg-[#c41e3a] rounded-full shadow-lg flex items-center justify-center text-2xl active:scale-90 transition-transform z-40"
      >
        +
      </button>
      
      {/* 表单弹窗 */}
      {showForm && <HabitForm onClose={() => setShowForm(false)} />}
      {editingHabit && <HabitForm habit={editingHabit} onClose={() => setEditingHabit(undefined)} />}
    </div>
  )
}
