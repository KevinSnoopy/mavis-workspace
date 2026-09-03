package com.eareyereading.ui.screens.vocabulary

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.eareyereading.domain.model.Vocabulary
import com.eareyereading.domain.repository.VocabularyRepository
import com.eareyereading.util.TranslationHelper
import com.eareyereading.util.TtsHelper
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

data class VocabularyUiState(
    val allWords: List<Vocabulary> = emptyList(),
    val filteredWords: List<Vocabulary> = emptyList(),
    val selectedTab: Int = 0,  // 0=全部, 1=新词, 2=已学
    val searchQuery: String = "",
    val totalCount: Int = 0,
    val learnedCount: Int = 0,
)

@HiltViewModel
class VocabularyViewModel @Inject constructor(
    private val vocabularyRepository: VocabularyRepository,
    private val translationHelper: TranslationHelper,
    private val ttsHelper: TtsHelper,
) : ViewModel() {

    private val _uiState = MutableStateFlow(VocabularyUiState())
    val uiState: StateFlow<VocabularyUiState> = _uiState.asStateFlow()

    private val searchQuery = MutableStateFlow("")

    // 历史生词的释义补齐只跑一次（补齐后落库，后续不再重复翻译）
    private var backfillStarted = false

    init {
        viewModelScope.launch {
            try {
                combine(
                    vocabularyRepository.getAllVocabulary(),
                    vocabularyRepository.getTotalCount(),
                    vocabularyRepository.getLearnedCount(),
                    searchQuery,
                ) { words, total, learned, query ->
                    val filtered = if (query.isBlank()) words
                    else words.filter {
                        it.word.contains(query, ignoreCase = true) ||
                        it.definition?.contains(query, ignoreCase = true) == true
                    }
                    VocabularyUiState(
                        allWords = filtered,
                        filteredWords = filtered,
                        totalCount = total,
                        learnedCount = learned,
                        searchQuery = query,
                    )
                }.collect { state ->
                    _uiState.update {
                        it.copy(
                            allWords = state.allWords,
                            filteredWords = state.filteredWords,
                            totalCount = state.totalCount,
                            learnedCount = state.learnedCount,
                            searchQuery = state.searchQuery,
                        )
                    }
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                // 数据层异常不再让 viewModelScope 未捕获异常崩 App
                android.util.Log.e("VocabularyViewModel", "vocabulary combine failed", e)
            }
        }

        // 为历史生词补齐释义（入库时没带 definition 的）
        backfillMissingDefinitions()
    }

    fun setTab(index: Int) {
        _uiState.update { it.copy(selectedTab = index) }
    }

    fun onSearch(query: String) {
        searchQuery.value = query
    }

    fun markAsLearned(vocabulary: Vocabulary) {
        viewModelScope.launch {
            try {
                vocabularyRepository.updateWord(
                    vocabulary.copy(
                        isLearned = true,
                        lastReviewTime = System.currentTimeMillis(),
                        reviewCount = vocabulary.reviewCount + 1,
                    )
                )
            } catch (e: Exception) {
                android.util.Log.e("VocabularyViewModel", "Failed to mark as learned", e)
            }
        }
    }

    fun updateNote(vocabulary: Vocabulary, note: String?, example: String?) {
        viewModelScope.launch {
            try {
                vocabularyRepository.updateWord(vocabulary.copy(note = note, example = example))
            } catch (e: Exception) {
                android.util.Log.e("VocabularyViewModel", "Failed to update note", e)
            }
        }
    }

    fun addToReview(vocabulary: Vocabulary) {
        viewModelScope.launch {
            try {
                vocabularyRepository.addWordToReview(vocabulary.id, vocabulary.word)
            } catch (e: Exception) {
                android.util.Log.e("VocabularyViewModel", "Failed to add to review", e)
            }
        }
    }

    fun deleteWord(vocabulary: Vocabulary) {
        viewModelScope.launch {
            try {
                vocabularyRepository.deleteWord(vocabulary)
            } catch (e: Exception) {
                android.util.Log.e("VocabularyViewModel", "Failed to delete word", e)
            }
        }
    }

    /**
     * 播放单词发音。词汇本就是英语单词，TTS 用 en。
     */
    fun speakWord(vocabulary: Vocabulary) {
        val text = vocabulary.word.trim()
        if (text.isBlank()) return
        viewModelScope.launch {
            try {
                try {
                    ttsHelper.initialize("en")
                } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
                    android.util.Log.w("VocabularyViewModel", "TTS init timed out", e)
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    android.util.Log.w("VocabularyViewModel", "TTS init failed", e)
                }
                ttsHelper.speak(text)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                android.util.Log.e("VocabularyViewModel", "speak failed", e)
            }
        }
    }

    /**
     * 为入库时缺少释义的历史生词补齐中译并落库（只跑一次，之后由列表直接显示）。
     * 网络/模型不可用时跳过，不阻塞词汇本使用。
     */
    private fun backfillMissingDefinitions() {
        if (backfillStarted) return
        backfillStarted = true
        viewModelScope.launch {
            try {
                val missing = vocabularyRepository.getAllVocabulary().first()
                    .filter { it.word.isNotBlank() && it.definition.isNullOrBlank() }
                for (w in missing) {
                    try {
                        val def = translationHelper.translateWord(w.word, "en") ?: continue
                        vocabularyRepository.updateWord(w.copy(definition = def))
                    } catch (e: kotlinx.coroutines.CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        // 单条失败跳过，继续处理其它词
                        android.util.Log.w("VocabularyViewModel", "backfill '${w.word}' failed", e)
                    }
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                android.util.Log.e("VocabularyViewModel", "backfill definitions failed", e)
            }
        }
    }
}
