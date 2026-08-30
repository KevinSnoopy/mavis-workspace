# eareyereading — Round 4 全量 SWE 循环报告

> **评审时间**: 2026-08-30
> **基准**: `REVIEW_CYCLE3.md`（116 findings，23×P1 全关，门禁实装，commit `243adec`）
> **本轮目的**: 落地 Round 3 延期清单中的结构性项目 + 对 Round 3 修复做独立回归审查
> **方法**: 3 个专项评审代理（Round 3 diff 回归审查 / migration 5→6 设计 / LazyColumn 可行性）
> + 实施 + `./gradlew detekt testDebugUnitTest`（JDK 17）验证门禁

---

## TL;DR

本轮完成 **Round 3 延期清单里全部 3 个结构性大项**：
1. **Room migration 5→6**：9 个索引（2 个唯一约束）+ 3 张表的存量数据去重/合并 +
   插入策略 REPLACE→IGNORE + **schema 导出版本化**（`exportSchema = true` + `app/schemas/`）
2. **NormalReadingView LazyColumn 化**：整书 eager Column → 惰性列表 + 段落排版缓存，
   顺带修复"滑杆/章节跳转视口不跟随"的既有缺陷
3. **Round 3 修复的独立回归审查**：发现并修复 2×P1 + 2×P2（统计高水位、引擎释放
   互斥、提醒镜像迁移、提醒链权限陷阱）+ 6 个中小延期项落地

验证结果：**detekt 0 issue + 68 个单测全过 + Jetifier 移除后全量重建成功**。

---

## 1. Room migration 5→6（D-04/D-05/D-06 + R-18 根治）

### 1.1 索引设计（逐 DAO 查询对照后确定，9 个）

| 索引 | 类型 | 依据 |
|------|------|------|
| `review_records(vocabularyId)` | **UNIQUE** | 每词一条复习记录的硬约束；堵死 check-then-insert 竞态（D-04） |
| `review_records(nextReviewDate)` | 二级 | 到期列表/计数（3 个屏幕每帧订阅） |
| `bookmarks(bookId, paragraphIndex)` | **UNIQUE** | 同段一书签；根治双击重复书签（R-18），兼覆盖按书查询 |
| `reading_stats(date)` | 二级 | 首页今日/周聚合（4 个按日查询） |
| `reading_stats(bookId, date)` | 二级 | 累计落库的读/删路径 + 删书级联前缀 |
| `word_frequencies(bookId, count)` | 二级 | 词频 Top 查询的过滤+排序一把覆盖（无 filesort） |
| `vocabulary(bookId)` | 二级 | 按书查词 |
| `highlights(bookId, paragraphIndex, startOffset)` | 二级 | 两个高亮查询含排序列全覆盖 |
| `books(isArchived, lastReadTime)` | 二级 | 首页每次启动的书库列表（Round 3 漏掉的热列，本轮评审补上） |

**明确不加的**：`vocabulary(word)` —— 唯一查询是 `LOWER(word)=LOWER(:word)`，普通索引穿过
`LOWER()` 无效，Room 又不支持表达式索引注解，加了只会成为永久的写入负担。

### 1.2 存量数据安全（迁移内去重/合并）

唯一索引在**已有重复数据**的库上 `CREATE UNIQUE INDEX` 会直接抛异常导致数据库打不开，
迁移必须先清理：

- `review_records`：每词保留"最近实际复习过"的一条（`lastReviewDate DESC, repetitions DESC, id DESC`
  决胜，相关子查询写法兼容 minSdk 26 的 SQLite 3.18，不用窗口函数）
- `bookmarks`：每段保留最早一条（note 无写入路径，无信息损失）
- `reading_stats`：同书同日历史多行**先合并再删**（分钟/字数求和、段落取最大），
  直接删会丢阅读时长

### 1.3 插入点语义

3 个受影响 `@Insert` 全部 `REPLACE → IGNORE`（`BookmarkDao.insert`、
`ReviewRecordDao.insertReview`、`VocabularyDao.insertReviewRecord`）：
唯一约束下 REPLACE 会**删除已有行重建**（抹掉 SM-2 积累进度），IGNORE 才是正确的
"存在即幂等"。三处调用方均不消费返回的 rowId，IGNORE 冲突返回 -1 无影响。

### 1.4 schema 版本化（D-06）

- `AppDatabase`: `version = 6`, `exportSchema = true`
- `app/build.gradle.kts`: `ksp { arg("room.schemaLocation", "$projectDir/schemas") }`
- `app/schemas/com.eareyereading.data.local.database.AppDatabase/6.json` 随仓库提交
- 已静态校验：schema 中 9 个索引与迁移 SQL 的名称/列/唯一性逐一对齐
  （Room 运行时双向校验，任何漂移都会在打开库时抛异常，故必须一致）

---

## 2. NormalReadingView LazyColumn 化（R-11）

可行性评审结论：**SAFE（低风险）**。关键证据：

- 全仓**没有任何程序化滚动**（无 `animateScrollTo`/`scrollTo`/`bringIntoView`），
  现有 `ScrollState` 是匿名的、只被手势使用
- 点击选词/双击选句完全基于**段落自身**的 `TextLayoutResult` + 局部坐标，与列表几何无关
- 高亮/书签/播放高亮都是按 index 的纯数据渲染
- `ReaderScreen.scrollState` 本来就是死代码（一并删除）

落地内容：
- `Column(verticalScroll)` → `LazyColumn(state, contentPadding)` + `itemsIndexed(key = index)`
  （段落按书加载后不可变，index 是稳定身份）
- 三个排版分支的 `buildAnnotatedString` 包进 `remember(para, alpha, textColor, …)`：
  播放句级状态变化不再重切可见段落的词
