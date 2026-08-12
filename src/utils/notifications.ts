// 原生通知工具 - Capacitor 版本

export const isCapacitor = () => {
  return typeof (window as any).Capacitor !== 'undefined'
}

export async function requestNotificationPermission(): Promise<boolean> {
  if (!isCapacitor()) {
    if ('Notification' in window) {
      const permission = await Notification.requestPermission()
      return permission === 'granted'
    }
    return false
  }

  try {
    const { LocalNotifications } = await import('@capacitor/local-notifications')
    const result = await LocalNotifications.checkPermissions()
    if (result.display === 'granted') return true
    const request = await LocalNotifications.requestPermissions()
    return request.display === 'granted'
  } catch (e) {
    console.log('Capacitor notifications not available')
    return false
  }
}

export async function showNotification(title: string, body: string): Promise<void> {
  if (!isCapacitor()) {
    if ('Notification' in window && Notification.permission === 'granted') {
      new Notification(title, { body })
    }
    return
  }

  try {
    const { LocalNotifications } = await import('@capacitor/local-notifications')
    const id = Date.now()
    await LocalNotifications.schedule({
      notifications: [{
        id,
        title,
        body,
        schedule: { at: new Date() }
      }]
    })
  } catch (e) {
    console.log('Show notification error:', e)
  }
}

export async function scheduleDailyReminder(time: string): Promise<void> {
  if (!isCapacitor()) return

  try {
    const { LocalNotifications } = await import('@capacitor/local-notifications')
    const [hours, minutes] = time.split(':').map(Number)
    const reminder = new Date()
    reminder.setHours(hours, minutes, 0, 0)
    
    if (reminder <= new Date()) {
      reminder.setDate(reminder.getDate() + 1)
    }

    await LocalNotifications.schedule({
      notifications: [{
        id: 1,
        title: '⚖️ 矛盾提醒',
        body: '今日习惯打卡时间到 🔥',
        schedule: { at: reminder, repeats: true }
      }]
    })
  } catch (e) {
    console.log('Schedule reminder error:', e)
  }
}

export async function cancelAllReminders(): Promise<void> {
  if (!isCapacitor()) return

  try {
    const { LocalNotifications } = await import('@capacitor/local-notifications')
    await LocalNotifications.cancel({ notifications: [{ id: 1 }] })
  } catch (e) {
    console.log('Cancel reminders error:', e)
  }
}
