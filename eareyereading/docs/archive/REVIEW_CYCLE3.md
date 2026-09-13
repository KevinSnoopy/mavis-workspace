# eareyereading — Round 3 全量 SWE 循环报告

> **评审时间**: 2026-08-30
> **基准**: `REVIEW_CYCLE2.md`（8 维 78 分，APPROVE_WITH_SUGGESTIONS，commit `a804800`）
> **本轮目的**: 全量代码再评审 + 修复落地 + 验证闭环（Generate → Review → Revise → Verify）
> **评审方法**: 6 个并行评审分片逐行扫描全仓 63 个 Kotlin 文件 + 构建/测试/CI/脚本，
> 修复后以 `./gradlew detekt testDebugUnitTest`（JDK 17）做验证门禁，迭代至全绿

---

## TL;DR

全量评审发现 **116 个新问题（0×P0，23×P1，52×P2，41×P3）**。本轮落地修复
**23/23 个 P1 + 30 余个低风险 P2 + 约 20 个 P3**，涉及 30+ 文件；其余为需要
DB migration、大型重构或产品决策的项，已列出并明确延期理由。修复后
**detekt 0 issue（门禁由"永不失败"改为真实生效）+ 68 个单测全部通过**。

验证过程中还额外暴露并修复了 2 个静态评审未发现的真实 bug
（见「验证循环的额外收获」）。

---

## 1. 评审分片与发现分布

| 分片 | 范围 | P1 | P2 | P3 | 合计 |
|------|------|----|----|----|------|
| T | TtsHelper / EmbeddedTtsEngine / TtsEngineHelper / Tts.kt | 3 | 12 | 3 | 18 |
| R | ReaderViewModel / ReaderScreen / Navigation / MainActivity / App | 5 | 9 | 7 | 21 |
| D | data 层（DAO/entity/repository/DI） | 1 | 7 | 2 | 10 |
| U | util 解析器与工具（Rss/Article/Epub/Dictionary/Translation/Collins/WordAnalyzer/PosTagger/Notification） | 5 | 11 | 13 | 29 |
| S | Home/Library/Settings/Dictionary/Review/Vocabulary 屏 + receivers + theme | 7 | 5 | 8 | 20 |
| B | 单测 / build.gradle / detekt / manifest / CI / scripts | 2 | 8 | 8 | 18 |
| **合计** | | **23** | **52** | **41** | **116** |

---

## 2. 已修复（按分片）

### T — TTS 子系统
- **T-01 [P1]** `TtsHelper.scope` 改为可换新：`shutdown()` 先换新 scope 再 cancel 旧的。
  原实现 shutdown 后单例 scope 永久失效，后续所有 embedded 朗读静默不执行。
- **T-02 [P1]** `EmbeddedTtsEngine.initialize()` 释放旧 native 实例改为在 `speakMutex` 锁内，
  堵住模型切换与 `generate()` 并发的 JNI use-after-free（SIGSEGV 同类问题）。
- **T-03 [P1]** 语言不支持时先尝试英语回退，只有英语也不支持才失败。
  原实现在 `setLanguage(Locale.US)` 成功后仍销毁引擎，`isInitialized=true` 但 `tts=null`，朗读永久静默。
- **T-04** AudioTrack 双重释放竞态：等待循环只在锁内确认所有权后释放；被打断分支不再二次 `release()`。
- **T-05** embedded 句子链 `try/finally` 保证 `onAllDone()` 在被 `stop()` 取消时也会触发（原实现阅读页永久卡在"朗读中"）。
- **T-06** SYSTEM 链 `stop()` 后引擎只回 `onStop`：新增 `activeChainOnAllDone` 补偿调用，链不再悬挂。
- **T-07** 新增 `embeddedSpeakJob`：`speak()`/`stop()` 取消停在互斥锁上的过期朗读，消除乱序音频。
- **T-08** `shutdown()` 先 `resume(false)` 所有等待初始化的协程再清空队列（原实现挂到 15s 超时）。
- **T-09** 切回 SYSTEM 成功时释放 embedded 模型（上百 MB native 内存不再滞留）。
- **T-10** `getCurrentModelInfo()` 全程 `firstOrNull` 兜底（陈旧模型 id 不再抛异常，且该方法在组合期被调用）。
- **T-11** 下载字节循环每 256KB 检查协程取消；取消异常不再被 `catch (Exception)` 吞掉。
- **T-12** 下载成功改发可划掉的普通通知，ongoing 进度通知随成功/取消退出（原实现永久常驻）。
- **T-13** tar 解压改 canonical 路径校验 + 跳过符号链接条目（原 `contains("..")` 检查既误杀又漏防）。
- **T-14** tarball 镜像列表过滤非 `.tar.bz2` URL（MeloTTS 的裸 model.onnx 镜像必然解压失败）。
- **T-15** 引擎综合扫描放 `Dispatchers.IO`，不再卡 Main。

