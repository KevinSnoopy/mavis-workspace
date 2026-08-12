import { useState } from 'react'
import { useHabitStore } from './store'
import { HabitForm } from './HabitForm'
import type { Habit } from './types'

type Filter = 'all' | 'active' | 'archived'

export function AllHabitsView() {
  const { habits } = useHabitStore()
  const [filter, setFilter] = useState<Filter>('active')
  const [showForm, setShowForm] = useState(false)
  const [editingHabit, setEditingHabit] = useState<Habit | undefined>()
  
  const filteredHabits = habits.filter((h) => {
    if (filter === 'active') return !h.archived
    if (filter === 'archived') return h.archived
    return true
  })
  
  const stats = useHabitStore((state) => state.getHabitStats)
  
  return (
    <div className="animate-fadeIn">
      {/* 筛选 */}
      <div className="flex gap-2 mb-6">
        {[
          { key: 'active', label: '进行中', count: habits.filter((h) => !h.archived).length },
          { key: 'archived', label: '已暂停', count: habits.filter((h) => h.archived).length },
          { key: 'all', label: '全部', count: habits.length },
        ].map((f) => (
          <button
            key={f.key}
            onClick={() => setFilter(f.key as Filter)}
            className={`flex-1 py-2.5 rounded-xl text-sm font-medium transition-all flex items-center justify-center gap-2 ${
              filter === f.key ? 'bg-[#c41e3a] text-white' : 'bg-[#1a1a2e] text-[#a0a0b0]'
            }`}
          >
            <span>{f.label}</span>
            <span className={`text-xs px-1.5 py-0.5 rounded-full ${
              filter === f.key ? 'bg-white/20' : 'bg-[#0f0f1a]'
            }`}>
              {f.count}
            </span>
          </button>
        ))}
      </div>
      
      {/* 习惯列表 */}
      {filteredHabits.length > 0 ? (
        <div className="space-y-3">
          {filteredHabits.map((habit) => {
            const habitStats = stats(habit.id)
            return (
              <div 
                key={habit.id}
                className={`bg-[#1a1a2e] rounded-2xl p-4 border border-white/5 ${habit.archived ? 'opacity-60' : ''}`}
                onClick={() => setEditingHabit(habit)}
              >
                <div className="flex items-center gap-3">
                  <div 
                    className="w-12 h-12 rounded-xl flex items-center justify-center text-2xl"
                    style={{ backgroundColor: habit.color + '20' }}
                  >
                    {habit.icon}
                  </div>
                  <div className="flex-1 min-w-0">
                    <div className="flex items-center gap-2">
                      <h3 className="font-medium text-sm truncate">{habit.name}</h3>
                      {habit.archived && (
                        <span className="text-xs px-1.5 py-0.5 bg-[#a0a0b0]/20 text-[#a0a0b0] rounded">已暂停</span>
                      )}
                    </div>
                    <div className="flex items-center gap-4 mt-1 text-xs text-[#a0a0b0]">
                      <span>🔥 {habitStats.currentStreak}天</span>
                      <span>📊 {habitStats.totalCount}次</span>
                      <span>{habitStats.completionRate}%</span>
                    </div>
                  </div>
                  <span className="text-[#a0a0b0]">→</span>
                </div>
              </div>
            )
          })}
        </div>
      ) : (
        <div className="text-center py-12">
          <p className="text-5xl mb-4">{filter === 'archived' ? '📦' : '🌱'}</p>
          <p className="text-[#a0a0b0]">
            {filter === 'archived' ? '没有暂停的习惯' : '还没有习惯'}
          </p>
          {filter !== 'archived' && (
            <p className="text-[#a0a0b0] text-sm mt-1">点击下方按钮添加新习惯</p>
          )}
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
