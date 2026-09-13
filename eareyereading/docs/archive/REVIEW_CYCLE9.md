# eareyereading — Round 9 未覆盖屏幕深评专项循环报告

> 评审时间：2026-08-30
> 基准：`REVIEW_CYCLE8.md` + `02c11a6`（git pull 后与 origin/eareyereading 一致，工作区干净）
> 本轮目的：深评此前未做过专项的屏幕与链路（SettingsScreen、ReviewScreen/SM-2、
> DictionaryManager、EPUB/URL/RSS 导入链、Home 文章流、数据层），修复全部
> P0/P1 与低风险 P2；消化跨轮延期清单中的低风险项
> 方法：先派 3 个只读宽分片代理；超时后按纪律中断并以窄分片重启 5 个
> （设置页 / SM-2 / 导入链 / Home+词典 / 数据层），共收齐 8 份报告交叉去重分诊；
> 每批修复后编译检查，全量门禁迭代至绿；收尾走 PiAdapter SWE 循环 + 独立终审

## TL;DR

- 未发现 P0；深评发现 **P1×11、P2×20、P3×18**，修复 **46 项**（全部 P1 + 全部低风险 P2 + 大部分 P3），其余为产品语义/架构/数据决策项，记延期。
- 高危项集中在三处：**导入链**（主线程 EPUB 解析 ANR、TXT 导入必然失败、结果静默、双击重复入库、切源竞态、解压炸弹）、**复习流**（双击把评分记到下一张未展示卡片、评分写库 fire-and-forget 静默丢分）、**词典下载**（无互斥并发写同一 .tmp 文件）。
- SWE 循环（PiAdapter）在已修复的 diff 上又抓出 **5 个真缺陷（1 high / 2 medium / 2 low）**，终审再抓 **3 条低危建议**，共 8 项全部修复 —— 含备份统计覆盖风险、SM-2 双写非原子、streak 算法三处复制等。
- 消化跨轮延期 3 项：**设置页滑杆防抖**（R6/R8 延期，复用 `persistSettingDebounced` 模式并补 reset 竞态）、**备份导入导出加固**（R6 相关项的彻底收口）、**提醒调度自愈**（首次安装永不排闹钟）。
- 新发现的自研回归：本轮新增的滑杆防抖会被 `resetToDefaults` 后的延迟写复活 —— 评审代理在代码提交前抓出，已随批修复。
- `Divider → HorizontalDivider` 改名在本仓库 BOM（2023.10.01 → material3 1.1.2）下不可编译（该 API 1.2.0 才有），已回滚并改列延期：1.1.2 中 `Divider` 并未弃用，升级 BOM 时一并处理。
- 门禁：`detekt` 0 issue，`testDebugUnitTest` 73/73 通过（与基线一致），每批修复后均复绿。
- SWE 循环与终审详情：见第 4 节。

## 1. 修复清单

### 1.1 导入链（P1×6 + P2×8 + P3×2）

