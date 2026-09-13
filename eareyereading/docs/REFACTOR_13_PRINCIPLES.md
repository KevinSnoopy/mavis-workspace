# eareyereading — 基于 13 条软件设计原则的代码重构报告

> 重构时间：2026-09-12
> 基线：`b5e2ec6`（上一轮 SRP 拆分 23 个文件之后）
> 约束：**零业务逻辑变更**——只做职责移动与提取，所有对外 API 签名、执行顺序、副作用、并发语义保持逐行等价
> 验证：`compileDebugKotlin` + `detekt` + `testDebugUnitTest`（138 项）全绿

---

## TL;DR

| 指标 | 重构前 | 重构后 |
|------|--------|--------|
| **文件超过 450 行** | **10 个** | **0 个** |
| 最大生产代码文件 | 894 行 | 430 行 |
| 最大测试文件 | 555 行 | 403 行 |
| 主源码文件数 | 218 | 238（拆出的内聚模块） |
| ≥60 行的长函数 | 84 个 | 84 个（其中 6 个已从 110~257 行降到 40~150 行） |
| detekt issues | 0 | 0 |
| 单元测试 | 138 通过 | 138 通过（**数量一致，无测试丢失**） |

核心动作：把「一个文件/函数承担多种变化原因」的地方，按**变化原因**拆分。
所有拆分均通过 `internal` 可见性 + 薄委托保持原有调用点零改动。

---

## 一、13 条原则 ↔ 实际动作对照

| # | 原则 | 本次落地 |
|---|------|---------|
| 1 | **单一职责 SRP** | 全部 13 个超大文件按职责拆分（详见第二节） |
| 2 | **开闭原则 OCP** | `MlKitLanguageTags` 独立成文件：新增支持语言只改映射表，不动翻译调度；`TtsBlockMerger`/`PcmCache` 等策略件可独立演进 |
| 3 | **里氏替换 LSP** | 未新增继承层次，保持既有 sealed class（`EngineState`/`Progress`）语义不变 |
| 4 | **接口隔离 ISP** | `MlKitTranslatorPool` 只暴露 `translateDefault`/`translatePair`/`close` 等最小必要 API，不再把「配置读取」「熔断」「回退链」塞进同一个门面 |
| 5 | **依赖倒置 DIP** | `LlmTranslationGate` 通过 `SettingsRepository` 接口读取配置（未直接依赖 DataStore 实现） |
| 6 | **迪米特法则 LoD** | `TranslationHelper` 不再穿透操作 ML Kit 的 6 个内部字段，只与 `MlKitTranslatorPool` 单点交互 |
| 7 | **合成复用 CRP** | 拆出的 `TtsSpeakOrchestrator`/`TtsTarballExtractor` 以**扩展函数**复用 `EmbeddedTtsEngine` 的锁与状态，不复制一份状态（避免多份真相） |
| 8 | **高内聚低耦合** | 词表数据按字母区间分片，每片是自洽的数据单元；解析器与网络层彻底解耦 |
| 9 | **关注点分离 SoC** | `ArticleParser`（HTTP）/ `ArticleHtmlExtractor`（HTML→结构）/ `ArticleLinkExtractor`（链接提取）三分离 |
| 10 | **KISS** | `loadBook` 257 行 → 35 行编排 + 6 个阶段方法；`addBook` 166 行 → 40 行编排 + 7 个阶段方法 |
| 11 | **DRY** | ① `TtsHttp` 两处逐行重复的连接配置 → `configureDownloadConnection`；② ML Kit 回退链中两条完全相同的 `online → 词典` 分支合并；③ 各拆出文件的日志 TAG 统一引用 `EmbeddedTtsEngine.TAG`，不再复制字面量 |
| 12 | **YAGNI** | 删除无用变量（`unusedTotal`、`fileBytes`）；未引入任何为「将来可能」而生的抽象层 |
| 13 | **契约式设计** | 拆分时严格保留前置/后置条件：`ensureActive` 检查点、`speakMutex` 互斥范围、`finally` 释放路径、取消传播逐行未变 |

---

## 二、文件拆分明细

### 2.1 语音合成（TTS）

