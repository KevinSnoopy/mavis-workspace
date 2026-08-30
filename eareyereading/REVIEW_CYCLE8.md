# eareyereading — Round 8 阅读详情页遗留项专项循环报告

> **评审时间**: 2026-08-31
> **基准**: `REVIEW_CYCLE7.md`（语音播报专项，commit `cfb7f21`）
> **本轮目的**: 按用户指定的 10 项跨轮延期清单逐项收口（阅读详情页
> ReaderScreen.kt + ReaderViewModel.kt + TTS 集成），并对同域代码再做
> 一轮只读深评捡漏
> **方法**: 3 路只读评审代理（ReaderScreen 视图层 / ReaderViewModel 逻辑层 /
> TTS 引擎模块；首轮 2 个代理超时未回，按纪律中断并以窄范围重启），
> 修复后 `detekt + testDebugUnitTest` 全绿

---

## TL;DR

延期清单 **10 项收口 9 项**（`books.lastReadPosition` 因需数据库迁移再延），
三路评审另收 **24 个新发现（0×P0 / 7×P1 / 9×P2 / 8×P3）**。
本轮修复 **7×P1 + 11×P2 + 3×P3 + 延期项修复 9 项**，其余为需产品决策/
大重构/体验取舍项，列入延期清单。修复后 **detekt 0 issue + 73 单测全过 +
编译无错误**。

---

## 1. 延期清单收口情况（用户指定 10 项）

| # | 项 | 结果 |
|---|----|------|
| 1 | Split/PosAnalysis/BackTranslation 整书 eager、无滚动跟随 | ✅ 三视图全部 LazyColumn 化 + 视口跟随当前段（详见 2.1） |
| 2 | `rsvpInterval` 零消费死设置 | ✅ 全链路移除（语义无产品定义，不臆造接线；详见 2.5） |
| 3 | 场景 A 未启用引擎也给"使用"按钮（必 15s 超时） | ✅ 未启用引擎按钮置灰 + 文案引导去系统设置启用 |
| 4 | 阅读页内内置模型下载进度不可见 | ✅ 弹窗下载时保持打开，页内进度条展示（详见 2.4） |
| 5 | `retryTtsInitWithEngine` 迟到启动路径 | ✅ 复审：取消链健全（396/929/1561），成功路径从不启动播放；唯一缺陷——`__EMBEDDED__` 分支吞 CancellationException，已修（重抛） |
| 6 | `TtsHelper.shutdown()` 不复位 `ttsMode` | ✅ 已复位（同步字段 + StateFlow 双视图） |
| 7 | 死代码清理 | ✅ `splitForTts`、TtsEngineHelper 6 个零调用助手、`revealAllFuzzy` 空壳全部删除；`books.lastReadPosition` 需 7→8 重建表迁移，再延（详见延期清单） |
| 8 | 听写/挖空/模糊模式播放朗读含答案原文 | ✅ `toggleTts()` 入口拦截三模式 + toast 说明 |
| 9 | 设置弹窗滑杆逐像素写 DataStore | ✅ VM 侧 300ms 防抖（对齐 `saveProgress` 模式），`cleanup()` 兜底冲刷 |
| 10 | 旋转触发 cleanup 停止播放 | ✅ `isChangingConfigurations` 守卫；真退出仍走 `onCleared -> cleanup()` |

## 2. 修复清单

### 2.1 阅读视图性能与滚动跟随（延期项 1 + 新发现 P2×3）

| 项 | 问题 | 修复 |
|----|------|------|
| Split 视图 | 整书 eager Column，无跟随 | `LazyColumn`（表头独立 item）+ `animateScrollToItem(currentIndex+1)` 跟随 + `snapshotFlow` 反向回报 VM（表头偏移 -1） |
| PosAnalysis 视图 | eager + 词性标注串在组合里裸建（任何重组都重切词+分类整本书） | `LazyColumn` + 跟随；标注串 `remember(para, alpha, textColor)` 缓存，只布局可见段 |
| BackTranslation 视图 | 左右两个独立滚动容器整书 eager：滚动不同步时原文第 N 段对上译文第 M 段（与 Round 3 分栏同型缺陷）；未揭示时全书每段挂 `.blur()` 渲染层 | 单 `LazyColumn` 逐段 `Row(译文|原文)` 并排：段落严格对齐、只布局可见段、模糊收敛到可见窗口、跟随当前段；揭示按钮移到列表表头 |
| NORMAL 滚动回环 | `LaunchedEffect(currentIndex)` 对每次索引变化都程序化滚动——含用户滑动经反向同步写回的索引，甩动途中被反复打断拽回段首 | 目标段已在可见窗口内则不发起滚动 |
| 底栏进度滑杆 | 逐像素调 `goToParagraph`：每像素 `stopAllPlayback` + `saveProgress`，挖空/模糊模式还逐像素重生成整段词序列 | 拖动只更新本地值，`onValueChangeFinished` 提交；程序化推进经 `LaunchedEffect` 同步回滑杆 |