| 级别 | 问题 | 修复 |
|------|------|------|
| P1 | `BookRepositoryImpl.addBook` 在 Main 线程跑 EPUB 解析（zip IO + 正则 + 词数统计），大文件 ANR | 整个 addBook 体包进 `withContext(Dispatchers.IO)` |
| P1 | 选择器与文案宣传 TXT 导入，但所有文件都走 `epubParser.parseBook`，TXT 必然 `导入失败: 文件读取错误` | `.txt` 走新增纯文本解析（按空行分段、字符上限），与 EPUB 段落结构对齐 |
| P1 | 导入成功/失败消息只在 `isLoading` 转圈分支渲染，结束后设置的消息永远不可见（URL 抓取失败完全静默） | LibraryScreen 增加 SnackbarHost：加载结束后以 Snackbar 呈现 loadingMessage，可点击关闭 |
| P1 | "加入书库"双击：成功集合要等抓取结束才写入，两次点击都过检查 → 重复入库；单 `isLoading` 被先结束的操作清掉 | 点击瞬间同步进 in-flight 集合去重；`activeImportOps` 计数器，全部操作结束才复位加载态 |
| P1 | `selectSource` 不取消上一次抓取，慢的旧源结果覆盖新选择（文章列表与头部不一致） | 保存抓取 Job，新选择先取消；结果落地前再核对当前选中源 |
| P1 | EpubParser 单文档有上限但 spine 条目数无上限 → 恶意 OPF 引用海量条目，总提取量无顶，OOM 炸弹 | 全书累计 10_000_000 字符上限 + 20_000 spine 条目上限，超限即停 |
| P2 | 文件拷贝无上限（SAF 可递数 GB 文件写满存储）；失败/取消后孤儿拷贝不清理 | 200MB 计数上限，超限中止；失败/取消路径删临时文件 |
| P2 | `deleteBook` 级联删库但从不删导入时拷贝的文件，存储泄漏 | 事务成功后清理 `filesDir/books` 内的对应文件（规范路径校验，绝不碰用户目录） |
| P2 | `extractSpineIds` 要求 idref 是首个属性，`<itemref linear="yes" idref="ch1"/>` 等合法 OPF 解析成空 spine | 正则改为属性顺序不敏感 + 单/双引号容忍（itemref 与 manifest 两处） |
| P2 | ArticleParser 顺序 replace 实体：`&amp;` 先解导致 `&amp;lt;` 二次解码成 `<` | 移植 EpubParser 的单遍实体解码（数字实体码点校验，不崩不串扰） |
| P2/P3 | `resolveUrl` 手写拼接 mishandle `./`、`../`、协议相对、带查询串 base；`RssItem` 死枚举；`ZipException` 死 catch | 统一 `java.net.URI.resolve`；删死代码；合并为单 IOException catch |
| P3 | spine href 未 URL 解码，`ch%201.xhtml` 静默跳章 | 解码后查条目，失败回退原串 |
| P3 | CJK 内容按空白切分整书报 1 词 | CJK 字符数 > 空白词数时按字符计词 |

### 1.2 复习流 SM-2（P1×2 + P2×3 + P3×3）

| 级别 | 问题 | 修复 |
|------|------|------|
| P1 | 双击评分记到下一张未展示卡片：AnimatedContent 淡出期间退场按钮仍可命中，`answerCard` 无 `isShowingAnswer` 守卫 | VM 侧 `!isShowingAnswer || isSubmitting` 不受理；UI 侧提交中禁用全部答案按钮 |
| P1 | 评分写库 fire-and-forget：写入失败/进程被杀时界面已推进，评分静默丢失，卡片原地"复活"；restart 与未落库写存在快照竞态 | 评分改为"先落库成功再推进"串行流程；失败保留当前卡片并显示可重试错误；写成功后才刷新 due 时间戳 |
| P2 | 按 `LOWER(word)` 字符串查词：同名多行取任意一行（可能无上下文），且 50 次全表扫描（N+1） | 新增 `getWordsByIds`，按 `vocabularyId` 一次批量取词组 Map |
| P2 | 复习只写 `review_records`，词汇表 `reviewCount`/`lastReviewTime` 永远为 0，两个子系统数据不一致 | 作答落库同时 `bumpReviewStats`（+1 / 刷新时间） |
| P2 | 加载失败被渲染成"太棒了！今日复习已完成"庆祝页，无重试 | `ReviewUiState.errorMessage` + 错误视图（重试/返回）；会话内写库失败也有卡片内提示 |
| P3 | VM 里与 `VocabularyRepositoryImpl` 重复且无调用点的 `addWordToReview` | 删除（统一走 repository 版本） |
| P3 | vocabulary 已删但复习记录仍在时，揭示答案是空白，用户盲评 | 显示"词条已删除，无法展示释义"占位 |
| P3 | Home/Library 的到期数把 `System.currentTimeMillis()` 冻结在收集起点，长停留页面角标不涨 | 移植 ReviewViewModel 的可刷新时间戳 + `flatMapLatest` 方案 |

### 1.3 设置页与提醒链（P1×3 + P2×9 + P3×7）

