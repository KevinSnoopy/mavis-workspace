import { useEffect, useState } from 'react'
import type { Achievement } from '../features/habit/types'

interface AchievementToastProps {
  achievement: Achievement
  onClose: () => void
}

export function AchievementToast({ achievement, onClose }: AchievementToastProps) {
  const [show, setShow] = useState(true)
  
  useEffect(() => {
    const timer = setTimeout(() => {
      setShow(false)
      setTimeout(onClose, 300)
    }, 4000)
    
    return () => clearTimeout(timer)
  }, [onClose])
  
  if (!show) return null
  
  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/50">
      <div className="achievement-toast card-elevated rounded-3xl p-8 max-w-xs mx-4 text-center">
        {/* 奖章图标 */}
        <div className="w-24 h-24 mx-auto mb-4 rounded-full bg-gradient-to-br from-[var(--color-accent)] to-[var(--color-primary)] flex items-center justify-center shadow-lg">
          <span className="text-5xl">🏆</span>
        </div>
        
        {/* 标题 */}
        <p className="text-[var(--color-accent)] text-sm font-medium mb-2">成就解锁</p>
        <h3 className="text-2xl font-bold mb-2">{achievement.name}</h3>
        <p className="text-[var(--color-text-secondary)] text-sm mb-6">
          {achievement.description}
        </p>
        
        {/* 按钮 */}
        <button
          onClick={() => {
            setShow(false)
            setTimeout(onClose, 300)
          }}
          className="btn-primary w-full"
        >
          太棒了！继续加油 💪
        </button>
      </div>
    </div>
  )
}

// Hook to track and show achievements
export function useAchievementNotifier() {
  const [currentAchievement, setCurrentAchievement] = useState<Achievement | null>(null)
  
  const notifyAchievement = (achievement: Achievement) => {
    setCurrentAchievement(achievement)
  }
  
  const clearAchievement = () => {
    setCurrentAchievement(null)
  }
  
  return {
    currentAchievement,
    notifyAchievement,
    clearAchievement,
    AchievementToastComponent: currentAchievement ? (
      <AchievementToast 
        achievement={currentAchievement} 
        onClose={clearAchievement} 
      />
    ) : null
  }
}
