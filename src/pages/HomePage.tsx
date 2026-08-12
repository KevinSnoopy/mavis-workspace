import { useState, useEffect } from 'react'
import { useHabitStore } from '../features/habit/store'

type Page = 'home' | 'analyzer' | 'habitflow' | 'history'

interface HomePageProps {
  onNavigate: (page: Page) => void
}

function HomePage({ onNavigate }: HomePageProps) {
  const [showConfetti, setShowConfetti] = useState(false)
  
  const { habits, checkIns, getTodayHabits, getCheckIn, getHabitStats } = useHabitStore()
  const todayHabits = getTodayHabits()
  const todayDate = new Date().toISOString().split('T')[0]
  
  // 今日数据
  const completedCount = todayHabits.filter((h) => {
    const check = getCheckIn(h.id, todayDate)
    return check && check.count >= h.targetPerDay
  }).length
  
  const isAllDone = completedCount === todayHabits.length && todayHabits.length > 0
  const isPartiallyDone = completedCount > 0 && completedCount < todayHabits.length
  
  // 全局连胜（最长的习惯连胜）
  const globalStreak = Math.max(
    ...habits.map(h => getHabitStats(h.id).currentStreak),
    0
  )
  
  // 今日打卡时触发彩屑
  useEffect(() => {
    if (isAllDone) {
      setShowConfetti(true)
      setTimeout(() => setShowConfetti(false), 2000)
    }
  }, [isAllDone])
  
  // 问候语
  const getGreeting = () => {
    const hour = new Date().getHours()
    if (hour < 6) return { text: '夜深了', emoji: '🌙' }
    if (hour < 9) return { text: '早上好', emoji: '🌅' }
    if (hour < 12) return { text: '上午好', emoji: '☀️' }
    if (hour < 14) return { text: '中午好', emoji: '🌞' }
    if (hour < 18) return { text: '下午好', emoji: '🌤️' }
    if (hour < 22) return { text: '晚上好', emoji: '🌆' }
    return { text: '夜深了', emoji: '🌙' }
  }
  
  const greeting = getGreeting()

  return (
    <div className="min-h-screen -mx-4 px-4 pb-24">
      {/* 顶部问候 */}
      <div className="pt-4 pb-6">
        <p className="text-[var(--color-text-secondary)] text-sm">
          {greeting.emoji} {greeting.text}
        </p>
        <h1 className="text-2xl font-bold mt-1">
          {isAllDone ? '太棒了！今天全部完成 🎉' : 
           isPartiallyDone ? '继续加油 💪' : 
           '开始今天的行动吧'}
        </h1>
      </div>

      {/* 连胜大卡片 - C位展示 */}
      {globalStreak > 0 && (
        <div className="glass rounded-[20px] p-6 mb-6 relative overflow-hidden">
          <div className="absolute top-0 right-0 w-32 h-32 bg-gradient-to-br from-[var(--color-accent)]/20 to-transparent rounded-full -translate-y-1/2 translate-x-1/4" />
          
          <div className="relative z-10 flex items-center gap-4">
            <div className="text-5xl fire-glow">
              🔥
            </div>
            <div>
              <p className="text-[var(--color-text-secondary)] text-sm">当前连胜</p>
              <p className="text-4xl font-bold">
                <span className="streak-fire">{globalStreak}</span>
                <span className="text-xl text-[var(--color-text-secondary)] ml-1">天</span>
              </p>
            </div>
          </div>
          
          <p className="text-xs text-[var(--color-text-secondary)] mt-4">
            星星之火，可以燎原
          </p>
        </div>
      )}

      {/* 今日打卡进度 */}
      <div className="card rounded-[20px] p-5 mb-6">
        <div className="flex items-center justify-between mb-4">
          <h2 className="font-semibold">今日行动</h2>
          <span className="text-sm text-[var(--color-text-secondary)]">
            {completedCount}/{todayHabits.length}
          </span>
        </div>
        
        {/* 圆形进度 */}
        <div className="flex justify-center mb-6">
          <div className="relative w-28 h-28">
            <svg className="w-full h-full transform -rotate-90">
              <circle
                cx="56"
                cy="56"
                r="48"
                fill="none"
                stroke="var(--color-bg-elevated)"
                strokeWidth="8"
              />
              <circle
                cx="56"
                cy="56"
                r="48"
                fill="none"
                stroke="url(#progressGradient)"
                strokeWidth="8"
                strokeLinecap="round"
                strokeDasharray={`${(completedCount / Math.max(todayHabits.length, 1)) * 301} 301`}
                className="transition-all duration-500"
              />
              <defs>
                <linearGradient id="progressGradient" x1="0%" y1="0%" x2="100%" y2="0%">
                  <stop offset="0%" stopColor="var(--color-primary)" />
                  <stop offset="100%" stopColor="var(--color-accent)" />
                </linearGradient>
              </defs>
            </svg>
            <div className="absolute inset-0 flex flex-col items-center justify-center">
              {isAllDone ? (
                <span className="text-3xl">🎉</span>
              ) : (
                <>
                  <span className="text-2xl font-bold">{Math.round((completedCount / Math.max(todayHabits.length, 1)) * 100)}%</span>
                  <span className="text-xs text-[var(--color-text-secondary)]">完成</span>
                </>
              )}
            </div>
          </div>
        </div>
        
        {/* 今日习惯列表 */}
        {todayHabits.length > 0 ? (
          <div className="space-y-3">
            {todayHabits.map((habit) => {
              const check = getCheckIn(habit.id, todayDate)
              const isDone = check && check.count >= habit.targetPerDay
              const stats = getHabitStats(habit.id)
              
              return (
                <div
                  key={habit.id}
                  onClick={() => {
                    if (isDone) {
                      useHabitStore.getState().cancelCheckIn(habit.id, todayDate)
                    } else {
                      useHabitStore.getState().checkIn(habit.id, todayDate)
                      if (stats.currentStreak + 1 >= 7) {
                        // 达成连胜
                      }
                    }
                  }}
                  className={`habit-card flex items-center gap-3 p-4 rounded-2xl cursor-pointer ${
                    isDone ? 'bg-[var(--color-success)]/10 border border-[var(--color-success)]/30' : 'bg-[var(--color-bg-elevated)]'
                  }`}
                >
                  <div 
                    className={`w-12 h-12 rounded-2xl flex items-center justify-center text-2xl transition-all ${
                      isDone ? 'animate-checkBounce' : ''
                    }`}
                    style={{ backgroundColor: habit.color + '20' }}
                  >
                    {isDone ? '✓' : habit.icon}
                  </div>
                  
                  <div className="flex-1 min-w-0">
                    <p className={`font-medium truncate ${isDone ? 'line-through opacity-60' : ''}`}>
                      {habit.name}
                    </p>
                    <p className="text-xs text-[var(--color-text-secondary)]">
                      {stats.currentStreak > 0 ? `🔥 ${stats.currentStreak}天` : '开始你的第一次'}
                    </p>
                  </div>
                  
                  <div className={`w-10 h-10 rounded-full flex items-center justify-center transition-all ${
                    isDone ? 'check-btn-done text-white' : 'check-btn-pending'
                  }`}>
                    {isDone ? '✓' : (
                      <span className="text-lg" style={{ color: habit.color }}>+</span>
                    )}
                  </div>
                </div>
              )
            })}
          </div>
        ) : (
          <div className="text-center py-8">
            <p className="text-4xl mb-3">🌱</p>
            <p className="text-[var(--color-text-secondary)]">还没有习惯</p>
            <button
              onClick={() => onNavigate('analyzer')}
              className="btn-primary mt-4 text-sm"
            >
              从矛盾分析开始
            </button>
          </div>
        )}
      </div>

      {/* 矛盾分析入口 */}
      <div 
        onClick={() => onNavigate('analyzer')}
        className="card rounded-[20px] p-5 cursor-pointer habit-card"
      >
        <div className="flex items-center gap-4">
          <div className="w-14 h-14 rounded-2xl bg-gradient-to-br from-[var(--color-primary)] to-[var(--color-primary-dark)] flex items-center justify-center text-2xl shadow-lg">
            ⚖️
          </div>
          <div className="flex-1">
            <h3 className="font-semibold">矛盾分析器</h3>
            <p className="text-sm text-[var(--color-text-secondary)]">
              找到核心问题，转化为行动
            </p>
          </div>
          <span className="text-[var(--color-primary)] text-xl">→</span>
        </div>
      </div>

      {/* 底部快捷操作 */}
      <div className="grid grid-cols-2 gap-3 mt-6">
        <button
          onClick={() => onNavigate('habitflow')}
          className="card p-4 text-left"
        >
          <span className="text-2xl mb-2 block">📊</span>
          <p className="font-medium text-sm">全部习惯</p>
          <p className="text-xs text-[var(--color-text-secondary)]">{habits.filter(h => !h.archived).length}个进行中</p>
        </button>
        
        <button
          onClick={() => onNavigate('history')}
          className="card p-4 text-left"
        >
          <span className="text-2xl mb-2 block">🏆</span>
          <p className="font-medium text-sm">我的成就</p>
          <p className="text-xs text-[var(--color-text-secondary)]">{checkIns.length}次累计打卡</p>
        </button>
      </div>

      {/* 彩屑效果 */}
      {showConfetti && (
        <div className="fixed inset-0 pointer-events-none z-50">
          {[...Array(20)].map((_, i) => (
            <div
              key={i}
              className="absolute w-3 h-3 rounded-full"
              style={{
                left: `${Math.random() * 100}%`,
                top: `${50 + Math.random() * 30}%`,
                backgroundColor: ['#ff6b6b', '#ffd93d', '#6bcb77', '#4d96ff', '#ff6b35'][Math.floor(Math.random() * 5)],
                animation: `confetti ${1 + Math.random()}s ease-out forwards`,
                animationDelay: `${Math.random() * 0.5}s`,
              }}
            />
          ))}
        </div>
      )}
    </div>
  )
}

export default HomePage