| 原文件 | 行数 | 拆出模块 | 结果 |
|--------|------|---------|------|
| `EmbeddedTtsEngine.kt` | 874 | `TtsSpeakOrchestrator.kt`（朗读执行链）<br>`EmbeddedTtsEngineLifecycle.kt`（初始化/预热/预合成） | **430** / 348 / 241 |
| `TtsModelDownloader.kt` | 477 | `TtsTarballExtractor.kt`（归档下载→解压→校验→清理） | **211** / 383 |
| `TtsHttp.kt` | 309 | 提取 `configureDownloadConnection`（消除 2 处 10 行重复） | 301 |

**关键约束保持**：`speakMutex` 串行化、`speakJobRegistry` 全量取消、
`warmUpCancelled` 标志、`synchronized` 双检、`handedOff` 连接交接——
全部逐行保留。`doSpeakQueueLocked`（230 行）拆为
`executeSpeakQueueLocked` + `synthesizeBlockIntoPlayer` + `launchWatermarkMonitor`。

### 2.2 翻译

| 原文件 | 行数 | 拆出模块 | 结果 |
|--------|------|---------|------|
| `TranslationHelper.kt` | 586 | `MlKitTranslatorPool.kt`（Translator 生命周期）<br>`LlmTranslationGate.kt`（AI 通道准入与熔断）<br>`MlKitLanguageTags.kt`（语言映射表） | **280** / 280 / 79 / 74 |

**回退链等价性**：原 `translateViaMlKit` 中「等待失败」与「翻译失败」两条分支
的兜底（`online → 本地词典`）完全相同，合并为一条链路，行为不变。
非默认语言对路径的 `isNullOrEmpty()` 判空语义**原样保留**（与默认路径的
`!= null` 判空有微妙差异，未擅自统一）。

### 2.3 书籍导入 / 文章解析 / 封面

| 原文件 | 行数 | 拆出模块 | 结果 |
|--------|------|---------|------|
| `ArticleParser.kt` | 453 | `ArticleHtmlExtractor.kt`（HTML→标题+正文）<br>`ArticleLinkExtractor.kt`（列表页→链接） | **208** / 232 / 79 |
| `BookCover.kt` | 464 | `BookCoverMotifs.kt`（DrawScope 手绘母题） | **249** / 233 |
| `BookImporter.kt` | 244 | `addBook` 166 行 → 7 个阶段方法 | 320（含说明，单函数降至 40 行） |

**测试兼容性**：`ArticleParser().extractArticle(...)` 与
`ArticleParser.isHtmlContentType(...)` 被 2 个测试类直接调用，
均保留为实例方法/伴生方法作薄委托，**测试零改动**。

### 2.4 阅读视图

| 原文件 | 行数 | 拆出模块 | 结果 |
|--------|------|---------|------|
| `PracticeReadingViews.kt` | 483 | `BackTranslationView.kt`<br>`DictationReadingView.kt` | **151** / 246 / 156 |
| `AssistedReadingViews.kt` | 466 | `SplitReadingView.kt`<br>`PosAnalysisView.kt` | **159** / 185 / 192 |
| `ReaderViewModelBookLoader.kt` | 306 | `loadBook` 257 行 → 阶段方法 | 402（单函数降至 35 行） |

### 2.5 词表数据

| 原文件 | 行数 | 分片 | 结果 |
|--------|------|------|------|
| `CollinsStar5Words.kt` | 894 | `Part1/2/3`（A–I / I–M / M–P 字母区间，各 1844 词） | **19** / 276×3 |
| `CollinsStar3Words.kt` | 587 | `Part1/2/3`（各 1409 词） | **19** / 214×3 |

主文件改为 `buildSet` 惰性合并。**词条内容与 Set 去重语义逐字不变**。

### 2.6 测试

| 原文件 | 行数 | 拆出 | 结果 |
|--------|------|------|------|
| `RssParserTest.kt` | 555 | `RssContentFilterTest.kt`（内容清洗与过滤主题） | **403** / 172 |

---

## 三、验证证据

```
./gradlew :app:detekt :app:testDebugUnitTest
→ BUILD SUCCESSFUL
→ detekt: 0 issues
→ testDebugUnitTest: 138 tests, 0 failures   （拆分前后数量一致）
```

