import { useState } from 'react'
import './index.css'
import HomePage from './pages/HomePage'
import AnalyzerPage from './pages/AnalyzerPage'
import PracticePage from './pages/PracticePage'
import HistoryPage from './pages/HistoryPage'
import { HabitFlowPage } from './features/habit'

type Page = 'home' | 'analyzer' | 'practice' | 'history' | 'habitflow'

function App() {
  const [currentPage, setCurrentPage] = useState<Page>('home')

  const renderPage = () => {
    switch (currentPage) {
      case 'home': return <HomePage onNavigate={setCurrentPage} />
      case 'analyzer': return <AnalyzerPage onBack={() => setCurrentPage('home')} />
      case 'practice': return <PracticePage onBack={() => setCurrentPage('home')} />
      case 'history': return <HistoryPage onBack={() => setCurrentPage('home')} />
      case 'habitflow': return <HabitFlowPage onBack={() => setCurrentPage('home')} />
    }
  }

  return (
    <div className="min-h-screen bg-[#0f0f1a] text-white pb-20">
      <main className="max-w-lg mx-auto px-4 py-6">
        {renderPage()}
      </main>
      
      {/* 底部导航 */}
      <nav className="fixed bottom-0 left-0 right-0 bg-[#1a1a2e] border-t border-white/10 px-4 py-2 z-50">
        <div className="max-w-lg mx-auto flex justify-around">
          {[
            { key: 'home', label: '首页', icon: '🏠' },
            { key: 'analyzer', label: '矛盾分析', icon: '⚖️' },
            { key: 'habitflow', label: '习惯', icon: '⚡' },
            { key: 'history', label: '记录', icon: '📝' },
          ].map(item => (
            <button
              key={item.key}
              onClick={() => setCurrentPage(item.key as Page)}
              className={`flex flex-col items-center py-2 px-4 rounded-lg transition-all ${
                currentPage === item.key 
                  ? 'text-[#c41e3a]' 
                  : 'text-[#a0a0b0]'
              }`}
            >
              <span className="text-xl">{item.icon}</span>
              <span className="text-xs mt-1">{item.label}</span>
            </button>
          ))}
        </div>
      </nav>
    </div>
  )
}

export default App
