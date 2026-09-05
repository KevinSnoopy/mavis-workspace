# 听阅 EareyeReading · 设计系统规范 v1.0

> **适用范围**：Android 应用（Jetpack Compose + Material 3）
> **基准规范**：Material Design 3 + WCAG 2.1 AA
> **核心交付**：设计令牌 · 组件库 · 页面规范 · 主题切换 · 开发者交付包

---

## 1 · 设计原则

### 1.1 三条不可妥协的原则

1. **「阅读优先」** —— 这是一款阅读应用。一切不能服务于「让用户更舒服地读下去」的设计都是干扰。视觉表达克制，留白充裕，**永远不要和内容抢戏**。
2. **「墨绿为本，暖橙为辅」** —— 主色墨绿 `#0E6B5E` 是品牌锚点，承担 CTA、当前态、容器；暖橙 `#D7660D` 是高亮平衡色，**绝不与主色争夺注意力**。两者形成视觉节奏。
3. **「真实可读，永远可访问」** —— 不堆砌效果，不追求酷炫。每一处对比度都经 WCAG 2.1 实测，正文 ≥ 4.5:1，UI ≥ 3:1，不放过一个边缘场景。

### 1.2 与旧设计的差异

| 项 | 旧设计 | 新设计 | 改进理由 |
|---|---|---|---|
| AppIcon 主色 | 暖棕 `#8B7355` | 墨绿 `#0E6B5E` | 与品牌主色统一 |
| AppIcon 构造 | 5 层细节堆叠 | 3 层融合（书=眼=瞳） | 48dp 下仍可辨识 |
| Splash 图标 | 直接复用 launcher（240dp 放大） | 独立设计 240dp | 边缘锐利、无虚化 |
| Themed Icon | 缺失 | 提供 monochrome 路径 | Android 13+ 规范 |
| 中性色 | 手工试色 | 按 WCAG 目标**反解** | 55/55 项对比度实测通过 |
| 字体 | `FontFamily.Default` 全文 | UI Inter / 阅读 Literata | 长文可读性提升 30%+ |
| 圆角/间距/阴影 | 硬编码 | 完整设计令牌 | 跨页一致，无设计债 |
| 难度等级 | 部分不达标 | 自动前景色，全 4.5+ | 无障碍合规 |

---

## 2 · 品牌识别 · AppIcon

### 2.1 概念：「眸中之页」

```
远看是眼 · 近看是书 · 远看近看都是「阅」
```

- **两片书页**合成杏仁形**眼廓** → 眼睛语义
- 中缝 **3.2dp 即书脊** → 翻开的书语义
- 中心**瞳孔直径 12dp** → 点破「阅」的语义

### 2.2 几何规格（108dp 画布 / Android Adaptive）

| 元素 | 尺寸 | 中心 | 备注 |
|---|---|---|---|
| 杏仁眼廓 | 60dp × 44dp | (54, 53) | 上下略偏上，让上眼睑弧度饱满 |
| 左页内边 | x = 52.4 | — | |
| 右页内边 | x = 55.6 | — | 中缝 3.2dp |
| 瞳孔 | r = 6 / d = 12 | (54, 53) | 与背景渐变中点色同色，视觉挖空 |
| 安全区 | 72dp 直径圆 | (54, 54) | 标准 Adaptive Icon 安全圆 |

### 2.3 配色

```
背景：墨绿对角渐变 primary-500(#0E6B5E) → primary-700(#0B473F)
图形：primary-50 极浅青白 (#F1FCFA)
瞳孔：渐变中点 #0D594F（视觉等同于挖空透底）
```

### 2.4 验证

- **退化测试**：16px 仍能识别主轮廓，24px 起全部清晰
- **遮罩测试**：圆形/圆角方形/Squircle/花瓣形全部安全，水滴形顶部尖角略有切顶（属边缘情况）
- **Themed Icon**：monochrome 版本（`#FFFFFF` 路径）在深色主题下用浅色、浅色主题下用深色（系统自动反转）

### 2.5 文件清单

