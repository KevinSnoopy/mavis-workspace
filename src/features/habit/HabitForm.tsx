import { useState } from 'react'
import type { Habit, Frequency, WeekDay } from './types'
import { ICONS, COLORS, HABIT_TEMPLATES } from './types'
import { useHabitStore } from './store'

interface HabitFormProps {
  habit?: Habit
  onClose: () => void
}

const WEEKDAYS = ['日', '一', '二', '三', '四', '五', '六']

export function HabitForm({ habit, onClose }: HabitFormProps) {
  const { addHabit, updateHabit, deleteHabit, archiveHabit } = useHabitStore()
  const isEdit = habit !== undefined
  
  const [name, setName] = useState(habit?.name || '')
  const [description, setDescription] = useState(habit?.description || '')
  const [icon, setIcon] = useState(habit?.icon || '🎯')
  const [color, setColor] = useState(habit?.color || COLORS[0])
  const [frequency, setFrequency] = useState<Frequency>(habit?.frequency || 'daily')
  const [weekDays, setWeekDays] = useState<WeekDay[]>(habit?.weekDays || [1, 2, 3, 4, 5])
  const [monthDays] = useState<number[]>(habit?.monthDays || [1])
  const [targetPerDay, setTargetPerDay] = useState(habit?.targetPerDay || 1)
  const [reminderTime, setReminderTime] = useState(habit?.reminderTimes?.[0] || '09:00')
  
  const handleSave = () => {
    if (!name.trim()) return
    
    const habitData = {
      name: name.trim(),
      description: description.trim() || undefined,
      icon,
      color,
      frequency,
      weekDays: frequency === 'weekly' ? weekDays : undefined,
      monthDays: frequency === 'monthly' ? monthDays : undefined,
      targetPerDay,
      reminderTimes: [reminderTime],
      archived: false,
    }
    
    if (isEdit && habit) {
      updateHabit(habit.id, habitData)
    } else {
      addHabit(habitData)
    }
    onClose()
  }
  
  const handleDelete = () => {
    if (habit && confirm('确定删除这个习惯吗？所有打卡记录将被清除。')) {
      deleteHabit(habit.id)
      onClose()
    }
  }
  
  const handleArchive = () => {
    if (habit) {
      archiveHabit(habit.id)
      onClose()
    }
  }
  
  const toggleWeekDay = (day: WeekDay) => {
    if (weekDays.includes(day)) {
      setWeekDays(weekDays.filter((d) => d !== day))
    } else {
      setWeekDays([...weekDays, day].sort())
    }
  }
  
  return (
    <div className="fixed inset-0 bg-[#0f0f1a] z-50 overflow-y-auto">
      <div className="max-w-lg mx-auto p-4 pb-20">
        {/* 头部 */}
        <div className="flex items-center justify-between mb-6">
          <button onClick={onClose} className="text-2xl text-[#a0a0b0]">←</button>
          <h2 className="text-lg font-bold">{isEdit ? '编辑习惯' : '新建习惯'}</h2>
          <button onClick={handleSave} className="text-[#c41e3a] font-medium">保存</button>
        </div>
        
        {/* 模板快捷添加 */}
        {!isEdit && (
          <div className="mb-6">
            <p className="text-xs text-[#a0a0b0] mb-2">快速添加模板</p>
            <div className="flex flex-wrap gap-2">
              {HABIT_TEMPLATES.slice(0, 6).map((t, i) => (
                <button
                  key={i}
                  onClick={() => {
                    setName(t.name)
                    setIcon(t.icon)
                    setColor(t.color)
                    setFrequency(t.frequency)
                    if (t.weekDays) setWeekDays(t.weekDays)
                  }}
                  className="px-3 py-1.5 bg-[#1a1a2e] rounded-full text-sm flex items-center gap-1.5 border border-white/5 active:bg-[#2a2a3e]"
                >
                  <span>{t.icon}</span>
                  <span>{t.name}</span>
                </button>
              ))}
            </div>
          </div>
        )}
        
        {/* 名称 */}
        <div className="mb-4">
          <label className="text-xs text-[#a0a0b0] block mb-1.5">习惯名称</label>
          <input
            type="text"
            value={name}
            onChange={(e) => setName(e.target.value)}
            placeholder="例如：每天跑步"
            className="w-full bg-[#1a1a2e] rounded-xl px-4 py-3 text-sm border border-white/5 focus:border-[#c41e3a]/50 outline-none"
          />
        </div>
        
        {/* 描述 */}
        <div className="mb-4">
          <label className="text-xs text-[#a0a0b0] block mb-1.5">描述（可选）</label>
          <input
            type="text"
            value={description}
            onChange={(e) => setDescription(e.target.value)}
            placeholder="简短描述这个习惯"
            className="w-full bg-[#1a1a2e] rounded-xl px-4 py-3 text-sm border border-white/5 focus:border-[#c41e3a]/50 outline-none"
          />
        </div>
        
        {/* 图标 */}
        <div className="mb-4">
          <label className="text-xs text-[#a0a0b0] block mb-1.5">图标</label>
          <div className="flex flex-wrap gap-2">
            {ICONS.map((ic) => (
              <button
                key={ic}
                onClick={() => setIcon(ic)}
                className={`w-10 h-10 rounded-lg flex items-center justify-center text-xl transition-all ${
                  icon === ic ? 'ring-2 ring-[#c41e3a]' : 'bg-[#1a1a2e]'
                }`}
              >
                {ic}
              </button>
            ))}
          </div>
        </div>
        
        {/* 颜色 */}
        <div className="mb-4">
          <label className="text-xs text-[#a0a0b0] block mb-1.5">颜色</label>
          <div className="flex flex-wrap gap-2">
            {COLORS.map((c) => (
              <button
                key={c}
                onClick={() => setColor(c)}
                className={`w-8 h-8 rounded-full transition-all ${
                  color === c ? 'ring-2 ring-white ring-offset-2 ring-offset-[#0f0f1a]' : ''
                }`}
                style={{ backgroundColor: c }}
              />
            ))}
          </div>
        </div>
        
        {/* 频率 */}
        <div className="mb-4">
          <label className="text-xs text-[#a0a0b0] block mb-1.5">频率</label>
          <div className="flex gap-2">
            {[
              { value: 'daily', label: '每天' },
              { value: 'weekly', label: '每周' },
              { value: 'monthly', label: '每月' },
            ].map((f) => (
              <button
                key={f.value}
                onClick={() => setFrequency(f.value as Frequency)}
                className={`flex-1 py-2.5 rounded-xl text-sm font-medium transition-all ${
                  frequency === f.value ? 'bg-[#c41e3a] text-white' : 'bg-[#1a1a2e] text-[#a0a0b0]'
                }`}
              >
                {f.label}
              </button>
            ))}
          </div>
        </div>
        
        {/* 每周几 */}
        {frequency === 'weekly' && (
          <div className="mb-4">
            <label className="text-xs text-[#a0a0b0] block mb-1.5">选择星期</label>
            <div className="flex gap-2">
              {WEEKDAYS.map((d, i) => (
                <button
                  key={i}
                  onClick={() => toggleWeekDay(i as WeekDay)}
                  className={`w-10 h-10 rounded-full text-sm font-medium transition-all ${
                    weekDays.includes(i as WeekDay) 
                      ? 'bg-[#c41e3a] text-white' 
                      : 'bg-[#1a1a2e] text-[#a0a0b0]'
                  }`}
                >
                  {d}
                </button>
              ))}
            </div>
          </div>
        )}
        
        {/* 每日目标次数 */}
        <div className="mb-4">
          <label className="text-xs text-[#a0a0b0] block mb-1.5">每日目标次数</label>
          <div className="flex items-center gap-4">
            <button
              onClick={() => setTargetPerDay(Math.max(1, targetPerDay - 1))}
              className="w-10 h-10 rounded-full bg-[#1a1a2e] text-xl flex items-center justify-center"
            >
              -
            </button>
            <span className="text-2xl font-bold w-12 text-center">{targetPerDay}</span>
            <button
              onClick={() => setTargetPerDay(targetPerDay + 1)}
              className="w-10 h-10 rounded-full bg-[#1a1a2e] text-xl flex items-center justify-center"
            >
              +
            </button>
          </div>
        </div>
        
        {/* 提醒时间 */}
        <div className="mb-6">
          <label className="text-xs text-[#a0a0b0] block mb-1.5">提醒时间</label>
          <input
            type="time"
            value={reminderTime}
            onChange={(e) => setReminderTime(e.target.value)}
            className="w-full bg-[#1a1a2e] rounded-xl px-4 py-3 text-sm border border-white/5 focus:border-[#c41e3a]/50 outline-none"
          />
        </div>
        
        {/* 操作按钮 */}
        {isEdit && (
          <div className="flex gap-3">
            <button
              onClick={handleArchive}
              className="flex-1 py-3 rounded-xl text-sm font-medium bg-[#1a1a2e] text-[#a0a0b0]"
            >
              {habit.archived ? '恢复习惯' : '暂停习惯'}
            </button>
            <button
              onClick={handleDelete}
              className="py-3 px-6 rounded-xl text-sm font-medium bg-red-500/20 text-red-400"
            >
              删除
            </button>
          </div>
        )}
      </div>
    </div>
  )
}
