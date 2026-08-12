import { useState, useEffect } from 'react'
import { useHabitStore } from './store'

export function SettingsView() {
  const { habits } = useHabitStore()
  const [darkMode, setDarkMode] = useState(true)
  const [notificationsEnabled, setNotificationsEnabled] = useState(false)
  const [notificationTime, setNotificationTime] = useState('09:00')
  
  // 加载设置
  useEffect(() => {
    const saved = localStorage.getItem('habitflow-settings')
    if (saved) {
      const settings = JSON.parse(saved)
      setDarkMode(settings.darkMode ?? true)
      setNotificationTime(settings.notificationTime ?? '09:00')
    }
  }, [])
  
  // 保存设置
  const saveSettings = (key: string, value: any) => {
    const saved = localStorage.getItem('habitflow-settings')
    const settings = saved ? JSON.parse(saved) : {}
    settings[key] = value
    localStorage.setItem('habitflow-settings', JSON.stringify(settings))
  }
  
  const toggleDarkMode = () => {
    setDarkMode(!darkMode)
    saveSettings('darkMode', !darkMode)
  }
  
  const requestNotificationPermission = async () => {
    if (!('Notification' in window)) {
      alert('你的浏览器不支持通知功能')
      return
    }
    
    const permission = await Notification.requestPermission()
    if (permission === 'granted') {
      setNotificationsEnabled(true)
      saveSettings('notificationsEnabled', true)
      scheduleReminder()
    }
  }
  
  const scheduleReminder = () => {
    // 简单提醒：检查当前时间是否匹配
    const now = new Date()
    const [hours, minutes] = notificationTime.split(':').map(Number)
    
    // 计算距离下次提醒的毫秒数
    const reminderTime = new Date()
    reminderTime.setHours(hours, minutes, 0, 0)
    
    if (reminderTime <= now) {
      reminderTime.setDate(reminderTime.getDate() + 1)
    }
    
    const msUntilReminder = reminderTime.getTime() - now.getTime()
    
    // 设置定时提醒
    setTimeout(() => {
      if (Notification.permission === 'granted') {
        new Notification('HabitFlow 习惯提醒', {
          body: '今天还有习惯没有完成哦，点击查看 👆',
          icon: '⚡',
        })
      }
      // 每24小时重复
      setInterval(() => {
        if (Notification.permission === 'granted') {
          new Notification('HabitFlow 习惯提醒', {
            body: '今天还有习惯没有完成哦，点击查看 👆',
            icon: '⚡',
          })
        }
      }, 24 * 60 * 60 * 1000)
    }, msUntilReminder)
  }
  
  const handleExportData = () => {
    const data = {
      habits,
      checkIns: useHabitStore.getState().checkIns,
      achievements: useHabitStore.getState().achievements,
      exportedAt: new Date().toISOString(),
    }
    
    const blob = new Blob([JSON.stringify(data, null, 2)], { type: 'application/json' })
    const url = URL.createObjectURL(blob)
    const a = document.createElement('a')
    a.href = url
    a.download = `habitflow-backup-${new Date().toISOString().split('T')[0]}.json`
    a.click()
    URL.revokeObjectURL(url)
  }
  
  const handleImportData = (e: React.ChangeEvent<HTMLInputElement>) => {
    const file = e.target.files?.[0]
    if (!file) return
    
    const reader = new FileReader()
    reader.onload = (event) => {
      try {
        const data = JSON.parse(event.target?.result as string)
        if (data.habits && data.checkIns) {
          localStorage.setItem('habitflow-storage', JSON.stringify({
            state: {
              habits: data.habits,
              checkIns: data.checkIns,
              achievements: data.achievements || [],
            },
            version: 0,
          }))
          alert('数据导入成功！页面将刷新。')
          window.location.reload()
        }
      } catch (err) {
        alert('导入失败：文件格式不正确')
      }
    }
    reader.readAsText(file)
  }
  
  const handleClearData = () => {
    if (confirm('确定清除所有数据吗？此操作不可恢复！')) {
      localStorage.removeItem('habitflow-storage')
      alert('数据已清除，页面将刷新。')
      window.location.reload()
    }
  }
  
  return (
    <div className="animate-fadeIn">
      {/* 设置列表 */}
      <div className="space-y-4">
        {/* 外观 */}
        <div className="bg-[#1a1a2e] rounded-2xl overflow-hidden border border-white/5">
          <h3 className="text-xs text-[#a0a0b0] px-4 pt-3 pb-2">外观</h3>
          <div className="flex items-center justify-between px-4 py-3">
            <div className="flex items-center gap-3">
              <span className="text-lg">🌙</span>
              <div>
                <p className="text-sm">深色模式</p>
                <p className="text-xs text-[#a0a0b0]">跟随系统或手动切换</p>
              </div>
            </div>
            <button
              onClick={toggleDarkMode}
              className={`w-12 h-6 rounded-full transition-all relative ${
                darkMode ? 'bg-[#c41e3a]' : 'bg-[#a0a0b0]'
              }`}
            >
              <span 
                className={`absolute top-1 w-4 h-4 bg-white rounded-full transition-all ${
                  darkMode ? 'left-7' : 'left-1'
                }`}
              />
            </button>
          </div>
        </div>
        
        {/* 通知 */}
        <div className="bg-[#1a1a2e] rounded-2xl overflow-hidden border border-white/5">
          <h3 className="text-xs text-[#a0a0b0] px-4 pt-3 pb-2">提醒</h3>
          <div className="px-4 py-3">
            <div className="flex items-center justify-between mb-3">
              <div className="flex items-center gap-3">
                <span className="text-lg">🔔</span>
                <div>
                  <p className="text-sm">每日提醒</p>
                  <p className="text-xs text-[#a0a0b0]">浏览器推送通知</p>
                </div>
              </div>
              {notificationsEnabled ? (
                <span className="text-xs text-[#22c55e] bg-[#22c55e]/20 px-2 py-1 rounded-full">已开启</span>
              ) : (
                <button
                  onClick={requestNotificationPermission}
                  className="text-xs text-[#c41e3a] bg-[#c41e3a]/20 px-2 py-1 rounded-full"
                >
                  开启
                </button>
              )}
            </div>
            
            {notificationsEnabled && (
              <div className="flex items-center gap-3">
                <span className="text-sm text-[#a0a0b0]">提醒时间</span>
                <input
                  type="time"
                  value={notificationTime}
                  onChange={(e) => {
                    setNotificationTime(e.target.value)
                    saveSettings('notificationTime', e.target.value)
                  }}
                  className="flex-1 bg-[#0f0f1a] rounded-lg px-3 py-2 text-sm"
                />
              </div>
            )}
          </div>
        </div>
        
        {/* 数据管理 */}
        <div className="bg-[#1a1a2e] rounded-2xl overflow-hidden border border-white/5">
          <h3 className="text-xs text-[#a0a0b0] px-4 pt-3 pb-2">数据</h3>
          <div className="px-4 py-3 space-y-3">
            <div className="flex items-center justify-between">
              <div className="flex items-center gap-3">
                <span className="text-lg">📤</span>
                <div>
                  <p className="text-sm">导出数据</p>
                  <p className="text-xs text-[#a0a0b0]">备份为 JSON 文件</p>
                </div>
              </div>
              <button
                onClick={handleExportData}
                className="text-xs text-[#c41e3a] bg-[#c41e3a]/20 px-3 py-1.5 rounded-full"
              >
                导出
              </button>
            </div>
            
            <div className="flex items-center justify-between">
              <div className="flex items-center gap-3">
                <span className="text-lg">📥</span>
                <div>
                  <p className="text-sm">导入数据</p>
                  <p className="text-xs text-[#a0a0b0]">从备份文件恢复</p>
                </div>
              </div>
              <label className="text-xs text-[#c41e3a] bg-[#c41e3a]/20 px-3 py-1.5 rounded-full cursor-pointer">
                导入
                <input
                  type="file"
                  accept=".json"
                  onChange={handleImportData}
                  className="hidden"
                />
              </label>
            </div>
            
            <div className="flex items-center justify-between">
              <div className="flex items-center gap-3">
                <span className="text-lg">🗑️</span>
                <div>
                  <p className="text-sm text-red-400">清除所有数据</p>
                  <p className="text-xs text-[#a0a0b0]">不可恢复，请先导出备份</p>
                </div>
              </div>
              <button
                onClick={handleClearData}
                className="text-xs text-red-400 bg-red-400/20 px-3 py-1.5 rounded-full"
              >
                清除
              </button>
            </div>
          </div>
        </div>
        
        {/* 统计信息 */}
        <div className="bg-[#1a1a2e] rounded-2xl p-4 border border-white/5">
          <div className="flex items-center justify-between text-sm">
            <span className="text-[#a0a0b0]">当前习惯数</span>
            <span>{habits.length} 个</span>
          </div>
        </div>
        
        {/* 关于 */}
        <div className="text-center py-6 text-[#a0a0b0]">
          <p className="text-2xl mb-2">⚡</p>
          <p className="font-bold">HabitFlow</p>
          <p className="text-xs mt-1">版本 1.0.0</p>
          <p className="text-xs mt-4">帮助建立好习惯<br />通过每日打卡和可视化进步</p>
        </div>
      </div>
    </div>
  )
}
