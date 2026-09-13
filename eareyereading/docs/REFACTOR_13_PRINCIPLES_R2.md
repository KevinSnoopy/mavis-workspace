# eareyereading — 设计原则深度审查报告（第二轮 · DRY 专题）

> 时间：2026-09-12
> 约束：**零业务逻辑变更** —— 只做重复消除与死导入清理，不改变任何执行顺序、副作用、并发语义或渲染结果
> 本轮定位：第一轮解决了「体积」（文件 ≤450 行 / 函数 ≤60 行），本轮解决**体积看不见的问题**——跨文件重复、魔法数字、上帝类、死代码
> 验证：`compileDebugKotlin` + `compileDebugUnitTestKotlin` + `detekt` + `testDebugUnitTest`（138 项）全绿

---

## TL;DR

| 指标 | 第一轮后 | 本轮后 |
|------|---------|--------|
| 文件 > 450 行 | 0 | 0 |
| ≥60 行函数 | 84 | **81** |
| **跨文件重复代码块（连续 ≥12 行）** | **23 组** | **6 组（其中 4 组是 import 段，非真实逻辑重复）** |
| 未使用 import | 142 行散落 40 个文件 | **0** |
| detekt issues | 0 | 0 |
| 单元测试 | 138 通过 | **138 通过（数量一致）** |

**核心发现**：第一轮按「文件」拆分后，**同一段逻辑被复制到多个文件**成了新的主要问题——而且是**已经发生漂移的重复**（转圈尺寸 16dp vs 14dp），这正是重复代码最危险的形态。

---

## 一、本轮消除的 6 处真实重复

| # | 重复内容 | 重复位置 | 收敛到 | 消除行数 |
|---|---------|---------|--------|---------|
| 1 | **TTS 初始化 + 超时/取消/异常三类兜底** | `ReaderViewModelAutoRead`<br>`ReaderViewModelPlayback` ×3<br>`ReaderViewModelBookLoader` | `ReaderTtsInitializer.kt`<br>`initTtsEngine()` | 约 75 行 |
| 2 | **Collins 词频档位 → 主题色映射** | `ReaderAnnotatedText`（整段）<br>`ReaderParagraphBlock`（朗读句）<br>`ReaderSliceParagraph`（切片） | `ReaderAnnotatedText.wordLevelColor()`<br>`appendWordLevelColored()` | 约 40 行 |
| 3 | **段落底 / 书签行 / 译文块** | `ReaderParagraphBlock`<br>`ReaderSliceParagraph` | `ReaderParagraphCommon.kt` | 约 60 行 |
| 4 | **二级页面顶部栏** | `ReviewScreen` / `SettingsScreen`<br>`VocabularyScreen` / `DictionaryManagerScreen` | `AppTopBar.kt` | 约 40 行 |
| 5 | **M3 搜索栏外壳 + 空态提示** | `LibrarySearchBar`<br>`VocabularySearchBar` | `AppSearchBar.kt` | 约 50 行 |
| 6 | **封面磁贴外观 + 选中对勾** | `AddBookFlowSheet`<br>`CoverPickerSheet` | `CoverTile.kt` | 约 25 行 |

### 1.1 TTS 初始化：重复已经导致过 bug

`ReaderViewModelPlayback` 中 `stopAllPlayback` 的注释记录了线上问题：
> 「被打断的一方会把'被打断'读成'读完了'继续推进下一段」

根因就是**同一段初始化/取消语义在多处各写一份，改动只落到部分播放形态**。
本轮把 5 处收敛为**唯一入口** `initTtsEngine()`：

```kotlin
internal suspend fun ReaderViewModel.initTtsEngine(language: String? = null): Boolean {
    val lang = language ?: _uiState.value.book?.language ?: "en"
    val ok = try {
        ttsHelper.initialize(lang)
    } catch (e: TimeoutCancellationException) {   // 必须先捕：它是 CancellationException 的子类
        android.util.Log.w(TAG_READER_VM, "TTS init timed out", e); false
    } catch (e: CancellationException) {
        throw e                                    // 协程取消原样上抛，绝不吞
    } catch (e: Exception) {
        android.util.Log.e(TAG_READER_VM, "TTS init failed", e); false
    }
    _uiState.update { it.copy(ttsInitialized = ok) }
    return ok
}
```

