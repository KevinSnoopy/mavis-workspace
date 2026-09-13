# eareyereading — Round 6 阅读详情页深度专项循环报告

> **评审时间**: 2026-08-30
> **基准**: `REVIEW_CYCLE5.md`（CI 修复，commit `7f1cbee`）
> **本轮目的**: 按用户要求聚焦阅读详情页（ReaderScreen.kt 2300 行 + ReaderViewModel.kt 1650 行
> + TtsHelper 播放契约 + 阅读页导航），深评审 → 修复 → 验证
> **方法**: 3 个深度评审代理（VM 逻辑/并发、Screen Compose 正确性、跨模块契约），
> 修复后 `detekt + testDebugUnitTest` 全绿

---

## TL;DR

三路深评审共 **66 个新发现（0×P0 / 15×P1 / 20×P2 / 31×P3）**。
本轮修复 **15/15 个 P1 + 15 个 P2 + 13 个 P3**，其余为需产品决策/大重构/体验取舍项，
列入延期清单。修复后 **detekt 0 issue + 73 单测全过 + 编译无错误**。

---

## 1. 修复清单

### 1.1 播放编排（P1×3 + 相关）

| 项 | 问题 | 修复 |
|----|------|------|
| 看门狗截断 | 每段固定 60s 超时，长段落读到一半被切断推进；看门狗自然完成后不取消，拖延任务结束 | 超时改按内容量估算（~80ms/字符，下限 60s）；`finishOnce()` 内取消看门狗 |
| 速读双倍停顿 | 音频完整播完后**再**叠加一个 WPM 时长的静默，每段耗时翻倍 | 音频后只留段间短停顿；WPM 停留仅用于无句子的空段 |
| 播放无仲裁 | 四种播放形态共用 TtsHelper 单例但互不停止：打断被读成"读完"继续推进、`isTtsPlaying` 卡死、自动朗读被 RSVP 打断后以 600ms/段扫完全书 | 新增 `stopAllPlayback()` 仲裁，所有播放入口/模式切换/手动跳转/换书统一先停全部播放并复位三个播放标志 |
| 句高亮落后一句 | `onSentenceDone(idx)` 语义是"第 idx 句读完"，被写成"当前句" | 当前句 = `idx + 1`（收敛到末句） |
| 句间停止竞态 | SYSTEM 链 stop 落在两句之间时下一句仍会出声 | `speakNext()` 顶部检查 `isInSentenceChain`（TtsHelper） |

### 1.2 阅读页视图（P1×7）

| 项 | 问题 | 修复 |
|----|------|------|
| 选句翻译永久转圈 | 失败写 `null`，而弹窗以 `== null` 判定"加载中"，失败分支是死代码 | 失败写空串，"翻译失败，请重试"分支复活 |
| 挖空只能揭示一个空 | `hideWord()` 永远 find 第一个隐藏词且不清标记 | 渐进揭示：每按一次清除一个隐藏词标记；按钮显示剩余空数，全部揭示后禁用 |
| 挖空/模糊一词一行 | 每个词是纵向 Column 的独立 Text，段落变成单词塔 | 改用 `FlowRow` 行内排布（保留逐词点击/模糊样式） |
| 听写题面泄漏答案 | 隐藏词**原文加下划线直接显示**在题面上；空格缺失；输入从不核对 | 隐藏词渲染为 `____`；token 流复用 `generateClozeText`（带分隔符）；新增 `checkDictationAnswer`：输入匹配才揭示，错误给提示 |
| 速读播放只见"●" | VM 按句驱动（切句/回调/索引齐全），视图却全部丢弃 | 接入 `currentSentences`/`currentSentenceIndex`：已读句变淡、当前句高亮 |
| 成分分析主题色盲 | 唯一不接阅读主题色的视图，深色主题下深底深字不可读 | 增加 `textColor` 参数，非词性着色全部改用主题色 |
| 顶栏深色主题消失 | 背景跟主题、内容色不跟，标题/返回/图标全是深色墨 | `topAppBarColors` 指定 title/navigation/action 内容色 |

### 1.3 状态与数据流（P1×3 + P2）

