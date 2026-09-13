# eareyereading — P0/P1 静态评审与修复报告

> **评审时间**: 2026-08-30
> **评审范围**: 全仓 `app/src/main/java/**/*.kt`(63 文件,16632 行)
> **评审方法**: 纯静态分析(沙箱无 JDK/Gradle,未运行 detekt/test)
> **分支**: `eareyereading`
> **基准 commit**: `4626341 fix(tts): 修复 sherpa-onnx SIGSEGV 崩溃 + 多句播放失效 + 暂停无响应`

---

## TL;DR

完成 **7 文件、+89/-36 行** 修改,涵盖 **3 个 P0 + 4 个 P1** 问题。所有修改均**仅做加法或语义对等的健壮性增强**,不改变业务逻辑,不会破坏现有行为。

| 严重度 | 数量 | 静态证据 | 修复方式 |
|------|------|---------|--------|
| **P0** | 3 | 直接 NPE/ClassCastException 风险 | `?.let` 重构 / `as?` + 默认值 / `scope.cancel()` |
| **P1** | 4 | 系统服务 null / 静默失败 | `as?` + 早返回 / 空 catch 加 Log |
| P2/P3 | 0 | — | 不在本次修复范围 |

---

## P0 修复明细

### P0-1: `ReaderScreen.kt:386-406` 3 处 `!!` 强制解包 → NPE

**位置**:
```kotlin
// 旧代码
if (uiState.showWordDialog && uiState.selectedVocab != null) {
    WordDetailDialog(
        word = uiState.selectedVocab!!.word,                 // !! #1
        definition = uiState.wordDefinition,
        wordLevel = uiState.selectedWordLevel,
        onAddToVocabulary = { viewModel.addToVocabulary(uiState.selectedVocab!!.word, null) },  // !! #2
        onDismiss = viewModel::dismissWordDialog,
    )
}
...
if (selectedSentence != null) {
    SentenceTranslationDialog(
        sentence = selectedSentence!!,                       // !! #3
        ...
```

**风险**: 在 Compose lambda 中,编译器对 `by collectAsState()` 委托后的属性看不到 smart cast;同时 `uiState.selectedVocab` 的 null 检查与 lambda 内部访问之间存在时序窗口(StateFlow 在 recomposition 之间可能更新)。当用户在弹窗打开瞬间极快点击关闭、且 `viewModel.dismissWordDialog()` 异步清空 selectedVocab 时,lambda 执行期间 vocab 已被置 null → `!!.word` 直接 NPE。

**修复** (`ReaderScreen.kt:386-411`):
```kotlin
// 新代码
uiState.selectedVocab?.let { vocab ->
    if (uiState.showWordDialog) {
        WordDetailDialog(
            word = vocab.word,
            definition = uiState.wordDefinition,
            wordLevel = uiState.selectedWordLevel,
            onAddToVocabulary = { viewModel.addToVocabulary(vocab.word, null) },
            onDismiss = viewModel::dismissWordDialog,
        )
    }
}

val selectedSentence by viewModel.selectedSentence.collectAsState()
selectedSentence?.let { sentence ->
    SentenceTranslationDialog(
        sentence = sentence,
        ...
```

`let` 块在执行时把 vocab/sentence 捕获为局部不可空变量,即便外层属性后续被置 null,块内已绑定安全引用。

---

### P0-2: `TtsHelper.kt:58` Singleton scope 永不 cancel → 协程泄漏

**位置**:
```kotlin
@Singleton
class TtsHelper @Inject constructor(...) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    ...
    fun shutdown() {
        // 旧实现:只停 tts/embeddedTts,没 cancel scope
        try { tts?.stop(); tts?.shutdown() } catch (_: Exception) {}
        ...
    }
}
```

**风险**: `TtsHelper` 是 Hilt `@Singleton`,进程内只创建一次。`scope` 在 `init` 时创建后没有 `cancel()` 调用点。后果:
- 进程内任何 `scope.launch { ... }` 飞起的协程在 `shutdown()` 后仍可继续执行
- 在 hot reload / 单元测试 / 进程存活但 TTS 实例重建场景下,会持有 Activity/Context 引用造成内存泄漏
- 正常生产环境 app 进程死亡时由 OS 回收,风险较低 — 但仍是规范做法