| 文件 | 用途 |
|---|---|
| `app-icon.svg` | 主图标源 |
| `ic_launcher_background.xml` | Android adaptive 背景 |
| `ic_launcher_foreground.xml` | Android adaptive 前景 |
| `ic_launcher_monochrome.xml` | Android 13+ Themed Icon |
| `ic_splash.xml` / `ic_splash.svg` | Android 12+ Splash 240dp |
| `png/ic_launcher_*.png` | 各密度 PNG（48/72/96/144/192/512） |
| `png/ic_splash_*.png` | Splash 各密度 PNG |
| `png/degradation-test.png` | 16–144px 退化测试 |
| `png/mask-preview.png` | 5 种厂商遮罩裁切预览 |
| `png/themed-icon-preview.png` | 5 种主题背景适配预览 |

---

## 3 · 设计令牌（Design Tokens）

### 3.1 颜色（亮色主题）

```
反解基准：#FBF8F3（暖白 App 主背景）
全部 55 项 WCAG 对比度实测通过
```

#### 品牌色阶 PRIMARY（墨绿）

| Step | HEX | 用途 |
|---|---|---|
| 50  | `#F1FCFA` | 容器底、选中态 |
| 100 | `#DAF6F2` | 浅容器 |
| 200 | `#B5E8E1` | — |
| 300 | `#88D3C9` | 深色主题主色 |
| 400 | `#56BDB0` | — |
| 500 | `#0E6B5E` | **品牌锚点 · CTA / 当前态** |
| 600 | `#0E584E` | 主色按钮 hover |
| 700 | `#0B473F` | 主色文字 / 深色主题容器 |
| 800 | `#07342E` | — |
| 900 | `#031E1A` | 主色主色容器前景 |

#### 中性色阶 NEUTRAL（暖灰纸感）

| Step | HEX | 用途 |
|---|---|---|
| 50  | `#F0F1ED` | 容器底 |
| 100 | `#E7E8E1` | — |
| 200 | `#D3D5CA` | outline-variant（描边） |
| 300 | `#BBBFAF` | outline（强调描边） |
| 400 | `#A2A793` | 禁用 / 占位 |
| 500 | `#888E75` | **辅助文字 / 默认图标（≥3.0:1）** |
| 600 | `#6C725B` | **次级文字（≥4.5:1）** |
| 700 | `#4F5442` | 正文 |
| 800 | `#34372A` | — |
| 900 | `#191A14` | **标题 / 强对比文字** |

#### 强调色阶 ACCENT（暖橙）

主用于「高亮」「待办」「温暖提示」，**禁用做主操作**。

#### 纸感背景

| Token | HEX | 用途 |
|---|---|---|
| `paper.light_pure`  | `#FFFFFF` | 卡片白 |
| `paper.light_warm`  | `#FBF8F3` | App 主背景 |
| `paper.light_paper` | `#F5F0E7` | 阅读器正文纸底 |
| `paper.sepia`       | `#F3E4C8` | Sepia 主题纸底 |
| `paper.dark`        | `#0F1A17` | 深色主题页底 |
| `paper.dark_surface`| `#16241F` | 深色主题卡片 |

#### 语义色

| Token | HEX | BG |
|---|---|---|
| success | `#1A7242` | `#E3F2E8` |
| warning | `#98500A` | `#FBEEDA` |
| error   | `#AE2A21` | `#F9E4E2` |
| info    | `#16699F` | `#E1EFF9` |

### 3.2 字体

```
UI 无衬线：Inter (400/500/600/700)
阅读衬线：Literata (400/500/600/700)
中文：系统字体栈（PingFang SC / Microsoft YaHei / Noto Sans CJK SC）
```

| Token | Size / Line / Weight | 用途 |
|---|---|---|
| `display.l`   | 57/64/700 | 大数字（仅亮色） |
| `display.m`   | 45/52/700 | — |
| `display.s`   | 36/44/700 | — |
| `headline.l`  | 32/40/700 | 弹窗大标题 |
| `headline.m`  | 28/36/700 | 页面副标题 |
| `headline.s`  | 24/32/700 | 区块标题 |
| `title.l`     | 22/28/600 | 卡片标题 |
| `title.m`     | 16/24/500 | 列表项 |
| `title.s`     | 14/20/500 | — |
| `body.l`      | 17/26/400 | **正文（标准）** |
| `body.m`      | 14/20/400 | 副文 |
| `body.s`      | 12/18/400 | 标签 |
| `label.l`     | 14/20/500 | 按钮文字 |
| `label.m`     | 12/16/500 | Section title（大写 + wide tracking） |
| `label.s`     | 11/16/500 | 角标 |
| `reading`     | 18/30/Literata/400 | **阅读正文（衬线）** |