| 项 | 问题 | 修复 |
|----|------|------|
| 退出保存是幻觉 | `viewModelScope` 在 onCleared **返回后**才取消，"scope 存活就异步保存"的分支必被取消打断 | `cleanup()` 一律 `runBlocking` 同步落库（短 IO，保证写完） |
| TTS 单例跨书泄漏 | 初始化快路径忽略 `language`：读完英文开中文永远旧 locale；换引擎重试谎报"已启用" | 快路径每次 `setLanguage`；请求引擎与运行引擎不同时销毁重绑；新增 `activeEnginePackage` |
| 挖空/模糊模式重开空白 | 模式从持久化恢复，但派生数据只在 `setReadingMode` 生成 | `loadBook` 恢复后按模式立即 `generateCloze()/generateFuzzy()` |
| 滑动阅读位置不跟随 | 视口只在程序化跳转时动，手滑阅读底栏/进度/统计全停滞 | `snapshotFlow(firstVisibleItemIndex)` 回报 VM（播放中忽略）；跳转时停止播放防互踩 |
| 统计可刷分 | 滑杆拖一次 = 整段字数入账；进程被杀丢整段会话 | 顺序阅读才累计经过段，大跳转只计目标段；距上次落库满 1 分钟增量写库；换书前先落上一本 |
| 翻译双启动 | 守卫标志在 launch 内才置位，快速开关连过两次守卫 | 同步置位 + `translationJob` 追踪，`cleanup()` 可取消 |
| 书签伪互斥 | `cancel()` 不阻塞、Room 不响应中途取消，双击窗口仍在 | 真 `Mutex` 串行（数据库层另有唯一索引兜底） |
| 点词竞态 | 慢查询覆盖新词的弹窗；加词去重查 A 存 B，失败静默 | `selectWordJob` 取消前次；去重与保存同词；失败给 toast |
| TTS 语速死线 | 设置页可写、数据层可存、零消费者 | VM 收集 `getTtsSpeed()` 推给 `ttsHelper.setSpeed()`；新引擎实例初始化应用 `currentSpeed` 而非硬编码 1.0 |
| 进度永远 99% | `idx/size` 到不了 1.0 | `(idx+1)/size` + `coerceIn(0,1)` |
| 每书字号/主题只写不读 | `saveState` 持久化、`loadBook` 从不恢复 | `loadBook` 恢复，书籍状态优先于全局设置 |
| 恢复词索引越界 | 只有下限收敛，重切分后 RSVP 空跑一声音不出 | `startRsvp` 使用点 `coerceIn(0, words.size)` |
| RSVP 播完重播只读一词 | 停在最后一个词 | 自然播完归零 |
| TTS 引导主线程扫描 | 7 个 PackageManager/Intent 查询全在主线程（恰是最卡的国产低端机场景） | 整体 `withContext(Dispatchers.IO)` |

### 1.4 其他修复

- 听写/挖空/速读/自动朗读初始化 catch 链补 `CancellationException` 重抛（5 处）
- 引擎重试协程纳入 `ttsInitJob` 追踪，退出随 `cleanup()` 取消
- 自动朗读起播段索引种子修正；朗读中段落背景从"零高度 Surface"改为内容容器背景
- 播放图标状态纳入 `isTtsPlaying`；RSVP 空段不再显示 "1 / 0"
- 译文间距只对实际有译文的段落生效；挖空/分栏/回译视图接入 `translationAlpha`
- 回译"查看原文"改本地揭示状态（原实现会把用户踢出回译模式并持久化 NORMAL）
- 分栏视图改单滚动容器逐段并排（原左右独立滚动，原文第 N 段对上译文第 M 段）
- 听写模式改经 `stopAllPlayback()` + 模式持久化
- 删除死代码 `getReadingDurationMinutes`
- reader 导航 `launchSingleTop`（防双击堆叠两个 reader 栈、两个 VM 抢 TTS 单例）；
  书籍不存在时 toast + 自动返回，不再停留死页面、不再写孤儿进度行

---

## 2. 验证结果

```
./gradlew detekt testDebugUnitTest        # JDK 17，默认规则集
BUILD SUCCESSFUL
- detekt: 0 issue
- 单测: 73 passed, 0 failed
- compileDebugKotlin: 无错误
```

遗留编译警告均为历史项（未使用参数、Secondary 色弃用提示），非阻断。

---

## 3. 延期项（需产品决策或独立重构窗口）

| 项 | 理由 |
|----|------|
| 分栏/成分分析/回译三视图 LazyColumn 化 | 整书 eager 组合的性能问题与 NORMAL 同型，但涉及滚动跟随重构；本轮已修正确性（对齐/主题色），性能留待下轮 |
| `rsvpInterval`（加粗间隔）设置 | 持久化+展示齐全但零消费的另一个死设置；语义（词间停顿？）需产品定义后再接线或移除 |
| 章节导航滚动到当前章 | 体验增强 |
| 配置变更（旋转）停止播放 | `DisposableEffect(Unit)` 在配置变更也会触发 cleanup；需按生命周期区分真退出与重建 |
| 单词点击的双击判定延迟 | `detectTapGestures` 固有 ~300ms，需自定义手势方案 |
| 模糊阅读揭示开关 / 挖空模式朗读剧透 / 加载中滑杆可用性 | 体验取舍项 |
| 设置弹窗滑杆拖动逐像素写 DataStore | 需与 saveProgress 同款防抖 |
| DAO/Repository 两套数据接缝、`books.lastReadPosition` 只写字段 | 架构整理，非缺陷 |

**决定**: `APPROVE` —— 阅读详情页 15 个 P1 全部闭环，播放编排有了统一仲裁，
各阅读模式的正确性缺陷（一行一词、题面泄漏、永久转圈、主题不可读等）全部修复，
门禁保持全绿。
