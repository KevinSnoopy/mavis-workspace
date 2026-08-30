# eareyereading — Round 7 语音播报功能深度专项循环报告

> **评审时间**: 2026-08-30
> **基准**: `REVIEW_CYCLE6.md`（阅读页专项，commit `31fde2c`）
> **本轮目的**: 聚焦语音播报全链路 —— TtsHelper 状态机 / EmbeddedTtsEngine（sherpa-onnx）/
> TtsEngineHelper 引擎探测 / Tts.kt JNI 封装 / 播放链集成 / 设置页 TTS 区
> **方法**: 3 路深评审（TtsHelper 不变量审计 / 引擎+JNI / 集成接缝）→ 修复 → 验证全绿

---

## TL;DR

三路评审共 **39 个新发现（3×P0 / 11×P1 / 13×P2 / 12×P3）**，
本轮修复 **3×P0 + 11×P1 + 11×P2 + 5×P3**，其余为低影响/需 UI 设计项，列入延期清单。
修复后 **detekt 0 issue + 73 单测全过 + 编译无错误**。

---

## 1. 修复清单

### 1.1 中文朗读断裂（P0，默认模型即中英双语）

- **中文句子不切分**：引擎与 VM 的句子边界只认 ASCII `.!?`，中文段落整体被当成
  一个"句子"再被 150 字符上限截断——中文书每段只读前 150 字且报告"成功"。
  修复：引擎侧 `splitSentences` 与 VM 侧 `splitSentencesCompat` 同时支持全角
  `。！？；`（允许尾随闭引号/括号），超长句改 `hardChunks` 按空白断块，
  替代直接 `substring(0,150)` 丢弃
- **数字 >9999 裸进 native（文档记载的 SIGSEGV 类）**：`numberToWords` 超 9999
  原样返回数字串；超 Int 的 `toIntOrNull` 空值也保留裸数字。修复：逐位读词
  `digitsToWords`，两个漏洞路径全部堵死；货币 `"$100"` 组合在数字转换前处理，
  语序不再颠倒
- **`< >` 等文档记载的 OOV 崩溃字符漏替换**：补齐 `< > * [ ] _ { }`

### 1.2 Tts.kt JNI 封装（P1）

- `ptr` 加 `@Volatile`；`free()/finalize()` 加 `@Synchronized`——GC finalizer 线程
  与显式释放并发时原实现双双通过 `ptr != 0` 检查 → native double free
- `generate()/generateWithCallback()/sampleRate()/numSpeakers()` 加释放后守卫
  （本地快照 + `check(p != 0L)`），释放后调用得到 Kotlin 异常而不是段错误

### 1.3 TtsHelper 状态机加固（P1×4 + P2，根因：跨线程状态零串行化）

不变量审计发现 4 条 `isInitialized=true ∧ tts=null` 构造路径、等待者队列毒化、
无 utteranceId 配对的双回调、模式切换中链悬挂、6 组 `@Volatile` check-then-act 竞态。
修复：

- 新增 `stateLock` 作为初始化状态的唯一串行化点：快路径/排队/重建决策、
  初始化回调转换、失败重置、`stop()` 补偿、`onEmbeddedReleased` 全部进锁
- `failAllWaitersAndReset`：失败/超时统一作废代际、销毁实例、**唤醒全部等待者**。
  旧实现只移除超时的那一个等待者——其余挂在死实例队列里各等 15s，且等待者的
  超时清理回调会销毁此后新建的实例（毒化后继初始化）
- 排队判定改 `initPending`（原 `tts != null && initPending` 在实例构造窗口漏判，
  并发调用者误走清理重建分支）
- 回调处理加 `tts != null` 守卫，杜绝"成功但实例已被并发清空"的静默态
- **utteranceId 配对**：`speak()`/`speakSentences()` 的监听器按 id 过滤回调，
  QUEUE_FLUSH 冲刷的旧句迟到的 onDone 不再双触发 `onComplete`、不再让链跳句
- `sentenceChainJob` 追踪内置引擎句子链：此前未入任何 job 字段，`stop()/shutdown()`
  取消不到，模式切换后循环继续调用已释放引擎
- `updateTtsMode` 切换前先 `stop()`：补偿逻辑按当前模式分发，先切模式会让
  旧链终止回调无人认领
