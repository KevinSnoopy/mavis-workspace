# UI 设计评审 & 功能引导缺口报告

评审对象：听阅 EareyeReading（`com.eareyereading`）
评审日期：2026-09-13
代码基线：`app/src/main/java/com/eareyereading/ui/**`（21,810 行 UI 代码）

---

## 一、结论速览

| 问题 | 结论 |
|------|------|
| UI 设计是否需要更改？ | **不需要推翻重做，需要精修。** 底层设计令牌（色彩/字体/间距/形状/明暗主题）已达成熟水准；问题集中在**信息架构密度**与**术语一致性**两处。 |
| 复杂功能是否缺少引导？ | **严重缺失，这是当前最大的体验短板。** 全工程 grep `Tooltip / CoachMark / Showcase / 帮助` 零命中；唯一的"引导页"实际上是通知权限申请页。9 种阅读模式中有 7 种进入后是死胡同。 |

---

## 二、UI 设计评估

### 2.1 已经做好、不需要动的部分（避免过度重构）

| 维度 | 现状 | 评价 |
|------|------|------|
| 设计令牌 | `theme/Spacing.kt` 9 级 4dp 基准；`theme/Shape.kt`；`theme/Type.kt` 双轨字体 | 规范完备，杜绝魔法数字，保持 |
| 字体 | UI 用 Inter，正文用 Literata（18sp / 30sp 行高 = 1.65） | 英语阅读场景的正确决策，不动 |
| 主题 | LIGHT / SEPIA / DARK 三态 + `readingColorScheme()` 让弹窗跟随纸面配色 | 做得比多数商业阅读 App 细，不动 |
| 深色对比度 | `readerAccentColor()` 专门解决深底上 Primary 仅 2.4:1 不可读的问题 | 有意为之，保留 |
| 触摸目标 | 44dp（`ReadingBottomBar.kt:59`） | 符合标准 |
| 无障碍 | TalkBack 语义、纯展示徽章不用可点击 Chip | 良好 |
| 自适应 | `Navigation.kt:143` 840dp 断点切 NavigationRail | 正确 |
| 动效 | 转场 lambda 提升为顶层常量、SharedAxis 风格 220/150ms | 正确且已优化 |
| M3 合规 | 底部导航、Icons 用法、ModalBottomSheet 规范 | 已从不规范自绘迁移过来 |

### 2.2 需要修改的部分

#### P0 — 影响可用性

**P0-1 「首启引导」名不副实**
- 证据：`MainActivity.kt:55-84` + `screens/onboarding/OnboardingScreen.kt`
- 现状：`FirstLaunchOnboarding` 是一个通知权限申请页（图标 + "开启通知，不错过每日复习" + 两个按钮），共 96 行。
- 问题：App 只有一次"教育窗口"，被用在了一个**权限申请**上。用户既没学到"点单词可以查词"，也不知道有 9 种阅读模式。
- 改法：拆成真正的 3 页 Onboarding（详见 §4.1），通知授权挪到第 3 页或延后到首次进入复习页时。

**P0-2 设置页信息架构过平**
- 证据：`SettingsScreen.kt:121-227`，单个 LazyColumn 平铺 9 个 item：
  `ProfileCard → 外观 → 阅读与词典 → AI 翻译 → 语音 → 通知 → 数据 → 危险区域 → 版本`
- 问题：
  - 无二级分组入口，用户要滚很久才能找到"TTS 模型下载"。
  - `SettingsAiTranslateSection` 把 `API Key / Base URL / Model` 三个技术字段直接暴露给普通用户（`SettingsScreen.kt:156-172`），无"这是什么"的解释。
  - `SettingsEmbeddedTtsManager` 337 行、`SettingsVoiceDialogs` 296 行，全部挂在同一层。
- 改法：收敛为 4 组二级页 —— 「阅读偏好」「语音朗读」「翻译」「数据与关于」；AI 翻译的高级字段折叠进「高级设置」开关之后。