| 级别 | 问题 | 修复 |
|------|------|------|
| P1 | **新装设备提醒永不触发**：通知默认开，但 `scheduleReviewReminder` 只被开关切换/闹钟触发后/开机广播调用；不重启的新机没有任何路径排首个闹钟。强杀进程也会丢一次性闹钟 | 应用启动对账：开关开 → 补排（`FLAG_UPDATE_CURRENT` 幂等），开关关 → 补取消；兼作强杀自愈 |
| P1 | （本轮新增防抖的回归）`resetToDefaults` 不取消防抖待写项：拖过滑杆立刻重置，300ms 内排队的旧值会在 `clearAll` 后落盘，设置"复活" | 重置前先取消所有 `settingsPersistJobs` 并清空待写表 |
| P1 | 词典下载无互斥：双击、或刷新把下载中卡片翻回"下载"按钮再点，两个协程并发写同一 `.tmp`，产物损坏后被 rename 转正 | `downloadingIds` 集合互斥，已在途直接返回 |
| P2 | 重置后 `cancelReminder` 而默认值是"开启提醒"：开关显示开却永不提醒 | 重置改为补排闹钟 |
| P2 | 备份导入回调裸 `openInputStream`（文档删除/权限失效即崩）且整段拷贝在 Main 线程 | try/catch + Snackbar 报错，拷贝放 IO |
| P2 | 导入 `REPLACE` 会用备份字段覆盖本地行的复习进度/书籍关联 | 已存在的词跳过保留本地状态，回报跳过数 |
| P2 | 导出写了 `stats`，导入只读 vocabulary："导出词汇和阅读数据"恢复时静默丢一半；插入循环无事务 | 恢复阅读统计 + 整体 `withTransaction`（中途失败回滚） |
| P2 | 导出/导入/清缓存的文件 IO 全在 Main 线程 | 全部 `withContext(Dispatchers.IO)` |
| P2 | 下载结束→progress 置空→UI 翻回"未下载"，初始化窗口可并发二次下载 | 新增 `embeddedInitializing` 状态，下载/初始化共用互斥守卫与进度区 |
| P2 | `updateStatuses` 硬编码 `downloading=false`，任何刷新/删除/切换都抹掉在途下载状态（与 P1 互斥问题互为因果） | 重建时保留在途条目的 downloading/progress |
| P2 | `setActiveDict`/单例 init 在 Main 线程解析 manifest + 逐词典文件检查 | 状态重建丢后台调度器（`bgScope`），init 缓存加载同样后台 |
| P3 | Snackbar `scope.launch` fire-and-forget + 立即 dismiss：两条消息并发抢一个 SnackbarHostState，后到被吞（设置页与词典页） | 改为直接挂起 `showSnackbar` 后再清状态 |
| P3 | 任意 HTTP 200 都被当成词典写入转正（CDN 错误页/自举门户页） | 转正前最小格式校验（至少一行 `word|def`），否则删除并报错 |
| P3 | chunked 响应无 Content-Length 时定量进度条永远 0% | `-1f` 哨兵 → UI 换不定量指示器；定量分支限幅 0..1 |
| P3 | manifest 缓存直接 `writeText`，半截写入毁掉离线列表 | `.tmp` + rename 原子写 |
| P3 | `delete()` 返回 false（文件不存在）仍报"已删除词典"；下载/刷新 catch Exception 吞 CancellationException | 按返回值分文案；CE 单独 catch 重抛 |
| P3 | `lookup` 剥离所有非 a-z：`don't`/`ice-cream` 对保留这些字符的词典键永远 miss | 先试小写原形，未命中再退回剥离归一化 |
| P3 | 页脚版本写死 `v1.9.0`，与实际安装包漂移；buildConfig 未开启不能用 BuildConfig | PackageManager 动态取 versionName |
| P3 | `refreshEmbeddedStatus` 多文件存在性检查跑在 Main | `withContext(IO)` |
| P3 | 防抖写 DataStore 失败会炸到未捕获处理器 | 写失败捕获并 Snackbar 提示，会话内值保留 |
| P3 | 通知权限撤销竞态：`areNotificationsEnabled` 通过后 revoke，裸 `notify` 抛 SecurityException 崩 Receiver | `NotificationManagerCompat.notify` + SecurityException 兜底 |

另：`SettingsViewModel` 注入 `AppDatabase` 以支持事务化导入；`SettingsUiState` 增
`embeddedInitializing`；词典列表 `items` 加 `key = info.id`。

### 1.4 全局（P2×1 + P3×2）

| 级别 | 问题 | 修复 |
|------|------|------|
| P2 | 三处 `calculateStreak` 用毫秒差除以 86_400_000：夏令时 23/25 小时日把"漏读一天"算成连续，未来日期记录混入（Home/Library/Settings 同型） | 改为 Calendar 逐日回退比对日期串；"今天未读不算断"的容差语义保留 |
| P3 | DAO provider 未加作用域（DB/Repository 均单例） | 8 个 `provideXxxDao` 补 `@Singleton` |
| P3 | `Divider` 弃用改名的延期项（R8）—— 本仓库 BOM 2023.10.01 = material3 1.1.2，**没有** `HorizontalDivider`（1.2.0 才引入），且 1.1.2 中 `Divider` 未弃用 | 改名不可编译，已回滚；延期至 BOM 升级时批量处理（见第 3 节） |

