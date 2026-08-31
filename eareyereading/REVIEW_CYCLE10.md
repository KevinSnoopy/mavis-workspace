# eareyereading — Round 10 阅读详情页 + 内置模型下载解压应用专项循环报告

> 评审时间：2026-08-31
> 基准：`91ca5f7`（git pull 后与 origin/eareyereading 一致，工作区干净）
> 本轮目的：完善阅读详情页（ReaderScreen/ReaderViewModel）与内置 TTS 模型
> 下载→解压→应用链路（EmbeddedTtsEngine 及 Settings/Reader 两个入口）
> 方法：先派只读宽分片代理深读阅读页双文件（2113+1778 行）产出 8 项发现，
> 人工逐条核实代码证据后实施修复；收尾走 SWE review_guided 循环
> （R1 REQUEST_CHANGES → 修订 6 项 → R2 APPROVE_WITH_SUGGESTIONS 0.82
> → 消化 3 条 P4 建议 → 门禁复绿）

## TL;DR

- **TTS 引擎修复 1 个真 P1**：HTTP Range 断点续传此前是坏的——发 Range 头拿到
  206 后却走 fullStream 以 truncate 模式打开文件，本地残片被截断、只写入尾部
  字节，产出"只有后半截"的损坏包，length>0 又骗过完成校验 → 必解压失败。
  接通了一直挂着 @Suppress("unused") 的 appendStream，按 206/200/416 分流。
- **阅读页修复 6 个 P1**：回译模式死胡同假加载（从没碰过翻译开关就进回译，
  永远"正在获取译文..."且没有任何任务在跑）、溢出菜单"自动朗读"绕过防剧透
  闸（挖空/听写/模糊模式整本念出答案）、换书泄漏旧书译文（按下标键控的
  Map 张冠李戴）与残留弹窗、换书统计落库必输竞态（launch 体永远读到已归零
  的字段，上一本会话时长/字数必丢）、阅读加载路径残留主线程 EPUB 解析
  （R9 修过 addBook 同款，这条漏网）、句子翻译无串行化（慢结果覆盖新弹窗）。
- SWE 循环 R1 抓出 **1 P1 + 1 P2**（新引入的回归）：焦点丢失只停了引擎层
  "正在出声的那句"，循环播放驱动（自动朗读/速读/RSVP 的 finally{onAllDone()}
  补偿）会在 ~600ms 后播下一段，继续压在电话通话上；"全空译文 Map"软失败
  （无 GMS ROM 的主失败路径）会把 hasTranslation 顶成 true，让新增的
  失败 toast + 重试入口不可达。两项均随批修复（externalStop SharedFlow
  先于 stop() 发射 + VM 收闸 stopAllPlayback；全空即失败 + 书本身份守卫）。
- 门禁：`detekt` 0 issue，`testDebugUnitTest` 73/73 通过（与基线一致），
  每批修复后均复绿。本机注意：~/.gradle/gradle.properties 钉了 JBR 21，
  其 jlink 处理不了 android-34 的 core-for-system-modules，跑门禁需
  `-Dorg.gradle.java.home=<jdk-17>` 覆盖。

## 1. 修复清单

### 1.1 内置模型下载→解压→应用（EmbeddedTtsEngine + 两个 UI 入口）

| 级别 | 问题 | 修复 |
|------|------|------|
| P1 | Range 续传产出半截损坏包（206 走 truncate 写流） | 新增 streamResponse 分流：206+残片→appendStream（校验 Content-Range，mismatch 删残片短路），200→全量覆盖；416 删失效残片并短路，避免每个 proxy 候选反复 416 |
| P1(SWE-R1) | 焦点丢失 stop() 只取消在播句，循环驱动继续播下一段压通话 | 新增 externalStop SharedFlow（先 emit 后 stop()，同 Main 调度器 FIFO 保序）；ReaderViewModel collect 后 stopAllPlayback() 收闸（清 isAutoReading/isPlaying/isTtsPlaying + 取消全部驱动 Job）；requestAudioFocus 返回值不再无视（拒给焦点不置 held） |
| P2 | 下载无磁盘空间预检，下到一半/解压到一半才抛不可读 IOException 还留残片 | 下载前 usableSpace < sizeBytes×3 即 fail-fast，明确文案"存储空间不足（需要约 X MB，仅剩 Y MB）" |
| P2 | 解压无总量上限，bzip2 炸弹可写满整盘 | MAX_EXTRACT_BYTES=512MB（正常归档 ~120MB 的 4 倍余量），超限中止 |
| P2 | 解压失败/取消只删 manifest 文件且用 delete()，espeak-ng-data 非空目录静默残留 ~30MB | 统一 cleanExtractionPartials：deleteRecursively 全部 manifest 产物 + tarball + 标记 |
| P2 | Android 13+ 未授权 POST_NOTIFICATIONS 时 notify() 静默丢弃（部分 ROM 抛 SecurityException） | NotificationManagerCompat.areNotificationsEnabled() 守卫；收尾通知无权限时也先撤 ongoing 进度通知 |
| P3 | 模型已完整下载时点下载仍闪"下载中 0%" | downloadModelLocked 顶部 isModelDownloaded 短路，直接进"初始化"语义 |
| P3 | 下载失败 toast/snackbar 笼统"检查网络" | 引擎新增 downloadFailureReasonOrNull() 统一出口（剥离历史前缀），阅读页/设置页透传具体原因 |
| P4 | finally 里 conn.inputStream 在 4xx 上抛异常逃出，绕过剩余 proxy/镜像 | 取值包 try，失败即 disconnect（预存问题，新 416 分支也走这里，随批修） |
| P4 | 逐文件续传进度少算存量（续传期间停滞、完成时突跳） | 残片长度计入 downloadedTotal 分子 |
| P4 | Content-Range mismatch 分支不消费响应体也不 disconnect | 显式 disconnect，别留 GC |

