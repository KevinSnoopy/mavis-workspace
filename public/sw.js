// Service Worker for HabitFlow PWA

const CACHE_NAME = 'habitflow-v1.0'
const STATIC_ASSETS = [
  '/',
  '/index.html',
  '/manifest.json',
  '/icon.svg',
]

// 安装 - 缓存静态资源
self.addEventListener('install', (event) => {
  console.log('[SW] Installing...')
  event.waitUntil(
    caches.open(CACHE_NAME)
      .then(cache => {
        console.log('[SW] Caching static assets')
        return cache.addAll(STATIC_ASSETS)
      })
      .then(() => self.skipWaiting())
  )
})

// 激活 - 清理旧缓存
self.addEventListener('activate', (event) => {
  console.log('[SW] Activating...')
  event.waitUntil(
    caches.keys()
      .then(keys => {
        return Promise.all(
          keys.filter(key => key !== CACHE_NAME)
            .map(key => caches.delete(key))
        )
      })
      .then(() => clients.claim())
  )
})

// 拦截请求 - 缓存优先
self.addEventListener('fetch', (event) => {
  // 只处理同源请求
  if (!event.request.url.startsWith(self.location.origin)) return
  
  // API 请求不走缓存
  if (event.request.url.includes('/api/')) return
  
  event.respondWith(
    caches.match(event.request)
      .then(cached => {
        // 找到缓存，直接返回
        if (cached) return cached
        
        // 没找到，发起网络请求
        return fetch(event.request)
          .then(response => {
            // 非成功状态不缓存
            if (!response || response.status !== 200) return response
            
            // 缓存新的响应
            const responseClone = response.clone()
            caches.open(CACHE_NAME)
              .then(cache => cache.put(event.request, responseClone))
            
            return response
          })
          .catch(() => {
            // 网络失败且没缓存，返回离线页面
            return caches.match('/index.html')
          })
      })
  )
})

// 处理推送通知
self.addEventListener('push', (event) => {
  let data = {
    title: '⚖️ 矛盾提醒',
    body: '今天还有习惯没有完成哦 💪',
    icon: '/icon.svg',
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
    icon: data.icon,
    badge: '/icon.svg',
    tag: data.tag,
    requireInteraction: true,
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
  event.notification.close()
  
  if (event.action === 'dismiss') return
  
  event.waitUntil(
    clients.matchAll({ type: 'window', includeUncontrolled: true })
      .then(clients => {
        // 如果有窗口，聚焦它
        for (const client of clients) {
          if (client.url.includes('habitflow') && 'focus' in client) {
            return client.focus()
          }
        }
        // 否则打开新窗口
        return clients.openWindow('/')
      })
  )
})

// 定期同步（Background Sync API）
self.addEventListener('periodicsync', (event) => {
  if (event.tag === 'habit-reminder') {
    event.waitUntil(checkAndNotify())
  }
})

// 后台同步
self.addEventListener('sync', (event) => {
  if (event.tag === 'habit-reminder-sync') {
    event.waitUntil(checkAndNotify())
  }
})

// 检查并发送通知
async function checkAndNotify() {
  try {
    // 尝试获取存储的数据
    const cache = await caches.open(CACHE_NAME)
    const response = await cache.match('/manifest.json')
    
    if (!response) return
    
    // 发送通知提示用户打开App
    await self.registration.showNotification('⚖️ 矛盾提醒', {
      body: '打开App完成今日习惯打卡 🔥',
      icon: '/icon.svg',
      tag: 'habit-check',
      requireInteraction: false
    })
  } catch (e) {
    console.log('[SW] Check notification error:', e)
  }
}

// 消息处理
self.addEventListener('message', (event) => {
  if (event.data && event.data.type === 'SKIP_WAITING') {
    self.skipWaiting()
  }
  
  if (event.data && event.data.type === 'SCHEDULE_NOTIFICATION') {
    const { time } = event.data
    scheduleNotification(time)
  }
})

// 调度通知
function scheduleNotification(targetTime) {
  const [hours, minutes] = targetTime.split(':').map(Number)
  const now = new Date()
  const target = new Date()
  target.setHours(hours, minutes, 0, 0)
  
  if (target <= now) {
    target.setDate(target.getDate() + 1)
  }
  
  const msUntil = target.getTime() - now.getTime()
  
  setTimeout(() => {
    if (self.registration) {
      self.registration.showNotification('⚖️ 矛盾提醒', {
        body: '今日习惯打卡时间到 🔥',
        icon: '/icon.svg',
        tag: 'habit-reminder'
      })
    }
  }, msUntil)
}
