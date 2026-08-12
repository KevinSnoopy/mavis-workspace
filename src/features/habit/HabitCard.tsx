import type { Habit, CheckIn } from './types'
import { useHabitStore } from './store'

interface HabitCardProps {
  habit: Habit
  checkIn?: CheckIn
  onCheckIn: () => void
  onCancelCheckIn: () => void
  onEdit: () => void
  showStats?: boolean
}

export function HabitCard({ habit, checkIn, onCheckIn, onCancelCheckIn, onEdit, showStats = true }: HabitCardProps) {
  const stats = useHabitStore((state) => state.getHabitStats(habit.id))
  const isCompleted = checkIn && checkIn.count >= habit.targetPerDay
  
  return (
    <div 
      className={`bg-[#1a1a2e] rounded-2xl p-4 border transition-all ${
        isCompleted ? 'border-[#22c55e]/30' : 'border-white/5'
      }`}
      onClick={onEdit}
    >
      <div className="flex items-start gap-3">
        {/* 图标 */}
        <div 
          className="w-12 h-12 rounded-xl flex items-center justify-center text-2xl shrink-0"
          style={{ backgroundColor: habit.color + '20' }}
        >
          {habit.icon}
        </div>
        
        {/* 信息 */}
        <div className="flex-1 min-w-0">
          <h3 className="font-medium text-sm truncate">{habit.name}</h3>
          {habit.description && (
            <p className="text-[#a0a0b0] text-xs mt-0.5 truncate">{habit.description}</p>
          )}
          
          {/* 进度条 */}
          {showStats && (
            <div className="mt-2">
              <div className="flex items-center justify-between text-xs text-[#a0a0b0] mb-1">
                <span>{stats.currentStreak > 0 ? `🔥 ${stats.currentStreak}天连续` : '尚未开始'}</span>
                <span>{checkIn?.count || 0}/{habit.targetPerDay}</span>
              </div>
              <div className="h-1.5 bg-[#0f0f1a] rounded-full overflow-hidden">
                <div 
                  className="h-full rounded-full transition-all duration-300"
                  style={{ 
                    width: `${Math.min(100, ((checkIn?.count || 0) / habit.targetPerDay) * 100)}%`,
                    backgroundColor: habit.color
                  }}
                />
              </div>
            </div>
          )}
        </div>
        
        {/* 打卡按钮 */}
        <button
          onClick={(e) => {
            e.stopPropagation()
            isCompleted ? onCancelCheckIn() : onCheckIn()
          }}
          className={`w-10 h-10 rounded-full flex items-center justify-center shrink-0 transition-all active:scale-90 ${
            isCompleted 
              ? 'bg-[#22c55e] text-white' 
              : 'bg-[#0f0f1a] border border-[#a0a0b0]/30 text-[#a0a0b0]'
          }`}
        >
          {isCompleted ? '✓' : habit.icon}
        </button>
      </div>
    </div>
  )
}
