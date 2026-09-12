@file:Suppress("TooGenericExceptionCaught")

package com.eareyereading.ui.screens.reader

import androidx.lifecycle.viewModelScope
import com.eareyereading.domain.model.*
import com.eareyereading.domain.repository.*
import com.eareyereading.ui.theme.*
import com.eareyereading.util.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/**
 * 翻译域：全文翻译（缓存合并/并发限流/渐进上屏/分批落库）、句子翻译弹窗与重试。
 */
/**
 * 段落翻译入口：委托 TranslationHelper.translateParagraph——
 * LLM 已配置时整段带上下文一次成文（文学化译文），否则按句切分
 * 逐句机翻拼接（规避 ML Kit 长输入截断）。
 */
private suspend fun ReaderViewModel.translateParagraphBySentences(
    paragraph: String,
    sourceLang: String,
): String? = translationHelper.translateParagraph(paragraph, sourceLang)

/** 回译模式译文缺失时的手动重试入口（翻译失败后视图提供重试按钮）。 */
fun ReaderViewModel.retryTranslation() {
    // 与 toggleTranslation 同语义：总是补缺（部分缓存的书也能续翻剩余段落）
    translateAllParagraphs()
}

/**
 * 把数据层译文整体提交给正文阅读视图（滚动 [NormalReadingView] / 翻页
 * [PagedReadingView]）。
 *
 * 上屏策略（数据层 [ReaderUiState.paragraphTranslations] 与上屏层
 * [ReaderUiState.readerTranslations] 分离的原因）：
 * 译文是后台逐段翻出来的，如果翻好一段就往页面上贴一段，用户正在读的
 * 页面会被反复推动——滚动模式下每贴一段，同屏下方的内容就往下移一次；
 * 翻页模式下译文参与整书分页，每次变化都会重排全书（当前页内容错位、
 * 页数跳变）。但"整本译完才上屏"同样不可取：长文要等很久，用户会
 * 以为卡死了。所以按"视线内外"区别对待：
 *
 *  · 视口内（用户正看着的这一屏）：先攒着，等这一屏涉及的段落全部翻完，
 *    再一次性上屏——用户只会看到"这一屏刷新了一次"，而且因为翻译顺序是
 *    从当前阅读位置向两侧扩散，这一屏通常几秒内就齐了，不会有等待感；
 *  · 视口外：随时上屏。滚动模式下这篇幅变化发生在屏幕之外，LazyColumn
 *    以首个可见项为锚，不会推动当前屏；翻页模式下不做及时上屏，因为
 *    任何一次译文变化都会触发整书重新分页。
 *
 * 另有几个整体提交点：打开翻译时先把 Room 缓存铺上屏，翻译结束时把
 * 补齐的译文整体提交（失败路径清空）。
 *
 * [ReaderUiState.paragraphTranslations] 同时供分栏/回译等"就是要看译文
 * 逐段浮现"的模式使用，那些模式不读上屏层。
 */
internal fun ReaderViewModel.commitReaderTranslations(snapshot: Map<Int, String>) {
    _uiState.update {
        it.copy(paragraphTranslations = snapshot, readerTranslations = snapshot)
    }
}

/**
 * 全文翻译开关随书落库（reading_state.showTranslation）。
 *
 * 失败路径也会调用它把值改回 false：否则用户下次进书会按"上次开着翻译"
 * 自动重跑整本翻译，失败的源会一直被自动重试（LLM 通道还会持续消耗额度）。
 */
internal fun ReaderViewModel.persistShowTranslation(show: Boolean) {
    val bookId = currentBookId ?: return
    viewModelScope.launch {
        try {
            readingRepository.updateShowTranslation(bookId, show)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            android.util.Log.e("ReaderViewModel", "persist showTranslation failed", e)
        }
    }
}

fun ReaderViewModel.toggleTranslation() {
    val show = !_uiState.value.showTranslation
    // 开关状态随书持久化（开关即排版输入：译文参与整书分页，重进书若回落
    // 到"关"，页边界全变、阅读位置漂移）
    persistShowTranslation(show)
    // 关闭翻译：取消正在进行的全书翻译 Job 并清空译文，避免偷跑流量后台继续
    // 翻译全部段落（issue 8.10）
    if (!show) {
        translationJob?.cancel()
        _uiState.update {
            it.copy(
                showTranslation = false,
                isTranslating = false,
                paragraphTranslations = emptyMap(),
                // 正文视图的提交快照一并清空：否则关掉开关后译文还挂在页面上
                readerTranslations = emptyMap(),
                translationDone = 0,
                translationTotal = 0,
            )
        }
        return
    }
    // isTranslating 必须同步置位：标志原来在 launch 内部才设置，
    // 快速开-关-开会在两次 launch 都未执行前连过两次守卫 → 并发双份全书翻译
    _uiState.update { it.copy(showTranslation = true, isTranslating = true) }
    // 打开翻译总是走"补缺"：loadBook 已把 Room 缓存灌进 paragraphTranslations，
    // 旧实现只要缓存非空就跳过——部分缓存的书（上次中途取消）永远缺尾巴
    translateAllParagraphs()
}

