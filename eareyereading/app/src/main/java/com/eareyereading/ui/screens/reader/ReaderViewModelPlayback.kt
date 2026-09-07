@file:Suppress("TooGenericExceptionCaught")

package com.eareyereading.ui.screens.reader

import androidx.lifecycle.viewModelScope
import com.eareyereading.domain.model.*
import com.eareyereading.util.*
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

/**
 * 播放控制域：RSVP/速读/自动朗读/单段朗读的仲裁（stopAllPlayback）、
 * 公共 toggle/stop API、TTS 初始化与句子切分常量。
 *
 * 自动朗读循环见 [ReaderViewModelAutoRead]；RSVP/速读循环见 [ReaderViewModelRsvpSpeed]。
 */

// 自动朗读：段落间停顿时间（毫秒）
internal const val PARAGRAPH_PAUSE_MS = 600L
// 快速阅读：默认语速（词/分钟），用于计算每段停留时间
internal const val SPEED_READ_WPM = 130
// 快速阅读：每段最小停留时间（毫秒）
internal const val SPEED_READ_MIN_DELAY_MS = 1500L

// 句子边界（ASCII）：句末标点 + 空白 + 大写字母/引号/左括号。
// "Aug." "Mr." "Dr." 这类缩写后的 "." + 空格 + 小写/数字不会误切，
// 而 "happened. The" 的正常句子边界仍能切出。提升为常量避免热路径重复编译。
private val SENTENCE_BOUNDARY = Regex("(?<=[.!?])\\s+(?=[A-Z\"\\(])")

// 句子边界（CJK）：全角句点 。！？；（允许尾随闭引号/括号）。
// 中文不靠空白分句；不处理的话整段中文是一个"句子"，
// 与引擎侧切分不一致且被逐句长度限制截断
private val SENTENCE_BOUNDARY_CJK = Regex("(?<=[。！？；][”’」』]?)")

/** 与 EmbeddedTtsEngine 侧一致的句子切分：先按全角句点切，再按 ASCII 边界切。 */
internal fun splitSentencesCompat(text: String): List<String> =
    text.split(SENTENCE_BOUNDARY_CJK)
        .flatMap { it.split(SENTENCE_BOUNDARY) }
        .map { it.trim() }
        .filter { it.isNotBlank() }

// ── 自动全文朗读 ─────────────────────────────
fun ReaderViewModel.toggleAutoRead() {
    if (_uiState.value.isAutoReading) {
        stopAutoRead()
        return
    }
    // 与 toggleTts 同一道防剧透闸：溢出菜单"自动朗读"直达本函数，
    // 没有这道守卫时挖空/听写/模糊模式下会把含答案的原文整本读出来
    when (_uiState.value.readingMode) {
        ReadingMode.CLOZE, ReadingMode.DICTATION, ReadingMode.FUZZY -> {
            showToast("当前模式含隐藏内容，朗读会泄露答案")
            return
        }
        else -> Unit
    }
    // 防抖：连点（<500ms）直接忽略，避免并发 initialize 竞争
    val now = System.currentTimeMillis()
    if (now - lastTogglePlayMs < 500L) return
    lastTogglePlayMs = now
    startAutoRead()
}

fun ReaderViewModel.stopAutoRead() {
    autoReadJob?.cancel()
    ttsHelper.stop()
    _uiState.update { it.copy(isAutoReading = false, currentSentences = emptyList(), currentSentenceIndex = 0) }
}

/**
 * 播放仲裁：四种播放形态（RSVP/速读/自动朗读/单段朗读）共用同一个
 * TtsHelper 单例，启动任何一种之前必须先停掉其余所有。
 * 否则被打断的一方会把"被打断"读成"读完了"继续推进下一段
 * （SYSTEM 的 stop 补偿回调/EMBEDDED 的 finally 都会触发 onAllDone），
 * 出现自动朗读以 600ms/段 扫完全书、与 RSVP 抢引擎的乱象；
 * 同时复位全部播放标志，杜绝 isPlaying/isTtsPlaying 卡死。
 */
