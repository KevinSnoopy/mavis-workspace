package com.eareyereading.ui.screens.vocabulary

import androidx.compose.foundation.clickable
import androidx.compose.material3.ListItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.eareyereading.domain.model.Vocabulary
import com.eareyereading.ui.components.AppSearchBar
import com.eareyereading.ui.components.SearchEmptyState
import com.eareyereading.ui.components.SearchResultList

/**
 * 词汇搜索栏：M3 SearchBar（与书库一致的收起/展开交互）。
 *
 * 搜索栏外壳（展开态管理、前后置图标、外边距）由 [AppSearchBar] 统一提供，
 * 本文件只保留词汇本特有的结果渲染。
 *
 * @param searchQuery 当前搜索关键词
 * @param onQueryChange 关键词变更回调
 * @param filteredWords 已按 searchQuery 过滤的单词列表
 * @param onWordClick 点击搜索结果回调（收起搜索）
 */
@Composable
internal fun VocabularySearchBar(
    searchQuery: String,
    onQueryChange: (String) -> Unit,
    filteredWords: List<Vocabulary>,
    onWordClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    AppSearchBar(
        query = searchQuery,
        onQueryChange = onQueryChange,
        placeholder = "搜索单词...",
        modifier = modifier,
    ) { collapse ->
        VocabularySearchResults(
            searchQuery = searchQuery,
            filteredWords = filteredWords,
            onWordClick = {
                collapse()
                onWordClick()
            },
        )
    }
}

/**
 * 搜索展开态结果列表。
 */
@Composable
private fun VocabularySearchResults(
    searchQuery: String,
    filteredWords: List<Vocabulary>,
    onWordClick: () -> Unit,
) {
    if (filteredWords.isEmpty()) {
        SearchEmptyState(
            query = searchQuery,
            idleHint = "输入关键词搜索单词",
            noun = "单词",
        )
    } else {
        SearchResultList(results = filteredWords, key = { it.id }) { word ->
            ListItem(
                headlineContent = { Text(word.word) },
                supportingContent = {
                    Text(word.definition ?: "", maxLines = 1)
                },
                modifier = Modifier.clickable { onWordClick() },
            )
        }
    }
}