### R — 阅读器
- **R-01 [P1]** `loadBook` 对持久化位置做 `coerceIn` 收敛（内容重导入后索引越界、Slider 越界、标签错乱）。
- **R-02 [P1]** RSVP/速读/朗读/自动朗读四条播放路径的 TTS 初始化纳入被追踪的 job：
  初始化窗口内连点先取消第一次尝试，杜绝两条并发播放循环交替 `speak()`。
- **R-03 [P1]** 每日统计改为**累计**：读出已有 (书,日期) 行叠加本会话，原实现 delete+insert
  只保留最后一次会话（早上读的时长被晚上抹掉）。
- **R-04 [P1]** `loadBook` 全包 try/catch，损坏 EPUB/DB 异常不再经未捕获处理器崩 App。
- **R-05 [P1]** RSVP 视图与 VM 统一用 `[a-zA-Z]+` 分词：原实现按空白切分，"don't" 等缩写
  导致显示空白 + 进度条超过 100%。进度值另加 `coerceIn(0,1)` 防御。
- **R-06** setter 持久化收敛后的值（原实现 UI 显示收敛值、存储原始值，下次启动越界值回流）。
- **R-07** 设置弹窗 Slider/列表索引全部 `coerceIn`（DataStore 历史越界值不再让弹窗一开就崩）。
- **R-08** 已打开书籍时全局设置流不再覆盖书籍自带的 `rsvpSpeed`（双写竞态）。
- **R-09** `statsFlushed` 单飞标记：`cleanup()` 的 onDispose + onCleared 双路径不再并发写坏统计表；
  `cleanup()` 补齐新增 job 的取消。
- **R-10** 高亮渲染按运行游标收敛：重叠高亮不再重复输出文本，负值/反向/越界偏移不再让 `substring` 崩溃。
- **R-12** `saveProgress()` 300ms 防抖（进度条拖动不再每像素写库；退出路径直调不受影响）。
- **R-13** 内置模型下载加 `downloadJob` 防重入。
- **R-14** RSVP 间隔计算对 `rsvpSpeed` 做 `coerceIn(100,800)`（存储值 0 会除零）。
- **R-15** 翻译捕获链补 `CancellationException` 重抛。
- **R-16** RSVP 暂停同时 `ttsHelper.stop()`（原实现最后一个词继续播）。
- **R-17** `setReadingMode` 复位 `isPlaying`（原实现按钮卡在暂停图标）。
- **R-18** 书签切换串行化单 job（双击不再产生重复书签）。
- **R-19** 首页/书库快捷导航与底栏共用 `navigateToTopLevel`（popUpTo+singleTop+restoreState），
  连续点击不再堆叠重复路由。
- **R-20** 删除死代码：`ttsInitLock`、从未被调用的 `VocabularyBar`、为它预留的 72.dp 底部空白。
- **R-21** 统一总字符口径（`\n\n` 拼接）、句子边界正则提升为常量、`hasTranslation` 直接派生、
  init 各 collector 补异常防护。

### D — 数据层
- **D-01 [P1]** Home/Library 的今日/周统计改用 `SUM` 聚合查询：原实现取单行，
  一天读多本书时少报（正确的 SUM 查询本来就有，只是没人用）。
