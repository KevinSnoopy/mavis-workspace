import { useState, useEffect } from 'react'

interface Task {
  id: number
  title: string
  status: 'todo' | 'doing' | 'done' | 'review'
  createdAt: string
  completedAt?: string
  reflection?: string
}

interface PracticePageProps {
  onBack: () => void
}

function PracticePage({ onBack }: PracticePageProps) {
  const [tasks, setTasks] = useState<Task[]>([])
  const [newTask, setNewTask] = useState('')
  const [showReflection, setShowReflection] = useState<number | null>(null)
  const [reflectionText, setReflectionText] = useState('')

  // 加载本地数据
  useEffect(() => {
    const saved = localStorage.getItem('mao-dun-tasks')
    if (saved) {
      setTasks(JSON.parse(saved))
    }
  }, [])

  // 保存到本地
  useEffect(() => {
    localStorage.setItem('mao-dun-tasks', JSON.stringify(tasks))
  }, [tasks])

  const addTask = () => {
    if (!newTask.trim()) return
    const task: Task = {
      id: Date.now(),
      title: newTask,
      status: 'todo',
      createdAt: new Date().toISOString(),
    }
    setTasks([task, ...tasks])
    setNewTask('')
  }

  const updateStatus = (id: number, status: Task['status']) => {
    setTasks(tasks.map(t => 
      t.id === id ? { 
        ...t, 
        status,
        completedAt: status === 'done' ? new Date().toISOString() : undefined
      } : t
    ))
  }

  const saveReflection = (id: number) => {
    setTasks(tasks.map(t => 
      t.id === id ? { ...t, reflection: reflectionText } : t
    ))
    setShowReflection(null)
    setReflectionText('')
  }

  const deleteTask = (id: number) => {
    if (confirm('确定删除这个任务？')) {
      setTasks(tasks.filter(t => t.id !== id))
    }
  }



  const todoTasks = tasks.filter(t => t.status === 'todo')
  const doingTasks = tasks.filter(t => t.status === 'doing')
  const doneTasks = tasks.filter(t => t.status === 'done' || t.status === 'review')

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
          <h1 className="text-xl font-bold">⚡ 实践循环</h1>
          <p className="text-[#a0a0b0] text-xs">做 → 记 → 复 → 改</p>
        </div>
      </div>

      {/* 新建任务 */}
      <div className="bg-[#1a1a2e] rounded-xl p-4 mb-6 border border-white/5">
        <input
          value={newTask}
          onChange={(e) => setNewTask(e.target.value)}
          onKeyDown={(e) => e.key === 'Enter' && addTask()}
          placeholder="添加一个最小行动（30分钟内能完成的）"
          className="w-full bg-transparent text-sm focus:outline-none placeholder:text-[#a0a0b0]/50"
        />
        <button
          onClick={addTask}
          disabled={!newTask.trim()}
          className={`mt-3 w-full py-2 rounded-lg text-sm font-medium transition-all ${
            newTask.trim() ? 'bg-[#c41e3a] text-white' : 'bg-[#1a1a2e] text-[#a0a0b0]'
          }`}
        >
          添加任务
        </button>
      </div>

      {/* 循环流程 */}
      <div className="flex justify-between items-center py-3 mb-6">
        {['待做', '执行中', '已完成', '待复盘'].map((_, i) => (
          <div key={i} className="flex items-center">
            <div className={`w-12 h-12 rounded-full flex items-center justify-center text-xs font-medium ${
              i === 0 ? 'bg-[#c41e3a]/20 text-[#c41e3a] border-2 border-[#c41e3a]' : 
              i === 1 ? 'bg-[#e6b800]/20 text-[#e6b800]' :
              i === 2 ? 'bg-[#22c55e]/20 text-[#22c55e]' :
              'bg-[#a0a0b0]/20 text-[#a0a0b0]'
            }`}>
              {i + 1}
            </div>
            {i < 3 && <span className="mx-2 text-[#a0a0b0]/50">→</span>}
          </div>
        ))}
      </div>

      {/* 待做任务 */}
      {todoTasks.length > 0 && (
        <div className="mb-6">
          <h3 className="text-sm text-[#a0a0b0] mb-3">🔥 马上做</h3>
          {todoTasks.map(task => (
            <div key={task.id} className="bg-[#1a1a2e] rounded-xl p-4 mb-3 border border-white/5">
              <p className="text-sm mb-3">{task.title}</p>
              <div className="flex justify-between items-center">
                <button
                  onClick={() => updateStatus(task.id, 'doing')}
                  className="text-xs px-3 py-1 bg-[#e6b800] text-black rounded-full font-medium"
                >
                  开始做
                </button>
                <button
                  onClick={() => deleteTask(task.id)}
                  className="text-xs px-3 py-1 text-[#a0a0b0]"
                >
                  删除
                </button>
              </div>
            </div>
          ))}
        </div>
      )}

      {/* 执行中 */}
      {doingTasks.length > 0 && (
        <div className="mb-6">
          <h3 className="text-sm text-[#e6b800] mb-3">⚡ 专注执行中</h3>
          {doingTasks.map(task => (
            <div key={task.id} className="bg-[#1a1a2e] rounded-xl p-4 mb-3 border border-[#e6b800]/30">
              <p className="text-sm mb-3">{task.title}</p>
              <div className="flex gap-2">
                <button
                  onClick={() => updateStatus(task.id, 'done')}
                  className="flex-1 text-xs py-2 bg-[#22c55e] text-white rounded-lg font-medium"
                >
                  标记完成
                </button>
                <button
                  onClick={() => updateStatus(task.id, 'todo')}
                  className="text-xs px-3 py-2 text-[#a0a0b0] border border-white/10 rounded-lg"
                >
                  暂停
                </button>
              </div>
            </div>
          ))}
        </div>
      )}

      {/* 待复盘 */}
      {doneTasks.filter(t => t.status === 'done').length > 0 && (
        <div className="mb-6">
          <h3 className="text-sm text-[#22c55e] mb-3">✅ 已完成 - 待复盘</h3>
          {doneTasks.filter(t => t.status === 'done').map(task => (
            <div key={task.id} className="bg-[#1a1a2e] rounded-xl p-4 mb-3 border border-[#22c55e]/30">
              <p className="text-sm mb-3">{task.title}</p>
              {showReflection === task.id ? (
                <div>
                  <textarea
                    value={reflectionText}
                    onChange={(e) => setReflectionText(e.target.value)}
                    placeholder="复盘一下：做得好的地方？需要改进的？下次怎么做？"
                    className="w-full h-24 bg-[#0f0f1a] rounded-lg p-3 text-sm resize-none focus:outline-none focus:ring-1 focus:ring-[#c41e3a]/50"
                  />
                  <div className="flex gap-2 mt-2">
                    <button
                      onClick={() => saveReflection(task.id)}
                      className="flex-1 text-xs py-2 bg-[#c41e3a] text-white rounded-lg font-medium"
                    >
                      保存复盘
                    </button>
                    <button
                      onClick={() => { setShowReflection(null); setReflectionText('') }}
                      className="text-xs px-3 py-2 text-[#a0a0b0]"
                    >
                      取消
                    </button>
                  </div>
                </div>
              ) : (
                <button
                  onClick={() => setShowReflection(task.id)}
                  className="w-full text-xs py-2 bg-[#c41e3a]/20 text-[#c41e3a] rounded-lg font-medium"
                >
                  写复盘
                </button>
              )}
            </div>
          ))}
        </div>
      )}

      {/* 空状态 */}
      {tasks.length === 0 && (
        <div className="text-center py-12">
          <p className="text-4xl mb-4">⚡</p>
          <p className="text-[#a0a0b0] text-sm">
            还没有任务<br />
            添加一个最小行动开始实践循环吧
          </p>
        </div>
      )}
    </div>
  )
}

export default PracticePage