**P0-3 阅读模式选择器信息量不足**
- 证据：`ReaderDialogs.kt:128-175`
- 现状：`LazyColumn` 平铺 `ReadingMode.entries`，每项 = 名称 + 一行 8~12 字副标题 + RadioButton。无分组、无图标、无示例、无适用场景。
- 问题：把「仿生阅读」「中译英回译」「成分分析」这类需要前置知识的模式，和「普通阅读」用同一种权重并列，用户没有任何判断依据。选错 = 进入一个完全看不懂的界面（见 §3）。
- 改法：
  1. 按意图分三组 —— **阅读**（普通/分栏）、**提速**（仿生/速读）、**训练**（挖空/模糊/听写/回译/成分）。
  2. 每项左侧加图示化图标，右侧加难度/耗时标签（如"约 3 分钟/段"）。
  3. 副标题改写为**动作导向**（见 §4.3 文案表）。

#### P1 — 影响观感与一致性

**P1-1 术语不统一（三处）**

| 位置 | 用词 A | 用词 B | 建议 |
|------|--------|--------|------|
| 底部导航 / 首页卡片 | `Navigation.kt:92` 「词汇」 | `HomeScreen.kt:137` 「生词本」 | 统一为「生词本」 |
| 模式名 / 设置项 | `Models.kt:87` 「仿生阅读」 | `ReaderSettingsDialog.kt:91` 「RSVP 速度」 | 模式名保留「仿生阅读」，参数区加注 "RSVP" 并提供说明 |
| 模式名 / 实现 | `Models.kt:94` 「成分分析」 | `PosAnalysisView.kt:24` 注释「词性着色」 | 统一为「词性分析」 |

**P1-2 空状态三套视觉**
- `ReviewStates.kt:20` `EmptyReviewView`（手写 Column，80dp 图标）
- `HomeScreen.kt:242` `EmptyReadingGuide`（Card + 圆形 Surface 底 + 28dp 图标 + OutlinedButton）
- `ReadingHeatmap.kt:154` `EmptyState`（通用组件，84dp 圆形底 + 38dp 图标，被 Library 使用）
- 改法：统一走 `EmptyState` 组件，扩展 `actionLabel` / `onAction` 两个参数，删掉另外两套手写实现。

**P1-3 快速阅读与仿生阅读的空态行为不一致**
- `AssistedReadingViews.kt:78`（RSVP）有 `"点击播放按钮开始"` 提示
- `AssistedReadingViews.kt:141-148`（Speed）未播放时直接 `paragraph.take(80)` 截断，无任何提示
- 改法：统一空态提示组件。

**P1-4 书库页顶部单行 4 个动作**
- `LibraryBookTabContent.kt:67-80` 把「分类 chips」「视图切换」挤在一行；搜索、导入另有入口。
- 改法：视图切换收进溢出菜单，把空间让给分类 chips。

#### P2 — 可选优化

- `ReadingBottomBar.kt:45-101` 底栏快捷设置行有 4 个控件（字号−/值/字号+/主题/衬线），信息密度偏高，衬线切换可下沉到设置弹窗。
- 动态取色（`Theme.kt:122`）与阅读页 SEPIA/DARK 存在耦合推导（`readingColorScheme`），当系统深色 + 用户选护眼时会得到 DARK。行为正确，但建议在设置页给一句说明。

---

## 三、引导缺口清单（逐功能）

> 判定标准：**「有没有告诉用户这个功能是干什么的、以及第一步按哪里」**

