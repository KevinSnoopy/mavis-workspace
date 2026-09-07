@file:Suppress("TooGenericExceptionCaught")

package com.eareyereading.ui.screens.reader

import androidx.lifecycle.viewModelScope
import com.eareyereading.domain.model.*
import com.eareyereading.domain.repository.*
import com.eareyereading.ui.theme.*
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * 导航域：段落前进/后退/跳转、视口滚动同步、阅读模式切换。
 */
internal class ReaderViewModelNavigation(
    private val vm: ReaderViewModel,
    private val readingRepository: ReadingRepository,
    private val practice: ReaderViewModelPractice,
) {
    fun setReadingMode(mode: ReadingMode) {
        // 切模式必须停掉所有形态的播放（含单段朗读），
        // 否则音频会跨模式继续播
        vm.stopAllPlayback()

        if (mode == ReadingMode.CLOZE) {
            practice.generateCloze()
        } else if (mode == ReadingMode.FUZZY) {
            practice.generateFuzzy()
        }

        // 回译/分栏模式依赖全书译文，但旧实现里全书翻译只有
        // toggleTranslation() 一个入口——从没开过翻译开关就进回译模式，
        // 页面永远停在"正在获取译文..."的假加载态（没有任何任务在跑）。
        // 总是补缺：部分缓存的书也继续翻完剩余段落
        if (mode == ReadingMode.BACK_TRANSLATION || mode == ReadingMode.SPLIT) {
            vm.translateAllParagraphs()
        }

        vm.viewModelScope.launch {
            vm._uiState.update { it.copy(readingMode = mode, showModeSelector = false) }
            vm.currentBookId?.let { readingRepository.updateMode(it, mode) }
        }
    }

    fun nextParagraph() {
        val paragraphs = vm._uiState.value.paragraphs
        if (paragraphs.isEmpty()) return
        // 手动跳转必须停掉进行中的播放：否则朗读循环下一步会把
        // currentParagraphIndex 又写回它自己的进度，视口被拽回
        vm.stopAllPlayback()
        val nextIdx = (vm._uiState.value.currentParagraphIndex + 1).coerceAtMost(paragraphs.size - 1)
        vm._uiState.update { it.copy(currentParagraphIndex = nextIdx, currentWordIndex = 0) }
        vm.recordParagraphVisit(nextIdx)  // issue 3.6：原子累计，不等防抖保存
        vm.saveProgress()
        if (vm._uiState.value.readingMode == ReadingMode.CLOZE) practice.generateCloze()
        if (vm._uiState.value.readingMode == ReadingMode.FUZZY) practice.generateFuzzy()
    }

    fun prevParagraph() {
        val paragraphs = vm._uiState.value.paragraphs
        if (paragraphs.isEmpty()) return
        vm.stopAllPlayback()
        val prevIdx = (vm._uiState.value.currentParagraphIndex - 1).coerceAtLeast(0)
        vm._uiState.update { it.copy(currentParagraphIndex = prevIdx, currentWordIndex = 0) }
        vm.saveProgress()
        if (vm._uiState.value.readingMode == ReadingMode.CLOZE) practice.generateCloze()
        if (vm._uiState.value.readingMode == ReadingMode.FUZZY) practice.generateFuzzy()
    }

    fun goToParagraph(index: Int) {
        val paragraphs = vm._uiState.value.paragraphs
        if (paragraphs.isEmpty()) return
        vm.stopAllPlayback()
        val idx = index.coerceIn(0, paragraphs.size - 1)
        vm._uiState.update { it.copy(currentParagraphIndex = idx, currentWordIndex = 0) }
        vm.recordParagraphVisit(idx)  // issue 3.6
        vm.saveProgress()
        if (vm._uiState.value.readingMode == ReadingMode.CLOZE) practice.generateCloze()
        if (vm._uiState.value.readingMode == ReadingMode.FUZZY) practice.generateFuzzy()
    }

    /**
     * 视口滚动同步（NORMAL 模式 LazyColumn 上报可见段落）。
     * 滑动阅读时让底栏/进度/统计跟上视口；播放进行中由播放循环主导索引，忽略上报
     */
    fun onVisibleParagraphChanged(index: Int) {
        val s = vm._uiState.value
        if (s.isAutoReading || s.isPlaying || s.isTtsPlaying) return
        if (index < 0 || index >= s.paragraphs.size) return
        if (index == s.currentParagraphIndex) return
        vm._uiState.update { it.copy(currentParagraphIndex = index, currentWordIndex = 0) }
        vm.recordParagraphVisit(index)  // issue 3.6：视口滚动前进按段累计
        vm.saveProgress()
        if (s.readingMode == ReadingMode.CLOZE) practice.generateCloze()
        if (s.readingMode == ReadingMode.FUZZY) practice.generateFuzzy()
    }
}
