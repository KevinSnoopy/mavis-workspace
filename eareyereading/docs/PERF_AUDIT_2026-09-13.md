# 全项目性能与死代码审计报告

日期：2026-09-13
范围：`app/src/main/java`（243 个 Kotlin 文件，38,034 行）+ 构建配置
验证：`./gradlew compileDebugKotlin detekt` → EXIT=0（detekt 门禁 0 findings）
真机：Redmi 2312CRAD3C / Android 16，`installDebug` 成功并完成冒烟

---

## 零、变更总览

| # | 文件 | 类型 | 收益 |
|---|---|---|---|
| 1 | `ui/components/category/CategoryEditSheet.kt` | 性能 | 消除每次重组的集合分配；列表补 key |
| 2 | `ui/screens/reader/SplitReadingView.kt` | 性能 | 分栏 item 少一次 @Composable 样式构建 + 记忆化 AnnotatedString |
| 3 | `ui/theme/Color.kt` | 死代码 | 删除 3 个零引用废弃别名 |
| 4 | `app/build.gradle.kts` | 性能（核心） | 开启 Compose 强跳过：不可跳过组件 **53 → 8** |

---

## 一、核心改动：开启 Compose 强跳过模式（收益最大）

### 编译器实测证据（非推测）

先用编译器报告确认问题存在：

```
restartable fun ReaderParagraphBlock(          ← 只有 restartable，没有 skippable
  stable   para: String
  unstable paraHighlights: List<HighlightData>   ← 4 个不稳定参数
  unstable knownWords: Set<String>
  unstable learnedWords: Set<String>
  unstable currentSentences: List<String>
  ...
)
```

同一份报告里 `ReaderSliceParagraphBlock` / `NormalReadingView` / `SplitReadingView` /
`PagedReadingView` / `ReaderContentDispatcher` / `ReadingBottomBar` 全部同样**不可跳过**。
后果：自动朗读（每句 `currentSentenceIndex` 变化）、全文翻译（译文 Map 持续增长）、
模型下载进度回调，都会让视口内**所有段落一起重组**。

### 改动

`app/build.gradle.kts`：

```kotlin
kotlinOptions {
    jvmTarget = "17"
    freeCompilerArgs += listOf(
        "-P",
        "plugin:androidx.compose.compiler.plugins.kotlin:experimentalStrongSkipping=true",
    )
    // 报告输出到 app/build/compose-reports/，供后续审计复用
    freeCompilerArgs += listOf(
        "-P",
        "plugin:androidx.compose.compiler.plugins.kotlin:reportsDestination=" +
            project.layout.buildDirectory.dir("compose-reports").get().asFile.absolutePath,
    )
}
```

> 开关名不是猜的：从 `compiler-1.5.5.jar` 的 `ComposeCommandLineProcessor` 中提取到
> 字面量 `experimentalStrongSkipping`（描述 "Enable experimental strong skipping mode"）。
> 1.5.5 上强跳过仍是实验特性、默认关闭，故必须显式打开。

### 效果（同一份报告前后对比）

| 指标 | 开启前 | 开启后 |
|---|---|---|
| 不可跳过组件 | 53 / 169 | **8 / 169** |
| `ReaderParagraphBlock` | NOSKIP | **SKIP** |
| `ReaderSliceParagraphBlock` | NOSKIP | **SKIP** |
| `NormalReadingView` | NOSKIP | **SKIP** |
| `SplitReadingView` | NOSKIP | **SKIP** |
| `PagedReadingView` | NOSKIP | **SKIP** |
| `ReaderContentDispatcher` | NOSKIP | **SKIP** |
| `ReadingBottomBar` | NOSKIP | **SKIP** |

剩余 8 个不可跳过项全部是返回 `Modifier` / `TextStyle` / 状态对象的工厂函数
（`shimmer`、`readerParagraphStyle`、`rememberReaderChromeController` 等），
本就不该是 skippable，无需处理。

### 安全性前提（已逐条核实）

强跳过把不稳定参数的判定从"直接不跳过"改为"实例相等（`===`）即跳过"，
**若状态对象被就地修改就会漏刷 UI**。核实结果：全工程状态更新一律走
`_uiState.update { it.copy(...) }` + 新建集合（`sentences` / `known` / `allWords` / `grouped`），
无 `.add()` / `.put()` / `.remove()` 就地变更，前提成立。