internal fun ReaderViewModel.stopAllPlayback() {
    rsvpJob?.cancel()
    speedJob?.cancel()
    autoReadJob?.cancel()
    // ttsInitJob 也必须取消：初始化成功后会回调 doToggleTts() 启动播放，
    // 若不取消，用户在初始化窗口内切到别的播放形态后，
    // 迟到的初始化回调会在新生播放之上再叠一层单段朗读
    ttsInitJob?.cancel()
    ttsHelper.stop()
    _uiState.update {
        it.copy(
            isPlaying = false,
            isTtsPlaying = false,
            isAutoReading = false,
            currentSentences = emptyList(),
            currentSentenceIndex = 0,
        )
    }
}

fun ReaderViewModel.togglePlay() {
    when (_uiState.value.readingMode) {
        ReadingMode.RSVP -> toggleRsvp()
        ReadingMode.SPEED -> toggleSpeed()
        // NORMAL 模式下「播放」= 从当前段开始自动朗读，与顶栏的「朗读」(只读当前段) 区分开
        ReadingMode.NORMAL -> toggleAutoRead()
        else -> toggleTts()
    }
}

fun ReaderViewModel.toggleRsvp() {
    if (_uiState.value.isPlaying) {
        rsvpJob?.cancel()
        // 暂停即停声：原实现最后一个词会继续播完
        ttsHelper.stop()
        _uiState.update { it.copy(isPlaying = false) }
    } else {
        // 启动前停掉其他播放形态（仲裁）
        stopAllPlayback()
        // 首声埋点：UI 点击瞬间标记计时起点
        if (ttsHelper.getEngineType() != "tencent") ttsHelper.getEmbeddedEngine().beginFirstAudioTrace()
        // 初始化放进被追踪的 rsvpJob：初始化窗口内的连点会取消第一次尝试，
        // 不再出现两条并发播放循环交替调 speak() 的乱序音频
        rsvpJob = viewModelScope.launch {
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
                    handleTtsInitFailure("RSVP 不可用")
                    return@launch
                }
            }
            // 内置模型与本书语言不匹配时先切换，已匹配时为 no-op
            ttsHelper.switchEmbeddedModelIfNeeded(_uiState.value.book?.language)
            hintEmbeddedVoiceMismatchIfNeeded()
            startRsvp()
        }
    }
}

fun ReaderViewModel.toggleSpeed() {
    if (_uiState.value.isPlaying) {
        speedJob?.cancel()
        ttsHelper.stop()
        _uiState.update { it.copy(isPlaying = false) }
    } else {
        // 启动前停掉其他播放形态（仲裁）
        stopAllPlayback()
        // 首声埋点：UI 点击瞬间标记计时起点
        if (ttsHelper.getEngineType() != "tencent") ttsHelper.getEmbeddedEngine().beginFirstAudioTrace()
        // 同 toggleRsvp：初始化纳入被追踪的 job，杜绝双循环竞态
        speedJob = viewModelScope.launch {
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
                    handleTtsInitFailure("速读不可用")
                    return@launch
                }
            }
            // 内置模型与本书语言不匹配时先切换，已匹配时为 no-op
            ttsHelper.switchEmbeddedModelIfNeeded(_uiState.value.book?.language)
            hintEmbeddedVoiceMismatchIfNeeded()
            startSpeed()
        }
    }
}

