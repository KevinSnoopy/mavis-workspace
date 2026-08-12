import { StrictMode } from 'react'
import { createRoot } from 'react-dom/client'
import './index.css'
import App from './App.tsx'

// 注册 Service Worker
if ('serviceWorker' in navigator) {
  window.addEventListener('load', () => {
    navigator.serviceWorker.register('/sw.js')
      .then((registration) => {
        console.log('[App] SW registered:', registration.scope)
        
        // 定期同步检查（如果支持）
        const reg = registration as ServiceWorkerRegistration & { periodicSync?: any }
        if (reg.periodicSync) {
          reg.periodicSync.register('habit-reminder', {
            minInterval: 60 * 60 * 1000 // 至少1小时
          }).catch(() => console.log('Periodic sync not supported'))
        }
      })
      .catch((error) => {
        console.log('[App] SW registration failed:', error)
      })
  })
}

// 主题初始化
const initTheme = () => {
  const saved = localStorage.getItem('habitflow-settings')
  if (saved) {
    const settings = JSON.parse(saved)
    if (settings.darkMode === false) {
      document.documentElement.classList.add('light-mode')
    }
  }
}
initTheme()

createRoot(document.getElementById('root')!).render(
  <StrictMode>
    <App />
  </StrictMode>,
)