### 1.2 阅读详情页（ReaderScreen + ReaderViewModel）

| 级别 | 问题 | 修复 |
|------|------|------|
| P1 | 回译/分栏模式死胡同：全书翻译只有 toggleTranslation 一个入口，没开过开关就进回译 = 永久假加载 | setReadingMode 对 BACK_TRANSLATION/SPLIT 且译文为空时自动 translateAllParagraphs；loadBook 恢复这两个模式时同样补触发 |
| P1 | 翻译失败完全静默（三个 catch 只 log） | 失败 toast + showTranslation=false；回译/分栏视图新增"译文不可用/点击重试"（isTranslating + onRetryTranslate，VM 新增 retryTranslation） |
| P1(SWE-R1) | ML Kit 软失败填全空 Map → hasTranslation=true 压住重试 UI，retryTranslation 的 isEmpty 守卫也挡住重试 | 全空视为失败：不留空 Map、清 isTranslating、toast，重试路径恢复可达 |
| P1 | 溢出菜单"自动朗读"绕过防剧透闸（toggleTts 有守卫，toggleAutoRead 没有） | toggleAutoRead 启动前同款 CLOZE/DICTATION/FUZZY 拦截（停止不受限） |
| P1 | 换书不取消 translationJob、不清译文/弹窗状态，旧书译文按下标写进新书 | loadBook 取消 translationJob；状态更新清空 paragraphTranslations/showTranslation/isTranslating/selectedVocab/showWordDialog/wordDefinition/hiddenWordAnswer + 句子翻译两个 StateFlow；翻译 Job 全程带 myBookId 身份守卫（取消是非抢占的，成功写也要核对） |
| P1 | 换书统计落库必输竞态：launch 体读到已同步归零的 sessionCharsRead 直接早返回 | loadBook 重置字段前快照传参；flushSessionStats 参数化（chars/baseTime/highWater/clearSession），换书路径 clearSession=false |
| P1 | 阅读加载路径残留主线程 epubParser.parseBook（阻塞 zip IO + 正则） | withContext(Dispatchers.IO) 包裹 |
| P1 | 默认配置下用户高亮永不渲染：只有普通分支画高亮，而生词高亮默认开 | 新增 overlayParagraphHighlights 共享后处理（词色之上叠背景），接入 Collins 与生词两个着色分支；remember 键补 paraHighlights |
| P1 | translateSentence 无串行化，慢翻译（首次下 ML Kit 模型）旧结果覆盖新句子弹窗 | sentenceTranslateJob cancel-and-relaunch（与 selectWordJob 同型），cleanup 一并取消 |
| P2 | 成分分析/回译视图无反向滚动同步：在这两个模式里滑多远，进度/统计都停在旧位置 | 两个视图补 onVisibleParagraphChanged + snapshotFlow（回译视图含 +1 表头偏移，与 SPLIT 同款），调用点接线 |
| P3(SWE-R1) | 单段朗读被外部停止时 onComplete 被吞，isTtsPlaying 卡 true | externalStop 收集器走 stopAllPlayback() 统一复位 |
| P4 | doSaveProgress 每次防抖保存都 joinToString 整本书（O(book)，滑杆拖动 300ms 一次） | 复用 loadBook 已算好的 totalReadChars（同口径），超限回退原计算 |

## 2. SWE 循环记录

- 策略：review_guided（候选 = 本轮实现的工作区 diff，engineering 8 维度评审）。
- R1：REQUEST_CHANGES（0.80），1 P1 + 1 P2 + 4 P4 —— 全部证据确凿、逐条修复，
  门禁复绿。
- R2（全新评审代理）：APPROVE_WITH_SUGGESTIONS（0.82），0 个 P0-P3；
  4 条 P4 建议消化 3 条（mismatch 连接泄漏、失败原因双份模板、SPLIT 补重试），
  第 4 条（种子进度在 416 删除后的计数回滚）判定为今日不可达路径上的纯装饰项
  （manifest 无逐文件 URL），记延期。
- 门禁最终态：detekt 0 issue；testDebugUnitTest 73/73 通过。

## 3. 延期清单

| 项 | 原因 |
|----|------|
| 逐文件续传"种子已计、残片后删"的进度多计回滚 | 仅装饰；今日不可达（无逐文件 URL 的模型），完成判定走 .complete 标记不受影响 |
| 高亮叠加未覆盖自动朗读句级分支 | 段落坐标系 → 句坐标系需要句内偏移映射，收益低、出错面大；自动朗读时句子本身有独立底色 |
| `Divider → HorizontalDivider`（R9 延期） | 沿用 R9 结论：BOM 升级时一并处理 |
