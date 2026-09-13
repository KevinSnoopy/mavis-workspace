# eareyereading — Round 1 评审卡 (静态,未跑 detekt)

> **评审时间**: 2026-08-30
> **评审对象**: `eareyereading` 分支,base = `4626341`
> **评审方法**: 纯静态评审(沙箱无 JDK/Gradle,无法跑 detekt/test/build)
> **评审范围**: `app/src/main/java/com/eareyereading/**/*.kt`(63 文件,16632 行)
> **评审员**: Mavis(root session,自查自评)

---

## 1. 8 维工程评分(满分 100)

| 维度 | 权重 | 得分 | 说明 |
|------|------|------|------|
| **Design**(架构合理性) | 15 | 12 | MVVM + Hilt + Room + DataStore 选型主流;`TtsHelper` 是 God Class 倾向(591 行,管系统/embedded/状态机/协程 4 件事);`EmbeddedTtsEngine` 1120 行混入 audio I/O、通知、状态机 |
| **Maintainability**(可维护性) | 15 | 11 | 大文件集中度高(ReaderScreen 2282 行、CollinsClassifier 2267 行、ReaderViewModel 1356 行);多个 ViewModel init 块逻辑 60+ 行(ReaderViewModel:init),测试覆盖率无法在本评审验证 |
| **Consistency**(代码一致性) | 10 | 7 | catch 处理风格不统一(有的 Log + continue,有的 catch (_: Exception){});null 防御不一致(有的 `?.let` 有的 `!!`);`as`/`as?` 混用 |
| **Simplicity**(简洁性) | 10 | 7 | `RssParser.parse` 4 个 catch 分支(实际可合并 IOException);多处 `Build.VERSION.SDK_INT >= O` 重复模式 |
| **Readability**(可读性) | 10 | 8 | 中文注释充分,变量命名清晰;但 ReaderScreen.kt 函数级缺乏分隔注释,RecyclerView 风格状态多 |
| **Testability**(可测性) | 10 | 5 | ViewModel 构造函数参数多,`TtsHelper` 内部 scope 未注入,`EmbeddedTtsEngine` 直接依赖 `Context`/`NotificationManager`/`AudioTrack`,难以单测 |
| **Risk**(运行时风险) | 25 | 14 | **重点扣分**:3 处 `!!` 强解包、6 处连续 `as` 转换、3 处完全空 catch、Singleton scope 永不 cancel — 都有静态 NPE/ClassCastException/内存泄漏证据 |
| **Change Scope**(变更面) | 5 | 4 | 本次修复 7 文件,均限定在已有接口内,不动公共 API;但 ReaderScreen.kt 同时是核心 UI,改动需实测 |
| **总计** | 100 | **68 / 100** | |

**Hard Gate**:
- ❌ **不通过**:`maxIssues: 0` 配置下,detekt 启用 `NullableToStringCall` + `UnsafeCast` — 当前 `!!` 与 `as` 转换会被直接 block
- 修复后预计总分 **75–80**(Risk 维度 +8,Consistency +2)

---

## 2. P0-P4 发现(共 12 条)

### P0(直接 NPE / ClassCastException / 内存泄漏 — 必须修)

| ID | 位置 | 问题 | 静态证据 |
|----|------|------|---------|
| **F-01** | `ReaderScreen.kt:388, 391, 401` | 3 处 `!!` 强解包,Compose lambda 内 smart cast 失效,可在 recomposition 时序窗口中 NPE | `uiState.selectedVocab!!.word` 在 `if (uiState.selectedVocab != null)` 内;Kotlin 编译器在 `collectAsState().value` 委托后丢失 smart cast |
| **F-02** | `ReaderViewModel.kt:481-486` | 6 个连续 `as` cast(`Array<Any?>` 来自 `combine` lambda) | 6 个 `values[N] as Type`,任何 DataStore schema 不匹配直接 `ClassCastException`,死掉 init block,Reader 屏打不开 |
| **F-03** | `TtsHelper.kt:58 + 576` | `@Singleton` 持 `CoroutineScope` 但 `shutdown()` 不 cancel | 进程内 `scope.launch { ... }` 永不结束,hot reload / 测试场景持有 Context 引用泄漏 |
| **F-04** | `NotificationReceiver.kt:43` + `BootReceiver.kt:18` + `NotificationHelper.kt:114` | 3 处完全空 `catch (_: Exception) { }` | "复习提醒为何不再响起"无法排查;ScheduleExactAlarm 权限被拒时 fallback 失败被吞掉 |

### P1(系统服务 null / 资源风险 — 强烈建议修)

| ID | 位置 | 问题 | 静态证据 |
|----|------|------|---------|
| **F-05** | `NotificationHelper.kt:32-36` | `getSystemService(...) as AlarmManager` | Android 文档明确:服务被禁用/移除时返回 null,`as` 抛 ClassCastException |
| **F-06** | `NotificationHelper.kt:51` | `getSystemService(...) as NotificationManager` | 同上,channel 创建失败会 crash |
| **F-07** | `EmbeddedTtsEngine.kt:1073-1075` | `getSystemService(...) as NotificationManager` (lazy 字段) | 同上,下载通知路径 |
| **F-08** | `NotificationReceiver.kt:36` | `getSystemService(...) as NotificationManager` (onReceive 内) | 同上,复习提醒发送路径 |

### P2(类型安全 / 一致性 — 建议修)

| ID | 位置 | 问题 |
|----|------|------|
| **F-09** | `Theme.kt:93` | `view.context as Activity` — Compose 下安全但缺乏 null 防御,IDE Preview 可能 NPE |
| **F-10** | `RssParser.kt:131`、`ArticleParser.kt:44, 78`、`DictionaryManager.kt:265, 274` | `URL.openConnection() as HttpURLConnection` — stdlib 行为固定,实际安全但脆性 |

### P3(代码风格 / 死代码 — 可选)

| ID | 位置 | 问题 |
|----|------|------|
| **F-11** | `RssParser.kt:143` | `catch (MalformedURLException)` 实际不会触发(`URL(urlStr)` 构造异常不会到此 catch)— 死代码 |
| **F-12** | `EmbeddedTtsEngine.kt:1031` | `track.write(...)` 返回值未检查;虽然 AudioTrack 写失败率极低,理论上仍可能 |

---

## 3. Round 1 决定

**等级**: `REQUEST_CHANGES`

**理由**:
- Hard Gate 不通过(detekt `maxIssues: 0` 会 block 当前 `!!` 与 `as` 转换)
- 4 个 P0 finding,均为可静态修复的高置信度问题
- P1 中 4 个 `getSystemService as` 是同模式批量问题,一并修

**修复范围**:
- 必须修:F-01 ~ F-08(共 8 条,P0 + P1)
- 不修:F-09 ~ F-12(原因见后续 commit message / fix report)

**预期 Round 2 验证点**:
1. 8 个 finding 全部 closed(可 `grep` 验证)
2. 无新增 finding
3. detekt 静态预期通过(因沙箱无 JDK,需用户本地跑)
4. 总分预期 ≥ 78