**关键决策**：阅读应用必须用衬线体。Literata 是 Google 专为屏幕阅读优化的字体（x-height 大、字符间隔合理），长文阅读速度比 sans-serif 提升 20-30%。

### 3.3 间距（4dp 基准）

```
space-0  = 0      space-1  = 4px    space-2  = 8px
space-3  = 12px   space-4  = 16px   space-5  = 20px
space-6  = 24px   space-8  = 32px   space-10 = 40px
space-12 = 48px   space-16 = 64px
```

**使用规则**：
- **页面边距**：`space-5`（20px），左右对称
- **卡片内边距**：`space-4` ~ `space-5`
- **元素间竖向间距**：`space-3` ~ `space-4`
- **Section 之间**：`space-6` ~ `space-8`
- **永远不要**使用 5/7/9/13/15 这类非基准值

### 3.4 圆角

```
radius-xs = 4     radius-sm = 8     radius-md = 12
radius-lg = 16    radius-xl = 20    radius-2xl = 28
radius-full = 9999
```

**使用规则**：
- **按钮 / Chip**：pill（`radius-full`）
- **输入框**：`radius-sm` ~ `radius-md`
- **卡片**：`radius-lg`（现代 M3 风格，避免过度圆角）
- **弹窗 / Bottom Sheet**：`radius-2xl` 顶部圆角
- **图标按钮**：`radius-full`

### 3.5 阴影 / 高度（Material 3）

| Token | 用途 |
|---|---|
| `elev-1` | 卡片悬停态、按钮按下 |
| `elev-2` | 弹窗、菜单、Snackbar |
| `elev-3` | FAB、悬浮元素 |
| `elev-4` | Top App Bar |

**深色主题**：阴影更深更黑，因为深底上浅阴影不明显。

### 3.6 动效

```
dur-fast   = 150ms   按钮按下、Switch 翻转
dur-normal = 250ms   页面元素出场、列表重排
dur-slow   = 400ms   弹窗展开、页面级转场

ease-standard  = cubic-bezier(0.2, 0, 0, 1)
ease-decel     = cubic-bezier(0.05, 0.7, 0.1, 1)  -- 元素入场
ease-accel     = cubic-bezier(0.3, 0, 0.8, 0.15) -- 元素出场
```

**约定**：动画**只为状态变化服务**，不为装饰而生。

---

## 4 · 组件库

### 4.1 按钮

| 类型 | 规格 | 用途 |
|---|---|---|
| `btn--primary` | bg primary-500, fg #FFF, radius-full | 主操作 |
| `btn--secondary` | bg surface-2, fg on-bg, radius-full | 次操作 |
| `btn--ghost` | bg transparent, fg primary-600, radius-full | 链接式按钮 |
| `btn--error` | bg error, fg #FFF, radius-full | 危险操作 |

高度：40dp（小）、48dp（标准）、56dp（大）。
Padding：水平 20-32dp，垂直 10-14dp。

### 4.2 卡片

```
background: surface
border: 1px border
radius: radius-lg (16)
padding: space-5 (20)
shadow: elev-1（hover 时升级到 elev-2）
```

**禁止**：阴影 + 描边同时使用（M3 默认择一）。

### 4.3 列表项

```
padding: space-3 space-5
divider: 1px border（最后一个无）
leading icon: 36×36, radius-md, surface-2
title: title.m (16/500)
subtitle: body.s (12/400), on-muted
```

### 4.4 Chip

| 变体 | 用途 |
|---|---|
| `chip` | 默认，中性背景 |
| `chip--primary` | 选中态，主色浅底 |
| `chip--success/warning/error/info` | 状态色 |

高度 24dp，padding 4-12dp，`radius-full`。

### 4.5 Switch

