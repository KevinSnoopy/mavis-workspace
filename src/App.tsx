import { useState, useEffect } from 'react'
import './index.css'
import HomePage from './pages/HomePage'
import AnalyzerPage from './pages/AnalyzerPage'
import { HabitFlowPage } from './features/habit'
import HistoryPage from './pages/HistoryPage'
import { AchievementToast } from './components/AchievementToast'
import { useHabitStore } from './features/habit/store'
import type { Achievement } from './features/habit/types'

type Page = 'home' | 'analyzer' | 'habitflow' | 'history'

function App() {
  const [currentPage, setCurrentPage] = useState<Page>('home')
  const [currentAchievement, setCurrentAchievement] = useState<Achievement | null>(null)
  const [achievementQueue, setAchievementQueue] = useState<Achievement[]>([])
  
  // 监听成就变化
  useEffect(() => {
    const store = useHabitStore.getState()
    const prevAchievements = store.achievements
    
    // 定期检查新成就
    const checkAchievements = () => {
      const current = useHabitStore.getState().achievements
      if (current.length > prevAchievements.length) {
        const newAchievements = current.filter(
          a => !prevAchievements.find(p => p.id === a.id)
        )
        if (newAchievements.length > 0) {
          setAchievementQueue(prev => [...prev, ...newAchievements])
        }
      }
    }
    
    // 每秒检查一次
    const interval = setInterval(checkAchievements, 1000)
    return () => clearInterval(interval)
  }, [])
  
  // 显示队列中的成就
  useEffect(() => {
    if (achievementQueue.length > 0 && !currentAchievement) {
      setCurrentAchievement(achievementQueue[0])
      setAchievementQueue(prev => prev.slice(1))
    }
  }, [achievementQueue, currentAchievement])
  
  const handleAchievementClose = () => {
    setCurrentAchievement(null)
  }

  const renderPage = () => {
    switch (currentPage) {
      case 'home': return <HomePage onNavigate={setCurrentPage} />
      case 'analyzer': return <AnalyzerPage onBack={() => setCurrentPage('home')} />
      case 'habitflow': return <HabitFlowPage onBack={() => setCurrentPage('home')} />
      case 'history': return <HistoryPage onBack={() => setCurrentPage('home')} />
    }
  }

  return (
    <div className="min-h-screen bg-[var(--color-bg)] text-[var(--color-text)]">
      <main className="max-w-lg mx-auto px-4">
        {renderPage()}
      </main>
      
      {/* 底部导航 */}
      <nav className="fixed bottom-0 left-0 right-0 bg-[var(--color-bg-card)] border-t border-[var(--color-border)] z-50 safe-area-bottom">
        <div className="max-w-lg mx-auto flex justify-around py-2">
          {[
            { key: 'home', label: '首页', icon: '🏠' },
            { key: 'analyzer', label: '分析', icon: '⚖️' },
            { key: 'habitflow', label: '习惯', icon: '✨' },
            { key: 'history', label: '记录', icon: '📊' },
          ].map(item => (
            <button
              key={item.key}
              onClick={() => setCurrentPage(item.key as Page)}
              className={`flex flex-col items-center py-2 px-5 rounded-xl transition-all ${
                currentPage === item.key 
                  ? 'text-[var(--color-primary)]' 
                  : 'text-[var(--color-text-secondary)]'
              }`}
            >
              <span className="text-xl">{item.icon}</span>
              <span className="text-xs mt-1 font-medium">{item.label}</span>
            </button>
          ))}
        </div>
      </nav>
      
      {/* 成就弹窗 */}
      {currentAchievement && (
        <AchievementToast 
          achievement={currentAchievement} 
          onClose={handleAchievementClose} 
        />
      )}
      
      {/* iOS安全区域 */}
      <style>{`
        .safe-area-bottom {
          padding-bottom: env(safe-area-inset-bottom, 0);
        }
      `}</style>
    </div>
  )
}

export default App