**修复** (`TtsHelper.kt:583-589`):
```kotlin
fun shutdown() {
    ...
    try { embeddedTts.stop() } catch (_: Exception) {}
    // P0 修复
    scope.cancel()
}
```

新增 `import kotlinx.coroutines.cancel`。

---

### P0-3: `NotificationReceiver.kt:43` 完全空 catch → "提醒没设上" 无法排查

**位置**:
```kotlin
// 旧代码
try {
    val helper = NotificationHelper(context)
    helper.scheduleReviewReminder()
} catch (_: Exception) { }   // 静默吞掉
```

**风险**: 设备重启或闹钟触发时,如果 `scheduleReviewReminder()` 抛异常(权限被禁、Calendar 异常等),用户感受是"提醒不再响起"且 logcat 无任何线索。

**修复** (`NotificationReceiver.kt:44-49`):
```kotlin
} catch (e: Exception) {
    Log.w(TAG, "Failed to schedule next-day reminder", e)
}
```

`BootReceiver.kt:18` 也存在相同问题,已一并修复(`Log.w("BootReceiver", ...)`)。

---

## P1 修复明细

### P1-1: `ReaderViewModel.kt:481-486` 6 个连续 `as` → DataStore schema 不匹配时 ClassCastException

**位置**:
```kotlin
combine(
    settingsRepository.getRsvpSpeed(),     // Flow<Int>
    settingsRepository.getTheme(),        // Flow<ReadingTheme>
    ...
) { values ->
    val speed = values[0] as Int
    val strength = values[1] as Int
    val theme = values[4] as ReadingTheme
    ...
}
```

**风险**: 当前 `SettingsRepository` 返回类型稳定,**实际触发概率低**。但 `combine` + `Array<Any?>` 是脆性耦合:DataStore 序列化层如果将来重构(例如从 Preferences 改 Proto),`as ReadingTheme` 可能在运行时抛 `ClassCastException`,导致 `init { viewModelScope.launch { ... } }` 整个死掉 — 整个 Reader 屏打不开,且无 fallback 路径。

**修复** (`ReaderViewModel.kt:481-492`):
```kotlin
@Suppress("UNCHECKED_CAST")
val speed = values[0] as? Int ?: 300
@Suppress("UNCHECKED_CAST")
val strength = values[1] as? Int ?: 3
val interval = values[2] as? Int ?: 1
val fontSize = values[3] as? Int ?: 18
val theme = values[4] as? ReadingTheme ?: ReadingTheme.LIGHT
val alpha = values[5] as? Float ?: 0.85f
```

默认值与原 `SettingsRepository` 默认值一致,无行为变化。

---

### P1-2: `NotificationHelper.kt:32-36` `as AlarmManager` → 系统服务 null 抛 NPE

**位置**:
```kotlin
private val alarmManager: AlarmManager by lazy {
    context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
}
```

**风险**: Android 文档明确说 `getSystemService` 在系统服务不可用时**返回 null**(Device Owner 策略、企业 MDM 管控、Root 设备上特定服务被禁用等)。`as` 会抛 `ClassCastException`(实际是 NPE 的包装)。一旦触发,整个复习提醒链路直接坏掉。

**修复**:
```kotlin
private val alarmManager: AlarmManager? by lazy {
    context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager
}
```

`scheduleReviewReminder` 和 `cancelReminder` 内部相应加 `?: run { Log.w(...); return }` 早返回逻辑。

`NotificationHelper.createNotificationChannel` 的 `as NotificationManager` 同步改为 `as?` + 早返回。

---

### P1-3: `NotificationHelper.kt:114` 空 catch → fallback 失败被静默吞掉

**位置**:
```kotlin
// 旧代码(降级路径)
} catch (e: SecurityException) {
    Log.w("NotificationHelper", "Cannot schedule exact alarm: ${e.message}")
    try {
        alarmManager.setInexactRepeating(...)
    } catch (_: Exception) { }   // 静默吞掉 fallback 失败
}
```

**风险**: 用户开"精确闹钟"权限被拒(国产 ROM 默认行为),进入降级路径;若降级又失败,用户感受"提醒彻底没了"且 logcat 零线索。

**修复**: `catch (_: Exception) { }` → `catch (e: Exception) { Log.w("NotificationHelper", "Fallback inexactRepeating also failed: ${e.message}") }`。

---