fun ReaderViewModel.toggleTts() {
    // 停止自动朗读（如果正在运行）
    if (_uiState.value.isAutoReading) {
        stopAutoRead()
        return
    }

    // 挖空/听写/模糊模式的练习目标是猜出隐藏词：
    // 单段朗读读的是含答案的原文，一开口就剧透，直接拦截
    when (_uiState.value.readingMode) {
        ReadingMode.CLOZE, ReadingMode.DICTATION, ReadingMode.FUZZY -> {
            showToast("当前模式含隐藏内容，朗读会泄露答案")
            return
        }
        else -> Unit
    }

    if (_uiState.value.isTtsPlaying) {
        ttsHelper.pause()
        // 句链被 stop 打断时 onAllDone 也会清一次；这里同步清，保证
        // UI 立即退出句子高亮态（等回调会有一帧延迟）
        _uiState.update {
            it.copy(isTtsPlaying = false, currentSentences = emptyList(), currentSentenceIndex = 0)
        }
    } else {
        // 防抖：连点（<500ms）直接忽略，避免并发 initialize 竞争
        val now = System.currentTimeMillis()
        if (now - lastTogglePlayMs < 500L) return
        lastTogglePlayMs = now
        // 启动前停掉其他播放形态（RSVP/速读可能在跑）
        stopAllPlayback()
        // 首声埋点：UI 点击瞬间标记计时起点（含初始化/等锁时间）
        if (ttsHelper.getEngineType() != "tencent") ttsHelper.getEmbeddedEngine().beginFirstAudioTrace()
        // TTS 未初始化：初始化纳入被追踪的 job，初始化窗口内的连点先取消上一次
        if (!_uiState.value.ttsInitialized) {
            ttsInitJob?.cancel()
            ttsInitJob = viewModelScope.launch {
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
                if (ok) {
                    hintEmbeddedVoiceMismatchIfNeeded()
                    // 初始化窗口内用户可能已启动别的播放形态（或被停止）：
                    // 复查状态，避免迟到的初始化回调在新生播放之上叠一层单段朗读
                    val s = _uiState.value
                    if (!s.isPlaying && !s.isAutoReading && !s.isTtsPlaying) {
                        doToggleTts()
                    }
                } else {
                    handleTtsInitFailure("朗读不可用")
                }
            }
            return
        }
        // 已初始化但内置模型可能与本书语言不匹配（换了书）：
        // 播放前复查并切换（已匹配/无对应模型时是廉价 no-op）。
        // 纳入被追踪的 ttsInitJob：切换窗口内用户改主意可被仲裁取消
        ttsInitJob?.cancel()
        ttsInitJob = viewModelScope.launch {
            ttsHelper.switchEmbeddedModelIfNeeded(_uiState.value.book?.language)
            hintEmbeddedVoiceMismatchIfNeeded()
            val s = _uiState.value
            if (!s.isPlaying && !s.isAutoReading && !s.isTtsPlaying) {
                doToggleTts()
            }
        }
    }
}

private fun ReaderViewModel.doToggleTts() {
    val para = _uiState.value.paragraphs.getOrNull(_uiState.value.currentParagraphIndex) ?: return
    // 插图标记段无文本可读：直接跳过，不进入 TTS 状态
    if (BookImages.isImageMarker(para)) return
    hintTtsWarmUpIfNeeded()
    // 按句朗读（与自动朗读/速读同一套切分与链式播放）：原实现整段
    // 一次合成，长段既无句级推进高亮，也没法按句暂停跟进
    val sentences = splitSentencesCompat(para)
    if (sentences.isEmpty()) return
    _uiState.update {
        it.copy(isTtsPlaying = true, currentSentences = sentences, currentSentenceIndex = 0)
    }
    ttsHelper.speakSentences(
        sentences = sentences,
        onSentenceDone = { sentenceIdx ->
            // 同自动朗读语义："第 sentenceIdx 句已读完"，高亮推进到下一句
            _uiState.update {
                it.copy(currentSentenceIndex = (sentenceIdx + 1).coerceAtMost(sentences.size - 1))
            }
        },
        onAllDone = {
            viewModelScope.launch {
                _uiState.update {
                    it.copy(isTtsPlaying = false, currentSentences = emptyList(), currentSentenceIndex = 0)
                }
            }
        },
    )
}

internal fun ReaderViewModel.getCurrentParagraphWords(): List<String> {
    val para = _uiState.value.paragraphs.getOrNull(_uiState.value.currentParagraphIndex) ?: return emptyList()
    // 插图标记段无词可读（RSVP 不闪 "[[IMG" 碎片）
    return wordAnalyzer.extractWords(BookImages.stripImageMarkers(para))
}
