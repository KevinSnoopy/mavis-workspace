import { useState } from 'react'
import { useHabitStore } from '../features/habit/store'
import { COLORS } from '../features/habit/types'

interface SuggestedHabit {
  name: string
  icon: string
  description: string
  color?: string
}

interface AnalyzerResult {
  mainConflict: string
  secondaryConflict: string
  rootCause: string
  suggestion: string
  suggestedHabits: SuggestedHabit[]
}

interface AnalyzerPageProps {
  onBack: () => void
}

function AnalyzerPage({ onBack }: AnalyzerPageProps) {
  const [input, setInput] = useState('')
  const [isAnalyzing, setIsAnalyzing] = useState(false)
  const [result, setResult] = useState<AnalyzerResult | null>(null)
  const { addHabit } = useHabitStore()
  const [addedHabits, setAddedHabits] = useState<string[]>([])
  const [analysisStep, setAnalysisStep] = useState<'input' | 'result'>('input')

  // 矛盾分析逻辑
  const analyze = (text: string): AnalyzerResult => {
    const keywords: Record<string, { patterns: string[], habits: { name: string, icon: string, description: string }[] }> = {
      career: {
        patterns: ['工作', '职业', '转行', '上班', '辞职', '创业', '加薪', '晋升'],
        habits: [
          { name: '技能学习30分钟', icon: '📚', description: '每天学习专业技能' },
          { name: '简历更新', icon: '💼', description: '每周更新简历' },
          { name: '人脉拓展', icon: '🤝', description: '每周认识一位同行' },
        ]
      },
      study: {
        patterns: ['考研', '考公', '学习', '考试', '读书', '考证', '学历'],
        habits: [
          { name: '晨间学习1小时', icon: '🌅', description: '早起专注学习' },
          { name: '单词背诵30个', icon: '📝', description: '每日词汇积累' },
          { name: '真题练习', icon: '✍️', description: '每周完成一套真题' },
        ]
      },
      health: {
        patterns: ['健康', '运动', '减肥', '睡眠', '饮食', '身体'],
        habits: [
          { name: '每日运动30分钟', icon: '🏃', description: '保持运动习惯' },
          { name: '23点前睡觉', icon: '🌙', description: '保证充足睡眠' },
          { name: '健康饮食', icon: '🥗', description: '每餐七分饱' },
        ]
      },
      finance: {
        patterns: ['钱', '存款', '投资', '负债', '省钱', '赚钱', '收入'],
        habits: [
          { name: '每日记账', icon: '📊', description: '清楚每一笔支出' },
          { name: '强制储蓄', icon: '💰', description: '每月储蓄20%' },
          { name: '理财学习', icon: '📈', description: '每天学点理财' },
        ]
      },
      relationship: {
        patterns: ['恋爱', '分手', '婚姻', '朋友', '同事', '领导', '家人'],
        habits: [
          { name: '主动问候', icon: '📱', description: '每天联系重要的人' },
          { name: '深度对话', icon: '💬', description: '每周一次深度交流' },
          { name: '表达感谢', icon: '🙏', description: '每天感谢一个人' },
        ]
      },
      productivity: {
        patterns: ['拖延', '效率', '专注', '时间', '计划'],
        habits: [
          { name: '番茄工作法', icon: '🍅', description: '每天4个番茄钟' },
          { name: '晨间规划', icon: '📋', description: '早上列计划清单' },
          { name: '晚间复盘', icon: '📓', description: '晚上总结反思' },
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
        rootCause: '你担心的不是选择本身，而是选择后能否胜任。在能力不足时，任何方向都充满风险。',
        suggestion: '先花2-3个月专注培养一项可迁移的底层能力（写作、数据分析、沟通表达），技能到手后方向自然清晰。',
        suggestedHabits: categoryData.habits,
      },
      study: {
        mainConflict: '目标与现状的差距',
        secondaryConflict: '失败恐惧',
        rootCause: '压力来自「万一失败」的假设，而非真实的困难。恐惧放大了障碍。',
        suggestion: '把大目标拆成每日可执行的小任务。今天背50个单词=考研成功，不要想"考研"两个字。',
        suggestedHabits: categoryData.habits,
      },
      health: {
        mainConflict: '理想与现实的落差',
        secondaryConflict: '意志力不足',
        rootCause: '你缺的不是意志力，而是「最小行动」。把"跑5公里"改成"穿上运动鞋出门"。',
        suggestion: '从微习惯开始：一个俯卧撑、提前一站下车、饭后站10分钟。习惯养成后再加量。',
        suggestedHabits: categoryData.habits,
      },
      finance: {
        mainConflict: '收入与支出的矛盾',
        secondaryConflict: '缺乏财务规划',
        rootCause: '钱的问题本质是选择问题——我们把钱花在了"别人觉得重要"的事上。',
        suggestion: '记账一周看钱流向。决定：①砍一项非必要支出 ②每月储蓄10%收入。',
        suggestedHabits: categoryData.habits,
      },
      relationship: {
        mainConflict: '期望与现实的落差',
        secondaryConflict: '沟通方式不当',
        rootCause: '多数人际问题是"我们没表达真实需求"，或"期待对方自动理解"。',
        suggestion: '沟通前先问自己："我最想让他理解的一件事是什么？"直接说出来。',
        suggestedHabits: categoryData.habits,
      },
      productivity: {
        mainConflict: '目标模糊导致行动瘫痪',
        secondaryConflict: '过度思考',
        rootCause: '「想太多」是追求完美方案。没有完美方案，只有"开始后才能看清"的路。',
        suggestion: '24小时规则：任何决定，24小时内必须有行动。不要等想清楚，在行动中想清楚。',
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
    
    await new Promise(resolve => setTimeout(resolve, 1500))
    
    const analysisResult = analyze(input)
    
    // 为每个习惯分配颜色
    analysisResult.suggestedHabits = analysisResult.suggestedHabits.map((h, i) => ({
      ...h,
      color: COLORS[i % COLORS.length]
    }))
    
    setResult(analysisResult)
    setIsAnalyzing(false)
    setAnalysisStep('result')
  }

  const handleAddHabit = (habit: SuggestedHabit) => {
    if (addedHabits.includes(habit.name)) return
    
    addHabit({
      name: habit.name,
      description: habit.description,
      icon: habit.icon,
      color: habit.color || COLORS[0],
      frequency: 'daily',
      targetPerDay: 1,
      archived: false,
    })
    
    setAddedHabits([...addedHabits, habit.name])
  }

  return (
    <div className="min-h-screen -mx-4 px-4 pb-24">
      {/* 头部 */}
      <div className="flex items-center gap-4 pt-4 pb-6">
        <button 
          onClick={onBack}
          className="w-10 h-10 rounded-full bg-[var(--color-bg-card)] flex items-center justify-center text-xl"
        >
          ←
        </button>
        <div>
          <h1 className="text-xl font-bold">⚖️ 矛盾分析器</h1>
          <p className="text-xs text-[var(--color-text-secondary)]">分清主次，转化行动</p>
        </div>
      </div>

      {/* 步骤指示器 */}
      <div className="flex items-center gap-2 mb-6">
        <div className={`w-8 h-8 rounded-full flex items-center justify-center text-sm font-medium ${
          analysisStep === 'result' ? 'bg-[var(--color-success)] text-white' : 'bg-[var(--color-primary)] text-white'
        }`}>
          1
        </div>
        <div className="flex-1 h-0.5 bg-[var(--color-bg-elevated)]">
          <div className={`h-full transition-all ${analysisStep === 'result' ? 'bg-[var(--color-success)]' : 'bg-[var(--color-bg-elevated)]'}`} style={{ width: analysisStep === 'result' ? '100%' : '0%' }} />
        </div>
        <div className={`w-8 h-8 rounded-full flex items-center justify-center text-sm font-medium ${
          analysisStep === 'result' ? 'bg-[var(--color-success)] text-white' : 'bg-[var(--color-bg-elevated)] text-[var(--color-text-secondary)]'
        }`}>
          2
        </div>
      </div>

      {/* 输入区域 */}
      {analysisStep === 'input' && (
        <div className="animate-fadeIn">
          <div className="card rounded-[20px] p-6 mb-6">
            <p className="text-sm text-[var(--color-text-secondary)] mb-4">
              描述你现在的困惑，系统帮你分析主要矛盾并推荐行动习惯
            </p>
            
            <textarea
              value={input}
              onChange={(e) => setInput(e.target.value)}
              placeholder="比如：想转行做程序员，但不知道学什么，怕学完还是找不到工作..."
              className="w-full h-36 bg-[var(--color-bg-elevated)] rounded-2xl p-4 text-sm resize-none focus:outline-none placeholder:text-[var(--color-text-secondary)]/50"
            />
            
            <button
              onClick={handleAnalyze}
              disabled={!input.trim() || isAnalyzing}
              className={`w-full mt-4 py-4 rounded-2xl font-semibold text-lg transition-all ${
                input.trim() && !isAnalyzing
                  ? 'btn-primary'
                  : 'bg-[var(--color-bg-elevated)] text-[var(--color-text-secondary)]'
              }`}
            >
              {isAnalyzing ? (
                <span className="flex items-center justify-center gap-3">
                  <span className="animate-spin">⚙️</span>
                  <span>分析中...</span>
                </span>
              ) : '开始分析'}
            </button>
          </div>
          
          {/* 示例问题 */}
          <div className="card rounded-[20px] p-5">
            <p className="text-xs text-[var(--color-text-secondary)] mb-3">试试这些</p>
            <div className="space-y-2">
              {[
                '想减肥但总是坚持不下去',
                '想转行但不知道该学什么',
                '每天都很忙但没什么进步'
              ].map((q, i) => (
                <button
                  key={i}
                  onClick={() => setInput(q)}
                  className="w-full text-left p-3 rounded-xl bg-[var(--color-bg-elevated)] text-sm hover:bg-[var(--color-primary)]/10 transition-colors"
                >
                  {q}
                </button>
              ))}
            </div>
          </div>
        </div>
      )}

      {/* 分析结果 */}
      {analysisStep === 'result' && result && (
        <div className="animate-slideUp space-y-4">
          {/* 主要矛盾 */}
          <div className="card rounded-[20px] p-6 border-l-4 border-l-[var(--color-primary)]">
            <div className="flex items-center gap-2 mb-3">
              <span className="text-xl">🔥</span>
              <span className="text-sm font-medium text-[var(--color-primary)]">主要矛盾</span>
            </div>
            <p className="text-xl font-bold mb-3">{result.mainConflict}</p>
            <p className="text-sm text-[var(--color-text-secondary)] leading-relaxed">
              {result.rootCause}
            </p>
          </div>

          {/* 次要矛盾 */}
          <div className="card rounded-[20px] p-6 border-l-4 border-l-[var(--color-accent)]">
            <div className="flex items-center gap-2 mb-3">
              <span className="text-xl">💡</span>
              <span className="text-sm font-medium text-[var(--color-accent)]">次要矛盾</span>
            </div>
            <p className="text-lg font-bold mb-2">{result.secondaryConflict}</p>
            <p className="text-sm text-[var(--color-text-secondary)]">
              它会分散注意力，让你忽视真正重要的事
            </p>
          </div>

          {/* 行动建议 */}
          <div className="card rounded-[20px] p-6 bg-gradient-to-br from-[var(--color-primary)]/10 to-transparent">
            <div className="flex items-center gap-2 mb-3">
              <span className="text-xl">⚡</span>
              <span className="text-sm font-medium">行动建议</span>
            </div>
            <p className="text-sm leading-relaxed">{result.suggestion}</p>
          </div>

          {/* 推荐的行动习惯 */}
          <div className="card rounded-[20px] p-6">
            <div className="flex items-center justify-between mb-4">
              <div className="flex items-center gap-2">
                <span className="text-xl">🎯</span>
                <span className="font-semibold">转化为习惯</span>
              </div>
              <span className="text-xs text-[var(--color-text-secondary)]">
                点击添加 · {addedHabits.length}/{result.suggestedHabits.length}
              </span>
            </div>
            
            <div className="space-y-3">
              {result.suggestedHabits.map((habit, i) => {
                const isAdded = addedHabits.includes(habit.name)
                
                return (
                  <div
                    key={i}
                    onClick={() => !isAdded && handleAddHabit(habit)}
                    className={`p-4 rounded-2xl transition-all cursor-pointer ${
                      isAdded 
                        ? 'bg-[var(--color-success)]/10 border border-[var(--color-success)]/30' 
                        : 'bg-[var(--color-bg-elevated)] hover:bg-[var(--color-primary)]/10'
                    }`}
                  >
                    <div className="flex items-start gap-3">
                      <div 
                        className="w-12 h-12 rounded-2xl flex items-center justify-center text-2xl shrink-0"
                        style={{ backgroundColor: habit.color + '20' }}
                      >
                        {habit.icon}
                      </div>
                      
                      <div className="flex-1 min-w-0">
                        <div className="flex items-center justify-between">
                          <h4 className="font-medium">{habit.name}</h4>
                          {isAdded ? (
                            <span className="text-xs text-[var(--color-success)] bg-[var(--color-success)]/20 px-2 py-1 rounded-full">
                              已添加 ✓
                            </span>
                          ) : (
                            <span className="text-[var(--color-primary)]">+ 添加</span>
                          )}
                        </div>
                        <p className="text-xs text-[var(--color-text-secondary)] mt-1">
                          {habit.description}
                        </p>
                      </div>
                    </div>
                  </div>
                )
              })}
            </div>
            
            {addedHabits.length > 0 && (
              <button
                onClick={onBack}
                className="w-full btn-primary mt-6"
              >
                去打卡 →
              </button>
            )}
          </div>

          {/* 重新分析按钮 */}
          <button
            onClick={() => {
              setAnalysisStep('input')
              setResult(null)
              setInput('')
              setAddedHabits([])
            }}
            className="w-full py-3 text-[var(--color-text-secondary)] text-sm"
          >
            ← 重新分析
          </button>
        </div>
      )}

      {/* 底部引言 */}
      <div className="mt-8 text-center">
        <p className="text-[var(--color-text-secondary)] text-sm italic">
          "抓住了主要矛盾，一切问题就迎刃而解了。"
        </p>
        <p className="text-[var(--color-primary)] text-xs mt-2">—— 《矛盾论》</p>
      </div>
    </div>
  )
}

export default AnalyzerPage