| # | 功能 | 复杂度 | 现有引导 | 缺口 |
|---|------|--------|----------|------|
| 1 | 导入 EPUB / TXT | 中 | 首启空状态 CTA（`HomeScreen.kt:242`）、`EmptyState` 副文案 | 支持的格式与大小限制、导入失败原因无说明 |
| 2 | URL 导入文章 | 高 | `LibraryUrlImportDialog.kt` 一个输入框 | 支持哪些站点、抓取失败怎么办，零说明 |
| 3 | 全文翻译 | 中 | 顶栏一个图标 | **首次整本翻译耗时很长且"整本译完才上屏"**（`ReaderUiState.kt:98-104` 有 `translationDone/Total` 字段，但进度只在部分界面呈现）。用户极易判定为卡死 |
| 4 | 内置 TTS 模型下载 | 高 | 阅读页弹窗 + 进度 + 阶段文案（`ReaderUiState.kt:126-129`） | **这个功能本身做得很好**，但入口藏在触发朗读时才出现，用户在设置页找不到模型大小/耗时预期 |
| 5 | 仿生阅读（RSVP） | 高 | `ReaderDialogs.kt:167` 一行副标题 | "RSVP 是什么"无解释；`rsvpStrength` 30%~70% 五档无推荐值 |
| 6 | 快速阅读 | 中 | 无 | 未播放态显示截断正文，零提示（对比 #5 有提示，不一致） |
| 7 | 挖空练习 | 高 | `PracticeReadingViews.kt:84-93` "显示答案（剩 N 空）" | 空格本身可点揭示（`:60` `.clickable { onReveal() }`）但无文字提示 |
| 8 | 模糊听读 | 高 | **无** | `PracticeReadingViews.kt:115-138` 全屏模糊文字，无标题、无按钮、无提示。用户会以为渲染坏了 |
| 9 | 听写练习 | 高 | `DictationReadingView.kt:46-72` 完整引导（图标+标题+说明+CTA） | **无缺口 —— 应作为全 App 的引导范本** |
| 10 | 分栏对照 | 中 | `ReaderDialogs.kt:172` 一行副标题；**进入时已自动触发生成全书译文**（`ReaderViewModelNavigation.kt:35-37`），并有翻译进度浮标 | 无"这个模式是什么"的说明；译文准备期间用户不知道要等多久。⚠️ 初版报告误判为"未开翻译时只有原文"，实际代码已处理，此处更正 |
| 11 | 中译英回译 | 高 | `ReaderDialogs.kt:173` 一行副标题；同 #10 自动准备译文 | 缺"看译文 → 自己译 → 对照原文"的流程说明 |
| 12 | 成分分析 | 中 | `PosAnalysisView.kt` 底部图例 | 图例在 `LazyColumn` **末尾**，首屏完全看不到颜色含义；且标签是颜色名（"青灰/赤褐/暖金/暖棕"）对用户无信息量 |
| 13 | Collins 词频色彩 | 中 | `ReaderSettingsDialog.kt:139-167` 五档 chip 图例 | 图例只在设置弹窗内，且需先打开开关才显示；溢出菜单里的开关（`ReaderTopBar.kt:151-162`）无任何解释 |
| 14 | 生词本 → SM-2 复习 | 高 | 复习空状态（`ReviewStates.kt:20`）"去阅读攒生词" | "生词从哪来 → 什么时候回来复习"的闭环链路无说明 |
| 15 | AI 翻译（LLM） | 高 | `SettingsAiTranslateSection` | 需要自备 API Key，无获取指引、无成本/能力预期、无失败排查 |
| 16 | 词典管理 | 中 | 二级页 `DictionaryManagerScreen` | 无"为什么要导入外部词典"、支持哪些格式 |
| 17 | 数据导出 / 导入 | 中 | 设置分区按钮 | 无 JSON 格式说明、无覆盖风险提示（危险区域有，但两者关系未说明） |
| 18 | 手势操作 | 高 | **无** | ① 点正文空白切换工具栏（`ReaderScreen.kt:180-181`）② 双击句子（`ReaderTapText.kt:80`）③ 点单词查义 ④ 翻页模式左右翻 —— 全部零提示 |
| 19 | **用户高亮（文本高亮）** | 高 | **无 —— 功能整体不可达** | 见 §7。存 / 读 / 渲染三段都完整，唯独缺创建入口 |

**缺口总计：19 项功能中 16 项缺引导，其中 6 项属于「进入后完全不知道下一步做什么」（#3 / #6 / #8 / #10 / #11 / #19）。**

---

## 四、落地方案

### 4.1 L1 — 首启引导（替换现有通知页）

改 `screens/onboarding/OnboardingScreen.kt` 为 `HorizontalPager` 三页，第 3 页或退出时再申请通知权限：

| 页 | 标题 | 内容 | 图示 |
|----|------|------|------|
| 1 | 边读边攒，越读越顺 | 导入一本书 → 点任一单词看释义 → 生词自动进生词本 → 到点回来复习 | 四步流程图 |
| 2 | 九种读法，随需切换 | 三组模式卡片预览（阅读 / 提速 / 训练），每张卡一句"适合什么时候用" | 模式卡横滑 |
| 3 | 提醒你回来 | 通知授权（复用现有实现） | 复用现有图标 |

- 存储：沿用现有 `has_seen_onboarding`（`MainActivity.kt:58`），无需引入新机制。
- 每页可跳过；右上角"跳过"直接进主页。