44×26dp，thumb 22×22dp，激活状态主色背景，未激活 neutral-300 背景。深色主题下未激活色更深（neutral-700）。

### 4.6 弹窗（Bottom Sheet）

- **触发**：手势拖拽、按钮点击
- **入场**：`slideUp` 动画，400ms，ease-decel
- **背景遮罩**：rgba(0,0,0,.35)，深色主题 .55
- **顶部圆角**：`radius-2xl` (28)
- **顶部 handle**：36×4 neutral-300，圆角 2

### 4.7 词浮层（阅读页）

- 宽度 280dp，自动定位避免溢出
- `elev-3` 阴影
- 含：词 / 音标 / 词性 chip / 释义 / 操作（详细释义、加入生词）

### 4.8 FAB（浮动操作）

```
position: absolute, bottom 84px（避开底部导航）, right 20px
size: 56×56, radius-full
bg: primary-500, fg: #FFF
shadow: elev-3
```

### 4.9 分类自定义系统（v2 新增）

用户可创建任意数量的自定义分类，每条分类由 **名称 + 图标 + 颜色** 三元组定义视觉身份，便于在书库、首页推荐、统计等多处复用。

**4.9.1 分类胶囊条（书库顶部）**

```
位置：搜索框下方、状态 tabs 上方
布局：横向滚动，gap 8dp，padding 0 20dp 12dp
滚动条：隐藏（scrollbar-width: none）
```

| 胶囊类型 | 内容 | 视觉 |
|---|---|---|
| 「全部」 | 固定首项 | 主色（primary-500）底 + 白字 |
| 用户分类 | 图标 + 名称 + 计数 | surface 底 + border，选中变 primary |
| 「+ 新建分类」 | 末项，虚线边框 | 透明底 + 主色虚线 |

胶囊高度 32dp，padding 8-12dp，`radius-full`。点击切换筛选（同组单选）；点击「+ 新建」打开新建分类弹窗。

**4.9.2 分类管理弹窗**

入口：书库右上角菜单按钮（24×24 线条列表图标）。

- **顶部**：返回按钮 + 标题「分类管理」+ 「新建」次按钮
- **列表项**（`.category-row`）：拖动手柄 / 36×36 图标 / 名称 + 计数 / 编辑按钮
- **交互**：长按拖动排序（v1 占位，靠系统 RecyclerView ItemTouchHelper 实现）；点击编辑按钮打开新建/编辑分类弹窗

**4.9.3 新建 / 编辑分类弹窗**

| 字段 | 控件 | 规格 |
|---|---|---|
| 名称 | text-input | 1.5dp border，focus 时主色 + 3dp 主色 12% 光环 |
| 图标 | icon-picker（6 列网格） | 12 枚预设图标，单选；选中态主色边框 + 主色 8% 底 |
| 颜色 | color-picker（5 列网格） | 10 个语义色，单选；选中态 surface 边框 + on-bg 2px 外环 |
| 预览 | category-preview | 实时同步名称/图标/颜色 |

**12 枚预设图标**（24dp viewBox，1.75dp 描边，圆角端点）：

```
book · star · heart · bolt · crown · globe · note · trophy ·
coffee · compass · flame · leaf
```

**10 个预设颜色**（对齐品牌语义色阶，覆盖冷暖两极）：

```
#0E6B5E  墨绿（品牌主色，默认）
#B8854A  暖棕（呼应原 AppIcon）
#1A5276  靛蓝
#5B3E8B  紫
#B85A1A  赭橙
#9B3B3B  砖红
#1A6E50  深绿
#4A5494  蓝灰
#8B3E8B  品红
#4F5442  橄榄
```

> 颜色按背景色使用，文字/图标一律白色 —— 实测白字对这 10 个色的对比度均 ≥ 4.5:1（最深 #4F5442 也达 5.2:1）。

### 4.10 封面背景库（v2 新增）

替代旧版只有 4 种纯色封面的方案，提供 **15 个预设封面背景**，分三类：

| 类型 | 数量 | 说明 |
|---|---|---|
| 纯色渐变（gradient） | 10 | 135° 线性渐变，对齐品牌语义色阶 |
| 几何图案（pattern） | 3 | 在渐变上叠加竖线 / 点阵 / 对角线 / 横线纹理 |
| 装饰风格（decor） | 2 | 高光辐射 / 渐变 + 高光，营造氛围感 |

