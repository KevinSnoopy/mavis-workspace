import { useState } from 'react'
import './index.css'
import HomePage from './pages/HomePage'
import AnalyzerPage from './pages/AnalyzerPage'
import { HabitFlowPage } from './features/habit'
import HistoryPage from './pages/HistoryPage'

type Page = 'home' | 'analyzer' | 'habitflow' | 'history'

function App() {
  const [currentPage, setCurrentPage] = useState<Page>('home')

  const renderPage = () => {
    switch (currentPage) {
      case 'home': return <HomePage onNavigate={setCurrentPage} />
      case 'analyzer': return <AnalyzerPage onBack={() => setCurrentPage('home')} />
      case 'habitflow': return <HabitFlowPage onBack={() => setCurrentPage('home')} />
      case 'history': return <HistoryPage onBack={() => setCurrentPage('home')} />
    }
  }

  return (
    <div className="min-h-screen bg-[var(--color-bg)] text-white">
      <main className="max-w-lg mx-auto px-4">
        {renderPage()}
      </main>
      
      {/* 底部导航 - 简化 */}
      <nav className="fixed bottom-0 left-0 right-0 bg-[var(--color-bg-card)] border-t border-white/5 z-50 safe-area-bottom">
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