### 4.2 L2 — 场景首次引导（CoachMark）

新增 `ui/components/CoachMark.kt`，一次性浮层，状态存 DataStore（`Map<String, Boolean>`）：

```kotlin
@Composable
fun CoachMark(
    id: String,                    // "mode_fuzzy" / "mode_rsvp" ...
    title: String,
    message: String,
    onDismiss: () -> Unit,
    target: @Composable () -> Unit, // 高亮的目标（用半透明遮罩挖洞）
)
```

优先覆盖 5 个"死胡同"模式：

| 触发点 | 提示文案 |
|--------|----------|
| 首次进入模糊听读 | 「文字被故意模糊了 —— 按播放，用耳朵补全它」+ 指向顶栏播放键 |
| 首次进入仿生阅读 | 「加粗的前半段是视线锚点，跟着它读会更快。可在设置里调速度与强度」 |
| 首次进入快速阅读 | 「按播放，句子会逐句闪现。已读的会变淡」 |
| 首次进入分栏对照 / 中译英回译（未开翻译时） | 「这两个模式需要译文，已为你打开翻译」+ 自动开启 `showTranslation` |
| 首次进入书库 | 「可以导入 EPUB/TXT，也可以粘贴网址抓一篇文章」 |

### 4.3 L3 — 常驻轻提示 + 帮助入口

1. **模式页顶部一行 hint**：在 `ReaderContentDispatcher` 每个分支顶部插入可关闭的 `ModeHintBar`，文案与 `getModeDescription` 统一为动作导向：
   - 挖空：「点击 ____ 可揭晓该空」—— 补上 `PracticeReadingViews.kt:60` 已实现但无提示的交互
   - 成分分析：把图例从列表末尾（`PosAnalysisView.kt:110`）提到顶部或做成吸顶条
   - 听写：保持现状（已经是范本）
2. **顶栏加问号入口**：`ReaderTopBar.kt` 溢出菜单加"当前模式说明"，点开 `ModalBottomSheet` 展示：这个模式是什么 / 第一步做什么 / 关键参数怎么调 / 什么场景用。
3. **术语解释**：凡出现 RSVP、SM-2、LLM 等术语的地方，加 `HelpIcon`，点击弹一行白话解释。

### 4.4 顺带修的硬问题（低风险、高收益）

| 问题 | 文件 | 改动 |
|------|------|------|
| Speed 未播放态无提示 | `AssistedReadingViews.kt:141-148` | 与 RSVP 统一提示组件 |
| 模式选择器分组 | `ReaderDialogs.kt:144-161` | 按 3 组加 `stickyHeader` + 图标 |
| 空状态统一 | `ReviewStates.kt:20` / `HomeScreen.kt:242` | 收敛到 `EmptyState` 组件 |
| 术语统一 | `Navigation.kt:92` 等 | 「词汇」→「生词本」等 3 处 |

---

## 五、实施顺序建议

| 阶段 | 内容 | 风险 |
|------|------|------|
| 1 | §4.4 四项低风险修复（术语、空状态、Speed 提示、图例位置） | 极低，纯 UI 文案与布局 |
| 2 | §4.1 三页首启引导 | 低，替换单文件 |
| 3 | §4.3 顶栏问号 + 模式说明弹窗 | 低，纯新增 |
| 4 | §4.2 CoachMark 组件 + 5 个模式接入 | 中，需 DataStore 新键、需处理遮罩与触摸穿透 |
| 5 | §2.2 P0-2 设置页二级分组 | 中，涉及页面结构调整 |

**验收标准**：新用户不看任何外部说明，5 分钟内能独立完成「导入一本书 → 点词查义 → 切换一个训练模式 → 完成一次复习」全链路。

---

## 附：本次评审未覆盖

- 真机视觉走查（本次为静态代码评审，未在设备上截图比对）
- 动效与转场的实际时序手感
- 无障碍 TalkBack 全流程实测
- 大字号 / 横屏 / 折叠屏的极端布局

---

## 六、实施记录

### 2026-09-13 —— 「用户拿不到 Key」问题（对应 §3 表格 #15、#4）

