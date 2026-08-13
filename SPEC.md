# 矛盾 App 功能规格文档

> 版本：v1.19 | 更新：2026-08-13

---

## 一、项目结构

```
maodun_app/
├── lib/                                  # Flutter 源码
├── android/                             # Android 原生项目（minSdk 23）
├── ios/                                  # iOS 原生项目（min iOS 12.0）
├── macos/                                # macOS 原生项目
├── windows/                              # Windows 原生项目
├── web/                                  # Web 静态资源
├── test/                                 # 单元测试 + Widget 测试
├── pubspec.yaml                          # 依赖声明
├── SPEC.md                               # 本文档
└── STANDARDS.md                           # 编码规范
```
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
- [x] **F6.1** 每日提醒调度（SharedPreferences + Timer，App 启动时自动恢复）
- [x] **F6.2** Web 浏览器通知（dart:js_interop + package:web，权限申请；条件导入 web_notification_stub.dart 隔离非 Web 平台）
- [x] **F6.3** 原生端推送通知（flutter_local_notifications，权限已配置）
- [x] **F6.4** 应用内横幅通知（NotificationProvider，5秒自动消失）
- [x] **F6.5** 铃铛图标 + 红点标记 + 清空功能
- [x] **F6.6** Per-habit 提醒（每分钟检查，触发后浏览器通知 + onHabitReminderDue）

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
- [x] **F12.1** 带 Logo + 品牌色的正式闪屏页（弹性缩放动画）
- [x] **F12.2** 闪屏期间初始化数据
- [x] **F12.3** 闪屏后根据首次使用状态跳转

### F13. 测试覆盖
- [x] **F13.1** HabitProvider 单元测试（打卡、添加、删除、成就、多习惯、频率过滤）
- [x] **F13.2** ThemeProvider 单元测试（主题切换、持久化）
- [x] **F13.3** Widget 测试（打卡流程、添加习惯、主题切换）
- [x] **F13.4** 覆盖率：30 个测试（20 habit + 7 theme + 3 widget + 新成就测试）

### F14. App 图标 & 资源
- [x] **F14.1** 多尺寸应用图标（web/icons/icon.svg + icon-192/512.png）
- [x] **F14.2** manifest.json 中声明图标路径
- [x] **F14.3** Android 自适应图标（mipmap-anydpi-v26/ic_launcher.xml）
  - 前景：矢量 "矛" + 天平（珊瑚红 #E85D4C + 琥珀金 #F5A623）
  - 背景：深海军蓝渐变（#0F0F1A → #1A1A2E）
  - 5 种密度 PNG（mdpi 48dp / hdpi 72dp / xhdpi 96dp / xxhdpi 144dp / xxxhdpi 192dp）

### F15. Deep Link
- [x] **F15.1** GoRouter 路由配置（/、/habit/:id、/settings）
- [x] **F15.2** GitHub Pages 404 重定向（web/404.html）

### F16. 隐私政策 & CI/CD
- [x] **F16.1** 隐私政策页面（web/privacy.html）
- [x] **F16.2** GitHub Actions 自动构建（.github/workflows/flutter.yml）

### F17. Per-habit 提醒
- [x] **F17.1** 每个习惯独立设置提醒时间（"HH:mm" 格式存储于 Habit.reminderTime）
- [x] **F17.2** 创建/编辑习惯对话框中时间选择器（showTimePicker）
- [x] **F17.3** NotificationService.updateHabitReminders(Map) 统一调度，同一时间只运行一个 Timer
- [x] **F17.4** 每分钟检查一次，到点触发 Web 浏览器通知 + onHabitReminderDue 回调
- [x] **F17.5** 习惯增删/归档时自动同步提醒调度

### F18. 成就扩充
- [x] **F18.1** 五湖四海：同时保持 5 个活跃习惯
- [x] **F18.2** 百次打卡：全 app 累计打卡 100 条记录
- [x] **F18.3** _checkMultiHabitAchievements() 在 addHabit / 数据加载时触发

### F19. 习惯模板库
- [x] **F19.1** lib/data/habit_templates.dart：18 个预设模板（健康/学习/效率/生活方式）
- [x] **F19.2** 创建习惯对话框中"从模板选择"按钮（OutlinedButton.auto_awesome）
- [x] **F19.3** _TemplatePickerSheet 底部面板网格展示模板，点击后自动填充名称/描述/图标/颜色/频率/提醒时间
- [x] **F19.4** 模板支持预设置提醒时间

### F20. 打卡热力图
- [x] **F20.1** HomeScreen 新增热力图卡片（进度卡片和习惯列表之间）
- [x] **F20.2** 显示近 13 周（约 90 天）打卡热力图，GitHub 风格
- [x] **F20.3** 按日计算应打卡习惯和完成率，5 档颜色（灰→浅绿→深绿）
- [x] **F20.4** Tooltip 显示每个格子的日期和完成百分比