- `shutdown()` 状态段进锁、唤醒等待者；`onEmbeddedReleased` 改 suspend 并退回系统模式
- 失败分类（含多次 PM 扫描）移到 IO + `NonCancellable`，不再卡主线程

### 1.4 EmbeddedTtsEngine（P0/P1/P2）

- **句子熔断器**：连续 3 句合成失败即中止并报 `FAILED`——旧实现逐句"跳过"后
  照常返回成功，损坏模型会让整本书"静音朗读"并推进进度
- **下载互斥锁**：设置页与阅读页弹窗是两个独立入口，跨入口并发下载会交错写
  同一批文件产出损坏模型；`downloadMutex` 包住整个下载
- **Content-Range 不匹配中止**：旧实现把起始位不符的 206 响应当全量写——
  产出缺了 `[0, start)` 字节的损坏文件且照常标记 `.complete`
- 下载取消路径复位状态（旧实现状态流永远停在 DOWNLOADING）
- `deleteModel`/`release()` 复位状态流（删完模型流里仍是 READY）
- `initialize` 快路径进 `speakMutex` 并摆正状态流；新实例构造失败但旧引擎存活时
  状态回 `READY(旧模型)` 而非 `FAILED`
- 解压循环加 `ensureActive()`（~100MB 归档解压原无挂起点，离开页面后仍解压几十秒），
  取消时清理半截解压产物与 tarball
- **音频焦点**：播放前申请 `AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK`，停止/释放时归还——
  此前朗读压在音乐/播客上互不相让；`AudioTrack.write/play` 失败显式处理并释放

### 1.5 集成与设置页（P1×2 + P2）

- `stopAllPlayback()` 补取消 `ttsInitJob`：初始化窗口内用户切到别的播放形态后，
  迟到的初始化回调不再在新生播放之上叠单段朗读；回调侧同步复查播放状态
- **删除内置模型链路重排**：先 `release()`（等完在播句子）→ `onEmbeddedReleased()`
  （TtsHelper 复位并退回系统模式）→ 再删文件。旧实现先删文件且 TtsHelper 不知情，
  之后所有朗读静默失效直到进程重启
- **manifest 补 `<queries>`**（Android 11+ 包可见性）：`TTS_SERVICE` intent +
  15 个已知引擎包名 + `com.android.vending`。此前所有引擎探测被系统过滤成空——
  `hasAnyEngine()` 恒 false、phantom 判定在每台失败设备上都误报、显式引擎绑定
  白等 15s 超时。恰是国产机 TTS 兜底逻辑的目标场景
- 安装引导场景 B 增加"已安装且非 phantom"前置判定：指向已知但未安装的包不再
  给用户"重试连接"（必然 15s 超时），改走安装引导
- 看门狗预算按语言分档：中文 ~350ms/字、英文 120ms/字、下限 90s——
  原 80ms/字 在中文段/慢语速下会在朗读中途切断
- 自动朗读/速读每段启动新链前先 `ttsHelper.stop()`，杜绝两条链交替朗读

## 2. 验证结果

```
./gradlew detekt testDebugUnitTest        # JDK 17
BUILD SUCCESSFUL
- detekt: 0 issue
- 单测: 73 passed, 0 failed
- compileDebugKotlin: 无错误；资源链接通过
```

## 3. 延期项

| 项 | 理由 |
|----|------|
| `shutdown()` 后 `ttsMode` 未复位 | 当前零调用点（单例=进程生命周期），无实际影响 |
| TTS 语速设置缺 UI 写入端 | 链路已通（设置→收集→引擎），缺设置页滑杆；需 UI 决策 |
| TtsEngineHelper 死代码助手清理 | 无调用点但属删除型改动，单独提交更清晰 |
| 阅读页内下载进度展示 | 需对话框交互设计 |
| 场景 A 禁用引擎按钮置灰 | 体验优化 |
| `splitForTts` 死代码 | 已被 `hardChunks` 取代，待删除 |
| `playPcm` 按 `playbackHeadPosition` 轮询 | 现按标称时长等待，误差可接受 |

**决定**: `APPROVE` —— 语音播报链路 3 个 P0（中文截断、大数字裸数字、`< >` 漏替换）
全部闭环，TtsHelper 状态机建立串行化根因修复，下载/解压/播放的资源安全补齐，
门禁保持全绿。
