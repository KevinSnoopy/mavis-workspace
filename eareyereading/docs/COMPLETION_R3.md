# 第三轮：功能补全与遗留清理

> 前两轮解决「体积」（超长文件/函数）与「重复」（跨文件复制）。
> 本轮解决**前两轮扫描器看不见的问题**：写了但没接上的功能、定义但没消费的代码。

---

## 一、修复：一个真实的功能断链

### 「✅ 启用内置 TTS」按钮点了没有任何反应

这是本轮最有价值的发现，且**不是重构问题，是线上 bug**。

**触发条件**：内置 TTS 模型已下载，但引擎尚未初始化（例如安装后重启、或引擎被释放过）。

**症状链路**：

| 环节 | 代码 | 结果 |
|---|---|---|
| 1 | `ReaderDialogs.kt` 判断 `prompt.embeddedModelDownloaded == true` | 显示「✅ 启用内置 TTS」按钮 |
| 2 | 点击 → `onAction(RetryWithEngine("__EMBEDDED__"))` | 构造了一个 action |
| 3 | `onTtsInstallAction` 的 `is RetryWithEngine -> {}` | **no-op，什么也没做** |
| 4 | `ReaderScreen` 关闭弹窗（因该 action 非 DownloadEmbeddedTts） | 弹窗消失 |

用户视角：点按钮 → 弹窗关闭 → 引擎仍未初始化 → 再点朗读 → 又是"未就绪"提示。**模型明明已经下载好了，却没有任何办法启用它**——而这个按钮就是为这个场景存在的唯一入口。

**根因**：`RetryWithEngine` 是系统 TTS 时代的动作，系统 TTS 下线时被归入"兼容 no-op"批次，但它的 UI 入口没有一起摘掉。批量把旧 action 置为 no-op 时，漏判了其中一个仍有生产者的。

**修复**：
- `TtsInstallAction` 收敛为 3 个成员：`DownloadEmbeddedTts` / `EnableEmbeddedTts` / `Dismiss`
- `RetryWithEngine("__EMBEDDED__")` → 语义明确的 `EnableEmbeddedTts`（同时消掉魔法字符串）
- 新实现走统一初始化入口 `initTtsEngine()`，失败复用 `handleTtsInitFailure` 给出可感知反馈

---

## 二、补全：三处"写了一半"的功能

### 1. 封面几何纹理层（15 个封面里 5 个图案从未被绘制）

封面背景库的设计是三类：`0-9` 纯色渐变、`10-12` 几何图案、`13-14` 装饰风格，`Color.kt` 里 `CoverPattern` 枚举与 `CoverPatterns` 映射表都写好了，注释也写明"图案层由 CoverPattern 实现端绘制"。

**但实现端从来没绘制过**——所有消费方都只取了 `CoverGradients` 的渐变底。后果：

- 索引 10-14 的封面与纯渐变封面**视觉上完全无法区分**
- 其中 **11 号底色是与自身同色的纯色**（`1A5276 → 1A5276`），补纹理前渲染出来是一个没有任何细节的纯色块

**修复**：新增 `CoverPatternPainter.kt`，按 `DrawScope` 实现 5 种纹理（8 等分竖/横线、6 等分错位点阵、45° 斜线、偏心径向高光），墨色统一用半透明白（与生成式封面母题同一约定：底色恒为深色渐变，白描边在任意调色板下都可读且不压过书名）。接入两处：

- `BookCover.PresetCover`（书架/详情页展示态；`compact` 小尺寸跳过纹理，避免糊成一团）
- 封面选择磁贴（见下条）

### 2. 新增书籍流程只能选到 6/15 个封面

`AddBookFlowSheet` 步骤 3 里写着 `val ids = (0..5).toList()`，注释自承"简化：仅显示前 6 个封面背景"。**用户在导入流程里选不到封面库后半部分的几何图案与装饰风格封面**。

同时这段是 `CoverPickerSheet.CoverOption` 的劣化复制（少了分段切换）。

**修复**：抽出共享的 `CoverPickerContent`（分段切换 + 3 列网格），两个入口复用同一份实现。至此两条路径都展示全部 15 个封面，选择行为一致，磁贴外观也统一（顺带修掉：原磁贴的 `Column` 只有 `fillMaxWidth`，内部 `weight(1f)` 撑不开，作者行不贴底）。

**附带修的可见缺陷**：分段切换的选中态原本是**白字 + 透明底**（注释写着"选中态背景通过外层 Box 处理"，但外层 Box 并不存在），在浅灰容器上几乎不可见。现补上主色实底。

### 3. 分类网格的「新建」卡片点了没反应

`AddBookFlowSheet` 步骤 2 的分类网格末尾有「新建分类」占位卡，`onAddClick = { /* v2 占位：可打开 CategoryEditSheet 新建后回填 */ }` 是空的。用户想在建书时新建分类，只能退出流程、去分类管理里建好、再重新走一遍导入。

**修复**：接入已有的 `CategoryEditSheet`，保存后把新分类插到本地列表末尾并**自动选中**；同时通过新增的 `onCreateCategory` 回调上抛给 `LibraryViewModel.saveCategoryMeta` 持久化。回调参数形态（`name` / `icon` / `color` long）刻意与既有的 `LibraryCategoryEditSheetWrapper` 保持一致。

