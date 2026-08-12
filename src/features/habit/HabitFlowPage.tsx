import { useState } from 'react'
import { TodayView } from './TodayView'
import { AllHabitsView } from './AllHabitsView'
import { StatsView } from './StatsView'
import { SettingsView } from './SettingsView'

type Tab = 'today' | 'habits' | 'stats' | 'settings'

interface HabitFlowPageProps {
  onBack: () => void
}

export function HabitFlowPage({ onBack }: HabitFlowPageProps) {
  const [currentTab, setCurrentTab] = useState<Tab>('today')
  
  const tabs = [
    { key: 'today', label: '今日', icon: '📅' },
    { key: 'habits', label: '习惯', icon: '✨' },
    { key: 'stats', label: '统计', icon: '📊' },
    { key: 'settings', label: '设置', icon: '⚙️' },
  ] as const
  
  return (
    <div className="min-h-screen bg-[#0f0f1a] text-white pb-20">
      {/* 头部 */}
      <div className="max-w-lg mx-auto px-4 pt-4">
        <div className="flex items-center gap-4 mb-4">
          <button onClick={onBack} className="text-2xl text-[#a0a0b0]">←</button>
          <div>
            <h1 className="text-xl font-bold">⚡ HabitFlow</h1>
            <p className="text-xs text-[#a0a0b0]">习惯追踪</p>
          </div>
        </div>
      </div>
      
      {/* 内容 */}
      <div className="max-w-lg mx-auto px-4">
        {currentTab === 'today' && <TodayView />}
        {currentTab === 'habits' && <AllHabitsView />}
        {currentTab === 'stats' && <StatsView />}
        {currentTab === 'settings' && <SettingsView />}
      </div>
      
      {/* 底部导航 */}
      <nav className="fixed bottom-0 left-0 right-0 bg-[#1a1a2e] border-t border-white/10 z-50">
        <div className="max-w-lg mx-auto flex justify-around">
          {tabs.map((tab) => (
            <button
              key={tab.key}
              onClick={() => setCurrentTab(tab.key)}
              className={`flex flex-col items-center py-3 px-4 transition-all ${
                currentTab === tab.key ? 'text-[#c41e3a]' : 'text-[#a0a0b0]'
              }`}
            >
              <span className="text-xl">{tab.icon}</span>
              <span className="text-xs mt-1">{tab.label}</span>
            </button>
          ))}
        </div>
      </nav>
    </div>
  )
}