每次拆分后均单独跑 `compileDebugKotlin` 复验（共 12 次），无一遗留编译错误。

**重建过程中的一次返工**：拆分 `PracticeReadingViews`/`AssistedReadingViews`
时按 `fun` 行号切分，漏掉了函数上方的 `@Composable` 注解行。已 `git checkout`
回滚后按含注解的边界重切，并用 grep 校验 4 个新文件均有 `@Composable` 配对。

---

## 四、有意保留、未做拆分的项（含理由）

| 对象 | 行数 | 不拆理由 |
|------|------|---------|
| `ReaderViewModel.translateAllParagraphs` | 226 | 内部是 5 个共享 10+ 个可变状态的闭包（`committed`/`held`/`doneIdx`/`flushedRange`…），构成一个**渐进式上屏调度算法**。强行拆成类需把可变状态跨对象共享，反而升高耦合，违反「高内聚」。且该文件带有未提交的在途改动，叠加改动会显著增加 review 负担 |
| `parseRssXml` | 170 | 单一 XML 事件状态机，`START_TAG`/`END_TAG`/`TEXT` 三个分支共享 8 个可变解析状态与局部 `applyField` 闭包。按事件拆分会让状态在函数间来回传递，可读性下降（KISS 权衡） |
| UI Composable 长函数<br>（`ReaderScreen` 255、`SettingsVoiceSection` 217、`HomeScreen` 195 等） | 60~255 | 声明式 UI 的长函数主要是**布局嵌套深度**而非逻辑分支；进一步拆分需要提取子组件并重新设计参数契约，属于 UI 结构调整而非纯重构，风险与收益不匹配。建议作为独立任务，配合视觉回归验证推进 |

**说明**：本报告范围是「不改变业务语义的结构重构」。上述三项若需继续，
建议各自作为独立批次，配合真机/截图回归验证。

---

## 五、改动文件清单

**修改（13 个，均为本次重构触及）**
`EmbeddedTtsEngine.kt` `TtsModelDownloader.kt` `TtsHttp.kt` `TranslationHelper.kt`
`ArticleParser.kt` `BookCover.kt` `BookImporter.kt` `CollinsStar3Words.kt`
`CollinsStar5Words.kt` `PracticeReadingViews.kt` `AssistedReadingViews.kt`
`ReaderViewModelBookLoader.kt` `RssParserTest.kt`

**新建（19 个）**
`TtsSpeakOrchestrator.kt` `EmbeddedTtsEngineLifecycle.kt` `TtsTarballExtractor.kt`
`MlKitTranslatorPool.kt` `LlmTranslationGate.kt` `MlKitLanguageTags.kt`
`ArticleHtmlExtractor.kt` `ArticleLinkExtractor.kt` `BookCoverMotifs.kt`
`BackTranslationView.kt` `DictationReadingView.kt` `SplitReadingView.kt` `PosAnalysisView.kt`
`CollinsStar3WordsPart{1,2,3}.kt` `CollinsStar5WordsPart{1,2,3}.kt` `RssContentFilterTest.kt`

> 注：工作区另有 8 个 reader 页面文件（`NormalReadingView` / `PagedReadingView` /
> `ReaderContentDispatcher` / `ReaderScreen` / `ReaderUiState` / `ReaderViewModel` /
> `ReaderViewModelTranslation` 等）的未提交改动**属于本次重构之前的工作内容**，
> 未被本次重构触碰，也未提交。这两部分改动叠加在同一工作区，提交时请分开处理。

---

## 六、建议的后续动作

1. **先提交本次重构**（与上述 8 个 reader 文件的在途改动分开 commit），
   便于出问题时单独 revert。
2. `ReaderViewModelTranslation.translateAllParagraphs` 的调度器提取，
   建议在阅读页在途改动落定后单独进行。
3. UI Composable 拆分建议作为独立批次，配合 Compose 预览/截图回归。
4. 可将「文件 ≤ 450 行、单函数 ≤ 60 行、单类 ≤ 400 行」写入 CI 门禁
   （用脚本扫描），防止体积再次膨胀。