- **D-02** `ReadingRepository.updatePosition` 补 `paragraph` 参数（原实现硬编码 0 会抹掉段落进度；当前无调用方，属排雷）。
- **D-03** `deleteBook` 级联补 `reading_stats`（原实现删书后"总书数"永久虚高）。
- **D-07** 统计落库配合 R-03/R-09 重构（读旧行 → 累计 → 删插），`sessionCharsRead` 落库后清零。
- **D-08** `addBook` 文件解析失败抛 `IOException` 让 UI 提示，不再静默创建 0 词空书。
- **D-09** `searchBooks` 补 `ORDER BY lastReadTime DESC` 并排除归档书（与库列表口径一致）。
- **DAO** `ReadingStatsDao` 新增 `getStatForBookAndDate` / `deleteForBook`，删除误导性的
  `getStatsForDate`（单行语义曾被当作日聚合用，即 D-01 根因）。

### U — 解析器与工具
- **U-01 [P1]** RssParser 处理自闭合标签（`<link .../>`、`<description/>`）：
  KXml2 不发 END_TAG，原实现进入采集态出不来，Atom feed 后续字段全部错位。
- **U-02 [P1]** ArticleParser 相对链接用 `URI.resolve` 补全（原 `if/else` 两支相同，纯死代码）。
- **U-03 [P1]** EpubParser 数字实体 `toIntOrNull` + 码点范围校验 + `Character.toChars`：
  恶意 `&#99999999999;` 不再炸 App，增补平面字符不再截断。
- **U-04 [P1]** DictionaryManager 下载改临时文件 + 原子改名（半截词典不再被当完整词典永久加载）。
- **U-05 [P1]** manifest 的 `fileName` 去路径段 + canonical 路径校验（堵住远端 manifest 路径穿越写/删沙箱文件）。
- **U-06** RssParser 响应体 10MB 上限 + 响应码检查（防超大 feed OOM）。
- **U-07** `parseDate` 每次调用新建 `SimpleDateFormat`（共享静态实例非线程安全）。
- **U-08/U-09** ArticleParser/EpubParser 读取加字符上限（超大页面/压缩炸弹不再 OOM）。
- **U-10** EPUB 章节按 OPF 目录解析全路径（`1.xhtml` 不再误配 `ch11.xhtml`），片段 `#sec2` 先剥离（U-21）。
- **U-11** DictionaryManager 网络请求补 `disconnect()` + 响应码检查。
- **U-12** ML Kit 初始化 `AtomicBoolean.compareAndSet`（并发首次翻译不再双初始化泄漏 Translator）。
- **U-13** ML Kit `translate` 加 20s 超时（GMS Task 挂死不再无限挂起调用方）。
- **U-14** 所有逻辑相关的 `lowercase()` 统一 `Locale.ROOT`（土耳其语设备 I→ı 变体不再破坏查词/分级）。
- **U-15** Collins 分级集在构造时统一小写归一（32 个大写条目如 "January"/"kWh" 原永远无法命中）。
- **U-17** UTF-16 BOM 检测（声明嗅探按 ASCII 解码，NUL 交错的声明永远匹配不到）。
- **U-18** charset 解析容忍带引号的 `charset="utf-8"`（RFC 合法写法原直接抛异常）。
- **U-19** JSON-LD `articleBody` 匹配跳过转义序列 + 完整反转义（原在第一个 `\"` 处截断）。
- **U-20** EpubParser 实体解码改单遍（`&amp;#39;` 不再被二次解码）。
- **U-22** `_statuses` 改 `StateFlow.update` 原子更新。
- **U-24** `estimateReadingLevel("")` 显式返回 "Easy"（原 NaN 落 else 误报 "Advanced"）。
- **U-25** 挖空自动选择先去重再采样（重复词不再吃掉隐藏名额）。
- **U-26** PosTagger 重复键显式去重（"most"→NUMERAL、"then"→ADVERB 语义钉住，配测试）。
- **U-28** Collins 字母正则提升为常量（大文本逐词分类不再重复编译）。
- **U-29** `cancelReminder` 改 `FLAG_NO_CREATE`（不再为了取消反而创建 PendingIntent）。
- 模糊文本随机边界修正：`nextFloat() < (1 - visibleRatio)`（visibleRatio=0 时不再概率漏词）。

### S — 屏幕与接收器
- **S-01 [P1]** 全部 ViewModel 的裸 `viewModelScope.launch { flow.collect() }` 补
  `CancellationException` 重抛 + 兜底捕获：Home/Library/Settings/Vocabulary/Dictionary/Review
  共 10+ 处，数据层异常不再经未捕获处理器崩 App（Home 是启动页，原本一崩全崩）。
