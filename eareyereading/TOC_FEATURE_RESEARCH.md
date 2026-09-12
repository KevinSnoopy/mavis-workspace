# 阅读详情页「目录」功能调研报告

> 调研时间：2026-09-12 ｜ 范围：阅读页目录链路现状 + 三类文章源可提取的目录信息 + 实现方案

---

## 一、现状盘点：目录入口已通，缺的是"真目录"

| 环节 | 现状 | 位置 |
|---|---|---|
| 入口 | 顶栏溢出菜单"目录"项 | `ReaderTopBar.kt` L143-150 |
| 状态 | `showChapterNav` 开关管线完整（含 forceChrome 常亮联动） | `ReaderUiState.kt` L113、`ReaderViewModelSettings.kt` L161 |
| 弹窗 | `ChapterNavDialog` = **段落导航**，把全书每段列一行（60 字预览），长书数千段不可用 | `ReaderDialogs.kt` L267-328 |
| 跳转 | `goToParagraph(index)` 已可用，落库进度/统计齐全 | `ReaderViewModelNavigation.kt` L70 |

**结论**：UI 骨架（底部抽屉、当前项高亮、打开定位当前段、点击跳转）全部可复用，唯一缺口是**章节级目录数据**——导入时章节边界被摊平丢失，运行时无章可依。

## 二、三类文章源的目录信息分析

### 1. EPUB 导入（信息最全，但有损）

导入链路：`LibraryBookImporter.importBook` → 拷贝到 `filesDir/books/` → data 层 `BookImporter.addBook` → `EpubParser.parseBook` → `EpubContentExtractor.extractContent`。

关键问题：`extractContent` 按 spine 顺序遍历章节 HTML，把所有段落**append 进同一个列表**（`EpubContentExtractor.kt` L99-110），章节边界没有记录。OPF metadata 提取了（title/author/language/identifier），但 EPUB 自带的 **toc.ncx / nav.xhtml 目录文件完全没解析**。

有利条件：
- spine 遍历天然按章分组——在循环里记一笔 `paragraphs.size` 就能得到每章起始段下标，**信息是免费的**
- EPUB 原文件始终保留在磁盘（`book.filePath` + `sourceUri` 兜底），随时可重解析
- `XhtmlParagraphExtractor` 过滤阈值已降到 3 字符（issue 10.8），"Chapter 1"类短标题段会保留在段落流里，可作标题兜底来源

### 2. Gutenberg 经典名著 .txt（格式规律，可正则提取）

下载链路：`LibraryBookImporter.downloadClassic` → `books/classics/{id}.txt` → `parsePlainText` 按空行分段 → `content` 落库。

Gutenberg 纯文本格式高度规律：
- `*** START OF THE PROJECT GUTENBERG EBOOK XXX ***` / `*** END OF ...` 头尾标记
- 章标题固定模式：`CHAPTER I`、`CHAPTER XII`、`Chapter 1` 等（占独立段落）
- 预置 15 本名著（`ClassicBooks.list`）全部适用

### 3. URL / RSS 文章（单篇，无章节概念）

`ArticleParser.parseFromUrl` / RSS `content:encoded` → 单篇文章按段落 join 落库（`ArticleSquareManager` L224-232）。**没有目录需求**，目录弹窗对此类书降级回落到现有段落导航即可。

### 存储层现状

- 所有书导入后正文统一持久化在 `books.content`（`paragraphs.joinToString("\n\n")`），阅读加载时 `content.split("\n\n")` 重建段落流——**段落下标跨会话稳定**，目录里存段落索引是可靠的
- Room `AppDatabase` version **15**，手写迁移链 `AppDatabaseMigrations.ALL`，`exportSchema = true`（app/schemas 对照）——加列是成熟低风险操作
- 项目无 kotlinx.serialization/gson/moshi 依赖，JSON 序列化用 Android 自带 `org.json` 零成本

## 三、方案对比

### 方案 A：导入时提取章节边界，JSON 落库（推荐 ✅）

在导入链路各解析器里顺手提取 `List<TocEntry(title, paragraphIndex)>`，序列化存入 `books` 表新列。

- ✅ 章节边界在导入时是免费信息（spine 分组 / Gutenberg 固定格式），错过时机后面只能启发式猜
- ✅ 阅读时零解析成本，目录瞬开
- ✅ 段落下标与 `content` 同源同刻生成，永不漂移
- ⚠️ 需要 Room 15→16 迁移（加一列 TEXT，风险很低）
- ⚠️ 存量书没有目录数据 → 需要懒回填兜底（见下）

### 方案 B：运行时重解析

打开目录时再算：EPUB 重读 zip 解析 toc.ncx；txt 用标题正则扫 `content`。

- ✅ 不动 DB schema
- ❌ EPUB 要整本 zip IO + 摊平正文比对（大书秒级卡顿），且本地文件丢失（`sourceUri` 失效）时目录直接没了
- ❌ EPUB 章节→段落下标映射仍需重跑整个 spine 提取才能算出，成本等同重新导入
- ❌ 每次打开目录都重复计算

**选 A**。B 的唯一优势（不动 schema）不抵其运行时成本与失效路径。

## 四、推荐方案详细设计（方案 A）

### 4.1 数据模型

