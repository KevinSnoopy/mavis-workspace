type Page = 'home' | 'analyzer' | 'habitflow' | 'history'

interface HomePageProps {
  onNavigate: (page: Page) => void
}

function HomePage({ onNavigate }: HomePageProps) {
  const today = new Date().toLocaleDateString('zh-CN', { month: 'long', day: 'numeric', weekday: 'long' })

  return (
    <div className="animate-fadeIn">
      {/* 顶部问候 */}
      <div className="mb-8">
        <p className="text-[#a0a0b0] text-sm">{today}</p>
        <h1 className="text-2xl font-bold mt-1">斗争中成长 💪</h1>
      </div>

      {/* 核心矛盾卡片 */}
      <div 
        onClick={() => onNavigate('analyzer')}
        className="bg-gradient-to-br from-[#c41e3a] to-[#8b0000] rounded-2xl p-6 mb-6 cursor-pointer active:scale-95 transition-transform"
      >
        <div className="flex items-center gap-3 mb-3">
          <span className="text-3xl">⚖️</span>
          <div>
            <h2 className="text-lg font-bold">矛盾分析器</h2>
            <p className="text-white/80 text-sm">找到问题的核心</p>
          </div>
        </div>
        <p className="text-white/90 text-sm">
          输入你的困惑，系统帮你分清主次矛盾，找到解决问题的关键突破口。
        </p>
        <div className="mt-4 flex items-center text-white/80 text-sm">
          <span>开始分析</span>
          <span className="ml-1">→</span>
        </div>
      </div>

      {/* HabitFlow 习惯追踪卡片 */}
      <div 
        onClick={() => onNavigate('habitflow')}
        className="bg-[#1a1a2e] rounded-2xl p-5 mb-6 cursor-pointer active:scale-[0.98] transition-transform border border-white/5"
      >
        <div className="flex items-center justify-between mb-4">
          <div className="flex items-center gap-3">
            <span className="text-2xl">⚡</span>
            <div>
              <h2 className="font-bold">习惯追踪</h2>
              <p className="text-[#a0a0b0] text-sm">建立并坚持好习惯</p>
            </div>
          </div>
          <span className="text-xs px-2 py-1 bg-[#22c55e]/20 text-[#22c55e] rounded-full">新功能</span>
        </div>
        
        {/* 功能预览 */}
        <div className="grid grid-cols-2 gap-3">
          <div className="bg-[#0f0f1a] rounded-lg p-3">
            <p className="text-lg mb-1">🎯</p>
            <p className="text-xs text-[#a0a0b0]">创建习惯</p>
          </div>
          <div className="bg-[#0f0f1a] rounded-lg p-3">
            <p className="text-lg mb-1">✅</p>
            <p className="text-xs text-[#a0a0b0]">每日打卡</p>
          </div>
          <div className="bg-[#0f0f1a] rounded-lg p-3">
            <p className="text-lg mb-1">📊</p>
            <p className="text-xs text-[#a0a0b0]">进度统计</p>
          </div>
          <div className="bg-[#0f0f1a] rounded-lg p-3">
            <p className="text-lg mb-1">🔥</p>
            <p className="text-xs text-[#a0a0b0]">连续提醒</p>
          </div>
        </div>
      </div>

      {/* 底部引言 */}
      <div className="bg-[#1a1a2e] rounded-xl p-4 border-l-4 border-[#c41e3a]">
        <p className="text-[#a0a0b0] text-sm italic">
          "实践、认识、再实践、再认识，这种形式，循环往复以至无穷……"
        </p>
        <p className="text-[#c41e3a] text-xs mt-2">—— 《实践论》</p>
      </div>
    </div>
  )
}

export default HomePage
