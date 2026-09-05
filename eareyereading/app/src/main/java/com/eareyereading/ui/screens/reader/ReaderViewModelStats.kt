@file:Suppress("TooGenericExceptionCaught")

package com.eareyereading.ui.screens.reader

import androidx.lifecycle.viewModelScope
import com.eareyereading.domain.model.*
import com.eareyereading.domain.repository.*
import com.eareyereading.ui.theme.*
import com.eareyereading.util.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

/**
 * 进度与统计域：防抖保存阅读进度、段落访问字符累计（高水位）、会话统计落库。
 */
fun ReaderViewModel.saveProgress() {
    // 防抖：进度条拖动时每像素都会触发一次保存，300ms 内合并成一次写库。
    // 退出路径不经这里（cleanup 直接调 doSaveProgress），不会丢进度
    saveJob?.cancel()
    saveJob = viewModelScope.launch {
        delay(300)
        doSaveProgress()
    }
}

/**
 * 持久化阅读进度 + 更新会话统计。
 *
 * 提取为 suspend 函数：[cleanup] 会同步（runBlocking）调用本函数，
 * 保证退出路径无论 viewModelScope 处于什么状态都能完成保存。
 */
internal suspend fun ReaderViewModel.doSaveProgress() {
    val bookId = currentBookId ?: return
    // 书籍从未成功加载（如 id 不存在）：不写任何进度，防止孤儿行
    if (!bookLoaded) return
    val state = _uiState.value
    // 总字数在 loadBook 时已按同一口径（段落 "\n\n" 拼接）算好并存进
    // totalReadChars——这里每次防抖保存都 joinToString 整本书是 O(book)
    // 字符串构建，滑杆拖动时 300ms 一次全在主线程上
    val totalChars = if (state.totalReadChars > 0) {
        state.totalReadChars.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
    } else {
        state.paragraphs.joinToString("\n\n").length
    }
    // 进度语义：读完第 idx 段 = (idx+1)/size，最后一段读完应到 1.0
    // （原实现 idx/size 永远到不了 1.0，书库显示 99%）
    val progress = if (state.paragraphs.isNotEmpty()) {
        (state.currentParagraphIndex + 1).toFloat() / state.paragraphs.size
    } else 0f
    bookRepository.updateProgress(bookId, progress.coerceIn(0f, 1f), state.currentParagraphIndex)

    readingRepository.saveState(
        ReadingState(
            bookId = bookId,
            currentPosition = state.currentWordIndex,
            currentParagraph = state.currentParagraphIndex,
            totalCharacters = totalChars,
            totalParagraphs = state.paragraphs.size,
            readingMode = state.readingMode,
            rsvpSpeed = state.rsvpSpeed,
            fontSize = state.fontSize,
            theme = state.theme,
        )
    )

    // 记录阅读统计（仅新增段落计入字符数）。
    // issue 3.6：段落计数改为在"进入/推进段落"时原子记录（recordParagraphVisit），
    // 不再等 300ms 防抖的 doSaveProgress 才推进 lastRecordedParagraphIndex。
    // 否则快速连跳/朗读循环里，防抖窗口内的状态与高水位错位，累计字数会虚增。
    // 这里只负责按需把会话统计落库（增量/兜底）。

    // 增量落库：距上次落库满 1 分钟就写一次，进程被杀不再丢整段会话。
    // 1 分钟门槛同时避免每次保存都记 1 分钟（收尾的零星部分由 cleanup 兜底）
    val now = System.currentTimeMillis()
    if (now - lastFlushTime >= 60_000) {
        flushSessionStats(bookId)
    }
}

/**
 * issue 3.6：原子记录"读到某段"的字符累计。
 * 只在严格前进（高水位上升）时累加，并把高水位 lastRecordedParagraphIndex
 * 同步推进到 newIndex——由此段落计数不再依赖 300ms 防抖的保存时机，
 * 朗读/速读循环里连过数段也按实际经过段落准确累计，不会虚增或漏记。
 * 大跳转（>2 段）只计目标段，防止拖进度条刷满整本书字数。
 */
internal fun ReaderViewModel.recordParagraphVisit(newIndex: Int) {
    if (newIndex <= lastRecordedParagraphIndex) return
    val paragraphs = _uiState.value.paragraphs
    val jumped = newIndex - lastRecordedParagraphIndex
    val charsAdded = if (jumped <= 2) {
        (lastRecordedParagraphIndex + 1..newIndex).sumOf { idx ->
            paragraphs.getOrNull(idx)?.length ?: 0
        }
    } else {
        paragraphs.getOrNull(newIndex)?.length ?: 0
    }
    sessionCharsRead += charsAdded
    lastRecordedParagraphIndex = newIndex
}

/**
 * 会话统计落库。
 *
 * 默认读会话字段（增量落库/cleanup 收尾路径）；换书路径由 loadBook
 * 在重置字段前快照传入，clearSession=false 表示字段已重置、落库后
 * 不再清（清了也无害，但会误清掉新书的全新会话起始基准）。
 */
internal suspend fun ReaderViewModel.flushSessionStats(
    bookId: Long,
    chars: Long = sessionCharsRead,
    baseTime: Long = lastFlushTime,
    paragraphsHighWater: Int = (lastRecordedParagraphIndex + 1).coerceAtLeast(1),
    clearSession: Boolean = true,
) {
    // 幂等：成功落库后 sessionCharsRead 归零，第二次调用（如
    // onDispose + onCleared 双路径）会在这里早返回，不会重复写
    if (chars <= 0) return
    val now = System.currentTimeMillis()
    // 按"距上次落库"计分钟：增量落库后基准前移，收尾只补尾部，
    // 不再把整个会话时长重复计入
    val base = if (baseTime > 0) baseTime else readingStartTime
    val minutesRead = ((now - base) / 60_000).toInt().coerceAtLeast(1)
    val dateFormat = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.getDefault())
    val today = dateFormat.format(java.util.Date(now))
    try {
        // 原子累计：@Transaction + (bookId,date) 唯一索引兜底
        readingStatsDao.accumulateDailyStat(
            bookId = bookId,
            date = today,
            addMinutes = minutesRead,
            addChars = chars.toInt(),
            paragraphsHighWater = paragraphsHighWater,
        )
        if (clearSession) {
            sessionCharsRead = 0L
            lastFlushTime = now
        }
        // statsFlushed 只在"会话结束式"收尾时置位（见 cleanup），
        // 增量落库后仍可继续累计
    } catch (e: Exception) {
        android.util.Log.e("ReaderViewModel", "Failed to record stats", e)
    }
}
