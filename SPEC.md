# 矛盾 App 功能规格文档

> 版本：v1.6 | 更新：2026-08-13

---

## 一、项目结构

```
maodun_app/
├── lib/
│   ├── main.dart                         # 入口、MultiProvider、MainScreen
│   ├── models/
│   │   └── habit.dart                   # Habit, CheckIn, Achievement, HabitStats, HabitFrequency
│   ├── providers/
│   │   ├── habit_provider.dart           # 习惯状态管理（核心）
│   │   ├── theme_provider.dart           # 主题切换
│   │   └── notification_provider.dart    # 应用内通知
│   ├── screens/
│   │   ├── home_screen.dart             # 首页：今日习惯打卡
│   │   ├── habits_screen.dart           # 习惯管理：增删改、搜索、归档
│   │   ├── analyzer_screen.dart          # 矛盾分析器
│   │   ├── history_screen.dart          # 历史：概览/成就/分析/日历
│   │   ├── settings_screen.dart         # 设置：主题/导入导出/清除
│   │   ├── habit_detail_screen.dart     # 习惯详情：7日图/月历/打卡历史
│   │   └── onboarding_screen.dart        # 首次引导（3页）
│   ├── services/
│   │   └── notification_service.dart     # 通知服务
│   └── theme/
│       └── app_theme.dart               # 主题配置、颜色常量
├── web/
│   ├── index.html                       # PWA meta tags, Open Graph
│   └── manifest.json                    # PWA 配置
└── test/
```

---

## 二、核心功能清单

### F1. 习惯管理
- [x] **F1.1** 创建习惯（名称、描述、图标、颜色、频率）
- [x] **F1.2** 编辑习惯
- [x] **F1.3** 删除习惯（需确认对话框）
- [x] **F1.4** 归档/恢复习惯
- [x] **F1.5** 习惯列表搜索（实时过滤）
- [x] **F1.6** 频率选择（每日/每周/每月）

### F2. 打卡系统
- [x] **F2.1** 今日打卡（点击打卡/取消打卡）
- [x] **F2.2** 打卡触感反馈（HapticFeedback）
- [x] **F2.3** 全部完成彩屑动画（Confetti）
- [x] **F2.4** 下拉刷新（RefreshIndicator）
- [x] **F2.5** 数据持久化（SharedPreferences）

### F3. 统计数据
- [x] **F3.1** 全局连胜（globalStreak）
- [x] **F3.2** 单习惯连胜（currentStreak / bestStreak）
- [x] **F3.3** 总打卡次数
- [x] **F3.4** 完成率
- [x] **F3.5** 7日柱状图（fl_chart）
- [x] **F3.6** 月度日历视图（点击查看当日详情）

### F4. 矛盾分析器
- [x] **F4.1** 周期性分析（每周习惯 vs 每月习惯矛盾检测）
- [x] **F4.2** 主观分析（输入习惯列表，AI 生成分析）
- [x] **F4.3** 将分析结果保存并展示在历史页

### F5. 成就系统
- [x] **F5.1** 首次打卡成就
- [x] **F5.2** 连续7天成就
- [x] **F5.3** 连续30天成就
- [x] **F5.4** 成就徽章展示（历史页成就 Tab）

### F6. 通知系统
- [x] **F6.1** 首次打开 App 显示今日打卡提醒（应用内横幅）
- [x] **F6.2** 打卡成功显示通知
- [x] **F6.3** 通知铃铛图标 + 红点标记
- [x] **F6.4** 点击铃铛清空所有通知

### F7. 主题系统
- [x] **F7.1** 深色主题（默认）
- [x] **F7.2** 浅色主题
- [x] **F7.3** 主题切换（设置页 Switch，实时生效）
- [x] **F7.4** 主题持久化

### F8. 数据管理
- [x] **F8.1** 数据导出（Share API，JSON 格式）
- [x] **F8.2** 数据导入（粘贴 JSON 恢复）
- [x] **F8.3** 清除所有数据（需确认对话框）

### F9. 首次使用引导
- [x] **F9.1** Onboarding 3 页（App介绍、哲学理念、使用说明）
- [x] **F9.2** 首次启动检测，已看过则跳过

### F10. PWA 配置
- [x] **F10.1** manifest.json（名称、图标、主题色、快捷方式）
- [x] **F10.2** iOS meta tags（apple-mobile-web-app-capable）
- [x] **F10.3** Open Graph（分享时显示预览卡片）
- [x] **F10.4** 自定义加载动画（index.html）

### F11. 页面转场
- [x] **F11.1** 底部导航流畅转场
- [x] **F11.2** 各页面 FadeUpwardsTransition

### F12. 启动页
- [ ] **F12.1** 带 Logo + 品牌色的正式闪屏页（2秒）
- [ ] **F12.2** 闪屏期间初始化数据
- [ ] **F12.3** 闪屏后根据首次使用状态跳转

### F13. 测试覆盖
- [ ] **F13.1** HabitProvider 单元测试（打卡、添加、删除、成就）
- [ ] **F13.2** ThemeProvider 单元测试（主题切换）
- [ ] **F13.3** Widget 测试（打卡流程、添加习惯）

### F14. App 图标 & 资源
- [ ] **F14.1** 多尺寸应用图标（web/icon_*.png）
- [ ] **F14.2** manifest.json 中声明图标路径

### F15. Deep Link
- [ ] **F15.1** URL Scheme 配置（maodun://）
- [ ] **F15.2** Web URL 直接打开（/habit/:id）

### F16. 隐私政策 & CI/CD
- [ ] **F16.1** 隐私政策页面（web/privacy.html）
- [ ] **F16.2** GitHub Actions 自动构建（flutter.yml）

---

## 三、非功能性需求

- **N1** `flutter analyze`: 0 errors, 0 warnings
- **N2** `flutter build web --release`: 构建成功
- **N3** 所有异步方法有 try/catch
- **N4** 所有颜色使用 AppTheme 常量，无硬编码
- **N5** 单个 build() 方法不超过 150 行
- **N6** 私有 widget 类以 `_` 开头
- **N7** 中文 UI，所有文字为中文

---

## 四、已知限制

- **L1** Web 端不支持原生推送通知（浏览器 Notification API 待集成）
- **L2** 尚未接入 flutter_local_notifications（原生端推送待实现）
- **L3** 没有后端，数据仅本地存储

---

## 五、审查记录

| 版本 | 日期 | 说明 |
|------|------|------|
| v1.6 | 2026-08-13 | 初版规格建立 |
