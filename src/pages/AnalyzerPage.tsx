import { useState } from 'react'
import { useHabitStore } from '../features/habit/store'
import { COLORS } from '../features/habit/types'

interface AnalyzerResult {
  mainConflict: string
  secondaryConflict: string
  rootCause: string
  suggestion: string
  suggestedHabits: { name: string; icon: string; description: string }[]
}

interface AnalyzerPageProps {
  onBack: () => void
}

function AnalyzerPage({ onBack }: AnalyzerPageProps) {
  const [input, setInput] = useState('')
  const [isAnalyzing, setIsAnalyzing] = useState(false)
  const [result, setResult] = useState<AnalyzerResult | null>(null)
  const { addHabit, addAnalysisInsight } = useHabitStore()
  const [addedHabits, setAddedHabits] = useState<string[]>([])

  // 简化的矛盾分析逻辑
  const analyze = (text: string): AnalyzerResult => {
    const keywords: Record<string, { patterns: string[], habits: { name: string, icon: string, description: string }[] }> = {
      career: {
        patterns: ['工作', '职业', '转行', '上班', '辞职', '创业', '加薪', '晋升'],
        habits: [
          { name: '每日技能学习', icon: '📚', description: '每天学习30分钟专业知识' },
          { name: '求职准备', icon: '💼', description: '每周投递5份简历' },
          { name: '网络社交', icon: '🤝', description: '每周认识一位新朋友' },
        ]
      },
      study: {
        patterns: ['考研', '考公', '学习', '考试', '读书', '考证', '学历'],
        habits: [
          { name: '晨间学习', icon: '🌅', description: '每天早起学习1小时' },
          { name: '单词背诵', icon: '📝', description: '每天背30个单词' },
          { name: '真题练习', icon: '✍️', description: '每周完成一套真题' },
        ]
      },
      health: {
        patterns: ['健康', '运动', '减肥', '睡眠', '饮食', '身体'],
        habits: [
          { name: '每日运动', icon: '🏃', description: '每天运动30分钟' },
          { name: '早睡早起', icon: '🌙', description: '23点前睡觉' },
          { name: '健康饮食', icon: '🥗', description: '每餐七分饱' },
        ]
      },
      finance: {
        patterns: ['钱', '存款', '投资', '负债', '省钱', '赚钱', '收入'],
        habits: [
          { name: '每日记账', icon: '📊', description: '记录每一笔支出' },
          { name: '储蓄计划', icon: '💰', description: '每月储蓄20%收入' },
          { name: '理财学习', icon: '📈', description: '每天学习理财知识' },
        ]
      },
      relationship: {
        patterns: ['恋爱', '分手', '婚姻', '朋友', '同事', '领导', '家人'],
        habits: [
          { name: '主动联系', icon: '📱', description: '每天给重要的人发消息' },
          { name: '深度交流', icon: '💬', description: '每周进行一次深度对话' },
          { name: '表达感谢', icon: '🙏', description: '每天感谢一个人' },
        ]
      },
      productivity: {
        patterns: ['拖延', '效率', '专注', '时间', '计划'],
        habits: [
          { name: '番茄工作', icon: '🍅', description: '每天4个番茄钟' },
          { name: '晨间规划', icon: '📋', description: '每天早上列计划' },
          { name: '日志复盘', icon: '📓', description: '每天晚上写复盘' },
        ]
      }
    }

    let matchedCategory = 'productivity'
    let maxMatch = 0
    
    for (const [key, data] of Object.entries(keywords)) {
      const matchCount = data.patterns.filter(p => text.includes(p)).length
      if (matchCount > maxMatch) {
        maxMatch = matchCount
        matchedCategory = key
      }
    }

    const categoryData = keywords[matchedCategory]

    const analysisMap: Record<string, AnalyzerResult> = {
      career: {
        mainConflict: '核心竞争力不足',
        secondaryConflict: '方向选择的焦虑',
        rootCause: '你担心的不只是选择本身，而是选择之后能否胜任。在能力不足时，任何方向看起来都充满风险。',
        suggestion: '建议先花2-3个月专注培养一项可迁移的底层能力（如写作、数据分析、沟通表达），而非急着做方向选择。技能到手后，方向自然清晰。',
        suggestedHabits: categoryData.habits,
      },
      study: {
        mainConflict: '目标与现状的差距',
        secondaryConflict: '失败恐惧',
        rootCause: '考研/考公的压力来自「万一失败」的假设，而非真实的困难。恐惧放大了障碍。',
        suggestion: '把大目标拆成每日可执行的小任务。比如：不是「考研」，而是「今天背50个单词，做一套选择题」。完成感会逐步替代恐惧。',
        suggestedHabits: categoryData.habits,
      },
      health: {
        mainConflict: '理想体型/健康状态 vs 现状',
        secondaryConflict: '意志力不足',
        rootCause: '你缺的往往不是意志力，而是「最小行动」。把「每天跑步5公里」改成「每天穿上运动鞋出门」，成功率翻倍。',
        suggestion: '从微习惯开始：每天一个俯卧撑、提前一站下车走路、饭后站立10分钟。习惯养成后，再逐步加量。',
        suggestedHabits: categoryData.habits,
      },
      finance: {
        mainConflict: '收入与支出的矛盾',
        secondaryConflict: '缺乏财务规划',
        rootCause: '钱的问题本质是选择问题——我们把钱花在了「别人觉得重要」的事上，而忽略了自己真正重视的东西。',
        suggestion: '从今天开始记账一周，你会发现钱流向的真相。然后做两个决定：①砍掉一项非必要支出 ②每月强制储蓄收入的10%。',
        suggestedHabits: categoryData.habits,
      },
      relationship: {
        mainConflict: '期望与现实的落差',
        secondaryConflict: '沟通方式不当',
        rootCause: '多数人际问题不是「对方不好」，而是「我们没有表达真实需求」或「期待对方自动理解」。',
        suggestion: '下次沟通前，先问自己：「我最想让他/她理解的一件事是什么？」直接说出来，比猜测和试探更有效。',
        suggestedHabits: categoryData.habits,
      },
      productivity: {
        mainConflict: '目标模糊导致行动瘫痪',
        secondaryConflict: '过度思考',
        rootCause: '「想太多」的本质是追求完美方案。但现实是：没有完美方案，只有「开始之后才能看清」的路。',
        suggestion: '给自己定一个24小时规则：任何决定，在24小时内必须有一个行动。不要等想清楚了再行动，而是在行动中想清楚。',
        suggestedHabits: categoryData.habits,
      },
    }

    return analysisMap[matchedCategory]
  }

  const handleAnalyze = async () => {
    if (!input.trim()) return
    
    setIsAnalyzing(true)
    setResult(null)
    setAddedHabits([])
    
    // 模拟AI分析延迟
    await new Promise(resolve => setTimeout(resolve, 1500))
    
    const analysisResult = analyze(input)
    setResult(analysisResult)
    setIsAnalyzing(false)
  }

  const handleAddHabit = (habit: { name: string; icon: string; description: string }) => {
    if (addedHabits.includes(habit.name)) return
    
    addHabit({
      name: habit.name,
      description: habit.description,
      icon: habit.icon,
      color: COLORS[Math.floor(Math.random() * COLORS.length)],
      frequency: 'daily',
      targetPerDay: 1,
      archived: false,
    })
    
    // 保存分析洞察
    if (result) {
      addAnalysisInsight({
        mainConflict: result.mainConflict,
        suggestedHabits: [...addedHabits, habit.name],
      })
    }
    
    setAddedHabits([...addedHabits, habit.name])
  }

  return (
    <div className="animate-fadeIn">
      {/* 头部 */}
      <div className="flex items-center gap-4 mb-6">
        <button 
          onClick={onBack}
          className="text-2xl text-[#a0a0b0] active:scale-90 transition-transform"
        >
          ←
        </button>
        <div>
          <h1 className="text-xl font-bold">⚖️ 矛盾分析器</h1>
          <p className="text-[#a0a0b0] text-xs">找到主要矛盾，转化为行动习惯</p>
        </div>
      </div>

      {/* 输入区域 */}
      <div className="mb-6">
        <textarea
          value={input}
          onChange={(e) => setInput(e.target.value)}
          placeholder="描述你现在的困惑或问题...\n\n比如：想转行但不知道转什么，怕转错了浪费时间..."
          className="w-full h-32 bg-[#1a1a2e] rounded-xl p-4 text-sm resize-none focus:outline-none focus:ring-2 focus:ring-[#c41e3a]/50 border border-white/5"
        />
        <button
          onClick={handleAnalyze}
          disabled={!input.trim() || isAnalyzing}
          className={`w-full mt-3 py-3 rounded-xl font-medium transition-all ${
            input.trim() && !isAnalyzing
              ? 'bg-[#c41e3a] text-white active:scale-[0.98]'
              : 'bg-[#1a1a2e] text-[#a0a0b0]'
          }`}
        >
          {isAnalyzing ? (
            <span className="flex items-center justify-center gap-2">
              <span className="animate-pulse">分析中</span>
              <span className="flex gap-1">
                <span className="w-1.5 h-1.5 bg-white rounded-full animate-bounce" style={{animationDelay: '0ms'}} />
                <span className="w-1.5 h-1.5 bg-white rounded-full animate-bounce" style={{animationDelay: '150ms'}} />
                <span className="w-1.5 h-1.5 bg-white rounded-full animate-bounce" style={{animationDelay: '300ms'}} />
              </span>
            </span>
          ) : '开始分析'}
        </button>
      </div>

      {/* 分析结果 */}
      {result && (
        <div className="space-y-4">
          {/* 主要矛盾 */}
          <div className="bg-[#1a1a2e] rounded-xl p-5 border-l-4 border-[#c41e3a]">
            <div className="flex items-center gap-2 mb-3">
              <span className="text-lg">🔥</span>
              <span className="text-[#c41e3a] text-sm font-medium">主要矛盾</span>
            </div>
            <p className="text-lg font-bold mb-2">{result.mainConflict}</p>
            <p className="text-[#a0a0b0] text-sm leading-relaxed">{result.rootCause}</p>
          </div>

          {/* 次要矛盾 */}
          <div className="bg-[#1a1a2e] rounded-xl p-5 border-l-4 border-[#e6b800]">
            <div className="flex items-center gap-2 mb-3">
              <span className="text-lg">💡</span>
              <span className="text-[#e6b800] text-sm font-medium">次要矛盾</span>
            </div>
            <p className="text-lg font-bold mb-2">{result.secondaryConflict}</p>
            <p className="text-[#a0a0b0] text-sm">
              它会分散你的注意力，让你忽视真正重要的事。
            </p>
          </div>

          {/* 行动建议 */}
          <div className="bg-gradient-to-br from-[#c41e3a]/20 to-[#1a1a2e] rounded-xl p-5 border border-[#c41e3a]/30">
            <div className="flex items-center gap-2 mb-3">
              <span className="text-lg">⚡</span>
              <span className="text-white text-sm font-medium">行动建议</span>
            </div>
            <p className="text-sm leading-relaxed">{result.suggestion}</p>
          </div>

          {/* 推荐的行动习惯 */}
          <div className="bg-[#1a1a2e] rounded-xl p-5 border border-white/5">
            <div className="flex items-center gap-2 mb-4">
              <span className="text-lg">🎯</span>
              <span className="text-white text-sm font-medium">基于分析推荐的习惯</span>
            </div>
            <p className="text-xs text-[#a0a0b0] mb-4">
              点击添加，将矛盾转化为具体的每日行动
            </p>
            
            <div className="space-y-3">
              {result.suggestedHabits.map((habit, i) => (
                <div 
                  key={i}
                  className={`bg-[#0f0f1a] rounded-xl p-4 border transition-all ${
                    addedHabits.includes(habit.name) 
                      ? 'border-[#22c55e]/30' 
                      : 'border-white/5 cursor-pointer hover:border-[#c41e3a]/30'
                  }`}
                  onClick={() => !addedHabits.includes(habit.name) && handleAddHabit(habit)}
                >
                  <div className="flex items-start gap-3">
                    <span className="text-2xl">{habit.icon}</span>
                    <div className="flex-1">
                      <div className="flex items-center justify-between">
                        <h4 className="font-medium text-sm">{habit.name}</h4>
                        {addedHabits.includes(habit.name) ? (
                          <span className="text-xs text-[#22c55e] bg-[#22c55e]/20 px-2 py-0.5 rounded-full">
                            已添加 ✓
                          </span>
                        ) : (
                          <span className="text-xs text-[#c41e3a]">点击添加</span>
                        )}
                      </div>
                      <p className="text-xs text-[#a0a0b0] mt-1">{habit.description}</p>
                    </div>
                  </div>
                </div>
              ))}
            </div>
          </div>
        </div>
      )}

      {/* 底部提示 */}
      <div className="mt-8 text-center">
        <p className="text-[#a0a0b0] text-xs">
          "抓住了主要矛盾，一切问题就迎刃而解了。"
        </p>
        <p className="text-[#c41e3a] text-xs mt-1">—— 《矛盾论》</p>
      </div>
    </div>
  )
}

export default AnalyzerPage