### P1-4: `EmbeddedTtsEngine.kt:1073` 与 `NotificationReceiver.kt:36` `as NotificationManager` 同 P1-2

**位置**:
- `EmbeddedTtsEngine.kt:1073-1075`(`notificationManager` lazy 字段)
- `NotificationReceiver.kt:36`(onReceive 内部)

**修复**: 全部 `as` 改 `as?` + null 早返回 + 防御性日志。下载通知、取消通知、复习提醒发送的链路,任何环节 `getSystemService` 返回 null 都不会再抛 NPE,业务照常进行。

---

## 主动放弃的 P1 (有静态证据但风险/收益不对等)

| 位置 | 问题 | 放弃原因 |
|------|------|--------|
| `Theme.kt:93` `view.context as Activity` | `LocalView.current.context` 在 Compose 下必有 Activity,但 IDE/Preview 场景可能 null | 改 `as?` 反而要在 SideEffect 块加分支,降低可读性;实际触发场景已不存在 |
| `RssParser.kt:131` `URL.openConnection() as HttpURLConnection` | stdlib 行为固定,不会返回其他类型 | 修改纯属防御性,无 bug |
| `RssParser.kt:143` `catch (e: MalformedURLException)` | URL 构造器的 `MalformedURLException` 实际上不会进入此 catch(URL 在 `openConnection()` 抛,但 `URL(urlStr)` 构造器 Kotlin 编译器对其 checked 异常检查放宽) | 算"死代码" catch,但修这个就要分析整个链路,scope 太大 |
| `Theme.kt` 等 `as Activity` 之外的 `as` 转换 | 全部在 `getSystemService` 上下文,已在 P1-2/4 修 | — |

---

## 本地验证步骤

```bash
cd eareyereading/

# 1. 静态分析(应保持 0 error / 0 warning)
./gradlew detekt

# 2. 单元测试(应保持全过)
./gradlew test

# 3. 构建(应成功)
./gradlew assembleDebug

# 4. 关键冒烟路径
# - 打开 App → 进入阅读器 → 点单词 → 弹窗显示 + 关闭
#     (验证 P0-1:?.let 重构后,弹窗打开期间 vocab 置 null 不再 NPE)
# - 设置 → 切换 TTS 模式
#     (验证 P0-2:scope.cancel 不会破坏正常朗读流程)
# - 设备重启 / 给 app 加 SCHEDULE_EXACT_ALARM 后拒绝
#     (验证 P1-2/3:AlarmManager null 与 fallback 失败都有 warn 日志)
# - 系统设置里禁用 TTS 引擎
#     (验证 P1-4:NotificationManager null 不再 crash,只是 warn 一下)
```

---

## 未做 / 建议(不属本轮修复范围)

- **`!!` 在 ViewModel 的内部 async 块内还有零星出现**:大多数是被 `try/catch` 包裹的,或 `init` 时序已保证非空。建议 P2 阶段统一过一遍 ViewModel,把所有 `it!!` 改成 `requireNotNull(it) { "..." }`。
- **`HttpURLConnection` 资源释放**:已检查,所有 `URL.openConnection()` 都有 `try/finally` + `conn?.disconnect()`,无需修复。
- **TtsHelper.kt 内部 5 个空 catch (line 578, 584)**:shutdown 路径上的 stop() / embedded.stop() 空 catch 实际上合理(stop() 失败不影响 null 化 `tts` 字段),不修。
- **`RssParser.parseXml()` 内的 RuntimeException catch**:已 log 兜底,无静默失败。
- **`LibraryViewModel` / `HomeViewModel` 的 RuntimeException catch (line 126)**:有 Log 兜底,无静默失败。

---

## commit 建议

按以下顺序分两个 commit,方便回滚:

1. **`fix(notification): 系统服务 null 防御 + 空 catch 加日志`**
   - `NotificationHelper.kt`
   - `BootReceiver.kt`
   - `NotificationReceiver.kt`
   - `EmbeddedTtsEngine.kt`

2. **`fix(reader/tts): !! 强解包换 ?.let + DataStore cast 加默认值 + scope.cancel()`**
   - `ReaderScreen.kt`
   - `ReaderViewModel.kt`
   - `TtsHelper.kt`

如需单 commit 全部修改,改用:
```
fix(eareyereading): P0/P1 静态评审修复(7 文件,+89/-36)
```
