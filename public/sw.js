// Service Worker for HabitFlow Push Notifications

const CACHE_NAME = 'habitflow-v1'

// 安装 Service Worker
self.addEventListener('install', (event) => {
  console.log('[SW] Installing...')
  self.skipWaiting()
})

// 激活时清理旧缓存
self.addEventListener('activate', (event) => {
  console.log('[SW] Activating...')
  event.waitUntil(clients.claim())
})

// 处理推送通知
self.addEventListener('push', (event) => {
  console.log('[SW] Push received')
  
  let data = {
    title: 'HabitFlow 习惯提醒',
    body: '今天还有习惯没有完成哦 💪',
    icon: '⚡',
    tag: 'habit-reminder'
  }
  
  if (event.data) {
    try {
      data = { ...data, ...event.data.json() }
    } catch (e) {
      data.body = event.data.text()
    }
  }
  
  const options = {
    body: data.body,
    icon: data.icon || '/favicon.svg',
    badge: '/favicon.svg',
    tag: data.tag,
    requireInteraction: true, // 让通知保持可见
    vibrate: [200, 100, 200],
    actions: [
      { action: 'open', title: '去打卡' },
      { action: 'dismiss', title: '稍后' }
    ],
    data: data
  }
  
  event.waitUntil(
    self.registration.showNotification(data.title, options)
  )
})

// 处理通知点击
self.addEventListener('notificationclick', (event) => {
  console.log('[SW] Notification clicked:', event.action)
  event.notification.close()
  
  if (event.action === 'dismiss') return
  
  // 打开或聚焦应用
  event.waitUntil(
    clients.matchAll({ type: 'window', includeUncontrolled: true })
      .then((clientList) => {
        // 如果已有窗口，打开它
        for (const client of clientList) {
          if (client.url.includes('habitflow') && 'focus' in client) {
            return client.focus()
          }
        }
        // 否则打开新窗口
        if (clients.openWindow) {
          return clients.openWindow('/')
        }
      })
  )
})

// 定期同步（如果支持）
self.addEventListener('periodicsync', (event) => {
  if (event.tag === 'habit-reminder') {
    event.waitUntil(checkAndNotify())
  }
})

// 后台同步
self.addEventListener('sync', (event) => {
  if (event.tag === 'habit-reminder-sync') {
    event.waitUntil(scheduleNextNotification())
  }
})

// 检查并发送通知
async function checkAndNotify() {
  // 从 IndexedDB 获取习惯数据（简化版，实际应该用更复杂的存储）
  const data = await getStoredData()
  
  if (!data || !data.habits || data.habits.length === 0) return
  
  const today = new Date().toISOString().split('T')[0]
  const todayHabits = data.habits.filter(h => !h.archived)
  const completedCount = data.checkIns?.filter(c => 
    c.date === today && c.count >= 1
  )?.length || 0
  
  if (completedCount < todayHabits.length && todayHabits.length > 0) {
    const options = {
      body: `还有 ${todayHabits.length - completedCount} 个习惯等待完成 🔥`,
      icon: '⚡',
      tag: 'habit-reminder',
      requireInteraction: true
    }
    
    await self.registration.showNotification('HabitFlow', options)
  }
}

// 获取存储的数据
async function getStoredData() {
  try {
    const cache = await caches.open(CACHE_NAME)
    const response = await cache.match('habit-data')
    if (response) {
      return response.json()
    }
  } catch (e) {
    console.log('[SW] Error getting stored data:', e)
  }
  return null
}

// 安排下一次通知
async function scheduleNextNotification() {
  // 通知调度被触发，可以重新检查数据
  await checkAndNotify()
}
