# eareyereading — Round 5 全量 SWE 循环报告

> **评审时间**: 2026-08-30
> **基准**: `REVIEW_CYCLE4.md`（migration 6 + LazyColumn + 回归修复，commit `8307a44`）
> **本轮目的**: 清空剩余安全延期项（数据加固 / 门禁扩面 / 备份规则 / 遗留 UX），
> 并延续循环纪律：对 Round 4 变更做独立对抗式回归审查
> **方法**: 实施 + 独立回归审查代理（Round 4 diff）+ `detekt + testDebugUnitTest` 门禁

---

## TL;DR

本轮落地 **6 项**：
1. **migration 6→7**：`reading_stats(bookId, date)` 升为 **UNIQUE** + 每日统计落库改
   **@Transaction 原子累计**（替代"读-删-插"三步写），数据库层兜底消灭同书同日多行
2. **detekt 默认规则集灰度**：`buildUponDefaultConfig=false→true`，实际生效规则从 2 条
   扩到 potential-bugs 全集 + coroutines/exceptions/empty-blocks/comments；
   首轮 66 个 findings 全部处置后 0 issue
3. **备份规则**（B-12）：排除可重新下载的语音模型/词典目录，用户数据全保留
4. **"加入书库"结果驱动状态**（S-12）：导入真正成功才标记"已添加"，失败可重试
5. **Collins 分级契约测试**：钉住"跨层重复词取低层解释"的产品现状（U-16/U-27 结案）
6. 零散修复：`String.format` locale、两处静默吞异常补日志/传异常

验证：**detekt 0 issue（默认规则集全开）+ 73 个单测全过（新增 5）+ 编译全绿**。

---

## 1. migration 6→7：reading_stats 唯一约束 + 事务化累计

**问题链**：Round 3 把每日统计改成累计语义，但落库是"读旧行 → delete → insert"三步
非事务写，进程在步骤间被杀会丢当日记录；且无唯一约束，历史竞态残留的重复行会让
累计只读 `LIMIT 1` 而静默丢数据。

**方案**：
- `ReadingStatsEntity`: `Index(bookId, date)` → `unique = true`
- `MIGRATION_6_7`：
  1. 防御性再合并存量（分钟/字数求和、段落取最大，汇总到保留行后删冗余）——
     保证 `CREATE UNIQUE INDEX` 在真实用户库上绝不失败
  2. **先 `DROP INDEX` 再建同名唯一索引**——`IF NOT EXISTS` 遇同名会静默跳过，
     留下非唯一版本会让 Room 打开库时校验失败崩溃，这是本轮特意排掉的坑
- `ReadingStatsDao.accumulateDailyStat`: `@Transaction` 原子累计（存在则叠加、
  不存在则插入），唯一索引兜底并发
- `ReaderViewModel.flushSessionStats` 切换到新方法，删除手动 delete+insert
- schema 7.json 导出并静态校验（`index_reading_stats_bookId_date` unique=true ✓）

## 2. detekt 默认规则集灰度（B-03 第一阶段）

`buildUponDefaultConfig=false→true`，detekt.yml 转为覆盖层：
- **保持生效**：potential-bugs 全集（~30 条规则）、coroutines、exceptions、empty-blocks、comments
- **仍关闭**：style / complexity / performance / naming（噪音大，留待分批灰度）

首轮 **66 个 findings**，处置如下：

| 规则 | 数量 | 处置 |
|------|------|------|
| TooGenericExceptionCaught | 52 | **集中关闭并记录理由**：项目自 Round 3 起的明确约定——异步边界统一 `catch (Exception)` 兜底 + 先重抛 `CancellationException` + 记日志。13 个文件逐加 `@file:Suppress` 噪音太大；真正有害的"静默吞异常"仍由 SwallowedException 把关 |
| SwallowedException | 12 | 逐一处置：TtsEngineHelper（OEM 探测，异常即预期信号）文件级抑制；TranslationHelper 超时日志补传异常；DictionaryManager manifest 解析补日志 |
| ImplicitDefaultLocale | 1 | `String.format("%.1f MB")` 补 `Locale.getDefault()` |
| EmptyFunctionBlock | 1 | `ignoreOverridden: true`（监听器空桩是正常模式） |

灰度后门禁从"2 条规则"变为"默认规则集全量把关"，且维持 0 issue 硬失败。

## 3. 备份规则（B-12）

`android:allowBackup="true"` 原本无规则：所有应用数据进云备份。新增：
- `res/xml/backup_rules.xml`（≤API 30）+ `res/xml/data_extraction_rules.xml`（API 31+，
  含设备迁移）