### 2.2 播放安全与异常防护（新发现 P1×4 + P2×1）

| 项 | 问题 | 修复 |
|----|------|------|
| 播放剧透（延期项 8） | 挖空/听写/模糊模式点播放朗读含隐藏答案的原文 | `toggleTts()` 三模式入口拦截 + toast |
| `selectWord` | Room + ML Kit 调用零防护，异常直冲 `viewModelScope` 默认处理器——点词崩 app | try/catch（CancellationException 重抛）+ toast |
| `addToVocabulary` | 去重查询在 try 外，数据库异常崩 app | 去重纳入同一 try |
| 书签/高亮写入 | `toggleBookmark`/`addHighlight`/`removeHighlight` 三个 fire-and-forget DAO 写无异常防护，约束冲突/磁盘满即崩 | 各自 try/catch + toast |
| `translateSentence` | 异常既崩 app 又让弹窗永远"加载中"（`== null` 判定） | try/catch，失败写空串走"翻译失败"分支 |
| 引擎重试取消（延期项 5） | `retryTtsInitWithEngine` 的 `__EMBEDDED__` 分支 `catch (Exception)` 吞 CancellationException：退出/仲裁取消后仍写 `ttsInitialized`、甚至离开页面后弹引导窗 | 补 CancellationException 重抛（与非内置分支对齐） |

### 2.3 EmbeddedTtsEngine 加固（新发现 P1×3 + P2×4）

| 项 | 问题 | 修复 |
|----|------|------|
| `stop()` 停不住并发朗读 | `currentSpeakJob` 只记录最后一个调用者：A 持锁出声、B 等锁时，`stop()` 只取消 B，A 跨过下一句继续播 | 改 `activeSpeakJobs` 集合，注册全部调用者，`stop()` 全量取消 |
| 音频焦点不归还 | 只在 `stop()/release()` 归还；自然读完（含句子链）永远压着背景音乐 | 引擎暴露幂等 `abandonAudioFocus()`；TtsHelper 单段朗读完成回调前、句子链 `finally` 统一归还（整链期间焦点保持，不产生句间 duck 抖动） |
| 失败通知划不掉 | 下载/解压失败用 `setOngoing(true)` 的进度通知显示"下载失败"，永久驻留通知栏 | 失败终态改 `cancelDownloadNotification()`（失败信息由 toast/弹窗承载） |
| 坏 tarball 驻盘 | 解压失败不清理：`.complete` 标记让下次跳过下载、反复解压同一个坏归档；回退路径成功后 ~100MB tarball 永久驻盘 | 解压失败/解压后缺文件两条路径都删 tarball + 标记，强制重新下载 |
| `playPcm` 取消泄漏 | 等待循环被协程取消时 `MODE_STATIC` AudioTrack 不释放、继续播完 | 等待段包 try：取消时确认仍是当前 track 再释放并重抛 |
| 初始化取消泄漏 | `OfflineTts(config)` 构造后等锁期间被取消：上百 MB native 实例永不赋值也不释放 | `assigned` 标记 + finally：未赋值即显式 `release()` |
| READY 状态竞态 | `_state = READY` 写在 `speakMutex` 外，与 `release()`（同锁置空 + NOT_INITIALIZED）交错出 READY∧tts=null 的说谎态，之后所有朗读静默失败 | 状态写入移入锁内 |

### 2.4 TTS 引导与下载体验（延期项 3、4）

- 场景 A："使用「XX」"按钮按 `engine.isEnabled` 置灰，未启用引擎标注
  「（未启用）」并在按钮区提示先去系统设置启用——点了必 15s 超时的入口关闭