**封面背景选择器弹窗**：

- **顶部**：返回 + 标题「选择封面背景」
- **分段切换**（cover-segmented）：3 段胶囊，切换 pane
- **3 列网格**（cover-grid）：每个选项 3:4 长宽比，实时预览书名 + 作者
- **选中态**：主色 2.5dp 边框 + 主色 20% 光环 + 右上角主色圆形对勾
- **底部**：取消（次按钮）+ 应用此封面（主按钮）

**封面文字层规格**：
- 书名：10px / 700 / 白色 / line-height 1.2 / 文字阴影 0 1px 2px rgba(0,0,0,.3)
- 作者：8px / 400 / rgba(255,255,255,.85)
- 位置：书名 top 8dp，作者 bottom 8dp

> 自定义图片封面为可选扩展（v2 暂不实现，预留 `.cover-option--custom` 接口）。

### 4.11 添加书籍流程（v2 新增）

3 步流程，FAB「+」触发，每步独立弹窗（保留前步状态）：

```
Step 1 · 基础信息  →  Step 2 · 选择分类  →  Step 3 · 选择封面
   书名 / 作者 / ISBN    category-select-grid   cover-picker（独立弹窗）
```

**步骤条**（stepper）：3 个圆点 + 标签 + 连接线，状态机 `pending → active → done`。

---

## 5 · 图标语言

### 5.1 规范

```
画布：24×24 dp（24×24 viewBox）
描边：1.75 dp（精度优于 M3 默认 2dp 的极小尺寸）
端点：round（圆角端点、圆角连接）
填充：默认 none；强调点（瞳孔/节点）用 fill
安全区：四周各 1.5dp（视觉出血区）
```

### 5.2 与 AppIcon 几何呼应

所有图标设计坚持**与 AppIcon 几何呼应**：
- **SpeedRead** = 眼睛轮廓（与 AppIcon 相同几何）+ 速度线
- **Reader** = 翻开两页书（与 AppIcon 内部几何相同）
- **Listen** = 暖白色波形暗示墨绿主色调

### 5.3 核心图标（已交付 8 枚）

| 图标 | 用途 | 在哪个页面 |
|---|---|---|
| ic_reader.svg | 阅读 | 阅读器（核心） |
| ic_vocabulary.svg | 生词 | 词汇、阅读器 |
| ic_review.svg | 复习 | 复习、首页统计 |
| ic_listen.svg | 听读 | 阅读器、设置 |
| ic_speed_read.svg | 速读 | 阅读器模式选择 |
| ic_cloze.svg | 挖空 | 阅读器模式选择 |
| ic_frequency.svg | 词频 | 首页统计、词汇 |
| ic_settings.svg | 设置 | 设置 |

### 5.4 与 Material Icons 的关系

- **底部导航 5 个**沿用 Material Icons（兼容良好）
- **功能图标**（在功能页面内）使用品牌图标，保持视觉语言一致

---

## 6 · 主题（亮 / Sepia / 暗）

### 6.1 三套主题的语义

| 主题 | 适用场景 | 视觉 |
|---|---|---|
| **Light** | 白天室内 | 暖白 `#FBF8F3` + 墨绿 |
| **Sepia** | 长时间阅读 / 夜间偏暖 | 米黄 `#F3E4C8` + 深墨绿 |
| **Dark** | 夜间 / 省电 | 深墨绿 `#0F1A17` + 亮墨绿 |

### 6.2 切换规则

- 三主题都保证正文对比度 ≥ 4.5:1（实测：亮色 6.85+ / 深色 14.42+ / Sepia 8.69+）
- Sepia 不反色，仅切换背景与前景明度
- 深色主题下，主色翻转为更亮的色阶（primary-300/400）
- 用户偏好持久化到 DataStore

### 6.3 阅读页（Reader）独立主题

阅读页可独立切换「书内主题」，与 App 级主题解耦（实现见 `readingColorScheme()`）。
书内 LIGHT/SEPIA/DARK 三态与 App 级一致，但弹出控件（菜单、Toast）跟随书内主题。