## 2. 验证结果

```
./gradlew detekt testDebugUnitTest        # JDK 17
BUILD SUCCESSFUL
- detekt: 0 issue
- 单测: 73 passed, 0 failed（5 个测试类，与基线一致）
- compileDebugKotlin: 无错误
```

每批改完先跑 `compileDebugKotlin`（共 5 轮编译迭代，含一次
HorizontalDivider 不可编译的回滚），全量门禁最终一次通过。

## 3. 延期项

| 项 | 理由 |
|----|------|
| `Divider → HorizontalDivider` | BOM 2023.10.01（material3 1.1.2）无此 API 且未弃用旧名；升级 BOM 时批量改名 |
| SM-2：q<3 当节内重排 / 到期基准改日历日翻转 | 正统 SM-2 即"忘了→次日"，改当节重排与日历基准属学习策略产品决策 |
| 复习会话 50 张上限与角标不一致的提示 | 交互设计（"还有 N 张，再来一轮"）需产品定稿 |
| 复习会话进程死亡恢复（SavedStateHandle） | 已答卡片已落库，重开安全；体验增强项 |
| 重复导入检测（同一本书/文章二次导入） | 需持久化来源标识（sourceUrl/内容哈希），schema 变更 |
| EPUB/网页非 UTF-8 字符集探测 | 需移植 RssParser 的 charset sniff 并重构解码路径，中等改动；默认 UTF-8 覆盖绝大多数场景 |
| EpubParser 按 `META-INF/container.xml` 定位 OPF（当前取首个 `.opf`） | 多 rendition 容器才触发，P3 增强 |
| `getAllBooks` 列表查询拖出整本书 `content` 列 | 架构级（content 拆表/投影），与延期项"DAO/Repository 接缝"同批处理 |
| `deleteBook` 保留该书的 vocabulary/review_records | 疑似产品意图（删书不丢已学词），待产品确认后再定清理策略 |
| `ReadingStatsDao.insertStat/deleteForBookAndDate` 公共面收敛 | 删除型改动，独立提交更清晰 |
| `searchBooks` LIKE 通配符转义 | 低危边界项 |
| RssParser：仅接受 HTTP 200（不跟 http→https 301）、日期解析失败回退 `now()` | 现有单测明确断言回退行为；改动属语义变更，待排序真用上时间戳时再改 |
| NPR 预置双源同 URL / MANIFEST_URL 钉在 `@branch` | 数据决策（换源/钉 commit），需确认后改 |
| 自定义文章来源持久化 | `ArticleSources` 为硬编码预置，功能缺失而非缺陷，需产品决策 |
| HomeViewModel `refresh/isLoading` 无接线 | 需下拉刷新交互设计 |
| `getAllStats` 全量载入算统计 → SQL 聚合 | 性能优化，数据量上来再做 |
| 词典文件哈希校验（manifest 加 sha256） | 加固项，需同步改词典生成脚本 |
| DictionaryManager 单测 | 补测试项，独立提交 |
| SettingsViewModel 职责拆分（防抖持久化/备份导入导出抽为独立协作者） | 终审建议，非本轮阻塞项；类内职责聚合是存量结构，拆分属架构整理，独立窗口做 |
| TTS 语速设置 UI / 模型选择列表（R7/R8 遗留） | 需 UI 决策 |
| 双击判定 ~300ms 延迟（R6 遗留） | `detectTapGestures` 固有，体验取舍 |
| `books.lastReadPosition` 移除（R6-R8 遗留） | 7→8 迁移重建表，风险/收益不划算 |
| `uiState` 单体重组热点、`ttsPrompt` 旋转丢失、TtsInstallDialog 按钮溢出、FuzzyReadingView blur、playPcm 名义时长（R8 遗留） | 架构/体验取舍项，维持延期 |

## 4. SWE 循环（PiAdapter）

配置：`LoopSkill(strategy="review_guided", max_iterations=3)`，
评审/修订均走 `PiAdapter(timeout=900)`（pi CLI，宿主模型继承，未指定模型），
`git diff`（19 文件，+900/-250）作为 `initial_pr` 注入。

