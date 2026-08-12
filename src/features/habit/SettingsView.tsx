import { useState, useEffect } from 'react'
import { useHabitStore } from './store'

export function SettingsView() {
  const { habits, checkIns } = useHabitStore()
  const [darkMode, setDarkMode] = useState(true)
  const [notificationPermission, setNotificationPermission] = useState<NotificationPermission>('default')
  
  // 加载设置
  useEffect(() => {
    const saved = localStorage.getItem('habitflow-settings')
    if (saved) {
      const settings = JSON.parse(saved)
      setDarkMode(settings.darkMode ?? true)
    }
    
    // 检查通知权限
    if ('Notification' in window) {
      setNotificationPermission(Notification.permission)
    }
  }, [])
  
  // 切换主题
  const toggleTheme = () => {
    const newMode = !darkMode
    setDarkMode(newMode)
    saveSettings('darkMode', newMode)
    
    if (newMode) {
      document.documentElement.classList.remove('light-mode')
    } else {
      document.documentElement.classList.add('light-mode')
    }
  }
  
  // 保存设置
  const saveSettings = (key: string, value: any) => {
    const saved = localStorage.getItem('habitflow-settings')
    const settings = saved ? JSON.parse(saved) : {}
    settings[key] = value
    localStorage.setItem('habitflow-settings', JSON.stringify(settings))
  }
  
  // 请求通知权限
  const requestNotificationPermission = async () => {
    if (!('Notification' in window)) {
      alert('你的浏览器不支持通知功能')
      return
    }
    
    const permission = await Notification.requestPermission()
    setNotificationPermission(permission)
    
    if (permission === 'granted') {
      saveSettings('notificationsEnabled', true)
      
      // 立即发送一条测试通知
      new Notification('HabitFlow 通知已开启 🔔', {
        body: '你会在设定的时间收到习惯提醒',
        icon: '⚡',
      })
      
      // 注册 Service Worker 定期同步
      if ('serviceWorker' in navigator && 'periodicSync' in ServiceWorkerRegistration.prototype) {
        try {
          const registration = await navigator.serviceWorker.ready
          await (registration as any).periodicSync.register('habit-reminder', {
            minInterval: 60 * 60 * 1000 // 1小时
          })
        } catch (e) {
          console.log('Periodic sync not supported')
        }
      }
    }
  }
  
  // 发送测试通知
  const sendTestNotification = () => {
    if (Notification.permission === 'granted') {
      new Notification('习惯提醒测试 📢', {
        body: '如果你看到这条消息，说明通知功能正常！',
        icon: '⚡',
        tag: 'test'
      })
    }
  }
  
  // 导出数据
  const handleExportData = () => {
    const data = {
      habits,
      checkIns,
      achievements: useHabitStore.getState().achievements,
      exportedAt: new Date().toISOString(),
      version: '1.0'
    }
    
    const blob = new Blob([JSON.stringify(data, null, 2)], { type: 'application/json' })
    const url = URL.createObjectURL(blob)
    const a = document.createElement('a')
    a.href = url
    a.download = `habitflow-backup-${new Date().toISOString().split('T')[0]}.json`
    a.click()
    URL.revokeObjectURL(url)
  }
  
  // 导入数据
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
              analysisInsights: data.analysisInsights || [],
            },
            version: 0,
          }))
          alert('数据导入成功！页面将刷新。')
          setTimeout(() => window.location.reload(), 1000)
        } else {
          alert('导入失败：文件格式不正确')
        }
      } catch (err) {
        alert('导入失败：' + (err as Error).message)
      }
    }
    reader.readAsText(file)
  }
  
  // 清除数据
  const handleClearData = () => {
    if (confirm('确定清除所有数据吗？此操作不可恢复！')) {
      if (confirm('最后一次确认：所有习惯和打卡记录将被永久删除！')) {
        localStorage.removeItem('habitflow-storage')
        alert('数据已清除，页面将刷新。')
        setTimeout(() => window.location.reload(), 500)
      }
    }
  }

  return (
    <div className="animate-fadeIn">
      <h1 className="text-xl font-bold mb-6">⚙️ 设置</h1>
      
      {/* 外观设置 */}
      <div className="card rounded-[20px] p-5 mb-4">
        <h3 className="text-sm font-medium text-[var(--color-text-secondary)] mb-4">外观</h3>
        
        <div className="flex items-center justify-between">
          <div className="flex items-center gap-3">
            <span className="text-xl">{darkMode ? '🌙' : '☀️'}</span>
            <div>
              <p className="font-medium">{darkMode ? '深色模式' : '浅色模式'}</p>
              <p className="text-xs text-[var(--color-text-secondary)]">
                {darkMode ? '护眼，适合夜间使用' : '明亮，适合白天使用'}
              </p>
            </div>
          </div>
          <button onClick={toggleTheme} className="theme-toggle" />
        </div>
      </div>
      
      {/* 通知设置 */}
      <div className="card rounded-[20px] p-5 mb-4">
        <h3 className="text-sm font-medium text-[var(--color-text-secondary)] mb-4">通知</h3>
        
        <div className="space-y-4">
          <div className="flex items-center justify-between">
            <div className="flex items-center gap-3">
              <span className="text-xl">🔔</span>
              <div>
                <p className="font-medium">每日提醒</p>
                <p className="text-xs text-[var(--color-text-secondary)]">
                  推送习惯打卡提醒
                </p>
              </div>
            </div>
            {notificationPermission === 'granted' ? (
              <span className="text-xs px-3 py-1 rounded-full bg-[var(--color-success)]/20 text-[var(--color-success)]">
                已开启 ✓
              </span>
            ) : notificationPermission === 'denied' ? (
              <span className="text-xs px-3 py-1 rounded-full bg-red-500/20 text-red-400">
                已拒绝
              </span>
            ) : (
              <button
                onClick={requestNotificationPermission}
                className="text-xs px-3 py-1 rounded-full bg-[var(--color-primary)]/20 text-[var(--color-primary)]"
              >
                开启
              </button>
            )}
          </div>
          
          {notificationPermission === 'granted' && (
            <div className="pt-3 border-t border-[var(--color-border)]">
              <button
                onClick={sendTestNotification}
                className="w-full py-3 rounded-xl bg-[var(--color-bg-elevated)] text-sm"
              >
                发送测试通知
              </button>
              <p className="text-xs text-[var(--color-text-secondary)] mt-2 text-center">
                点击后检查你的系统通知
              </p>
            </div>
          )}
          
          {notificationPermission === 'denied' && (
            <div className="pt-3 border-t border-[var(--color-border)]">
              <p className="text-xs text-[var(--color-text-secondary)] text-center">
                浏览器已阻止通知，请在设置中允许通知
              </p>
            </div>
          )}
        </div>
      </div>
      
      {/* 数据管理 */}
      <div className="card rounded-[20px] p-5 mb-4">
        <h3 className="text-sm font-medium text-[var(--color-text-secondary)] mb-4">数据</h3>
        
        <div className="space-y-3">
          <button
            onClick={handleExportData}
            className="w-full flex items-center gap-3 p-4 rounded-xl bg-[var(--color-bg-elevated)]"
          >
            <span className="text-xl">📤</span>
            <div className="text-left">
              <p className="font-medium text-sm">导出数据</p>
              <p className="text-xs text-[var(--color-text-secondary)]">备份为 JSON 文件</p>
            </div>
          </button>
          
          <label className="w-full flex items-center gap-3 p-4 rounded-xl bg-[var(--color-bg-elevated)] cursor-pointer">
            <span className="text-xl">📥</span>
            <div className="text-left">
              <p className="font-medium text-sm">导入数据</p>
              <p className="text-xs text-[var(--color-text-secondary)]">从备份文件恢复</p>
            </div>
            <input
              type="file"
              accept=".json"
              onChange={handleImportData}
              className="hidden"
            />
          </label>
          
          <button
            onClick={handleClearData}
            className="w-full flex items-center gap-3 p-4 rounded-xl bg-red-500/10"
          >
            <span className="text-xl">🗑️</span>
            <div className="text-left">
              <p className="font-medium text-sm text-red-400">清除所有数据</p>
              <p className="text-xs text-red-400/60">不可恢复，请先导出备份</p>
            </div>
          </button>
        </div>
      </div>
      
      {/* 统计信息 */}
      <div className="card rounded-[20px] p-5 mb-4">
        <h3 className="text-sm font-medium text-[var(--color-text-secondary)] mb-4">统计</h3>
        
        <div className="grid grid-cols-2 gap-4">
          <div className="text-center p-3 rounded-xl bg-[var(--color-bg-elevated)]">
            <p className="text-2xl font-bold text-[var(--color-primary)]">{habits.length}</p>
            <p className="text-xs text-[var(--color-text-secondary)]">总习惯数</p>
          </div>
          <div className="text-center p-3 rounded-xl bg-[var(--color-bg-elevated)]">
            <p className="text-2xl font-bold text-[var(--color-success)]">{checkIns.length}</p>
            <p className="text-xs text-[var(--color-text-secondary)]">累计打卡</p>
          </div>
        </div>
      </div>
      
      {/* 关于 */}
      <div className="text-center py-8">
        <p className="text-3xl mb-2">⚡</p>
        <p className="font-bold">HabitFlow</p>
        <p className="text-xs text-[var(--color-text-secondary)] mt-1">版本 1.0.0</p>
        <p className="text-xs text-[var(--color-text-secondary)] mt-4 max-w-xs mx-auto">
          用《矛盾论》和《实践论》的智慧<br />
          帮助建立好习惯，实现螺旋上升
        </p>
      </div>
    </div>
  )
}