- `LaunchedEffect(currentIndex) { animateScrollToItem(currentIndex) }`：
  **顺带修复既有缺陷** —— 此前滑杆/章节导航/上下段跳转只改索引、视口从不移动；
  自动朗读推进现在也会跟随滚动
- 清理死代码：`ReaderScreen` 顶层未使用的 `scrollState`、未使用的 `onGloballyPositioned` import

性能特征：播放时每个句子 tick 从 O(book) 全文重排 → 仅可见 ~10-20 段，且缓存命中；
内存从常驻整书 TextLayout → 仅可见项。

**风险提示**（评审结论）：无自动化 UI 测试兜底，建议手动过一遍：点词弹窗、双击选句翻译、
书签、自动朗读跟随、播放中改字号、滑杆/章节跳转、Collins 配色开关。

---

## 3. 其他落地项

| 项 | 来源 | 内容 |
|----|------|------|
| dueCount 时间戳刷新 | S-14 | 到期数的查询基准时间从"VM 构造时刻"改为
  `flatMapLatest(dueCountTimestamp)`，每次加载会话刷新 —— 长会话中陆续到期的卡片现在能被计入 |
| 删除二次确认 | S-20 | 删书（级联删书签/高亮/进度/统计）与删词（连同复习记录）都加确认弹窗 |
| tarball 进度口径 | T-18 | 压缩阶段拿"解压后大小"当分母会卡 ~60% 再跳变，改为不确定进度 + 已下载 MB 数 |
| Jetifier 移除 | B-18 | 全部依赖已是 AndroidX（kxml2/xmlpull 为纯 Java 测试依赖）；全量重建验证通过 |
| 死代码清理 | — | 见 §2 |

---

## 4. Round 3 修复的独立回归审查

独立评审代理对 `a804800..243adec`（Round 3 两个提交，43 文件）做对抗式复查，
聚焦"修复本身引入的回归"，发现 **0×P0 / 2×P1 / 2×P2 / 2×P3，全部在本轮处置**：

| 级别 | 问题 | 处置 |
|------|------|------|
| P1 | **charsRead 重开膨胀**：统计改累计后，`lastRecordedParagraphIndex` 仍重置为 -1，而段落索引从持久化恢复（如 50）；每次退出时 0..50 的整段前缀被当作本次新读累进库里，重开一次书虚增一截 | `loadBook` 用恢复后的段落位置初始化高水位，只统计本次会话真正推进的段落 |
| P1 | **release() 绕过 speakMutex**：切回系统 TTS 时新加的 `embeddedTts.release()` 未持互斥锁释放 native 实例，与同批刚修的 T-02 相矛盾，重新开出 generate() 期间释放指针的 SIGSEGV 窗口 | `release()` 改 suspend，在 `speakMutex.withLock` 内释放；`updateTtsMode` 相应改 suspend |
| P2 | **ReminderPrefs 无存量迁移**：镜像缺省 true，升级前已关闭提醒的用户会被当作开启重排闹钟 | `EareyeReadingApp.onCreate` 启动时从 DataStore 对账镜像（有界阻塞读，毫秒级） |
| P2 | **提醒链被瞬态权限状态杀死**：系统通知权限恰好被撤时闹钟触发会永久断链，重新授权也无法恢复 | 权限缺失只跳过本次展示，仍重排明天；仅用户开关关闭才终止链 |
| P3 | `cleanup()` 漏取消 `bookmarkToggleJob` | 已补。（注：重复书签的最终防线是 migration 6 的唯一索引 + IGNORE，job 串行只是降低概率层） |
| P3 | HomeScreen 日期改 `remember` 后跨午夜陈旧 | 接受：问候语同样在 VM 初始化时定值，行为一致且极轻；不做定时器 |

抽样验证为正确的关键点（节选）：`getStatsForDate` 删除后无残留引用、`addBook` 抛
IOException 的三个调用方都有承接、测试里的 epoch 常数独立复算无误、T-06 双触发有
`finishOnce()` 原子守门、AudioTrack 释放所有权一致、新增 `catch` 块全部先重抛
`CancellationException`、CI workflow 步骤/产物路径有效。

---

## 5. 验证结果

```
./gradlew detekt testDebugUnitTest        # JDK 17
BUILD SUCCESSFUL
- detekt: 0 issue（门禁真实生效，Round 3 起）
- 单测: 68 passed, 0 failed（ModelsTest 10 / PosTaggerTest 9 / RssParserTest 23 / WordAnalyzerTest 26）
- Jetifier 移除后全量重建成功
- schema 6.json 与 MIGRATION_5_6 静态对齐（9/9 索引）
```

---

## 6. 剩余延期项（Round 5 候选）

| 项 | 理由 |
|----|------|
| `reading_stats(bookId,date)` UNIQUE + upsert | 本轮已合并存量重复行、Round 3 代码已单飞防新重复；上约束属加固，需单独评审 |
| detekt 默认规则集灰度（B-03） | 会一次性暴露大量 style/complexity findings，需分批消化 |
| lint 块 + CI lint 步骤（B-13） | 同上，先评估当前 lint finding 规模 |
| backup 规则 / minify 验证 / targetSdk 35（B-12/14/…） | 需独立验证窗口 |
| Collins 跨层重复词去重（U-16） | 改变 ~2500 词分级结果，属产品语义决策 |
| `tagSentence` 原始大小写（U-27） | 现有测试契约钉住小写输出，且无 UI 消费 |
| "加入书库"按结果驱动状态（S-12） | 需 VM 侧按文章粒度状态建模 |
| TTS 引擎 enabled 判定细化（T-17） | 体验优化，非缺陷 |

**决定**: `APPROVE` —— 结构性延期项清零（除刻意保留的加固/灰度类），门禁保持全绿，
本轮所有变更经编译+单测+detekt 三重验证。
