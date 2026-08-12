import { useState } from 'react'
import { useHabitStore } from './store'

// 简单的热力图组件
function Heatmap({ dates, color }: { dates: string[], color: string }) {
  const today = new Date()
  const months: { date: Date; dates: string[] }[] = []
  
  // 生成最近90天的数据
  for (let i = 89; i >= 0; i--) {
    const date = new Date(today)
    date.setDate(date.getDate() - i)
    const month = months.find((m) => m.date.getMonth() === date.getMonth() && m.date.getFullYear() === date.getFullYear())
    
    if (month) {
      month.dates.push(date.toISOString().split('T')[0])
    } else {
      months.push({ date: new Date(date.getFullYear(), date.getMonth(), 1), dates: [date.toISOString().split('T')[0]] })
    }
  }
  
  const dateSet = new Set(dates)
  
  return (
    <div className="overflow-x-auto">
      <div className="flex gap-1 min-w-max">
        {months.map((month, mi) => (
          <div key={mi} className="flex flex-col gap-0.5">
            {month.dates.map((d) => {
              const date = new Date(d)
              const dayOfWeek = date.getDay()
              const hasActivity = dateSet.has(d)
              
              // 只在每月第一天显示月份
              const showMonth = date.getDate() === 1
              const isToday = d === today.toISOString().split('T')[0]
              
              return (
                <div key={d} className="relative group">
                  {showMonth && (
                    <span className="absolute -top-4 left-0 text-[10px] text-[#a0a0b0]">
                      {date.toLocaleDateString('zh-CN', { month: 'short' })}
                    </span>
                  )}
                  {dayOfWeek === 0 && (
                    <span className="absolute -left-3 text-[10px] text-[#a0a0b0]">
                      {date.getDate() === 1 ? '' : '日'}
                    </span>
                  )}
                  <div 
                    className={`w-3 h-3 rounded-sm transition-all cursor-pointer group-hover:ring-1 group-hover:ring-white/50 ${
                      isToday ? 'ring-1 ring-[#c41e3a]' : ''
                    } ${hasActivity ? '' : 'bg-[#1a1a2e]'}`}
                    style={{ backgroundColor: hasActivity ? color : undefined }}
                    title={`${d}${hasActivity ? ' ✅' : ''}`}
                  />
                </div>
              )
            })}
          </div>
        ))}
      </div>
    </div>
  )
}

// 周趋势图（简单文字版）
function WeekTrend({ dates }: { dates: string[] }) {
  const today = new Date()
  const weekData: { day: string; count: number }[] = []
  
  for (let i = 6; i >= 0; i--) {
    const date = new Date(today)
    date.setDate(date.getDate() - i)
    const dateStr = date.toISOString().split('T')[0]
    const count = dates.filter((d) => d === dateStr).length
    weekData.push({
      day: date.toLocaleDateString('zh-CN', { weekday: 'short' }),
      count,
    })
  }
  
  const maxCount = Math.max(...weekData.map((d) => d.count), 1)
  
  return (
    <div className="flex items-end gap-2 h-20">
      {weekData.map((d, i) => (
        <div key={i} className="flex-1 flex flex-col items-center">
          <div 
            className="w-full bg-[#c41e3a] rounded-t transition-all"
            style={{ height: `${(d.count / maxCount) * 60}px`, minHeight: d.count > 0 ? '4px' : '0' }}
          />
          <span className="text-[10px] text-[#a0a0b0] mt-1">{d.day}</span>
        </div>
      ))}
    </div>
  )
}

