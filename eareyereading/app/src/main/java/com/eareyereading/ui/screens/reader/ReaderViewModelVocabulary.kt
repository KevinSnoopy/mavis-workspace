@file:Suppress("TooGenericExceptionCaught")

package com.eareyereading.ui.screens.reader

import androidx.lifecycle.viewModelScope
import com.eareyereading.domain.model.Vocabulary
import com.eareyereading.domain.repository.*
import com.eareyereading.util.*
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * 词汇域：点词查询（清洗/分级/释义兜底/预合成）与加入生词本（去重/入复习队列）。
 */
fun ReaderViewModel.selectWord(word: String) {
    val clean = word.trim().replace(Regex("[^a-zA-Z]"), "")
    if (clean.isBlank()) return

    val level = collinsClassifier.classify(clean)
    // 点词串行化：快速点两个词时取消上一个查询，
    // 否则慢查询会在用户已切到新词后覆盖弹窗内容
    selectWordJob?.cancel()
    selectWordJob = viewModelScope.launch {
        // Room 查询 + ML Kit/网络翻译都可能抛运行时异常：
        // 不拦会直冲 viewModelScope 默认处理器 → 点词崩整个 app
        try {
            // 检查是否已收录
            val existing = vocabularyRepository.getWord(clean)
            // issue 8.1：源语言随书取（不再写死 en→zh），书是法/日/中文时
            // ML Kit 也用对应语言模型做源，避免中文串被当英文翻译致空/乱码
            val sourceLang = _uiState.value.book?.language?.takeIf { it.isNotBlank() } ?: "en"
            // 如果没有释义，用 ML Kit 翻译
            val definition = existing?.definition
                ?: translationHelper.translateWord(clean, sourceLang)
                ?: "未找到释义"
            _uiState.update {
                it.copy(
                    selectedVocab = existing ?: Vocabulary(
                        word = clean,
                        level = level.level,
                        dateAdded = System.currentTimeMillis(),
                    ),
                    wordDefinition = definition,
                    selectedWordLevel = level,
                    showWordDialog = true,
                )
            }
            // 弹窗打开即后台预合成单词 PCM：Kokoro 每次 generate 有 ~2s 固定开销
            // （与文本长度无关），用户看释义的几秒内完成合成，点喇叭时命中缓存
            // 立即出声（2026-09-05 "读一个单词都卡"修复）。tryLock 语义：
            // 正文朗读持锁时自动放弃，绝不阻塞正文播放
            viewModelScope.launch {
                try {
                    ttsHelper.getEmbeddedEngine()
                        .prewarmSynthesis(clean, speed = ttsHelper.getSpeed())
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (_: Exception) {
                    // 预合成失败静默：点喇叭时走正常合成路径兜底
                }
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            android.util.Log.e("ReaderViewModel", "selectWord failed", e)
            showToast("查询失败，请重试")
        }
    }
}

fun ReaderViewModel.addToVocabulary(word: String, context: String?) {
    // 书身份快照：launch 体执行时用户可能已换书（点词弹窗开着按返回再进 B 书），
    // 此时 currentBookId 已是 B 书——不对照快照，A 书生词会记到 B 书的 bookId/title 下
    val myBookId = currentBookId
    viewModelScope.launch {
        val currentVocab = _uiState.value.selectedVocab ?: return@launch
        // issue: 生词入库时把查好的释义一起持久化，否则"词汇本"里每词无翻译
        val wordDef = _uiState.value.wordDefinition
            ?.takeIf { it.isNotBlank() && it != "未找到释义" }
        val vocabToSave = currentVocab.copy(
            bookId = myBookId,
            bookTitle = _uiState.value.book?.takeIf { it.id == myBookId }?.title,
            context = context,
            definition = wordDef ?: currentVocab.definition,
        )

        // 去重查询也纳入 try：它是 Room 调用，原实现留在 try 外，
        // 数据库异常会在"加入生词本"时直接崩 app
        try {
            // 去重与保存用同一个词：此前去重查 word 参数、保存却用 selectedVocab，
            // 点词竞态下两者不一致会反复插入失败且无提示
            val dedupeWord = vocabToSave.word.ifBlank { word }
            val existing = vocabularyRepository.getWord(dedupeWord)
            if (existing != null) {
                // 此前重复词静默关闭弹窗，与成功路径无差别——用户不知道
                // 到底加没加进去；补一条明确提示
                _uiState.update { it.copy(showWordDialog = false, selectedVocab = null) }
                showToast("「$dedupeWord」已在生词本中")
                return@launch
            }

            // 捕获 DB 生成的 id，替换 selectedVocab 使「加入复习」拿到正确 vocabularyId
            val id = vocabularyRepository.addWord(vocabToSave)

            // 写库期间换书：丢弃这次写入的 UI 更新，不把 A 书的弹窗状态安到 B 书
            if (currentBookId != myBookId) return@launch

            // 阅读页加入的生词此前从不进复习队列：due count 永远 0，
            // "点词 → 加生词本 → 等复习"主流程断链（issue 11.3）
            vocabularyRepository.addWordToReview(id, vocabToSave.word)

            _uiState.update {
                it.copy(
                    showWordDialog = false,
                    selectedVocab = vocabToSave.copy(id = id),
                )
            }
            showToast("已加入生词本")
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            android.util.Log.e("ReaderViewModel", "Failed to add word to vocabulary", e)
            showToast("添加生词失败，请重试")
        }
    }
}
