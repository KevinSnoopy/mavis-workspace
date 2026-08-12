type Page = 'home' | 'analyzer' | 'practice' | 'history'

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

      {/* 今日实践 */}
      <div 
        onClick={() => onNavigate('practice')}
        className="bg-[#1a1a2e] rounded-2xl p-5 mb-6 cursor-pointer active:scale-[0.98] transition-transform border border-white/5"
      >
        <div className="flex items-center justify-between mb-4">
          <div className="flex items-center gap-3">
            <span className="text-2xl">⚡</span>
            <div>
              <h2 className="font-bold">今日实践</h2>
              <p className="text-[#a0a0b0] text-sm">闭环反馈循环</p>
            </div>
          </div>
          <span className="text-xs px-2 py-1 bg-[#c41e3a]/20 text-[#c41e3a] rounded-full">进行中</span>
        </div>
        
        {/* 循环示意 */}
        <div className="flex justify-between items-center py-3">
          {['做', '记', '复', '改'].map((step, i) => (
            <div key={i} className="flex items-center">
              <div className="w-10 h-10 rounded-full bg-[#c41e3a]/20 flex items-center justify-center text-sm font-medium">
                {step}
              </div>
              {i < 3 && <span className="mx-2 text-[#a0a0b0]">→</span>}
            </div>
          ))}
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
