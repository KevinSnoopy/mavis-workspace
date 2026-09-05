@file:Suppress("TooGenericExceptionCaught")

package com.eareyereading.ui.screens.reader

import androidx.lifecycle.viewModelScope
import com.eareyereading.domain.model.*
import com.eareyereading.domain.repository.*
import com.eareyereading.ui.theme.*
import com.eareyereading.util.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
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

fun ReaderViewModel.toggleTranslation() {
    val show = !_uiState.value.showTranslation
    // 关闭翻译：取消正在进行的全书翻译 Job 并清空译文，避免偷跑流量后台继续
    // 翻译全部段落（issue 8.10）
    if (!show) {
        translationJob?.cancel()
        _uiState.update { it.copy(showTranslation = false, isTranslating = false, paragraphTranslations = emptyMap()) }
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
        _uiState.update { it.copy(isTranslating = true) }
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
            // 缓存先上屏：开关一开立即可读，不必等全书补翻完成
            if (merged.isNotEmpty() && currentBookId == myBookId) {
                _uiState.update { it.copy(paragraphTranslations = merged.toMap()) }
            }
            // 需要翻译的段落：有源文、尚未缓存；插图标记段无文本不参与翻译
            val missing = paragraphs.indices.filter { idx ->
                paragraphs[idx].isNotBlank() && !merged.containsKey(idx) &&
                    !BookImages.isImageMarker(paragraphs[idx])
            }
            if (missing.isNotEmpty()) {
                // 逐段翻译 · 预翻译优先：从当前阅读位置向两侧扩散排序，
                // 用户正在看的段落最先翻译上屏；后台仍 Semaphore 限流并发
                val center = _uiState.value.currentParagraphIndex
                val ordered = missing.sortedBy { kotlin.math.abs(it - center) }
                val semaphore = Semaphore(TRANSLATION_CONCURRENCY)
                // 渐进上屏合批：翻页模式下 paragraphTranslations 每次更新都会
                // 触发整书重新分页测量，逐段直推 = O(段落数²) 测量开销；
                // 按时间双阈值合并成 ~2.5 次/秒
                var uiDirty = false
                var lastUiFlushMs = 0L
                fun flushUi(force: Boolean = false) {
                    if (!uiDirty) return
                    val now = android.os.SystemClock.elapsedRealtime()
                    if (!force && now - lastUiFlushMs < TRANSLATION_UI_FLUSH_MS) return
                    lastUiFlushMs = now
                    uiDirty = false
                    if (currentBookId == myBookId) {
                        _uiState.update { it.copy(paragraphTranslations = merged.toMap()) }
                    }
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
                    // 逐段渐进：按"离当前阅读位置由近及远"的顺序 await，
                    // 译完一段记一段，达阈值成批上屏 + 落库
                    for (job in jobs) {
                        val (idx, result) = job.await()
                        // issue 8.3：失败段（null/空）不写入显示，也不落缓存
                        if (!result.isNullOrBlank()) {
                            merged[idx] = result
                            pendingSave[idx] = result
                            uiDirty = true
                        }
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
                    _uiState.update { it.copy(isTranslating = false, showTranslation = false) }
                    showToast("翻译失败：翻译模型不可用，请检查网络后重试")
                }
                return@launch
            }
            // 取消是非抢占的：cancel() 若恰好落在 translate 返回之后，
            // 本段仍会执行——按书核对，旧书译文不写进新书状态
            if (currentBookId != myBookId) return@launch
            _uiState.update { it.copy(
                paragraphTranslations = merged.toMap(),
                isTranslating = false,
            ) }
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
                    )
                }
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
                    )
                }
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