```kotlin
// domain/model/Models.kt
data class TocEntry(
    val title: String,        // 章节标题
    val paragraphIndex: Int,  // 该章在段落流中的起始下标（跳转目标）
)
// Book 增加：val toc: List<TocEntry> = emptyList()
```

### 4.2 持久化（Room 15→16）

```kotlin
// BookEntity 增加：val tocJson: String? = null   // [{"t":"Chapter I","p":12}, ...]
// AppDatabase version 15 → 16
// AppDatabaseMigrations 新增：
//   ALTER TABLE books ADD COLUMN tocJson TEXT
```

- `BookMapper` 补 entity↔domain 映射（org.json 序列化/反序列化，解析失败容错返回空列表）
- 复用现有 `BookDeleter`/去重链路，无额外改动

### 4.3 EPUB 章节提取（核心新增）

新建 `util/EpubTocParser.kt`：
1. 定位并解析 **toc.ncx**（EPUB2：`navMap/navPoint/navLabel/text` + `content@src`）与 **nav.xhtml**（EPUB3：`<nav epub:type="toc">`），产出有序 `(href, title)` 列表
2. href 归一化规则与 `EpubContentExtractor` 现有逻辑保持一致（去 `#fragment`、相对 OPF 目录、URL 解码）

`EpubContentExtractor.extractContent` 微调：
- spine 循环每处理一个章节条目前记录 `paragraphs.size` 作为章起始下标
- 章标题来源优先级：**toc.ncx/nav 匹配的标题 → 该章 HTML 第一个 `<h1>-<h6>` 文本（去标签前截取）→ `Chapter N` 兜底**
- `ExtractedContent` / `ParsedBook` 增加 `chapters: List<TocEntry>`

### 4.4 Gutenberg TXT 章节提取

`BookImporter.parsePlainText`（或下载路径）分段后扫描：
- 正则识别章标题段：`^(CHAPTER|Chapter)\s+([IVXLC]+|\d+)\b`，可选扩展 `^PART|^BOOK`
- 标题段自身即段落流一员，其下标即跳转目标
- 连续命中 ≥3 次才认定有目录（防误伤普通文章），否则 toc 留空

### 4.5 阅读页接线

- `ReaderUiState` 增加 `toc: List<TocEntry>`
- `ReaderViewModelBookLoader.loadBookContent`：`book.toc` 随 DB 读取直接进 uiState（content.isNotBlank() 的书不必重解析）
- `ChapterNavDialog` 改造为双模式：
  - `toc` 非空 → **章节模式**：章名列表、当前章强调色高亮（复用 `LocalReaderAccent`）、可附"本章占全书 %"；点击 `goToParagraph(tocEntry.paragraphIndex)`
  - `toc` 为空 → 回落现有段落导航（URL/RSS 文章、无目录的 txt）

### 4.6 存量书懒回填（二期可选）

打开目录且 `toc` 为空且 `filePath` 以 `.epub` 结尾 → 后台重解析一次 EPUB，算出 chapters 后回写 `tocJson` 列；本地文件丢失则静默回落段落导航。txt 书同理跑标题正则回填。可选但强烈建议，否则老用户永远用不到目录。

## 五、实施清单与预估

| # | 任务 | 涉及文件 | 复杂度 |
|---|---|---|---|
| 1 | TocEntry 模型 + Book 字段 | `Models.kt` | 低 |
| 2 | Entity 加列 + v16 迁移 + Mapper | `BookEntity.kt`、`AppDatabase.kt`、`AppDatabaseMigrations`、`BookMapper.kt` | 中 |
| 3 | EpubTocParser（ncx/nav 解析） | 新文件 `EpubTocParser.kt` | 中 |
| 4 | EpubContentExtractor 记录章边界 + 标题兜底 | `EpubContentExtractor.kt`、`EpubParser.kt` | 中 |
| 5 | Gutenberg/TXT 章标题检测 | `BookImporter.kt` | 低 |
| 6 | UiState + BookLoader 接线 | `ReaderUiState.kt`、`ReaderViewModelBookLoader.kt` | 低 |
| 7 | ChapterNavDialog 双模式改造 | `ReaderDialogs.kt` | 中 |
| 8 | 单元测试（ncx 解析、Gutenberg 正则、JSON 往返） | `app/src/test` | 低 |
| 9 | （二期）存量书懒回填 | loader + repository | 中 |

核心链路（1-8）预估 1 个工作日；含懒回填与边界情况 1.5-2 天。

## 六、风险与注意点

1. **toc.ncx 的 src 匹配**：带 `#fragment`、路径相对 OPF 目录、URL 编码——必须复用 `EpubZipReader.resolveEntry`/`decodeHref` 的归一化语义，否则标题对不上章
2. **截断书**（MAX_TOTAL_CHARS）：章节可能截在中间，段落索引仍在有效范围内，UI 无需特判
3. **插图标记段** `[[IMG:n]]` 参与段落计数，不影响索引正确性（与 content 拆分同源）
4. **JSON 解析容错**：脏数据/旧版本降级为空 toc，走段落导航，不得抛异常阻断进书
5. **迁移必须**同时更新 `AppDatabaseMigrations.ALL` 并核对 `app/schemas` 导出 schema，防启动即崩的 schema 漂移
6. **去重复用旧 id**（filePath/identifier 命中）：复用旧书时新解析的 toc 不会入库——可在 reuse 分支里顺带回写空 toc 的旧书（与懒回填同一落库函数）
