# 矛盾 App 编程规范

> 本规范基于 Flutter/Dart 官方最佳实践制定，适用于「矛盾」个人成长 App 的代码库。
> 版本：v1.5 | 最后更新：2026-08-13

---

## 1. 项目结构

```
maodun_app/
├── lib/
│   ├── main.dart                    # 入口
│   ├── models/                      # 数据模型（纯数据，无逻辑）
│   │   └── habit.dart
│   ├── providers/                   # 状态管理
│   │   ├── habit_provider.dart
│   │   ├── theme_provider.dart
│   │   └── notification_provider.dart
│   ├── screens/                     # 页面
│   │   ├── home_screen.dart
│   │   ├── habits_screen.dart
│   │   ├── analyzer_screen.dart
│   │   ├── history_screen.dart
│   │   ├── settings_screen.dart
│   │   ├── habit_detail_screen.dart
│   │   └── onboarding_screen.dart
│   ├── services/                    # 业务服务
│   │   └── notification_service.dart
│   ├── theme/                       # 主题
│   │   └── app_theme.dart
│   └── utils/                       # 工具函数（可选）
├── web/                             # PWA 配置
├── test/                            # 单元测试
└── pubspec.yaml
```

**规范：**
- 每个屏幕对应一个独立文件
- 每个 Provider 对应一个文件
- 禁止在 `models/` 中放业务逻辑
- 私有 widget 类以 `_` 开头，放在其所属屏幕文件的底部

---

## 2. 命名规范

### 文件名
- 使用蛇形命名：`habit_provider.dart`
- 模型文件：`habit.dart`

### 类名
- 公开类：`PascalCase` — `HabitProvider`, `HomeScreen`
- 私有类：`_PascalCase` — `_HabitTile`, `_FrequencySelector`
- Provider 类：`*Provider` 后缀

### 变量 & 函数
- 局部变量 / 函数参数：`lowerCamelCase` — `isLoading`, `habitId`
- 私有成员：`lowerCamelCase`（dart 无强制约定，但约定加 `_` 前缀）— `_habits`, `_checkIn()`

### 常量
- `static const` / `final`：`lowerCamelCase` — `primary`, `bgCard`
- 全局枚举：`PascalCase` — `HabitFrequency.daily`

---

## 3. Widget 规范

### 3.1  StatelessWidget vs StatefulWidget
- 纯展示、无交互状态 → `StatelessWidget`
- 需要 `setState`、动画、生命周期 → `StatefulWidget`
- 页面根组件通常用 `StatelessWidget`（状态放 Provider）

### 3.2  Build 方法
- 单个 `build()` 方法不超过 ~150 行；超过则拆分 widget
- `build()` 内不允许有副作用（IO、网络请求、存储）
- 不在 `build()` 内调用 `notifyListeners()`

### 3.3  内联 vs 独立 widget
- 仅被一个地方使用且 <30 行 → 内联（放在使用位置底部）
- 被多个地方使用 → 独立为私有类 `_XxxWidget`
- 超过 100 行 → 独立文件

### 3.4  Builder 模式
- 避免过深的 `Builder` 嵌套（超过 3 层考虑拆分）
- 使用 `Consumer`/`context.watch` 而非多层 `Builder`

---

## 4. 状态管理（Provider）

### 4.1  Provider 结构
```dart
class HabitProvider extends ChangeNotifier {
  List<Habit> _habits = [];        // 私有字段
  bool _isLoading = true;

  // 公开 getter
  List<Habit> get habits => _habits;
  bool get isLoading => _isLoading;

  // 公开方法
  Future<void> addHabit(Habit habit) async { ... }
}
```

### 4.2  规则
- `notifyListeners()` 在所有数据变更后调用
- 异步操作使用 `Future<void>`，成功后 `notifyListeners()`
- 禁止在 `build()` 内直接修改 Provider 状态
- 统计类计算使用缓存（`_statsCache`），避免每次 rebuild 重算

### 4.3  Consumer 使用
```dart
// 推荐：Consumer2 / context.watch
Consumer<HabitProvider>(
  builder: (context, provider, child) { ... }
)

// 简单读取用 context.watch
final provider = context.watch<HabitProvider>();
```

---

## 5. 错误处理