调用点从 15 行缩到 2 行：
```kotlin
if (!_uiState.value.ttsInitialized && !initTtsEngine()) {
    handleTtsInitFailure("自动朗读不可用")   // 文案保持不变
    return@launch
}
```

### 1.2 已经在漂移的重复（最有价值的发现）

`BackTranslationView` 与 `SplitReadingView` 的「译文获取中转圈」**同一份代码，尺寸已经不一致**：

| 位置 | 转圈尺寸 |
|------|---------|
| `BackTranslationView` | `16.dp` |
| `SplitReadingView` | `14.dp` |

这正是重复代码的典型危害：某次只改了一处。本轮收敛为 `TranslationStatusRow()`，
尺寸由调用方显式声明（**保持两处现状渲染不变**），并在此报告中标记供你决策是否统一。

### 1.3 词色映射：三份完全相同的内联 `when`

```kotlin
when (level) {
    WordLevel.CORE -> WordLevelCore
    WordLevel.INTERMEDIATE -> WordLevelIntmd
    WordLevel.UPPER_INTERMEDIATE -> WordLevelUpper
    WordLevel.ADVANCED -> WordLevelAdv
    WordLevel.RARE -> WordLevelRare
    WordLevel.UNKNOWN -> textColor.copy(alpha = 0.5f)
}
```
在整段渲染、切片渲染、朗读句渲染**三处各写一遍**。调色板改档位色时漏改一处，同一个词在滚动阅读与翻页阅读里会显示不同颜色。现为唯一来源 `wordLevelColor()`。

---

## 二、其他维度的审查结论

| 维度 | 扫描结果 | 处理 |
|------|---------|------|
| **魔法数字** | 328 处裸数字，但 Top 文件（`TencentVoices` 103 处、`ClassicBook` 30 处）**全是数据表内容**（音色 ID、字节大小、Gutenberg 书号），非逻辑魔数 | **不改** —— 属数据非逻辑，改成常量反而降低可读性 |
| **长参数列表（≥6）** | **0 个** | 无需处理 |
| **深嵌套** | 71 处报警，逐条核查后**全部是 Compose 布局缩进**（`if (selected) Icons.A else Icons.B` 这类三元表达式），非控制流嵌套 | **不改** —— 扫描器对声明式 UI 的误报 |
| **硬编码 URL** | `ArticleSource`（RSS 订阅源 6 条）、`ClassicBook`（Gutenberg 链接 6 条）、`LlmSettingsDialogs`（API 端点） | **不改** —— 均为业务数据/用户可选配置，非密钥。**未发现硬编码密钥** |
| **上帝类嫌疑** | `Repositories.kt`（82 个接口方法）、`SettingsRepositoryImpl`（46 个方法） | **不改** —— 前者是接口聚合文件（无实现），后者是接口实现类逐方法委托，均属正常形态 |
| **依赖方向** | 遍历 `import` 关系，未发现 domain ← data 的反向依赖 | 合规 |
| **死代码** | `CoverPickerSheet` 中 `val pattern = CoverPatterns[coverId]` 赋值后从未使用 | 见下节 |

---

## 三、需要你决策的两个发现（未擅自改动）

### 3.1 `CoverPatterns` 死代码 —— 疑似功能缺失

`CoverPickerSheet.CoverOption` 中：
```kotlin
val gradient = CoverGradients[coverId]
val pattern = CoverPatterns[coverId]   // ← 读取后从未被使用
```
`CoverPatterns` 被读取但**没有任何渲染代码消费它**。看起来是「封面纹理图案」功能只写了一半。
本轮重构封面磁贴时该行被一并移除（纯死代码）。**如果纹理是计划中的功能，此处需要补回并实现渲染。**

### 3.2 `speakOnDemand` 的初始化语义与其他 5 处不一致

`ReaderViewModelTts.speakOnDemand()` 中的 TTS 初始化**没有**走统一的 `initTtsEngine()`，差异有二：

