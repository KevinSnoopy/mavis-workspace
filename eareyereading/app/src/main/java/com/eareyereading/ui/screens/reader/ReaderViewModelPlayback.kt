@file:Suppress("TooGenericExceptionCaught")

package com.eareyereading.ui.screens.reader

import androidx.lifecycle.viewModelScope
import com.eareyereading.domain.model.*
import com.eareyereading.domain.repository.*
import com.eareyereading.tts.EmbeddedTtsEngine
import com.eareyereading.ui.theme.*
import com.eareyereading.util.*
import kotlin.coroutines.resume
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * 播放控制域：RSVP/速读/自动朗读/单段朗读的仲裁（stopAllPlayback）、
 * 驱动循环、看门狗与句子切分。
 */

// 自动朗读：段落间停顿时间（毫秒）
private const val PARAGRAPH_PAUSE_MS = 600L

// 快速阅读：默认语速（词/分钟），用于计算每段停留时间
private const val SPEED_READ_WPM = 130

// 快速阅读：每段最小停留时间（毫秒）
private const val SPEED_READ_MIN_DELAY_MS = 1500L

// 句子边界（ASCII）：句末标点 + 空白 + 大写字母/引号/左括号。
// "Aug." "Mr." "Dr." 这类缩写后的 "." + 空格 + 小写/数字不会误切，
// 而 "happened. The" 的正常句子边界仍能切出。提升为常量避免热路径重复编译。
private val SENTENCE_BOUNDARY = Regex("(?<=[.!?])\\s+(?=[A-Z\"\\(])")

// 句子边界（CJK）：全角句点 。！？；（允许尾随闭引号/括号）。
// 中文不靠空白分句；不处理的话整段中文是一个"句子"，
// 与引擎侧切分不一致且被逐句长度限制截断
private val SENTENCE_BOUNDARY_CJK = Regex("(?<=[。！？；][”’」』]?)")

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
    startAutoRead()
}

private fun ReaderViewModel.startAutoRead() {
    val paragraphs = _uiState.value.paragraphs
    if (paragraphs.isEmpty()) return

    // 启动前停掉其他播放形态（仲裁，见 stopAllPlayback 说明）
    stopAllPlayback()

    // 初始化放进被追踪的 autoReadJob：初始化窗口内的第二次点击
    // 会先 cancel 掉第一次尝试，不再出现两条并发朗读链
    autoReadJob = viewModelScope.launch {
        if (!ensureTtsInitialized()) {
            handleTtsInitFailure("自动朗读不可用")
            return@launch
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
private fun ReaderViewModel.watchdogMs(sentences: List<String>): Long {
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
            kotlinx.coroutines.delay(PARAGRAPH_PAUSE_MS)
        }

        _uiState.update { it.copy(isAutoReading = false, currentSentences = emptyList()) }
    }
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
        // 初始化放进被追踪的 rsvpJob：初始化窗口内的连点会取消第一次尝试，
        // 不再出现两条并发播放循环交替调 speak() 的乱序音频
        rsvpJob = viewModelScope.launch {
            if (!ensureTtsInitialized()) {
                handleTtsInitFailure("RSVP 不可用")
                return@launch
            }
            // 内置模型与本书语言不匹配时先切换，已匹配时为 no-op
            ttsHelper.switchEmbeddedModelIfNeeded(_uiState.value.book?.language)
            hintEmbeddedVoiceMismatchIfNeeded()
            startRsvp()
        }
    }
}

private fun ReaderViewModel.startRsvp() {
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

fun ReaderViewModel.toggleSpeed() {
    if (_uiState.value.isPlaying) {
        speedJob?.cancel()
        ttsHelper.stop()
        _uiState.update { it.copy(isPlaying = false) }
    } else {
        // 启动前停掉其他播放形态（仲裁）
        stopAllPlayback()
        // 同 toggleRsvp：初始化纳入被追踪的 job，杜绝双循环竞态
        speedJob = viewModelScope.launch {
            if (!ensureTtsInitialized()) {
                handleTtsInitFailure("速读不可用")
                return@launch
            }
            // 内置模型与本书语言不匹配时先切换，已匹配时为 no-op
            ttsHelper.switchEmbeddedModelIfNeeded(_uiState.value.book?.language)
            hintEmbeddedVoiceMismatchIfNeeded()
            startSpeed()
        }
    }
}

private fun ReaderViewModel.startSpeed() {
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
        // 启动前停掉其他播放形态（RSVP/速读可能在跑）
        stopAllPlayback()
        // TTS 未初始化：初始化纳入被追踪的 job，初始化窗口内的连点先取消上一次
        if (!_uiState.value.ttsInitialized) {
            ttsInitJob?.cancel()
            ttsInitJob = viewModelScope.launch {
                if (ensureTtsInitialized()) {
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

private fun ReaderViewModel.getCurrentParagraphWords(): List<String> {
    val para = _uiState.value.paragraphs.getOrNull(_uiState.value.currentParagraphIndex) ?: return emptyList()
    // 插图标记段无词可读（RSVP 不闪 "[[IMG" 碎片）
    return wordAnalyzer.extractWords(BookImages.stripImageMarkers(para))
}

/** 与 EmbeddedTtsEngine 侧一致的句子切分：先按全角句点切，再按 ASCII 边界切。 */
private fun splitSentencesCompat(text: String): List<String> =
    text.split(SENTENCE_BOUNDARY_CJK)
        .flatMap { it.split(SENTENCE_BOUNDARY) }
        .map { it.trim() }
        .filter { it.isNotBlank() }