internal fun ReaderViewModel.translateAllParagraphs() {
    // 已在翻译中则不重复启动
    if (translationJob?.isActive == true) return
    // 书本身份快照：换书会取消本 Job，但取消/失败的收尾写仍可能落在
    // 新书加载之后——所有 uiState 写入与 toast 都要先核对当前书
    val myBookId = currentBookId
    val sourceLang = _uiState.value.book?.language?.takeIf { it.isNotBlank() } ?: "en"
    translationJob = viewModelScope.launch {
        // 缓存键分层（LLM/机翻分开缓存）：开启 AI 翻译后旧书的机翻缓存
        // 不会被命中，整本按 LLM 重新翻译落库（挂起读取需在协程内）
        val langPair = translationHelper.effectiveCacheLangPair("$sourceLang>zh")
        // 进度归零后再由"待补翻段落数"置分母：全缓存命中的书不会残留上一次的进度
        _uiState.update { it.copy(isTranslating = true, translationDone = 0, translationTotal = 0) }
        try {
            val paragraphs = _uiState.value.paragraphs
            val bookId = myBookId
            if (bookId == null) {
                if (currentBookId == myBookId) {
                    _uiState.update { it.copy(isTranslating = false, showTranslation = false) }
                }
                return@launch
            }
            // issue 8.5：优先读 Room 缓存，只有未缓存的段落才重新翻译
            val cached = readingRepository.getTranslations(bookId, langPair)
            val merged = cached.toMutableMap()
            // 缓存先上屏：开关一开立即可读，不必等全书补翻完成。
            // 这是正文视图的第一次提交——一次性整体替换，且绑定在"用户刚
            // 打开翻译"这个动作上，不存在"页面自己动"的观感
            if (merged.isNotEmpty() && currentBookId == myBookId) {
                commitReaderTranslations(merged.toMap())
            }
            // 需要翻译的段落：有源文、尚未缓存；插图标记段无文本不参与翻译
            val missing = paragraphs.indices.filter { idx ->
                paragraphs[idx].isNotBlank() && !merged.containsKey(idx) &&
                    !BookImages.isImageMarker(paragraphs[idx])
            }
            if (missing.isNotEmpty()) {
                // 逐段翻译 · 预翻译优先：从当前阅读位置向两侧扩散排序。
                // 这个顺序不仅决定落库先后，也保证"用户正看着的这一屏"
                // 最先翻完——整屏上屏的等待因此只有几秒（见文件头注释）
                val center = _uiState.value.currentParagraphIndex
                val ordered = missing.sortedBy { kotlin.math.abs(it - center) }
                val orderedSet = ordered.toHashSet()
                val semaphore = Semaphore(TRANSLATION_CONCURRENCY)
                // 进度分母先落定，正文顶部浮标立刻可见
                if (currentBookId == myBookId) {
                    _uiState.update {
                        it.copy(translationDone = 0, translationTotal = ordered.size)
                    }
                }
                // 上屏层：数据层的"已可见"子集。视口内段落先攒进 held，
                // 这一屏翻完才一起放出去；视口外在滚动模式下随时放行
                val committed = merged.toMutableMap()
                val held = LinkedHashMap<Int, String>()
                val doneIdx = HashSet<Int>()
                // 每次读取而不是捕获一次：翻译途中切换"滚动/翻页"时上屏
                // 策略要立刻跟着换，否则会把整本一直压着不上屏
                fun isPaged(): Boolean = _uiState.value.pageMode
                var uiDirty = false
                var lastUiFlushMs = 0L
                // 翻页模式下"上一次整屏上屏时所在的页"：只允许每次进入新页
                // 刷新一次。否则本页翻完之后，后续每一批完成都会立刻放行，
                // 每次都触发整书重新分页 → 又回到持续抽搐
                var flushedRange: IntRange = IntRange.EMPTY
                // 完成计数：无论成功失败都推进（失败段也走完了流程），
                // 否则进度条会被永久卡在 99%
                var doneCount = 0
                var progressDirty = false
                fun flushUi(force: Boolean = false) {
                    if (!uiDirty && !progressDirty) return
                    val now = android.os.SystemClock.elapsedRealtime()
                    if (!force && now - lastUiFlushMs < TRANSLATION_UI_FLUSH_MS) return
                    lastUiFlushMs = now
                    val dataDirty = uiDirty
                    val done = doneCount
                    uiDirty = false
                    progressDirty = false
                    if (currentBookId == myBookId) {
                        _uiState.update {
                            it.copy(
                                paragraphTranslations = if (dataDirty) merged.toMap() else it.paragraphTranslations,
                                readerTranslations = if (dataDirty) committed.toMap() else it.readerTranslations,
                                translationDone = done,
                            )
                        }
                    }
                }
                // 视口变化后释放滑出屏幕的段落：它们已经不在用户眼前，
                // 再压着只会让底下的内容缺译文
                fun releaseOutOfView() {
                    if (isPaged() || held.isEmpty()) return
                    val range = visibleParaRange
                    val leaving = held.keys.filter { it !in range }
                    if (leaving.isEmpty()) return
                    leaving.forEach { committed[it] = held.remove(it).orEmpty() }
                    uiDirty = true
                }
                // 这一屏翻完了：整屏一次性上屏。判据是"本屏涉及的待翻段落
                // 都已 await 过"，用有序 await 的进度判断，无需额外状态
                fun releaseIfViewComplete() {
                    if (held.isEmpty()) return
                    val range = visibleParaRange
                    if (range.isEmpty()) return
                    val blocked = range.any { it in orderedSet && it !in doneIdx }
                    if (blocked) return
                    // 翻页模式：本页已经刷新过就不再刷新，避免后续每批完成
                    // 都触发整书重新分页（页内容会持续重排）
                    if (isPaged() && range == flushedRange) return
                    flushedRange = range
                    committed.putAll(held)
                    held.clear()
                    uiDirty = true
                }
                // 分批落库：每 N 段一个事务，中途取消/失败时已译段落不丢
                val pendingSave = LinkedHashMap<Int, String>()
                suspend fun flushSave() {
                    if (pendingSave.isEmpty()) return
                    val batch = pendingSave.toMap()
                    pendingSave.clear()
                    try {
                        readingRepository.saveTranslations(bookId, langPair, paragraphs, batch)
                    } catch (e: Exception) {
                        android.util.Log.e("ReaderViewModel", "save translation cache failed", e)
                    }
                }
                coroutineScope {
                    val jobs = ordered.map { idx ->
                        async(Dispatchers.IO) {
                            semaphore.withPermit {
                                idx to translateParagraphBySentences(paragraphs[idx], sourceLang)
                            }
                        }
                    }
                    // 逐段渐进：按"离当前阅读位置由近及远"的顺序 await。
                    // 视口内的段落先攒着（held），视口外在滚动模式下随时放行
                    for (job in jobs) {
                        val (idx, result) = job.await()
                        doneIdx.add(idx)
                        // issue 8.3：失败段（null/空）不写入显示，也不落缓存
                        if (!result.isNullOrBlank()) {
                            merged[idx] = result
                            pendingSave[idx] = result
                            if (isPaged() || idx in visibleParaRange) {
                                held[idx] = result
                            } else {
                                committed[idx] = result
                            }
                            uiDirty = true
                        }
                        doneCount++
                        progressDirty = true
                        releaseOutOfView()
                        releaseIfViewComplete()
                        flushUi()
                        if (pendingSave.size >= TRANSLATION_SAVE_BATCH) flushSave()
                    }
                }
                flushUi(force = true)
                flushSave()
            }
            // 全空视为失败：所有段落要么失败要么无缓存——非空 Map 会把
            // hasTranslation 顶成 true——回译视图变永久空白栏，
            // retryTranslation 的 isEmpty() 守卫又让重试永远不可达
            if (merged.values.none { it.isNotBlank() } && paragraphs.isNotEmpty()) {
                if (currentBookId == myBookId) {
                    _uiState.update {
                        it.copy(
                            isTranslating = false,
                            showTranslation = false,
                            readerTranslations = emptyMap(),
                        )
                    }
                    // 开关同步落回 false：否则下次进书会自动重试这本翻不动的书
                    persistShowTranslation(false)
                    showToast("翻译失败：翻译模型不可用，请检查网络后重试")
                }
                return@launch
            }
            // 取消是非抢占的：cancel() 若恰好落在 translate 返回之后，
            // 本段仍会执行——按书核对，旧书译文不写进新书状态
            if (currentBookId != myBookId) return@launch
            // 整本翻译结束：数据层与上屏层在同一帧内一次写完，
            // 页面只经历这一次重排（此前是每 400ms 重排一次）
            commitReaderTranslations(merged.toMap())
            _uiState.update {
                it.copy(isTranslating = false, translationDone = 0, translationTotal = 0)
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            // 协程取消必须向上传播，否则取消后还会继续更新状态；
            // 只在还是同一本书时复位标志——换书取消后这里若落地，
            // 会把新书正在进行的翻译 spinner 提前掐灭
            if (currentBookId == myBookId) {
                _uiState.update { it.copy(isTranslating = false) }
            }
            throw e
        } catch (e: com.google.mlkit.common.MlKitException) {
            android.util.Log.e("ReaderViewModel", "ML Kit translation failed", e)
            // 失败必须可见：旧实现只 log，回译模式永远停在"正在获取译文..."，
            // NORMAL 模式开关开着却什么都没有，用户无任何线索
            if (currentBookId == myBookId) {
                // issue 8.3：失败分支显式清空译文，isEmpty() 失败判定
                // 才能重新触发，"重试"入口可达
                _uiState.update {
                    it.copy(
                        isTranslating = false,
                        showTranslation = false,
                        paragraphTranslations = emptyMap(),
                        readerTranslations = emptyMap(),
                    )
                }
                persistShowTranslation(false)
                showToast("翻译失败：模型下载或翻译出错，请稍后重试")
            }
        } catch (e: java.lang.RuntimeException) {
            android.util.Log.e("ReaderViewModel", "Translation failed", e)
            if (currentBookId == myBookId) {
                _uiState.update {
                    it.copy(
                        isTranslating = false,
                        showTranslation = false,
                        paragraphTranslations = emptyMap(),
                        readerTranslations = emptyMap(),
                    )
                }
                persistShowTranslation(false)
                showToast("翻译失败，请稍后重试")
            }
        }
    }
}

fun ReaderViewModel.translateSentence(sentence: String) {
    // 与 selectWord 同款串行化：旧实现每次双击各起一个不取消的协程，
    // 慢翻译（首次要下载 ML Kit 模型）的旧结果会后到覆盖新句子的弹窗——
    // 用户看到的是句子 B 配译文 A
    sentenceTranslateJob?.cancel()
    sentenceTranslateJob = viewModelScope.launch {
        // issue 8.8：必须先清旧译文再换标题。两个 StateFlow 分开发射，
        // 若先写 sentence 再写 null，Compose 可能在中间帧读到
        // "新句子 + 旧译文"（标题已换译文还是旧的）。先清译文，
        // 中间帧只会是"旧句子 + 空译文"，不会张冠李戴。
        _sentenceTranslation.value = null
        _selectedSentence.value = sentence
        // issue 8.1：随书语言翻译句子，不再写死 en→zh
        val sourceLang = _uiState.value.book?.language?.takeIf { it.isNotBlank() } ?: "en"
        // 抛异常与返回 null 同样按失败处理：不拦会崩 app，
        // 且弹窗以 == null 判定"加载中"，异常后不写值会永远转圈
        val result = try {
            translationHelper.translateSentence(sentence, sourceLang)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            android.util.Log.e("ReaderViewModel", "translateSentence failed", e)
            null
        }
        // 失败时写空串而不是 null：弹窗以 == null 判定"加载中"，
        // 失败写 null 会让加载指示永远转下去（"翻译失败"分支是死代码）
        _sentenceTranslation.value = result ?: ""
    }
}

fun ReaderViewModel.dismissSentenceTranslation() {
    _selectedSentence.value = null
    _sentenceTranslation.value = null
}

/** 句子翻译失败后的重试入口：对当前选中句子重新翻译。 */
fun ReaderViewModel.retrySentenceTranslation() {
    _selectedSentence.value?.let { translateSentence(it) }
}

    // 整书翻译并发上限：几百段一次性 async 同时压 ML Kit（各自还可能
    // 等模型就绪/触发下载限流），限流后吞吐更高也更稳
    private const val TRANSLATION_CONCURRENCY = 6

    // 逐段渐进上屏的合批窗口：翻页模式下每次译文更新触发整书重新分页，
    // 400ms 合并一次把重组/测量开销压到常数级，视觉上仍是"逐段浮现"
    private const val TRANSLATION_UI_FLUSH_MS = 400L

    // 译文分批落库批大小：中途取消/失败时已译段落不丢，也避免整本一个大事务
    private const val TRANSLATION_SAVE_BATCH = 16
