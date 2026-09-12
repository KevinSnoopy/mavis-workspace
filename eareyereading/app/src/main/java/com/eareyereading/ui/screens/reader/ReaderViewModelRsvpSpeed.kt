@file:Suppress("TooGenericExceptionCaught")

package com.eareyereading.ui.screens.reader

import androidx.lifecycle.viewModelScope
import com.eareyereading.domain.model.*
import com.eareyereading.util.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * RSVP 速读与快速阅读循环。
 *
 * 从 [ReaderViewModelPlayback] 按 SRP 抽出：RSVP 逐词闪烁与速读逐段驱动
 * 是独立职责，与自动朗读循环、播放仲裁解耦。
 */

internal fun ReaderViewModel.startRsvp() {
    _uiState.update { it.copy(isPlaying = true) }
    rsvpJob = viewModelScope.launch {
        val words = getCurrentParagraphWords()
        // rsvpSpeed 来自 DataStore/阅读状态，未全程校验；0 会直接除零崩溃
        val interval = 60_000L / _uiState.value.rsvpSpeed.coerceIn(100, 800)
        // 恢复的词索引只有下限收敛；内容重切分后可能超出本段词数，
        // 越界时 range 为空 → 一声音不出就结束。在使用点收敛
        val startIdx = _uiState.value.currentWordIndex.coerceIn(0, words.size)
        for (i in startIdx until words.size) {
            if (!_uiState.value.isPlaying) break
            _uiState.update { it.copy(currentWordIndex = i) }
            val word = words.getOrNull(i) ?: break
            ttsHelper.speak(word)
            delay(interval)
        }
        // 自然播完（非暂停）把词索引归零：原实现停在最后一个词，
        // 再点播放只会读出最后一个词就停
        if (_uiState.value.isPlaying) {
            _uiState.update { it.copy(isPlaying = false, currentWordIndex = 0) }
        } else {
            _uiState.update { it.copy(isPlaying = false) }
        }
    }
}

internal fun ReaderViewModel.startSpeed() {
    _uiState.update { it.copy(isPlaying = true) }
    speedJob = viewModelScope.launch {
        val paragraphs = _uiState.value.paragraphs
        for (i in _uiState.value.currentParagraphIndex until paragraphs.size) {
            if (!_uiState.value.isPlaying) break
            _uiState.update { it.copy(currentParagraphIndex = i) }
            recordParagraphVisit(i)  // issue 3.6：速读逐段累计

            // 插图标记段无文本：不驱动 TTS/句子高亮，直接滑过
            if (BookImages.isImageMarker(paragraphs[i])) continue

            // 按句切分（跟自动朗读/引擎侧用同一套切分，保证句边界一致；含中文标点）
            val sentences = splitSentencesCompat(paragraphs[i])
            _uiState.update { it.copy(currentSentences = sentences) }

            if (sentences.isEmpty()) {
                // 没有句子（极少见），按原 WPM 停留时间跳过
                val wordCount = paragraphs[i].split(Regex("\\s+")).count { it.isNotBlank() }
                delay((wordCount * 60L / SPEED_READ_WPM).coerceAtLeast(SPEED_READ_MIN_DELAY_MS))
                continue
            }

            // 启动新链前停掉上一条链（同自动朗读）
            ttsHelper.stop()

            // 调 speakSentences — UI 会按句推进 currentSentenceIndex
            suspendCancellableCoroutine<Unit> { cont ->
                val completed = java.util.concurrent.atomic.AtomicBoolean(false)
                var watchdog: kotlinx.coroutines.Job? = null
                fun finishOnce() {
                    if (completed.compareAndSet(false, true) && cont.isActive) {
                        watchdog?.cancel()
                        cont.resume(Unit)
                    }
                }

                ttsHelper.speakSentences(
                    sentences = sentences,
                    onSentenceDone = { sentenceIdx ->
                        // 同自动朗读：当前读的是"已完成句"的下一句
                        _uiState.update {
                            it.copy(currentSentenceIndex = (sentenceIdx + 1).coerceAtMost(sentences.size - 1))
                        }
                    },
                    onAllDone = { finishOnce() },
                )

                // 超时保护：按内容量估算，自然完成时取消看门狗
                watchdog = kotlinx.coroutines.CoroutineScope(cont.context).launch {
                    kotlinx.coroutines.delay(watchdogMs(sentences))
                    finishOnce()
                }

                cont.invokeOnCancellation {
                    completed.set(true)
                    ttsHelper.stop()
                }
            }

            // 段间停顿：音频已经在上面完整播完，这里只留短停顿。
            // 原实现在音频之后再叠加一个完整 WPM 时长的静默，
            // 每段耗时翻倍，整本书累计出数小时的死空气
            delay(PARAGRAPH_PAUSE_MS)
        }
        _uiState.update {
            it.copy(
                isPlaying = false,
                currentSentences = emptyList(),
                currentSentenceIndex = 0,
            )
        }
    }
}