- 所有 `await` 链式调用必须 `try/catch`
- 异步方法返回 `Future<void>`，出错时打印并回滚状态
- UI 层错误：显示 `SnackBar` 或 `AlertDialog`，不吞掉
- SharedPreferences 操作：`getXxx()` 返回 `null` 时使用 `??` 提供默认值
- JSON 解析：`as Map<String, dynamic>` 前确保类型正确

---

## 6. 性能规范

- 列表渲染超过 50 项 → 使用 `ListView.builder`
- 避免在 `build()` 内创建新对象（每次 rebuild 重新创建）
- 使用 `const` 构造函数减少 rebuild
- 图片资源：Web 端不超过 200KB，CDN 优先
- 动画：使用 `AnimatedBuilder` / `ImplicitlyAnimatedWidget`，避免手动 `setState` 驱动动画

---

## 7. 主题 & 样式

### 7.1  颜色使用
- 所有硬编码颜色必须提取到 `AppTheme` / `AppColors`
- 深浅主题公用的颜色：`AppTheme.primary`、`AppTheme.accent`
- 深色专属：`AppTheme.bgDark`、`AppTheme.bgCard`
- 浅色专属：`AppTheme.bgLight`、`AppTheme.bgCardLight`
- 主题感知颜色使用 `AppThemeColors` 辅助类

### 7.2  文字样式
- 主文字：`AppTheme.textPrimary`（深色）/ `AppTheme.textPrimaryLight`（浅色）
- 次要文字：`AppTheme.textSecondary` / `AppTheme.textSecondaryLight`
- 禁止硬编码文字颜色

### 7.3  圆角
- 使用 `AppTheme` 中预定义常量：`radiusSm(12)`, `radiusMd(16)`, `radiusLg(24)`

---

## 8. 路由 & 导航

- 使用 `MaterialPageRoute` 进行页面跳转
- 跳转前检查 `ctx.mounted`（`Navigator.pop` 等异步回调）
- 禁止在 `build()` 中执行 `Navigator.push/pop`

---

## 9. 注释 & 文档

- 每个 Provider 方法需有 `// ── 分隔注释`（已约定俗成）
- 复杂业务逻辑添加 `///` 文档注释
- 禁止无意义的 `// TODO`（无描述的 TODO）
- 代码行内注释仅解释「为什么」，不解释「是什么」

---

## 10. Git 提交规范

```
<type>: <简短描述>

type: feat | fix | refactor | style | docs | test | chore
```

示例：
```
feat: 添加日历热力图 Tab
fix: 修复浅色模式下文字颜色问题
refactor: 将通知逻辑抽离为独立 Provider
```

---

## 11. 测试规范

- 每个 Provider 方法至少有一个单元测试
- Widget 测试覆盖核心交互（打卡、添加习惯）
- 测试文件：`test/<对应文件>_test.dart`

---

## 12. 安全 & 隐私

- 禁止在日志中打印用户输入内容
- API Token、密钥使用 `Secret` 管理，不硬编码
- 导出数据时对敏感字段脱敏

---

## 规范检查清单

| # | 检查项 | 状态 |
|---|--------|------|
| S1 | 所有颜色在 AppTheme 中定义，无硬编码颜色 | ✅ 已修复 |
| S2 | 所有文字颜色使用 AppTheme 常量 | ✅ 通过 |
| S3 | Consumer 使用规范，无过深嵌套 | ✅ 通过 |
| S4 | 异步方法有 try/catch | ✅ 已修复 |
| S5 | Navigator 调用前检查 mounted | ✅ 通过（均为同步回调） |
| S6 | 列表渲染使用 ListView.builder（超过 20 项） | ✅ 通过 |
| S7 | 私有 widget 类以 `_` 开头 | ✅ 通过 |
| S8 | 无未使用的 import | ✅ 通过 |
| S9 | 无未使用的变量 | ✅ 通过 |
| S10 | 代码行数：单个 build() < 150 行 | ✅ 已修复（HomeScreen: 73行） |

### 修复详情

**S1 - 硬编码颜色**
- Confetti 颜色提取为 `AppColors.confettiColors`
- 连胜卡片橙色 → `AppTheme.accent`
- 渐变色修复

**S4 - 异步异常处理**
- `HabitProvider` 中 10 个异步方法全部加 try/catch

**S10 - build() 拆分**
- `HomeScreen` build(): 432行 → 73行
- 新增 `_HomeHeader`、`_StreakCard` widget