| 迭代 | 阶段 | 决定 | 置信 | 说明 |
|------|------|------|------|------|
| 1 | review | `request_changes` | 0.72 | 5 个缺陷（1 high / 2 medium / 2 low） |
| 1 | revise | `failed` | — | pi 未产出补丁（empty diff），缺陷转人工处置 |
| 3 | verify | `request_changes` | — | "Failed to apply patch" —— 已知假阴性：沙箱拷贝工作区含未提交改动，同一 diff 二次 apply 必失败；真实验证以 gradle 门禁为准 |

循环指出的 5 个缺陷**全部为真问题**，逐条人工修复后门禁复绿：

| 级别 | 缺陷 | 修复 |
|------|------|------|
| high | 备份恢复阅读统计直接 `insertStat`（REPLACE 策略）：撞 (bookId,date) 唯一索引语义风险 + 会静默覆盖本地当天真实数据，与词汇"保留本地"语义不一致 | 恢复前先查 `getStatForBookAndDate`，已有则跳过；Snackbar 汇报统计导入/跳过条数 |
| medium | 新 `calculateStreak` 日历日算法在 Home/Library/Settings 三处逐字复制，口径修正需同步三改 | 收敛为 `util/ReadingStreak.calculate` 单一实现，三个 ViewModel 委托 |
| medium | SM-2 评分两次写库（`updateReview` + `bumpReviewStats`）非原子：第一次成功第二次失败后重试会在已推进记录上再套一次算法，间隔被错误放大 | 注入 `AppDatabase`，两次写包进 `database.withTransaction` |
| low | `onCleared` 冲刷防抖待写项未逐条隔离异常，一条失败丢弃其余 | 冲刷循环逐条 try/catch（CE 重抛） |
| low | `DictionaryManager.download` 准入后多个提前 return 各自手动清理 `downloadingIds`，窗口内意外异常会让 dictId 永久残留、该词典再也不能下载 | 准入后整体包进单一 `try/finally`，唯一清理点 |

修复后重跑门禁：`detekt` 0 issue、73/73 单测、编译通过。

最终确认（一）：单独运行 `ReviewSkill(prompt_style="engineering")` 终审 ——
`approve_with_suggestions`（置信 0.86，评分 88），3 条低危建议，均为真问题，
逐条修复：

| 级别 | 建议 | 修复 |
|------|------|------|
| low | `answerCard` 成功分支不清 `errorMessage`：保存失败后重试成功，旧错误文案残留在后续每张卡片顶部 | 成功推进分支补 `errorMessage = null`，提示生命周期与实际错误状态绑定 |
| low | ArticleParser 新增的实体表/单遍解码与 EpubParser 已有实现逐键重复（本轮刚在 streak 上消除的复制模式） | 提取 `util/HtmlEntities.decode` 共享实现，两条导入路径复用同一份实体表与解码行为 |
| low | 备份导入循环内逐词 `getWord`（LOWER 无索引全表扫描）是 O(n²)，且 JSON 解析留在 Main | 新增 `getAllWordsLowercase()` 一次性预加载判存集合走内存判存；JSON 解析并入 IO 上下文 |

修复后再跑门禁：`detekt` 0 issue、73/73 单测、编译通过。

最终确认（二）：再次单独运行 `ReviewSkill` —— `approve_with_suggestions`
（0.7，评分 82），2 条低危：其一为 SettingsViewModel 职责拆分（明确
"非本轮阻塞项"，已入延期表）；其二为误报 —— `resetToDefaults` 走的
`settingsRepository.clearAll()` 内部已调用 `ReminderPrefs.setEnabled(context, true)`
（SettingsRepositoryImpl 注释在案），接收端镜像随重置确定性置真。

最终确认（三）：携上述两点在库证据再次运行 `ReviewSkill` ——
**`APPROVE`，置信 0.86，0 缺陷**。

**终审结论**：`APPROVE` / 0.86 / 0 缺陷。循环 verify 假阴性（同一
diff 对已含改动的工作区副本二次 apply 必失败）不影响结论，
以 gradle 门禁 + 三轮终审为准。

**决定**: `APPROVE` —— 未覆盖屏幕深评 49 项发现全部闭环（11×P1 全修 +
低风险 P2/P3 收口），跨轮延期消化 3 项，SWE 循环抓出的 5 个残余缺陷
（含 1 个 high）与终审 3 条建议全部修复，门禁保持全绿。