---

## 二、真机验证结果（Redmi 2312CRAD3C / Android 16）

安装：`JAVA_HOME=<jbr-17> ./gradlew installDebug` → `app-arm64-v8a-debug.apk` 安装成功
（注：用 Android Studio 自带的 JBR 21 会卡在 `JdkImageTransform` 的 jlink 失败，
改用 mise 钉的 JDK 17 即通过。）

| 验证项 | 结果 | 依据 |
|---|---|---|
| 冷启动 / 首页渲染 | ✅ | 统计卡、热力图、最近阅读均正常 |
| **自动连播（核心：验证重组未被破坏）** | ✅ | 高亮从第 1 段推进到第 2 段，进度 1/25 → 2/25，旧段落转灰 |
| 翻译开关（译文 Map 变化） | ✅ | 双向切换：译文隐藏 → 显示，均立即生效 |
| **分栏对照（SplitReadingView 改动点）** | ✅ | 左英右中逐段对齐渲染正常，底栏「分栏对照 · 4/25」 |
| 单段朗读 / 连播互切（历史冲突点） | ✅ | 无重复音轨、状态一致、无异常 |
| **分类编辑弹窗（CategoryEditSheet 改动点）** | ✅ | 12 图标 / 10 颜色网格正常，切换图标+颜色后预览同步更新 |
| 应用异常日志 | ✅ 0 条 | 全量 logcat 过滤 AndroidRuntime / FATAL / Exception 无本应用记录 |

帧统计（`dumpsys gfxinfo`，覆盖启动 + 朗读 + 模式切换全过程）：

```
Total frames rendered: 9114
Janky frames: 57 (0.63%)        ← 50th 12ms / 90th 20ms / 99th 81ms
GPU 50th: 6ms
```

---

## 三、其余已修复（3 项，均已编译验证）

### 1. `CategoryEditSheet.kt` — 每次重组重新分配图标列表

```kotlin
// 修复前：每次重组都执行 values()（分配数组）+ toList()（分配 ArrayList）
items(CategoryIcon.values().toList()) { ic -> ... }

// 修复后：enum entries 提升为顶层常量，只算一次
private val CategoryIconList: List<CategoryIcon> = CategoryIcon.entries
items(CategoryIconList, key = { it.name }) { ic -> ... }
```

同一文件的两个 `LazyVerticalGrid` 补上 key（图标按 name、颜色按 value），
避免选中态切换时 item 复用错位、以及无谓的重组。

影响：分类编辑弹窗（每次输入名称都触发重组）从「每次分配 2 个集合」降为 0 分配。

### 2. `SplitReadingView.kt` — 单 item 内重复调用 @Composable 样式构建 + 重复分配 AnnotatedString

```kotlin
// 修复前：readerParagraphStyle 被左右两列各调用一次；AnnotatedString 每次重组新建
TappableParagraphText(text = AnnotatedString(para), ..., style = readerParagraphStyle(fontSize).copy(...))
Text(..., style = readerParagraphStyle(fontSize).copy(...))

// 修复后：主题样式取一次，派生两份；AnnotatedString 记忆化
val annotatedPara = remember(para) { AnnotatedString(para) }
val baseStyle = readerParagraphStyle(fontSize)
val paraStyle = baseStyle.copy(color = textColor.copy(alpha = alpha))
val translationStyle = baseStyle.copy(color = if (translation != null) accent.copy(...) else textColor.copy(...))
```

注意点（已踩坑）：`readerParagraphStyle` 是 `@Composable`，放进 `remember {}` 计算块会编译失败
（`Composable calls are not allowed inside the calculation parameter`）。
正确写法是在组合中调用一次、再 `copy()` 派生。

影响：分栏（SPLIT）对照阅读是逐段 × 双列的渲染路径，每屏可省下 2N 次样式构建 + N 次
AnnotatedString 分配。

### 3. `Color.kt` — 删除 3 个零引用废弃别名

`PrimaryVariant` / `SecondaryVariant` / `SurfaceVariant` 三个 `@Deprecated` 兼容别名，
全仓（含测试）引用数 = 0，属系统 TTS 时代遗留的"兼容层"，已删除。
`PrimaryDark` / `Error` / `SurfaceSecondary` 本体保留不动。