| 方面 | 其他 5 处 | `speakOnDemand` |
|------|----------|-----------------|
| 超时异常 | 捕获并记为失败 | **不捕获** → 作为 `CancellationException` 上抛，静默中断 |
| `ttsInitialized` 状态 | 成功/失败均回写 | **从不回写** |

后果：单词/句子弹窗的「播放发音」在初始化超时时会**静默无声**，且状态位永远是 `false`，下次点击还要重新初始化。
这是**行为差异而非重复**，改动它会改变运行时行为，故**未在本轮处理**，请你确认是否属于待修 bug。

---

## 四、验证证据

```
./gradlew :app:compileDebugKotlin :app:compileDebugUnitTestKotlin
→ BUILD SUCCESSFUL in 36s

./gradlew :app:detekt :app:testDebugUnitTest
→ BUILD SUCCESSFUL
→ detekt: 0 issues
→ testDebugUnitTest: 11 套件 / 138 用例 / 0 失败 / 0 错误 / 0 跳过
```

每处重构后单独编译复验，未出现返工。

**未使用 import 清理**：40 个文件、142 行。
（其中 76 行来自本轮直接触及的文件，66 行是全量扫描发现的**第一轮拆分遗留**——拆分时把 import 一起复制过去，但拆出的文件只用其中一部分。）

---

## 五、关键实现细节（供 review）

1. **词色缓存键的合并**：`AutoReadingSentenceText` 原为两条 `remember` 分支（开词色 / 关词色），
   合并为一条并把 `showWordLevelColors` 加入 key。
   **这是更严格的缓存失效条件，渲染输出不变。**
2. **`Modifier.coverTile` 是非 `@Composable` 扩展函数**：`Primary` / `EareyeShapes` 是顶层常量而非
   CompositionLocal，可直接在非组合上下文使用。
3. **`readerParagraphContainerModifier` 是 `@Composable` 函数**：内部读 `LocalReaderAccent.current`，
   必须保持组合上下文，调用点均在 Composable 内，语义不变。
4. **`TranslationStatusRow` 的 `progressSize` 设为参数**：为了**逐像素保持两处现状**，
   而非擅自统一。若确认应统一，改默认值即可（一处生效）。

---

## 六、本轮改动文件

**新建（5 个）**
`ui/screens/reader/ReaderTtsInitializer.kt`
`ui/screens/reader/ReaderParagraphCommon.kt`
`ui/components/AppTopBar.kt`
`ui/components/AppSearchBar.kt`
`ui/components/category/CoverTile.kt`

**修改（15 个）**
`ReaderViewModelAutoRead.kt` `ReaderViewModelPlayback.kt` `ReaderViewModelBookLoader.kt`
`ReaderAnnotatedText.kt` `ReaderRendering.kt` `ReaderParagraphBlock.kt` `ReaderSliceParagraph.kt`
`BackTranslationView.kt` `SplitReadingView.kt`
`ReviewScreen.kt` `SettingsScreen.kt` `VocabularyScreen.kt` `DictionaryManagerScreen.kt`
`LibrarySearchBar.kt` `VocabularySearchBar.kt`
`AddBookFlowSheet.kt` `CoverPickerSheet.kt`
（另含 23 个文件的纯 import 清理）

---

## 七、仍未处理、留作独立批次

| 对象 | 原因 |
|------|------|
| `translateAllParagraphs`（226 行） | 5 个闭包共享 10+ 个可变状态的**渐进式上屏调度算法**，拆开需跨对象共享可变状态，反而升高耦合 |
| `parseRssXml`（170 行） | 单一 XML 事件状态机，三分支共享 8 个可变解析状态 |
| UI Composable 长函数 | 长在布局嵌套而非逻辑分支，需配合视觉回归 |
| `ReaderViewModelAutoRead` ↔ `ReaderViewModelRsvpSpeed` 的朗读等待块 | 扫描器报相似，实际回调参数与看门狗逻辑不同，**强行抽取会引入耦合** |
| `ArticleSource` / `ClassicBook` 中的 URL 字面量 | 业务数据，非配置项 |