**问题定性**：App 的翻译与朗读**本身都不需要 Key** ——
翻译有内置离线链（ML Kit 端侧 → 在线端点 → 本地词典），
朗读有内置 sherpa-onnx 离线模型。AI 翻译与腾讯云 TTS 都只是**可选增强**。
但设置页把「服务商 / API Key / 模型 / 接口地址」四个技术字段与
「腾讯云凭证 / 音色」平铺在主路径上，用户的第一反应是"这功能要申请密钥"，
于是直接放弃 —— 一个可选增强项被摆成了必填门槛。

**改动**

| 文件 | 改动 |
|------|------|
| `settings/KeyGuideSheet.kt`（新增） | 可复用的取 Key 分步引导抽屉：[KeyGuide] 描述内容，编号圆点 + 标题 + 说明，一键 `ACTION_VIEW` 跳转控制台。内置 [GlmKeyGuide]（智谱，4 步）与 [TencentKeyGuide]（腾讯云，5 步，含 SecretKey 安全提示） |
| `settings/SettingsAiTranslateSection.kt` | ① 顶部新增「不配置也能全文翻译」说明条；② 开关改名「AI 增强翻译」并写明"不开也照样能全文翻译"；③ 新增「如何获取免费的 API Key」入口行；④ **「模型」「接口地址」仅在服务商选「自定义」时显示**，走预设的用户不再看到这两个字段；⑤ 未配 Key 就打开开关时直接送进引导，而不是弹空白输入框 |
| `settings/LlmSettingsDialogs.kt` | `LlmTextFieldDialog` 增加 `onOpenGuide`；Key 输入框就地清洗（trim + 去 `key=` 前缀）—— 从网页整段复制带上不可见字符会一直 401 |
| `settings/SettingsVoiceSection.kt` | 新增「如何获取腾讯云凭证」入口行；离线引擎副标题补"无需任何账号"；凭证行副标题明确"需要一对 SecretId / SecretKey" |
| `settings/SettingsVoiceDialogs.kt` | `TencentCredentialDialog` 增加 `onOpenGuide`；两个输入框就地 trim；`TtsEngineTypeDialog` 把离线标为「推荐 · 无需账号」，腾讯云标为「进阶 · 需自备腾讯云账号与密钥」 |

**真机验证（Redmi 2312CRAD3C / Android 16）**

- `./gradlew installDebug detekt` EXIT=0，全量 logcat 无异常
- 设置页 AI 翻译区显示说明条，「模型 / 接口地址」两行已按预期隐藏
- 智谱引导抽屉：一键跳转，浏览器实际打开 open.bigmodel.cn 登录页 ✅
- 腾讯云引导抽屉：5 步 + 安全提示完整可见 ✅
- 弹窗内「不知道去哪拿？看获取步骤」可正确打开抽屉 ✅
  （首版两个 Modal 叠加导致抽屉被 AlertDialog 压住，已改为先关弹窗再开抽屉）

**遗留**：DeepSeek 尚未提供引导（付费通道，优先级低）；
若后续要彻底免除 Key，「托管代理」需要后端支持，本 App 目前无服务端。


### 2026-09-13（第二批）—— §4.4 低风险修复 + 死代码清理

**术语统一**

| 位置 | 原 | 现 |
|------|----|----|
| `ui/Navigation.kt:92` 底部导航 | 词汇 | **生词本** |
| `VocabularyScreen.kt:36` 页面顶栏 | 词汇本 | **生词本** |

同一个页面此前三个名字（导航「词汇」、顶栏「词汇本」、首页统计卡「生词本」），
统一为「生词本」——它是用户实际产生的东西，也与复习页「去阅读攒生词」对上。

**空状态收敛到 `EmptyState`**

- 新增 `ui/components/EmptyState.kt`（从 `ReadingHeatmap.kt` 迁出，SRP：
  它和热力图毫无关系），扩展三个能力：
  `tint`（保留语义色，复习完成用 Success、失败用 Error）、
  `actionLabel` / `actionIcon` / `onAction`（空状态必须给去路）、
  标题与副文案 `textAlign = Center`（多行副文案此前内部左对齐）
- `ReviewStates.kt`：`EmptyReviewView` / `ErrorReviewView` 改为复用；
  顺带补上闭环说明——副文案由「今日复习已完成」改为
  「生词来自阅读时点开的词。按遗忘曲线排期，到点会自动回到这里」，
  此前用户看不出这个页面是"完成了"还是"坏了"