- **S-02 [P1]** 导入 `finally` 不再清空错误信息（用户终于能看到导入失败原因）。
- **S-03 [P1]** EPUB 整文件拷贝放 `Dispatchers.IO`（主线程拷大文件 ANR）。
- **S-04 [P1]** 导入文件名加时间戳前缀 + 去掉路径分隔符：SAF 的 `lastPathSegment`
  常是裸文档 id，原实现会静默覆盖上一本书的文件（数据丢失）。
- **S-05 [P1]** 备份导出/导入改用 `org.json`：原手写 JSON 只转义双引号（产物非法），
  导入按字段正则索引配对（释义含 `"word":"..."` 即整体错位批量损坏）。导入同时补回 `example` 字段。
- **S-06 [P1]** 备份改写 `getExternalFilesDir`（文件管理器可取走），不再放"清除缓存"会清掉的 `cacheDir`。
- **S-07 [P1]** `BootReceiver` 读 `ReminderPrefs` 同步镜像，用户关闭提醒后开机不再私自重排闹钟。
  （新增 `ReminderPrefs`：`SettingsRepositoryImpl` 写 DataStore 时同步镜像到 SharedPreferences。）
- **S-08** 删除与"复习间隔提醒"共用同一布尔、且绕过通知权限检查的重复"连胜提醒"开关。
- **S-09** 缓存目录大小移到 ViewModel 异步计算（原实现在组合期每次重组遍历磁盘）。
- **S-10** 各 `catch (RuntimeException)` / `catch (Exception)` 补 `CancellationException` 重抛。
- **S-11** `loadDueReviews` 补异常防护（失败降级为空会话，不再崩）。
- **S-13** Home `refresh()` 同时取消两个加载 job（原实现只取消一半，旧回调会抢新状态的 loading 标记）。
- **S-15** 复习间隔乘法先转 `Long`（EF 无上限增长时 Int 溢出会把下次复习算进过去）。
- **S-16** `NotificationReceiver` 先查用户开关 + 系统通知权限，关闭时终止每日提醒链。
- **S-17** 时区/系统时间变化也重排闹钟（manifest 注册 `TIMEZONE_CHANGED`/`TIME_SET`）。
- **S-18** 首页日期文本 `remember` 缓存（不再每次重组 new SimpleDateFormat）。
- **S-19** 笔记对话框 `remember` 带数据键（外部更新不再显示陈旧初值）。

### B — 构建 / 测试 / CI / 脚本
- **B-01 [P1]** CI workflow 增加 `testDebugUnitTest` 步骤 + Android SDK setup（此前 CI 从不跑单测）。
- **B-02 [P1]** 移除 `ignoreFailures = true`：detekt 门禁真实生效（`maxIssues: 0` 不再是死配置）。
- **B-04** `parseDate` 测试改精确 epoch 断言（原 `> 0` 断言在"失败回退 now"下毫无判别力）。
- **B-05** 空文本阅读等级契约钉死 "Easy"（原测试接受任何结果）。
- **B-06** 挖空测试补正向断言（原只有负向断言，隐藏管线全坏测试也绿）。
- **B-07** `resolveCharset` 提升为包可见 + 新增 6 个用例（GBK 头优先、XML 声明、非法名回退、引号、UTF-16 BOM×2）。
- **B-08** 删除 GitHub 永不读取的嵌套 `.github` 配置；根 workflow 改用仓库 wrapper（消除 8.5 vs 8.2 漂移）。
- **B-09** `download-sherpa-onnx.sh`：`curl -f`、暂存区校验后再动仓库 `.so`、Tts.kt 内容校验（不再把 404 页面提交进源码树）。
- **B-10** PosTagger 补 "most"/"then" 去重语义测试。
- **B-11** manifest 移除冗余 `USE_EXACT_ALARM` 与未使用的 `READ_MEDIA_IMAGES`。
- **B-15** `ci_check.sh` 定位项目根 + 跑 detekt 和单测，任一失败即非零退出。
- **B-16** 词典生成脚本：显式查找 `.db` + 明确报错、流式解压（600MB 不再整体读入内存）。
- **B-17** `ReadingMode` 9 个持久化值全部钉住 + 唯一性/小写契约测试（原只钉 4 个）。
- 新增 Atom 自闭合回归测试、无时区格式按本地时区口径断言等。