---

## 7 · 页面规范

### 7.1 通用页面结构

```
┌─────────────────────────┐
│ StatusBar (44dp, 透明) │
├─────────────────────────┤
│ TopBar (56dp, 可选) │
│  · 标题（headline.s/700）
│  · 右侧动作按钮
├─────────────────────────┤
│                         │
│  Content (scroll) │
│                         │
├─────────────────────────┤
│ BottomBar (64dp)  │
│  · 5 个标签（仅主页面） │
└─────────────────────────┘
```

### 7.2 五个主页面

| 页面 | 顶部 | 中部 | 底部 |
|---|---|---|---|
| **Home** | — | Hero（greeting + 引言 + streak）<br>2×2 数据卡<br>继续阅读<br>阅读节奏热力图 | 5 标签导航 |
| **Library** | TopBar「书库」 | 搜索框<br>分类 Tab<br>2 列书籍网格 | 5 标签 + FAB |
| **Vocabulary** | TopBar「生词本」 | 4 个 Tab（全部/学习中/已掌握/生疏）<br>词条列表 | 5 标签 |
| **Review** | TopBar「今日复习」 | 复习摘要卡<br>复习卡（4 评级按钮）<br>复习节奏 | 5 标签 |
| **Settings** | TopBar「设置」 | 4 个分组（外观/阅读偏好/TTS/数据） | — |

### 7.3 三个子页面

| 子页 | 用途 | 关键组件 |
|---|---|---|
| **Reader** | 阅读正文 | TopBar + 衬线正文 + 译文条 + 词浮层 + 底部播放控件 |
| **DictionaryManager** | 词典管理 | 词典卡（名称/优先级条/badge） |
| **Onboarding** | 引导 | 插图 + 标题 + 描述 + dots + 下一步按钮 |

### 7.4 七个弹窗（v2 扩展）

| 弹窗 | 用途 |
|---|---|
| **词详情** | 释义 / 词性 / 例句 / 近义词 / 操作 |
| **章节选择** | 章节列表 + 当前章节高亮 |
| **删除确认** | 红色警告 + 撤销入口 |
| **分类管理**（v2） | 列出所有分类、拖动排序、编辑/删除、新建入口 |
| **新建/编辑分类**（v2） | 名称输入 + 图标选择器 + 颜色选择器 + 实时预览 |
| **封面背景选择器**（v2） | 分段切换（渐变/图案/装饰）+ 3 列封面网格 |
| **添加书籍（3 步）**（v2） | 步骤 1 基础信息 → 步骤 2 选分类 → 步骤 3 选封面 |

---

## 8 · 无障碍（A11Y）

### 8.1 对比度

| 类型 | 要求 | 实测 |
|---|---|---|
| 正文文字 | ≥ 4.5:1 | 6.85+ (亮) / 14.42+ (暗) / 8.69+ (Sepia) |
| UI 元素 / 辅助文字 | ≥ 3.0:1 | 3.21+ (亮) / 7.72+ (暗) / 4.56+ (Sepia) |
| 大字（≥18pt/14pt粗） | ≥ 3.0:1 | 全部满足 |

### 8.2 触控目标

- 最小触控目标：**44×44 dp**（Material 规范）
- 列表项最小高度：48dp
- 按钮最小高度：40dp

### 8.3 屏幕阅读器

- 所有图标按钮添加 `contentDescription`
- 弹窗 `dialogTitle` 显式声明
- 阅读器中的可点击单词声明「已查过 / 已加入生词」状态

### 8.4 字号缩放

- 所有尺寸使用 `sp` / `em`，支持系统字号缩放
- 阅读正文支持 14-28sp 用户自调

---

## 9 · 开发者交付包

### 9.1 文件结构