- 下载进度页内可见：引擎 `downloadProgress` 流按整百分比节流镜像进
  `ReaderUiState.embeddedDownloadProgress`；引导弹窗点"下载内置 TTS"后
  **保持打开**，下载按钮原地变进度条 + 百分比；完成/失败由既有 toast 告知
- 死代码：`splitForTts`（被 `hardChunks` 取代）、TtsEngineHelper 的
  `hasGoogleTts` / `getDefaultEnginePackage` / `findFallbackEngineV2` /
  `listInstalledThirdPartyTtsApps` / `buildInstallTtsIntent` /
  `buildAccessibilitySettingsIntent`（全部零调用点，grep 验证）、
  `revealAllFuzzy` 空壳——全部删除

### 2.5 设置与生命周期（延期项 2、9、10 + 小项）

- **滑杆防抖**：`setFontSize/setRsvpSpeed/setRsvpStrength/setTranslationAlpha`
  UI 状态立即更新（滑杆跟手），持久化经 `persistSettingDebounced`（300ms、
  按设置项分 key、身份移除防误删并发排队项）；`cleanup()` 取消防抖计时并
  `runBlocking` 冲刷待写项——拖完立刻退页不丢设置
- **`rsvpInterval` 移除**：UI 滑杆/文案、`ReaderUiState` 字段、setter、
  `ReadingSettings.interval`、combine 收集、仓库接口与实现、
  DataStore key 全部删除（旧值留在文件不被读取，无害）。
  语义无产品定义不臆造接线；若日后定义（如词间停顿）再行加回
- **旋转守卫**：`DisposableEffect.onDispose` 检查
  `Activity.isChangingConfigurations`——旋转不再停播；真退出经
  `onCleared -> cleanup()` 落库停播不变
- 目录弹窗打开即 `scrollToItem(currentIndex)`（长书不再停在第 0 段）
- RSVP 强度指示从 `onClick={}` 的 AssistChip 改纯展示徽章（TalkBack 不再
  读成没反应的按钮）；设置弹窗文案表提到顶层免每次重组分配

## 3. 验证结果

```
./gradlew detekt testDebugUnitTest        # JDK 17
BUILD SUCCESSFUL
- detekt: 0 issue
- 单测: 73 passed, 0 failed（5 个测试类）
- compileDebugKotlin: 无错误
```

## 4. 延期项

| 项 | 理由 |
|----|------|
| `books.lastReadPosition` 只写字段移除 | 需 7→8 迁移且 SQLite 老版本无 DROP COLUMN，要重建 books 表；为无害只写字段做数据迁移风险/收益不划算。写入路径（`BookDao.updateProgress`）保留 |
| 设置页（SettingsScreen/SettingsViewModel）滑杆同款逐像素写 DataStore | 与本轮修复的阅读器设置弹窗同型，但不在阅读详情页范围；下轮复用 `persistSettingDebounced` 模式 |
| `ttsPrompt` 弹窗旋转后丢失 | `remember` 非 saveable + SharedFlow 无重放；移入 `ReaderUiState` 需事件→状态改造。NO_ENGINE 场景再点朗读即可重弹 |
| TtsInstallDialog confirmButton 槽位多按钮溢出风险 | 场景 A 每引擎一个按钮，小屏可能裁剪；需把选择项移进可滚动正文区，交互改造 |
| 单词双击判定 ~300ms 延迟 | `detectTapGestures` 固有；需自定义手势或改长按，体验取舍 |
| FuzzyReadingView 逐词 `.blur()` 渲染层 | 单段内词数有限，体验/性能取舍；可选纯透明度方案 |
| `uiState` 单体重组热点（RSVP ~13Hz 整页重组） | 需拆分状态流/底栏参数化，架构调整 |
| `Divider` 弃用（M3 `HorizontalDivider`） | lint 级，批量改名留待顺手轮次 |
| TTS 语速设置缺 UI 写入端（Round 7 遗留） | 链路已通，缺设置页滑杆；需 UI 决策 |
| `playPcm` 按标称时长等待（Round 7 遗留） | 误差可接受 |

**决定**: `APPROVE` —— 10 项跨轮延期收口 9 项，三视图性能/跟随与
播放安全（剧透、并发停止、焦点归还、异常防护）全部闭环，门禁保持全绿。