---

## 3. 验证循环的额外收获（静态评审没抓到、被严格测试逼出来的）

1. **RFC3339 偏移归一 bug**：`+00:00 -> +0000` 的正则替换写成只保留 `$1`（`+00`），
   非法偏移导致所有带冒号时区的 RFC3339 时间戳静默回退成 `now()`——feed 排序整体失真。
   精确 epoch 断言第一次运行就把它打了出来。
2. **GMT 尾部被静默吞掉**：`SimpleDateFormat.parse` 容忍尾部未消费文本，`... 12:00:00 GMT`
   的区段解析失败后按**设备本地时区**解释时间并忽略 " GMT"，RSS 最常见的 GMT 时间整体偏移数小时。
   已在 `parseDate` 里把 `GMT/UTC/UT` 归一为 `+0000`。

这正是本轮把 `> 0` 弱断言换成精确断言的价值：**门禁从"装饰"变成"抓 bug"**。

---

## 4. 延期项（明确不做 + 理由）

| ID | 内容 | 理由 |
|----|------|------|
| D-04/D-05/D-06 | review_records 唯一索引、热查列加索引、exportSchema | 需要 Room migration 5→6 + 回归验证，风险/收益比不适合放进本轮；建议下一轮单独做 |
| R-11 | NormalReadingView 改 LazyColumn | 涉及滚动状态/自动滚屏逻辑重构，属性能重构而非缺陷修复 |
| R-18(根治) | 书签唯一索引 | 同 D-05，需要 migration；本轮已用串行 job 消除症状 |
| S-12 | "加入书库"乐观状态按结果驱动 | 需要 VM 侧按文章粒度状态建模，UI 改动面大 |
| S-14 | dueCount 长会话过期 | 低频、无害（下拉/重进即刷新） |
| S-20 | 删除操作确认弹窗/撤销 | 交互增强，非缺陷 |
| T-16 | vendored Tts.kt 的 release 竞态 | 第三方源码，侵入修改收益低 |
| T-17/T-18 | 引擎 enabled 判定细化 / tarball 进度口径 | 体验微调 |
| U-16 | Collins 跨层重复词去重 | 改变 ~2500 词的分级结果，属产品语义决策（低层命中优先 vs 高层优先），需产品确认 |
| U-27 | tagSentence 返回原始大小写 | 现有单测明确钉住小写契约，且当前无 UI 消费该输出 |
| B-03 | detekt 恢复默认规则集 | 会一次性暴露大量 style/complexity findings，`maxIssues:0` 下直接堵死构建，应分步灰度 |
| B-12/13/14/18 | backup 规则/lint 块/minify/Jetifier | 需要独立验证窗口（尤其 minify 牵动 R8 规则） |

Round 1/2 已决议的延期项（F-09~F-12、F-14/F-15）维持不变，不重复列出。

---

## 5. 验证结果

```
./gradlew detekt testDebugUnitTest        # JDK 17（全局 gradle.properties 指向 JBR 21 会挂
                                          # AGP 的 JdkImageTransform，本机需 -Dorg.gradle.java.home=<jdk17>）
BUILD SUCCESSFUL
- detekt: 0 issue（maxIssues: 0，ignoreFailures 已移除 → 门禁真实生效）
- ModelsTest 10 / PosTaggerTest 9 / RssParserTest 23 / WordAnalyzerTest 26 — 68 passed, 0 failed
```

验证迭代记录：4 轮（编译错误 1 次：测试用 `Charsets.forName`；测试失败 2 次：即第 3 节两个真实 bug）。

---

## 6. 决定

**等级**: `APPROVE_WITH_SUGGESTIONS`

- 23/23 P1 关闭；52 个 P2 中低风险项全部落地，结构性/迁移类列入延期清单。
- 质量门禁从"永不失败"变为真实门禁（CI 跑单测 + detekt 硬失败）。
- 所有修改保持"加法/语义对等健壮性增强"原则；唯一行为面变化是备份导出位置
  （cacheDir → getExternalFilesDir）与重复开关删除，均为修复语义本身。

**下一轮建议**（Round 4 scope）：Room migration 6（唯一索引 + 热查列索引 + exportSchema）、
NormalReadingView LazyColumn 化、detekt 默认规则灰度、targetSdk 35 评估。