---

## 三、清理：15 处 `@Suppress("unused")` 归零

这些标记是"删系统 TTS 时留下的兼容层"，全部**既无生产者也无读取者**：

| 位置 | 内容 |
|---|---|
| `TtsInstallAction` | 4 个从未被任何 UI 构造的子类（打开引擎设置 / 安装 Google TTS / 打开未知来源 / 安装第三方 TTS app） |
| `TtsInstallPrompt` | 10 个 `@Suppress("UNUSED_PARAMETER")` 历史字段（引擎列表、回退包名、幻影默认态、Google Play 可用性、安装引导步骤…）+ `DialogScenario` 枚举 |
| `TtsHelper` | 恒为 `EMBEDDED` 的 `ttsMode`、恒为 null 的 `lastFailureReason`、6 个不再被 set 的 `InitFailureReason` 枚举项、`TtsMode.SYSTEM` 常量、无调用方的 `initializeWith` |
| `ReaderDialogs` | `LocalContext` 取值 + `unusedCtx` 保留位 |

连带的连锁清理：`ttsModeState`（恒定单值 StateFlow，零订阅者）与整个 `TtsMode` 枚举随之失去消费者，一并移除。

**顺带修掉的调试损耗**：`ReaderViewModelBookLoader` 原先打印 `"TTS init failed silently on load: ${ttsHelper.lastFailureReason}"`，而该字段恒为 null —— 日志实际输出 `…: null`，排查时毫无信息量。改为记录本书语言。

**保留但未动的两处**（有意为之，非遗留）：`EmbeddedTtsEngine.resolveModelForLanguage(language)` / `modelForInitialize(language)` 的 `language` 参数确实不参与路由，但代码注释明确记录了 2026-09-04 双模型时代的设计决策（"用户意图 > 语言启发式"），且全部调用方都在传值——属于有意的接口形态，不属于遗忘的兼容层。

---

## 四、验证

| 项目 | 结果 |
|---|---|
| `compileDebugKotlin` | ✅ 通过 |
| `compileDebugUnitTestKotlin` | ✅ 通过 |
| 编译警告 | **3 → 0**（原 3 个均为本轮清理的死参数） |
| `detekt` | ✅ 0 problems |
| `testDebugUnitTest` | ✅ **11 套件 / 138 用例 / 0 失败 / 0 错误**（与基线一致） |
| 超 450 行文件 | 0 个（保持前两轮成果） |
| `@Suppress("unused")` | **15 → 0** |
| 零引用顶层符号复扫 | 0 个 |

---

## 五、改动清单

**新增 2 个文件**

- `ui/components/CoverPatternPainter.kt`（125 行）— 5 种封面纹理的 DrawScope 绘制器
- `ui/components/category/CoverPickerContent.kt`（121 行）— 分段 + 网格的共享封面选择内容

**修改 12 个文件**

| 文件 | 变更 |
|---|---|
| `ui/components/BookCover.kt` | 接入纹理层；删除未使用的 `coverWidth` 参数 |
| `ui/components/category/CoverTile.kt` | 新增 `CoverTilePreview`（渐变+纹理+文字+对勾 一整枚磁贴） |
| `ui/components/category/CoverPickerSheet.kt` | 210 → 95 行，改用共享内容组件 |
| `ui/components/category/AddBookFlowSheet.kt` | 封面选择补全至 15 个；接入新建分类闭环 |
| `ui/screens/reader/ReaderUiState.kt` | `TtsInstallPrompt` 13 → 3 字段；`TtsInstallAction` 7 → 3 成员 |
| `ui/screens/reader/ReaderViewModelTts.kt` | 实现 `EnableEmbeddedTts`；`speakOnDemand` 走统一初始化入口；删除冗余分支 |
| `ui/screens/reader/ReaderDialogs.kt` | 按钮指向新 action；删除保留位变量 |
| `ui/screens/reader/ReaderViewModelBookLoader.kt` | 修正无信息量的失败日志 |
| `util/TtsHelper.kt` | 移除 4 类遗留成员；校正过时注释（`edge` → `tencent`） |
| `ui/screens/library/LibraryScreen.kt` | 删除 2 个从未调用的导航参数 |
| `ui/screens/library/LibraryCategorySheets.kt` | 透传 `onCreateCategory` |
| `ui/Navigation.kt` | 移除对应死参数传参 |

**附带**：清掉 6 行因改动而失效的 import。

---

## 六、需要你确认的两点

1. **封面纹理的密度**：预览页（`.workbuddy/artifacts/cover-library-preview.html`）按 Kotlin 端几何参数逐条复刻，可确认观感。若要调整疏密，只改 `CoverPatternPainter.kt` 里各图案的 `step` 系数即可，两处消费方自动生效。**注意真机效果以 Compose 为准，预览页是等参复刻而非真实渲染。**

2. **新建分类采用「弹窗叠加弹窗」**：`CategoryEditSheet` 自身是一个 `ModalBottomSheet`，在导入流程的 sheet 之上再叠一层（M3 底层是独立 Dialog window，可正常交互与关闭）。若你更希望它内联为流程的第四步（不叠层），告诉我，改造量不大。
