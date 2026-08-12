import { useState, useEffect } from 'react'

interface AnalysisRecord {
  id: number
  type: 'analysis'
  content: string
  result: {
    mainConflict: string
    secondaryConflict: string
    suggestion: string
  }
  createdAt: string
}

interface TaskRecord {
  id: number
  type: 'task'
  title: string
  status: string
  reflection?: string
  completedAt?: string
  createdAt: string
}

type RecordItem = AnalysisRecord | TaskRecord

interface HistoryPageProps {
  onBack: () => void
}

function HistoryPage({ onBack }: HistoryPageProps) {
  const [records, setRecords] = useState<RecordItem[]>([])
  const [filter, setFilter] = useState<'all' | 'analysis' | 'task'>('all')

  useEffect(() => {
    // 加载矛盾分析记录
    const analysisRecords = JSON.parse(localStorage.getItem('mao-dun-records') || '[]')
    
    // 加载任务记录
    const tasks = JSON.parse(localStorage.getItem('mao-dun-tasks') || '[]')
    const taskRecords: TaskRecord[] = tasks.map((t: any) => ({
      ...t,
      type: 'task' as const,
    }))

    // 合并并按时间排序
    const allRecords = [...analysisRecords, ...taskRecords].sort(
      (a, b) => new Date(b.createdAt).getTime() - new Date(a.createdAt).getTime()
    )

    setRecords(allRecords)
  }, [])

  const filteredRecords = filter === 'all' 
    ? records 
    : records.filter(r => r.type === filter)

  const formatDate = (dateStr: string) => {
    const date = new Date(dateStr)
    const now = new Date()
    const diff = now.getTime() - date.getTime()
    const days = Math.floor(diff / (1000 * 60 * 60 * 24))
    
    if (days === 0) return '今天'
    if (days === 1) return '昨天'
    if (days < 7) return `${days}天前`
    return date.toLocaleDateString('zh-CN', { month: 'numeric', day: 'numeric' })
  }

  return (
    <div className="animate-fadeIn">
      {/* 头部 */}
      <div className="flex items-center gap-4 mb-6">
        <button 
          onClick={onBack}
          className="text-2xl active:scale-90 transition-transform"
        >
          ←
        </button>
        <div>
          <h1 className="text-xl font-bold">📝 成长记录</h1>
          <p className="text-[#a0a0b0] text-xs">你的实践轨迹</p>
        </div>
      </div>

      {/* 筛选 */}
      <div className="flex gap-2 mb-6">
        {[
          { key: 'all', label: '全部' },
          { key: 'analysis', label: '矛盾分析' },
          { key: 'task', label: '实践任务' },
        ].map(item => (
          <button
            key={item.key}
            onClick={() => setFilter(item.key as typeof filter)}
            className={`px-4 py-2 rounded-full text-sm transition-all ${
              filter === item.key 
                ? 'bg-[#c41e3a] text-white' 
                : 'bg-[#1a1a2e] text-[#a0a0b0]'
            }`}
          >
            {item.label}
          </button>
        ))}
      </div>

      {/* 统计卡片 */}
      <div className="grid grid-cols-2 gap-3 mb-6">
        <div className="bg-[#1a1a2e] rounded-xl p-4 text-center border border-white/5">
          <p className="text-2xl font-bold text-[#c41e3a]">
            {records.filter(r => r.type === 'analysis').length}
          </p>
          <p className="text-xs text-[#a0a0b0]">矛盾分析</p>
        </div>
        <div className="bg-[#1a1a2e] rounded-xl p-4 text-center border border-white/5">
          <p className="text-2xl font-bold text-[#22c55e]">
            {records.filter(r => r.type === 'task').length}
          </p>
          <p className="text-xs text-[#a0a0b0]">实践任务</p>
        </div>
      </div>

      {/* 记录列表 */}
      <div className="space-y-4">
        {filteredRecords.map(record => (
          <div key={record.id} className="bg-[#1a1a2e] rounded-xl p-4 border border-white/5">
            <div className="flex items-center justify-between mb-3">
              <div className="flex items-center gap-2">
                <span className="text-lg">
                  {record.type === 'analysis' ? '⚖️' : '⚡'}
                </span>
                <span className={`text-xs px-2 py-0.5 rounded-full ${
                  record.type === 'analysis' 
                    ? 'bg-[#c41e3a]/20 text-[#c41e3a]' 
                    : 'bg-[#22c55e]/20 text-[#22c55e]'
                }`}>
                  {record.type === 'analysis' ? '矛盾分析' : '实践任务'}
                </span>
              </div>
              <span className="text-xs text-[#a0a0b0]">{formatDate(record.createdAt)}</span>
            </div>

            {record.type === 'analysis' ? (
              <>
                <p className="text-sm text-[#a0a0b0] mb-2 line-clamp-2">
                  "{record.content}"
                </p>
                <div className="space-y-2">
                  <div className="flex items-start gap-2">
                    <span className="text-xs text-[#c41e3a]">主要:</span>
                    <span className="text-sm">{(record as AnalysisRecord).result.mainConflict}</span>
                  </div>
                  <div className="flex items-start gap-2">
                    <span className="text-xs text-[#e6b800]">建议:</span>
                    <span className="text-sm text-[#a0a0b0] line-clamp-2">
                      {(record as AnalysisRecord).result.suggestion}
                    </span>
                  </div>
                </div>
              </>
            ) : (
              <>
                <p className="text-sm mb-2">{(record as TaskRecord).title}</p>
                {(record as TaskRecord).reflection && (
                  <div className="bg-[#0f0f1a] rounded-lg p-3 mt-2">
                    <p className="text-xs text-[#a0a0b0] mb-1">复盘:</p>
                    <p className="text-sm">{(record as TaskRecord).reflection}</p>
                  </div>
                )}
              </>
            )}
          </div>
        ))}
      </div>

      {/* 空状态 */}
      {filteredRecords.length === 0 && (
        <div className="text-center py-12">
          <p className="text-4xl mb-4">📝</p>
          <p className="text-[#a0a0b0] text-sm">
            {filter === 'all' 
              ? '还没有记录，开始你的第一次实践吧' 
              : filter === 'analysis' 
              ? '还没有矛盾分析记录'
              : '还没有实践任务记录'}
          </p>
        </div>
      )}

      {/* 底部引言 */}
      <div className="mt-8 text-center">
        <p className="text-[#a0a0b0] text-xs">
          "认识从实践始，经过实践得到了理论的认识，<br />
          还须再回到实践去。"
        </p>
        <p className="text-[#c41e3a] text-xs mt-2">—— 《实践论》</p>
      </div>
    </div>
  )
}

export default HistoryPage
