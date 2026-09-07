@file:Suppress("TooGenericExceptionCaught")

package com.eareyereading.ui.screens.reader

import androidx.lifecycle.viewModelScope
import com.eareyereading.domain.model.*
import com.eareyereading.domain.repository.*
import com.eareyereading.ui.theme.*
import com.eareyereading.util.*
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * 练习域：挖空（Cloze）、模糊（Fuzzy）、听写（Dictation）的生成与答案核对。
 */
internal class ReaderViewModelPractice(
    private val vm: ReaderViewModel,
    private val wordAnalyzer: WordAnalyzer,
    private val readingRepository: ReadingRepository,
) {
    fun generateCloze() {
        val paragraphs = vm._uiState.value.paragraphs
        val currentIdx = vm._uiState.value.currentParagraphIndex
        if (currentIdx < paragraphs.size) {
            // 插图标记剔除后再生成（标记不是可挖空的文本）
            val text = BookImages.stripImageMarkers(paragraphs[currentIdx])
            val clozeWords = wordAnalyzer.generateClozeText(text, ratio = CLOZE_RATIO)
            vm._uiState.update { it.copy(clozeWords = clozeWords, hiddenWordAnswer = null) }
        }
    }

    fun generateFuzzy() {
        val paragraphs = vm._uiState.value.paragraphs
        val currentIdx = vm._uiState.value.currentParagraphIndex
        if (currentIdx < paragraphs.size) {
            // 插图标记剔除后再生成（标记不是可模糊的文本）
            val text = BookImages.stripImageMarkers(paragraphs[currentIdx])
            val fuzzyWords = wordAnalyzer.generateFuzzyText(text, visibleRatio = FUZZY_VISIBLE_RATIO)
            vm._uiState.update { it.copy(fuzzyWords = fuzzyWords) }
        }
    }

    /**
     * 显示答案（挖空练习）：逐个揭示隐藏词。
     * 原实现每次只 find 第一个 isHidden 且永不清除标记，
     * 多点几次永远只能看到同一个词的答案；现在每按一次
     * 揭示一个隐藏词（清掉该位置的 isHidden），全部揭示后按钮失效
     */
    fun hideWord() {
        val words = vm._uiState.value.clozeWords
        val idx = words.indexOfFirst { it.isHidden }
        if (idx < 0) return
        val revealed = words[idx]
        vm._uiState.update {
            it.copy(
                hiddenWordAnswer = revealed.text,
                clozeWords = words.toMutableList().apply {
                    this[idx] = revealed.copy(isHidden = false)
                },
            )
        }
    }

    /**
     * 听写模式核对答案：输入与下一个隐藏词匹配才揭示。
     * @return 是否匹配成功（视图侧据此清空输入框）
     */
    fun checkDictationAnswer(input: String): Boolean {
        val words = vm._uiState.value.clozeWords
        val idx = words.indexOfFirst { it.isHidden }
        if (idx < 0) return false
        val target = words[idx].text
        if (!input.trim().equals(target, ignoreCase = true)) {
            vm.showToast("不对，再试试（提示：${target.length} 个字母）")
            return false
        }
        vm._uiState.update {
            it.copy(
                hiddenWordAnswer = target,
                clozeWords = words.toMutableList().apply {
                    this[idx] = words[idx].copy(isHidden = false)
                },
            )
        }
        return true
    }

    // ── 听写练习 ─────────────────────────────
    fun startDictation(paragraphIndex: Int) {
        val para = vm._uiState.value.paragraphs.getOrNull(paragraphIndex) ?: return
        // 插图标记不是可听写文本，剔除后再取词
        val allWords = wordAnalyzer.extractWords(BookImages.stripImageMarkers(para))
        if (allWords.isEmpty()) return
        // 采样要听写的词（去重）后，复用 generateClozeText 生成**带分隔符**的
        // token 流：旧实现只放纯单词 token，渲染出来所有词连成一串没法读。
        // 答案核对走 checkDictationAnswer（输入匹配才揭示）
        val hideSet = allWords.map { it.lowercase(java.util.Locale.ROOT) }
            .filter { it.length > 2 }
            .distinct()
            .shuffled()
            .take(maxOf(1, allWords.size / 3))
            .toSet()
        val cloze = wordAnalyzer.generateClozeText(para, wordsToHide = hideSet)
        vm.stopAllPlayback()
        vm._uiState.update {
            it.copy(
                readingMode = ReadingMode.DICTATION,
                clozeWords = cloze,
                hiddenWordAnswer = null,
                currentParagraphIndex = paragraphIndex,
            )
        }
        // 与 setReadingMode 对齐：持久化模式，重开书能恢复
        vm.currentBookId?.let { id ->
            vm.viewModelScope.launch { readingRepository.updateMode(id, ReadingMode.DICTATION) }
        }
    }

    companion object {
        // 挖空练习：挖空比例
        private const val CLOZE_RATIO = 0.15f
        // 模糊听读：可见字符比例
        private const val FUZZY_VISIBLE_RATIO = 0.3f
    }
}
