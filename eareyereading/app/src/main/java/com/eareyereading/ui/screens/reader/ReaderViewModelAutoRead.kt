@file:Suppress("TooGenericExceptionCaught")

package com.eareyereading.ui.screens.reader

import androidx.lifecycle.viewModelScope
import com.eareyereading.domain.model.*
import com.eareyereading.util.*
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * 自动全文朗读循环：逐段驱动 TTS、看门狗超时保护与句子高亮推进。
 *
 * 从 [ReaderViewModelPlayback] 按 SRP 抽出：自动朗读的驱动循环是独立职责，
 * 与播放仲裁（stopAllPlayback）、RSVP/速读循环解耦。
 */

// ── 自动全文朗读 ─────────────────────────────

internal fun ReaderViewModel.startAutoRead() {
    val paragraphs = _uiState.value.paragraphs
    if (paragraphs.isEmpty()) return

    // 启动前停掉其他播放形态（仲裁，见 stopAllPlayback 说明）
    stopAllPlayback()
    // 首声埋点：UI 点击瞬间标记计时起点（含等锁/初始化时间）
    if (ttsHelper.getEngineType() != "tencent") ttsHelper.getEmbeddedEngine().beginFirstAudioTrace()

    // 初始化放进被追踪的 autoReadJob：初始化窗口内的第二次点击
    // 会先 cancel 掉第一次尝试，不再出现两条并发朗读链
    autoReadJob = viewModelScope.launch {
        if (!_uiState.value.ttsInitialized) {
            val ok = try {
                ttsHelper.initialize(_uiState.value.book?.language ?: "en")
            } catch (e: TimeoutCancellationException) {
                android.util.Log.w("ReaderViewModel", "TTS init timed out", e)
                false
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                android.util.Log.e("ReaderViewModel", "TTS init failed", e)
                false
            }
            _uiState.update { it.copy(ttsInitialized = ok) }
            if (!ok) {
                handleTtsInitFailure("自动朗读不可用")
                return@launch
            }
        }
        // 内置模型与本书语言不匹配时先切换（英文书→纯英文模型），
        // 已匹配/无对应模型时为 no-op
        ttsHelper.switchEmbeddedModelIfNeeded(_uiState.value.book?.language)
        hintEmbeddedVoiceMismatchIfNeeded()
        doStartAutoRead(paragraphs)
    }
}

/**
 * 朗读看门狗时长：按内容量估算，下限 90 秒。
 * 中文语速 ~3-4 字/秒（≈300ms/字），比英文慢得多，必须分开预算，
 * 否则 200 字以上的中文段会在朗读中途被看门狗切断。
 * 语速倍率（0.5x-2.0x）也影响实际时长，统一放宽到最慢档兜底。
 */
internal fun ReaderViewModel.watchdogMs(sentences: List<String>): Long {
    val text = sentences.joinToString("")
    val hasCjk = text.any { it in '\u4e00'..'\u9fff' }
    val perCharMs = if (hasCjk) 350L else 120L
    return maxOf(90_000L, text.length * perCharMs)
}

private fun ReaderViewModel.doStartAutoRead(paragraphs: List<String>) {
    val startParaIdx = _uiState.value.currentParagraphIndex
    hintTtsWarmUpIfNeeded()
    // autoReadingParaIndex 从实际起播段开始（原实现恒置 0，与起播位置不符）
    _uiState.update { it.copy(isAutoReading = true, autoReadingParaIndex = startParaIdx, currentSentenceIndex = 0) }

    autoReadJob = viewModelScope.launch {
        for (paraIdx in startParaIdx until paragraphs.size) {
            if (!_uiState.value.isAutoReading) break

            val para = paragraphs[paraIdx]
            // 空段/插图标记段无可读文本：推进索引与统计后跳过
            if (para.isBlank() || BookImages.isImageMarker(para)) {
                _uiState.update { it.copy(autoReadingParaIndex = paraIdx, currentParagraphIndex = paraIdx) }
                recordParagraphVisit(paraIdx)  // issue 3.6
                continue
            }

            _uiState.update { it.copy(autoReadingParaIndex = paraIdx, currentParagraphIndex = paraIdx) }
            recordParagraphVisit(paraIdx)  // issue 3.6：自动朗读逐段累计

            // 按句子分割（与速读/引擎侧共用同一套切分，保证行为一致；含中文标点）
            val sentences = splitSentencesCompat(para)
            _uiState.update { it.copy(currentSentences = sentences) }

            // 启动新链前停掉上一条链：看门狗触发或引擎迟滞时，
            // 旧链可能还在出声，直接叠新链会造成两条链交替朗读
            ttsHelper.stop()

            suspendCancellableCoroutine<Unit> { cont ->
                // 用 AtomicBoolean 防止 race；并优先靠 cont.isActive 守门
                val completed = java.util.concurrent.atomic.AtomicBoolean(false)
                var watchdog: kotlinx.coroutines.Job? = null

                fun finishOnce() {
                    if (completed.compareAndSet(false, true) && cont.isActive) {
                        // 自然完成时必须取消看门狗：原实现它作为子协程一直睡到超时，
                        // 拖延 autoReadJob 结束并补一次无意义 finish
                        watchdog?.cancel()
                        cont.resume(Unit)
                    }
                }

                ttsHelper.speakSentences(
                    sentences = sentences,
                    onSentenceDone = { sentenceIdx ->
                        // onSentenceDone 语义是"第 sentenceIdx 句已读完"，
                        // 当前正在读的是下一句；原实现直接写 sentenceIdx，
                        // 高亮永远落后音频一句
                        _uiState.update {
                            it.copy(currentSentenceIndex = (sentenceIdx + 1).coerceAtMost(sentences.size - 1))
                        }
                    },
                    onAllDone = {
                        finishOnce()
                    },
                )

                // 超时保护：按内容量估算而不是固定 60 秒——
                // 固定 60 秒会把超过 ~150 词的段落读到一半就切断推进。
                // 用 cont.context 派生子协程，cont 被取消时子协程自动取消。
                watchdog = kotlinx.coroutines.CoroutineScope(cont.context).launch {
                    kotlinx.coroutines.delay(watchdogMs(sentences))
                    finishOnce()
                }

                // cont 取消时（父协程 stopAutoRead() 取消），立刻把 completed 标 true
                // 防止 speakSentences 的异步回调在取消后又 resume。
                cont.invokeOnCancellation {
                    completed.set(true)
                }
            }

            // 段落间停顿
            // 段间 gap 埋点：标记上一段播完时刻，与下一段首声日志对照可量化段间静默
            // paraBoundarySilence = 下一段 click→headMoved（首声埋点已覆盖）+ PARAGRAPH_PAUSE_MS
            android.util.Log.i(
                "EmbeddedTtsEngine",
                "TTS gap: paraBoundary paraIdx=$paraIdx doneAt=${System.currentTimeMillis()}ms, pauseMs=$PARAGRAPH_PAUSE_MS",
            )
            kotlinx.coroutines.delay(PARAGRAPH_PAUSE_MS)
        }

        _uiState.update { it.copy(isAutoReading = false, currentSentences = emptyList()) }
    }
}