---

## 四、已核查，无需改动（避免后续重复排查）

| 项目 | 结论 | 证据 |
|---|---|---|
| Room 索引 | 完备 | 11 张表共 16 个索引，覆盖 bookId/paragraphIndex/langPair/isLearned/nextReviewDate 等全部热查路径 |
| 主线程阻塞 | 无 | 无 `GlobalScope`、无 `Thread.sleep`；唯一的 `runBlocking` 在 `ReaderViewModel.onCleared` 的 `finalSave()`（视图销毁后同步落库，属必要设计） |
| 启动性能 | 已优化 | DataStore 首读 + 通知渠道创建 + TTS `warmUp` 全部在 `appScope(IO)`，冷启动无 DiskReadViolation |
| 滚动重组 | 已优化 | 阅读器视口同步走 `snapshotFlow`（`ReaderViewportSync`/`PagedReadingView`），不逐帧重组 |
| Coil 图片 | 已优化 | `ReaderImageBlock` 用显式尺寸解码 + 稳定 `memoryCacheKey`，EPUB 图导入期已重编码为小 JPEG |
| release 体积 | 已优化 | `isMinifyEnabled` + `isShrinkResources` + `proguard-android-optimize.txt`；仅打包 arm64-v8a / armeabi-v7a，并开启 ABI splits |
| 协程/线程模型 | 规范 | TTS 层 `@Volatile` + `speakMutex` + `SpeakJobRegistry`；IO 全部 `withContext(Dispatchers.IO)` |
| 死代码 | 几乎为零 | 全项目符号扫描：零引用函数仅 3 个 `@Preview`（`ReadingHeatmapPreview` 等，属 IDE 预览，应保留） |
| 列表 key | 已覆盖 | 所有列表型 Lazy 容器均带 key（复合 key 场景见 `ArticleSquareScreen`） |
| TODO/FIXME | 无 | 全仓 0 处遗留标记 |

---

## 五、后续可做（未改动，按收益排序）

1. **`material-icons-extended`（2 万+ 图标类）** — release 已被 R8 裁掉，但 debug 构建、
   IDE 索引、构建耗时受影响。若图标用点有限，换 `material-icons-core` + 显式 vector 可提速构建，
   属工程量换构建时间的取舍。
2. **TTS 启动预热时机** — `EareyeReadingApp.onCreate` 无条件 `initialize + warmUp`
   （模型约 1–3s IO，Kokoro 首次推理实测 ~8s）。对"装了但从不朗读"的用户是纯浪费，
   可加"最近 7 天有朗读记录才预热"的开关。当前行为是有意为之（规避首次点击抢在预热前），
   故不动。
3. **TTS 播放完成轮询 `delay(20)`**（`TtsSpeakOrchestrator.launchWatermarkMonitor`）—
   20ms 轮询播放头，长文本朗读期间约每秒 50 次轻量查询。改回调/Flow 可省掉轮询，
   但涉及音频同步时序，改动风险高于收益，暂不动。
4. **`E/AppOps: attributionTag not declared in manifest`** — MIUI 系统侧日志，
   与本应用逻辑无关（不影响功能），仅记录以免后续被误判为新问题。

---

## 六、总体结论

代码库处于**高位稳态**：Room 索引、协程模型、启动路径、图片解码、release 裁剪、
列表 key 均已到位，detekt 门禁 0 findings，几乎无死代码。

本轮实际收益集中在两处：
- **Compose 强跳过**（构建配置一行）：不可跳过组件 53 → 8，阅读器整条渲染链
  （段落 / 切片 / 滚动 / 翻页 / 分栏 / 底栏）全部转为可跳过，直接削减 TTS 与翻译
  高频状态更新引发的重组量。真机帧统计 9114 帧仅 57 帧 janky（0.63%）。
- **3 处高频路径上的重复分配**：分类弹窗的 `values().toList()`、分栏 item 的重复样式构建与
  AnnotatedString 分配、3 个零引用废弃主题别名。

如需进一步压榨，下一步应做**基准对比测量**：用同一本书分别在开关关闭/开启下采集
`dumpsys gfxinfo` 与宏基准（Macrobenchmark）首帧/滚动指标，把 0.63% 这个数字压成
可回归的门槛值。

