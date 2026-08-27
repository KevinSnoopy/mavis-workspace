package com.eareyereading.ui.screens.vocabulary

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.eareyereading.data.local.dao.ReviewRecordDao
import com.eareyereading.data.local.entity.ReviewRecordEntity
import com.eareyereading.domain.model.Vocabulary
import com.eareyereading.domain.repository.VocabularyRepository
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
    private val reviewRecordDao: ReviewRecordDao,
) : ViewModel() {

    private val _uiState = MutableStateFlow(VocabularyUiState())
    val uiState: StateFlow<VocabularyUiState> = _uiState.asStateFlow()

    private val searchQuery = MutableStateFlow("")

    init {
        viewModelScope.launch {
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
                _uiState.value = state
            }
        }
    }

    fun setTab(index: Int) {
        _uiState.update { it.copy(selectedTab = index) }
    }

    fun onSearch(query: String) {
        searchQuery.value = query
    }

    fun markAsLearned(vocabulary: Vocabulary) {
        viewModelScope.launch {
            vocabularyRepository.updateWord(
                vocabulary.copy(
                    isLearned = true,
                    lastReviewTime = System.currentTimeMillis(),
                    reviewCount = vocabulary.reviewCount + 1,
                )
            )
        }
    }

    fun updateNote(vocabulary: Vocabulary, note: String?, example: String?) {
        viewModelScope.launch {
            vocabularyRepository.updateWord(vocabulary.copy(note = note, example = example))
        }
    }

    fun addToReview(vocabulary: Vocabulary) {
        viewModelScope.launch {
            // 检查是否已在复习队列
            val existing = reviewRecordDao.getReviewForVocab(vocabulary.id)
            if (existing == null) {
                val now = System.currentTimeMillis()
                reviewRecordDao.insertReview(
                    ReviewRecordEntity(
                        vocabularyId = vocabulary.id,
                        word = vocabulary.word,
                        nextReviewDate = now,
                        lastReviewDate = now,
                    )
                )
            }
        }
    }

    fun deleteWord(vocabulary: Vocabulary) {
        viewModelScope.launch {
            reviewRecordDao.deleteReviewForVocab(vocabulary.id)
            vocabularyRepository.deleteWord(vocabulary)
        }
    }
}