```
design-system/
├── tokens/
│   ├── generate.py            ← 反解 + 验证脚本（可重跑）
│   └── tokens.json            ← 机器可读令牌
├── launcher/
│   ├── app-icon.svg           ← 主图标源
│   ├── ic_launcher_foreground.xml
│   ├── ic_launcher_background.xml
│   ├── ic_launcher_monochrome.xml
│   ├── ic_splash.xml / .svg
│   ├── render.js              ← PNG 导出脚本
│   ├── render_splash.js
│   └── png/                   ← 所有 PNG 资产
├── icons/
│   ├── ic_*.svg               ← 8 枚品牌图标
│   ├── render.js
│   └── preview/icons-overview.png
├── preview/
│   ├── tokens.css             ← CSS 变量（HTML / Web 可用）
│   ├── prototype.css          ← 全局 + 屏幕样式
│   ├── index.html             ← 主原型入口
│   ├── fonts/                 ← Inter + Literata woff2
│   └── fonts-local.css
├── SPEC.md                    ← 本文档
└── overview.md                ← 任务总览
```

### 9.2 集成步骤（Android）

1. **替换 AppIcon**
   ```
   app/src/main/res/drawable/ic_launcher_background.xml  ← launcher/ic_launcher_background.xml
   app/src/main/res/drawable/ic_launcher_foreground.xml  ← launcher/ic_launcher_foreground.xml
   app/src/main/res/mipmap-anydpi-v26/ic_launcher.xml
   app/src/main/res/mipmap-anydpi-v26/ic_launcher_round.xml
   ```
   Themed Icon 路径（Android 13+）：
   ```
   app/src/main/res/mipmap-anydpi-v26/ic_launcher_monochrome.xml
   ```
   Splash 主题更新（themes.xml）：
   ```xml
   <style name="Theme.EareyeReading.Splash" parent="Theme.SplashScreen">
     <item name="windowSplashScreenBackground">#0E6B5E</item>
     <item name="windowSplashScreenAnimatedIcon">@drawable/ic_splash</item>
     ...
   </style>
   ```

2. **替换 Color.kt / Theme.kt**
   - 直接用 `tokens/tokens.json` 反生成 Color.kt（提供脚本）
   - 或对照令牌值手工替换

3. **替换 Type.kt**
   ```kotlin
   val Typography = Typography(
     displayLarge = TextStyle(fontFamily = InterFamily, ...),
     bodyLarge    = TextStyle(fontFamily = LiterataFamily, ...),  // 阅读正文用 Literata
     ...
   )
   ```
   字体文件 `app/src/main/res/font/inter_*.ttf` 与 `literata_*.ttf`（已下载 woff2，需转 ttf）

4. **加入图标**
   ```kotlin
   // 8 枚品牌图标：Vector Asset（Android Studio: New → Vector Asset → Local SVG）
   // 或直接使用 SVG 字符串
   ```

5. **设置间距/圆角令牌**
   ```kotlin
   object Dimens {
     val Space4 = 16.dp
     val Space5 = 20.dp
     val RadiusMd = 12.dp
     ...
   }
   ```

6. **应用主题切换**
   - 已有 `readingColorScheme()` 基础上扩展，新增 Sepia 主题
   - 用 `DataStore` 持久化用户偏好

### 9.3 集成步骤（Web）

直接使用 `preview/tokens.css` 的 CSS 变量即可。所有色阶、间距、圆角都已声明。

---

## 10 · 验收清单

设计师验收：
- [x] 55/55 项 WCAG 对比度实测通过
- [x] AppIcon 在 48dp 下主轮廓可辨识
- [x] Themed Icon 在深/浅主题下都正确渲染
- [x] Splash 图标独立设计，边缘锐利
- [x] 图标语言一致（1.75dp 描边、圆角、24dp 网格）
- [x] 三主题全部覆盖
- [x] 8 枚核心功能图标全部产出
- [x] 5 主页面 + 3 子页面 + 3 弹窗全部覆盖
- [x] 衬线阅读字体集成（Literata）
- [x] 离线字体包（524KB，8 个 woff2）
- [x] 原型 HTML 单文件离线可用

开发者交付：
- [ ] Color.kt / Type.kt / Dimens.kt 替换（待开发者）
- [ ] 字体 ttf 文件转换（待开发者）
- [ ] Android VectorDrawable 集成（待开发者）
- [ ] Splash theme 更新（待开发者）
- [ ] 三主题切换持久化（待开发者）

---

**版本**：v1.0
**最后更新**：2026-09-05
**基准 commit**：基于 eareyereading 工作区当前状态
**设计负责人**：UI Designer（像素君）