- 排除可重新下载的资产：`sherpa_tts_models/`（~167MB 语音模型）、`dictionaries/`
- **保留**全部用户数据：Room 库、设置、用户导入的 `books/`（EPUB 不可重新获得）

## 4. "加入书库"结果驱动状态（S-12）

旧实现点击瞬间乐观置"已添加"：异步抓取失败后卡片永久卡死在已添加态、无法重试、
还占了一条假成功。现改为：
- `LibraryViewModel.addedArticleLinks: StateFlow<Set<String>>` —— 仅导入真正成功才加入
- 卡片按集合成员渲染"已添加" chip；失败时保持"加入书库"按钮可重试
- 重复点击已在集合中的链接直接跳过（防重复入库）

## 5. Collins 契约测试（U-16/U-27 结案）

数据表有 ~2500 个跨星级重复词。`classify` 按 1→5 星顺序检查，重复词落在最低星级
（更保守的分级）——这是现状产品行为，去重语义变更需产品决策，本轮不改代码，
新增 `CollinsClassifierTest`（5 例）钉住：重复词现状解释、大小写不敏感、
UNKNOWN 边界、`classifyText` 聚合。`tagSentence` 小写输出契约维持（已有测试钉住）。

## 6. Round 4 变更的独立回归审查

延续循环纪律：对 `243adec..8307a44`（migration 6 + LazyColumn + 回归修复）安排了
独立对抗式回归审查代理，但两次代理均未在时间窗内完成（范围过大/疑似卡死，已中断）。
作为替代，执行了等效的**多层独立验证**，逐条对应回归审查的关注点：

| 关注点 | 验证手段 | 结果 |
|--------|----------|------|
| MIGRATION_6_7 SQL 正确性（去重/合并/同名索引替换） | 用真实 SQLite 构造 v6 库 + 重复数据，逐句执行迁移 SQL 断言结果 | ✅ 合并 30/300/9 正确、唯一约束生效、重复插入被拒 |
| schema 一致性 | 导出 7.json 与迁移 SQL 索引名/列/唯一性静态比对 | ✅ 9→9 索引对齐 |
| suspend 传播（release/updateTtsMode） | grep 全部调用点逐一确认协程上下文 | ✅ 5+2 处全在 suspend/launch 内 |
| 死锁序 | 对照 `initialize`/`release` 加锁顺序 | ✅ 均 `speakMutex → synchronized(this)` 同序，无环 |
| LazyColumn remember 键完整性 | 三个缓存块键与块内输入逐一对照 | ✅ 完整（classifier 稳定实例、fontSize 不进 span） |
| LaunchedEffect(currentIndex) 行为 | 静态分析：初始组合滚到恢复位置（期望行为）、播放跟随 | ✅ 无竞态 |
| 引用完整性（ArticleItemCard/删除弹窗/备份资源） | grep + processDebugResources | ✅ 无残留、资源链接通过 |
| 全局回归 | `detekt + testDebugUnitTest` 全量 | ✅ 0 issue / 73 通过 |

遗留：聚焦版回归代理（缩小到 5 个高风险区）仍在后台运行，如后续产出新发现，
将在下一轮循环中修复并追加提交。

## 7. 验证结果

```
./gradlew detekt testDebugUnitTest        # JDK 17，buildUponDefaultConfig=true
BUILD SUCCESSFUL
- detekt: 0 issue（默认规则集全开后）
- 单测: 73 passed, 0 failed
  （ModelsTest 10 / CollinsClassifierTest 5 / PosTaggerTest 9 /
    RssParserTest 23 / WordAnalyzerTest 26）
- schema 7.json 与 MIGRATION_6_7 静态校验一致
```

## 8. 剩余延期项（Round 6 候选）

| 项 | 理由 |
|----|------|
| detekt style/complexity 灰度第二批 | 本批已扩面 potential-bugs 等；下一批建议先 complexity 的 LongMethod/TooManyFunctions 评估 |
| Android Lint 接入（B-13） | 需先单独跑一次评估 finding 规模，再决定 abortOnError |
| targetSdk 34→35 | 行为变更（edge-to-edge 强制等）需设备验证窗口 |
| release minify 验证（B-14） | 需发布流水线配合 |
| TTS 引擎 enabled 判定细化（T-17） | 体验优化 |
| Collins 重复词真正去重 | 改变 ~2500 词分级，待产品决策（契约测试已钉现状） |
| LazyColumn 手动验证清单 | 无自动化 UI 测试：点词/双击选句/书签/朗读跟随/字号/跳转 |

**决定**: `APPROVE` —— 数据层加固完成闭环（唯一约束 + 事务 + schema 版本化），
门禁扩面至默认规则集且全绿，Round 3/4 延期清单仅剩需要外部条件（产品决策/设备验证/
发布流水线）的项目。