export function StatsView() {
  const { habits, checkIns, achievements } = useHabitStore()
  const [selectedHabitId, setSelectedHabitId] = useState<string | null>(null)
  
  const selectedHabit = selectedHabitId ? habits.find((h) => h.id === selectedHabitId) : null
  const stats = useHabitStore((state) => state.getHabitStats)
  
  // 总体统计
  const totalCheckIns = checkIns.length
  const totalHabits = habits.filter((h) => !h.archived).length
  const todayCheckIns = checkIns.filter((c) => c.date === new Date().toISOString().split('T')[0]).length
  
  // 连续天数（取所有习惯中最长的）
  const longestStreak = Math.max(...habits.map((h) => stats(h.id).currentStreak), 0)
  
  if (habits.length === 0) {
    return (
      <div className="animate-fadeIn text-center py-12">
        <p className="text-5xl mb-4">📊</p>
        <p className="text-[#a0a0b0]">还没有数据</p>
        <p className="text-[#a0a0b0] text-sm mt-1">先添加几个习惯并开始打卡吧</p>
      </div>
    )
  }
  
  return (
    <div className="animate-fadeIn">
      {/* 概览卡片 */}
      <div className="grid grid-cols-2 gap-3 mb-6">
        <div className="bg-[#1a1a2e] rounded-2xl p-4 text-center border border-white/5">
          <p className="text-3xl font-bold text-[#c41e3a]">{todayCheckIns}</p>
          <p className="text-xs text-[#a0a0b0] mt-1">今日打卡</p>
        </div>
        <div className="bg-[#1a1a2e] rounded-2xl p-4 text-center border border-white/5">
          <p className="text-3xl font-bold text-[#22c55e]">{longestStreak}</p>
          <p className="text-xs text-[#a0a0b0] mt-1">最长连续</p>
        </div>
        <div className="bg-[#1a1a2e] rounded-2xl p-4 text-center border border-white/5">
          <p className="text-3xl font-bold text-[#3b82f6]">{totalHabits}</p>
          <p className="text-xs text-[#a0a0b0] mt-1">进行中的习惯</p>
        </div>
        <div className="bg-[#1a1a2e] rounded-2xl p-4 text-center border border-white/5">
          <p className="text-3xl font-bold text-[#e6b800]">{totalCheckIns}</p>
          <p className="text-xs text-[#a0a0b0] mt-1">累计打卡</p>
        </div>
      </div>
      
      {/* 成就 */}
      {achievements.length > 0 && (
        <div className="mb-6">
          <h3 className="text-sm font-medium mb-3">🏆 成就</h3>
          <div className="flex flex-wrap gap-2">
            {achievements.map((a) => (
              <div key={a.id} className="bg-[#e6b800]/20 text-[#e6b800] px-3 py-1.5 rounded-full text-xs flex items-center gap-1">
                <span>🏆</span>
                <span>{a.name}</span>
              </div>
            ))}
          </div>
        </div>
      )}
      
      {/* 习惯选择 */}
      <div className="mb-4">
        <h3 className="text-sm font-medium mb-3">📈 详细统计</h3>
        <select
          value={selectedHabitId || ''}
          onChange={(e) => setSelectedHabitId(e.target.value || null)}
          className="w-full bg-[#1a1a2e] rounded-xl px-4 py-3 text-sm border border-white/5 outline-none"
        >
          <option value="">选择习惯查看详情</option>
          {habits.filter((h) => !h.archived).map((h) => (
            <option key={h.id} value={h.id}>{h.icon} {h.name}</option>
          ))}
        </select>
      </div>
      
      {/* 选中习惯的详细统计 */}
      {selectedHabit && (
        <div className="bg-[#1a1a2e] rounded-2xl p-5 border border-white/5">
          <div className="flex items-center gap-3 mb-4">
            <div 
              className="w-12 h-12 rounded-xl flex items-center justify-center text-2xl"
              style={{ backgroundColor: selectedHabit.color + '20' }}
            >
              {selectedHabit.icon}
            </div>
            <div>
              <h4 className="font-medium">{selectedHabit.name}</h4>
              <p className="text-xs text-[#a0a0b0]">{selectedHabit.description}</p>
            </div>
          </div>
          
          {/* 关键数据 */}
          <div className="grid grid-cols-3 gap-4 mb-4">
            <div className="text-center">
              <p className="text-2xl font-bold text-[#c41e3a]">{stats(selectedHabit.id).currentStreak}</p>
              <p className="text-xs text-[#a0a0b0]">当前连续</p>
            </div>
            <div className="text-center">
              <p className="text-2xl font-bold text-[#22c55e]">{stats(selectedHabit.id).longestStreak}</p>
              <p className="text-xs text-[#a0a0b0]">最长连续</p>
            </div>
            <div className="text-center">
              <p className="text-2xl font-bold text-[#3b82f6]">{stats(selectedHabit.id).totalCount}</p>
              <p className="text-xs text-[#a0a0b0]">累计次数</p>
            </div>
          </div>
          
          {/* 热力图 */}
          <div className="mb-4">
            <p className="text-xs text-[#a0a0b0] mb-2">近90天打卡记录</p>
            <Heatmap dates={stats(selectedHabit.id).checkInDates} color={selectedHabit.color} />
          </div>
          
          {/* 周趋势 */}
          <div>
            <p className="text-xs text-[#a0a0b0] mb-2">本周趋势</p>
            <WeekTrend dates={stats(selectedHabit.id).checkInDates} />
          </div>
        </div>
      )}
      
      {/* 所有习惯概览 */}
      <div className="mt-6">
        <h3 className="text-sm font-medium mb-3">📊 所有习惯概览</h3>
        <div className="space-y-2">
          {habits.filter((h) => !h.archived).map((h) => {
            const s = stats(h.id)
            return (
              <div key={h.id} className="bg-[#1a1a2e] rounded-xl p-3 border border-white/5">
                <div className="flex items-center justify-between mb-2">
                  <div className="flex items-center gap-2">
                    <span>{h.icon}</span>
                    <span className="text-sm">{h.name}</span>
                  </div>
                  <span className="text-xs text-[#a0a0b0]">{s.completionRate}%完成率</span>
                </div>
                <div className="h-1.5 bg-[#0f0f1a] rounded-full overflow-hidden">
                  <div 
                    className="h-full rounded-full"
                    style={{ width: `${s.completionRate}%`, backgroundColor: h.color }}
                  />
                </div>
              </div>
            )
          })}
        </div>
      </div>
    </div>
  )
}
