import { useHabitStore } from '../features/habit/store'

type Page = 'home' | 'analyzer' | 'habitflow' | 'history'

interface HomePageProps {
  onNavigate: (page: Page) => void
}

function HomePage({ onNavigate }: HomePageProps) {
  const today = new Date().toLocaleDateString('zh-CN', { month: 'long', day: 'numeric', weekday: 'long' })
  
  const { habits, checkIns, getTodayHabits, getCheckIn, analysisInsights } = useHabitStore()
  const todayHabits = getTodayHabits()
  
  const todayDate = new Date().toISOString().split('T')[0]
  const completedToday = todayHabits.filter((h) => {
    const check = getCheckIn(h.id, todayDate)
    return check && check.count >= h.targetPerDay
  }).length
  
  // 根据时间显示不同的励志语
  const quotes = [
    { condition: () => true, quote: '实践、认识、再实践、再认识，循环往复以至无穷……', source: '《实践论》' },
    { condition: () => completedToday === todayHabits.length && todayHabits.length > 0, quote: '星星之火，可以燎原。今天的任务已全部完成！', source: '🎉' },
    { condition: () => completedToday > 0, quote: '完成的每一件事，都是在垒砌成功的阶梯。', source: '继续加油' },
    { condition: () => true, quote: '最后的胜利，往往在于再坚持一下的努力之中。', source: '《论持久战》' },
  ]
  
  const motivationalQuote = quotes.find(q => q.condition())?.quote || quotes[0].quote
  const motivationalSource = quotes.find(q => q.condition())?.source || quotes[0].source

  return (
    <div className="animate-fadeIn">
      {/* 顶部问候 */}
      <div className="mb-6">
        <p className="text-[#a0a0b0] text-sm">{today}</p>
        <h1 className="text-2xl font-bold mt-1">斗争中成长 💪</h1>
      </div>

      {/* 今日概览卡片 */}
      <div 
        onClick={() => onNavigate('habitflow')}
        className="bg-gradient-to-br from-[#c41e3a] to-[#8b0000] rounded-2xl p-5 mb-6 cursor-pointer active:scale-[0.98] transition-transform"
      >
        <div className="flex items-center justify-between mb-3">
          <div className="flex items-center gap-3">
            <span className="text-3xl">⚡</span>
            <div>
              <h2 className="text-lg font-bold">今日行动</h2>
              <p className="text-white/80 text-sm">矛盾分析 → 习惯养成 → 螺旋上升</p>
            </div>
          </div>
          <div className="text-center">
            <p className="text-4xl font-bold">{completedToday}/{todayHabits.length}</p>
            <p className="text-white/80 text-xs">已完成</p>
          </div>
        </div>
        
        {/* 进度条 */}
        {todayHabits.length > 0 && (
          <div className="mt-3">
            <div className="h-2 bg-white/20 rounded-full overflow-hidden">
              <div 
                className="h-full bg-white rounded-full transition-all duration-500"
                style={{ width: `${(completedToday / todayHabits.length) * 100}%` }}
              />
            </div>
          </div>
        )}
        
        {/* 今日习惯预览 */}
        {todayHabits.length > 0 && (
          <div className="flex gap-2 mt-4 overflow-x-auto pb-1">
            {todayHabits.slice(0, 5).map((habit) => {
              const check = getCheckIn(habit.id, todayDate)
              const isDone = check && check.count >= habit.targetPerDay
              return (
                <div 
                  key={habit.id}
                  className={`shrink-0 px-3 py-1.5 rounded-full text-xs flex items-center gap-1 ${
                    isDone ? 'bg-white/30' : 'bg-black/20'
                  }`}
                >
                  <span>{habit.icon}</span>
                  <span className={isDone ? 'line-through opacity-80' : ''}>{habit.name}</span>
                </div>
              )
            })}
            {todayHabits.length > 5 && (
              <div className="shrink-0 px-3 py-1.5 rounded-full text-xs bg-black/20">
                +{todayHabits.length - 5} 更多
              </div>
            )}
          </div>
        )}
      </div>

      {/* 矛盾分析器入口 */}
      <div 
        onClick={() => onNavigate('analyzer')}
        className="bg-[#1a1a2e] rounded-2xl p-5 mb-6 cursor-pointer active:scale-[0.98] transition-transform border border-white/5"
      >
        <div className="flex items-center gap-3">
          <span className="text-3xl">⚖️</span>
          <div className="flex-1">
            <h2 className="font-bold">矛盾分析器</h2>
            <p className="text-[#a0a0b0] text-sm">分清主次矛盾，找到核心突破口</p>
          </div>
          <span className="text-[#c41e3a]">→</span>
        </div>
        
        {/* 最近分析预览 */}
        {analysisInsights.length > 0 && (
          <div className="mt-4 pt-4 border-t border-white/10">
            <p className="text-xs text-[#a0a0b0] mb-2">最近分析</p>
            <p className="text-sm truncate">
              {analysisInsights[0].mainConflict}
              <span className="text-[#c41e3a] ml-2">
                → {analysisInsights[0].suggestedHabits.length}个行动建议
              </span>
            </p>
          </div>
        )}
      </div>

      {/* 快速操作网格 */}
      <div className="grid grid-cols-2 gap-3 mb-6">
        <div 
          onClick={() => onNavigate('habitflow')}
          className="bg-[#1a1a2e] rounded-2xl p-4 cursor-pointer active:scale-[0.98] transition-transform border border-white/5"
        >
          <p className="text-2xl mb-2">✅</p>
          <p className="font-medium text-sm">打卡习惯</p>
          <p className="text-xs text-[#a0a0b0] mt-1">{todayHabits.length}个待完成</p>
        </div>
        
        <div 
          onClick={() => onNavigate('history')}
          className="bg-[#1a1a2e] rounded-2xl p-4 cursor-pointer active:scale-[0.98] transition-transform border border-white/5"
        >
          <p className="text-2xl mb-2">📊</p>
          <p className="font-medium text-sm">查看记录</p>
          <p className="text-xs text-[#a0a0b0] mt-1">{checkIns.length}次累计打卡</p>
        </div>
      </div>

      {/* 核心理念 */}
      <div className="bg-[#1a1a2e] rounded-xl p-4 border-l-4 border-[#c41e3a]">
        <p className="text-[#a0a0b0] text-sm italic">
          "{motivationalQuote}"
        </p>
        <p className="text-[#c41e3a] text-xs mt-2">—— {motivationalSource}</p>
      </div>

      {/* 底部数据统计 */}
      {habits.length > 0 && (
        <div className="mt-6 grid grid-cols-3 gap-3">
          <div className="bg-[#1a1a2e] rounded-xl p-3 text-center border border-white/5">
            <p className="text-xl font-bold text-[#c41e3a]">{habits.filter(h => !h.archived).length}</p>
            <p className="text-xs text-[#a0a0b0]">进行中的习惯</p>
          </div>
          <div className="bg-[#1a1a2e] rounded-xl p-3 text-center border border-white/5">
            <p className="text-xl font-bold text-[#22c55e]">{checkIns.length}</p>
            <p className="text-xs text-[#a0a0b0]">累计打卡</p>
          </div>
          <div className="bg-[#1a1a2e] rounded-xl p-3 text-center border border-white/5">
            <p className="text-xl font-bold text-[#e6b800]">{analysisInsights.length}</p>
            <p className="text-xs text-[#a0a0b0]">矛盾分析</p>
          </div>
        </div>
      )}
    </div>
  )
}

export default HomePage