### F21. App 图标品牌化
- [x] **F21.1** iOS 图标：品牌设计（红矛 + 金天平），所有 15 种尺寸更新
- [x] **F21.2** Web 图标：更新 icon-192.png / icon-512.png / favicon.png 为品牌设计
- [x] **F21.3** 删除旧的上大写 Icon-*.png 文件，统一小写命名

### F22. UI 设计升级（v1.18）
- [x] **F22.1** Theme 系统：阴影预设（shadowSm/Md/Lg/Primary）、玻璃态渐变、主题 Data 全面完善
- [x] **F22.2** 进度卡片：渐变圆弧（CustomPainter）、动画百分比数字、进度条
- [x] **F22.3** 连胜卡片：玻璃态设计（永远显示）、激励文案（7天/30天/0天不同状态）
- [x] **F22.4** 习惯打卡卡片：按压缩放动画、打卡缩放动画、渐变打卡按钮
- [x] **F22.5** 首页头部：品牌 logo（"矛"字图标）、时间感知问候语
- [x] **F22.6** 空状态：品牌色图标容器、两个行动按钮（创建习惯 + 矛盾分析）

### F23. 全局设计语言统一（v1.19）
- [x] **F23.1** 所有 AppBar 标题去掉 emoji，统一纯文字标题
- [x] **F23.2** 习惯管理页：习惯卡片加阴影、StatChip 改为药丸胶囊、空状态升级为品牌图标
- [x] **F23.3** 记录页：统计卡片图标加品牌色背景、成就卡片金渐变图标容器 + 阴影
- [x] **F23.4** 设置页：ListTile 图标加品牌色背景、Section 卡片加阴影
- [x] **F23.5** 矛盾分析器：输入框和示例问题卡片统一加阴影、主要/次要矛盾卡片加主题色阴影
- [x] **F23.6** 引导页：emoji 升级为品牌渐变图标容器

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

## 四、平台就绪度

| 平台 | 状态 | 构建命令 | 说明 |
|------|-------|----------|------|
| **Web** | ✅ 就绪 | `flutter build web` | GitHub Pages 部署 |
| **iOS** | ✅ 目录就绪 | `flutter build ios --release` | 需 macOS + Xcode 签名 |
| **Android** | ✅ 目录就绪 | `flutter build apk --release` | minSdk 23，签名待配置 |
| **macOS** | ✅ 目录就绪 | `flutter build macos --release` | 需 macOS + Xcode |
| **Windows** | ✅ 目录就绪 | `flutter build windows --release` | 需 Windows + MSVC |
| **HarmonyOS** | ⚠️ 未适配 | — | 建议华为快应用路径 |

### Android 签名配置（发布前需完成）
1. 生成 keystore：`keytool -genkey -v -keystore key.jks -alias key -keyalg RSA -keysize 2048 -validity 10000`
2. 在 `android/app/build.gradle` 中配置 signingConfig
3. 或使用 Android App Bundle（Google Play）

### iOS 签名配置（发布前需完成）
1. 配置 Apple Developer 证书和 Profile
2. 在 Xcode Runner 项目中设置 Signing & Capabilities
3. `flutter build ios --release` 生成 .ipa

## 五、已知限制

- **L1** Web 端浏览器通知需要用户授权（首次访问时弹出）
- **L2** flutter_local_notifications Web 端不可用（原生端专用）
- **L3** 没有后端，数据仅本地存储
- **L4** flutter_secure_storage 已激活 — HabitProvider 通过 StorageServiceInterface 走平台适配层（Web→SharedPreferences，Native→flutter_secure_storage）
- **L5** macOS/iOS CI 构建需要 macOS runner（公开 repo 免费额度有限）；iOS 发布需要 Apple 开发者账号签名
- **L6** Android release 构建需要签名配置（Debug 模式可直接安装）

### 性能优化记录（v1.9）

- **P-01a** `toggleArchive` 添加 `_invalidateCache(habitId)` — 修复归档后 globalStreak 缓存不更新 Bug
- **P-01b** `HistoryScreen` TabBar `length: 3` → `length: 4`（修复 4 tab view 对应 3 count 的错误）
- **P-01c** `_AchievementsTab` `ListView` → `ListView.builder` — 成就列表懒加载渲染
- **P-01d** 所有列表渲染验证：`HomeScreen`（SliverChildBuilderDelegate）✅、`HabitsScreen`（ListView.builder）✅、`_InsightsTab`（ListView.builder）✅

---

## 五、审查记录

| 版本 | 日期 | 说明 |
|------|------|------|
| v1.6 | 2026-08-13 | 初版规格建立 |
| v1.6 | 2026-08-13 | 补充 F12-F16：闪屏页、测试、图标、Deep Link、隐私政策、CI/CD |
| v1.9 | 2026-08-13 | flutter_secure_storage 激活 + flutter_local_notifications 初始化接入 + 性能优化（缓存 Bug 修复 + ListView.builder + TabBar 修复） |