- `HomeScreen.kt`：`EmptyReadingGuide` 内部改为复用，删掉第三套手绘空状态

**阅读模式内的提示补齐**

| 模式 | 问题 | 修复 |
|------|------|------|
| 快速阅读 | `AssistedReadingViews.kt` 未播放态只显示截断正文，零提示；隔壁 RSVP 却有「点击播放按钮开始」 | 正文预览压淡 + 「点击上方 ▶ 开始逐句闪现」 |
| 挖空练习 | 空位实现了「点击单独揭示」，但无任何文字说明 | 顶部提示「点 ____ 可单独揭示该空，或用下方按钮一次全揭」 |
| 成分分析 | 图例挂在 `LazyColumn` 末尾，滑到全书结尾才看得到；标签是颜色名 | 图例提到 `Column` 顶部**常驻**；标签只留词性名（名词/动词/形容词/副词）；文字色跟随阅读主题 |

**新增「当前模式怎么用」入口**

- `ReaderDialogs.kt` 新增 `ModeHelp` / `modeHelpOf()` / `ModeHelpSheet`：
  9 个模式各补齐四段——**是什么 / 怎么用 / 小技巧 / 什么时候用**。
  内容按"用户会卡在哪"写，例如仿生阅读给推荐起步值（300 字/分钟、强度 3）
  并说明"读不下去就降速，速度是练出来的"；成分分析明说词性是后缀规则推测的、
  生僻词可能标错，别当语法工具用
- 顶栏溢出菜单**首项**即「这个模式怎么用」（`ReaderTopBar.kt`），
  不再排在第五位——用户看不懂时第一反应就是打开这个菜单
- 接线遵循既有 `uiState` 驱动模式：`ReaderUiState.showModeHelp` +
  `ReaderViewModelSettings.toggleModeHelp()`；与模式选择器**互斥**，
  不会两个抽屉叠着
- 抽屉底部有「换一个模式」出口，看完说明可直接跳转

**死代码清理（编译警告驱动）**

以 `compileDebugKotlin` 警告为准，清掉 5 个从未被使用的参数：

| 文件 | 参数 |
|------|------|
| `ReadingBottomBar.kt` | `textColor`（连带移除 `Color` 导入） |
| `ReaderParagraphBlock.kt` | `index` |
| `AssistedReadingViews.kt` | `RsvpReadingView.isPlaying` |
| `PracticeReadingViews.kt` | `ClozeReadingView.answer` |
| `DictationReadingView.kt` | `paragraph` |

**验证**：`./gradlew installDebug detekt` EXIT=0；
`compileDebugKotlin` 警告从 18 条降到 3 条（剩余为 tts/util 层与两处
`ExperimentalCoroutinesApi` opt-in，属既有问题）。
真机核对：底部导航与页面顶栏均显示「生词本」✅。
**其余改动未能真机核对 —— 测试机在构建期间锁屏，`mCurrentFocus` 停在
`NotificationShade`、`screencap` 全黑，无法唤醒。** 待设备解锁后需补验：
复习空状态、模式说明抽屉、快速阅读/挖空/成分分析三处提示。

---

## 七、意外发现：用户高亮功能整体不可达（未被要求，但需决策）

在按编译警告做死代码清理时，追查到一处**功能断链**，不是引导问题，是功能缺失。

### 证据链

| 环节 | 状态 | 位置 |
|------|------|------|
| 数据表 / DAO | ✅ 完整 | `data/local/dao/HighlightDao`、`HighlightEntity` |
| 写入 | ✅ 已实现，含失败 toast | `ReaderViewModelBookmarks.kt:60 addHighlight` / `:89 removeHighlight` |
| 读取 | ✅ 已订阅并映射进 UI 状态 | `ReaderViewModelBookLoader.kt:364 collectHighlights` → `uiState.highlights` |
| 渲染 | ✅ 已实现，含滚动 / 翻页两条路径与切片坐标平移 | `ReaderAnnotatedText.kt:101-132`、`ReaderSliceParagraph.kt:53-60` |
| **创建入口** | ❌ **断在这里** | `ReaderContentDispatcher.kt:95-98` 把 `onAddHighlight` / `onRemoveHighlight` 传给 `NormalReadingView`，**它从未转发给 `ReaderParagraphBlock`**；而 `TappableParagraphText`（`ReaderTapText.kt`）只注册了 `onTap` 与 `onDoubleTap`，没有长按或选区 |

