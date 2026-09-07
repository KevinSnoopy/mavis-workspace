package com.eareyereading.ui.screens.vocabulary

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.eareyereading.domain.model.Vocabulary

/**
 * 词汇搜索栏：M3 SearchBar（与书库一致的收起/展开交互）。
 *
 * @param searchQuery 当前搜索关键词
 * @param onQueryChange 关键词变更回调
 * @param filteredWords 已按 searchQuery 过滤的单词列表
 * @param onWordClick 点击搜索结果回调（收起搜索）
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun VocabularySearchBar(
    searchQuery: String,
    onQueryChange: (String) -> Unit,
    filteredWords: List<Vocabulary>,
    onWordClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var searchActive by rememberSaveable { mutableStateOf(false) }
    SearchBar(
        query = searchQuery,
        onQueryChange = onQueryChange,
        onSearch = { searchActive = false },
        active = searchActive,
        onActiveChange = { searchActive = it },
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp),
        placeholder = {
            Text("搜索单词...", color = MaterialTheme.colorScheme.onSurfaceVariant)
        },
        leadingIcon = {
            Icon(
                Icons.Default.Search,
                null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp),
            )
        },
        trailingIcon = {
            if (searchQuery.isNotEmpty()) {
                IconButton(onClick = { onQueryChange("") }) {
                    Icon(Icons.Default.Clear, "清除", modifier = Modifier.size(18.dp))
                }
            } else if (searchActive) {
                IconButton(onClick = { searchActive = false }) {
                    Icon(Icons.Default.Close, "收起", modifier = Modifier.size(18.dp))
                }
            }
        },
    ) {
        VocabularySearchResults(
            searchQuery = searchQuery,
            filteredWords = filteredWords,
            onWordClick = {
                searchActive = false
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
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 48.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                if (searchQuery.isBlank()) "输入关键词搜索单词"
                else "没有匹配「$searchQuery」的单词",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    } else {
        LazyColumn(contentPadding = PaddingValues(vertical = 8.dp)) {
            items(filteredWords, key = { it.id }) { word ->
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
}