### 结论

**用户无法创建任何一条高亮。** 存、读、渲染三段都写好了，回调却停在
`NormalReadingView` 的参数列表里，且编译器一直在报
`Parameter 'onAddHighlight' is never used` —— 两处警告是这个断链的唯一提示。

### 建议

修它需要新增交互，涉及阅读器手势层（该层历史上出过多次误触问题），
因此**没有在本次一并改**：这次改动的其余部分都可在构建期验证，
而手势改动必须真机核对。两个未使用的参数**刻意保留**，并在
`NormalReadingView.kt` 原位加了注释说明，作为这个缺口的路标 ——
删掉只会让问题更隐蔽。

待定方案（择一）：

- **A（推荐）**：`TappableParagraphText` 增长按 → 弹出「本句 / 本词」选择 +
  4 色色板 → `addHighlight`。与现有单击查词、双击翻译的手势不冲突
- **B**：从单词详情抽屉里加「高亮这个词」按钮。手势风险最低，但只能高亮单词，
  无句高亮
- **C**：确认这个功能不做了 → 连同 `HighlightData`、渲染分支、DAO 一起删干净，
  而不是留一条永远走不通的半成品链路

### 2026-09-13（第三批）—— 上批遗留项真机补验 + L1 首启引导

**上批遗留项已全部真机核对通过**（设备解锁后）：

| 项 | 结果 |
|----|------|
| 复习空状态（新 `EmptyState` + 闭环副文案） | ✅ 渲染正确；发现副文案几乎顶到屏幕两侧 → 补 `horizontal = 32.dp` |
| 模式说明抽屉（普通阅读） | ✅ 四段齐全；发现底部按钮被压到屏幕边缘 → 改 `skipPartiallyExpanded = true` |
| 成分分析图例位置 | ✅ 顶部常驻；顶栏显示时会遮住它（见下方取舍说明） |
| 挖空练习提示 | ✅ 「点 ____ 可单独揭示该空…」 |
| 快速阅读未播放态提示 | ✅ 正文预览压淡 + 「点击上方 ▶ 开始逐句闪现」 |
| 顶栏溢出菜单首项 | ✅ 「这个模式怎么用」已排第一 |

**成分分析图例的取舍**：阅读页顶栏是**叠加层**（沉浸态设计、自动收起），
所以顶栏显示时图例会被遮住。可接受 —— 顶栏显示意味着用户正在操作工具栏
而不是在认颜色；顶栏收起后图例就是常驻的。已给图例行加了表面底衬，
正文滚过时色点不会被文字压花，并在代码注释里写明这个取舍。

**L1 首启引导：通知权限页 → 三页 HorizontalPager**

旧实现叫 `FirstLaunchOnboarding`，实际只是个通知权限申请页（96 行）——
App 唯一一次教育用户的窗口，被花在了一件用户本来就会在设置里做的事上。

| 页 | 内容 | 讲什么 |
|----|------|--------|
| 1 边读边攒，越读越顺 | 4 步编号：导入一本书 → 点任意单词 → 生词自动排队 → 到点回来巩固 | 核心闭环：生词是读出来的，不是抄出来的 |
| 2 九种读法，随需切换 | 三张分组卡：阅读 / 提速 / 训练，各一句「适合什么时候用」 | 模式速览；底部提示「书内点顶栏的书本图标即可切换」 |
| 3 开启通知，不错过每日复习 | 复用原有权限申请逻辑 | 主按钮文案改为「开启通知，开始使用」 |

- 完成标记沿用 `has_seen_onboarding`（`app_prefs.xml`），MainActivity 无需改动
- 已授权通知时第 3 页主按钮直接完成，不重复弹框
- 每页可跳过；页指示点当前页放大

**验证**：清掉 `has_seen_onboarding` 后真机走查三页 ✅，
页点、跳过、完成标记回写均正常；logcat 无异常。截图见
`build/verify/onboarding_3pages.png`。验证后标记已恢复为 true。

**剩余待办**（按 §5 顺序）：L2 CoachMark（5 个死胡同模式）→
§2.2 P0-2 设置页二级分组 → 高亮断链修复（等方案确认）。
