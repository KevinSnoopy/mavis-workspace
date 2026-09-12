package com.eareyereading.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SearchBar
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * 通用 M3 搜索栏（Google 式收起/展开）。
 *
 * ── 重构说明（DRY）──
 * 书库搜索与词汇搜索此前各有一份逐行相同的 SearchBar 外壳：展开态状态管理、
 * placeholder、放大镜前导图标、清空/收起尾随图标、外边距全部一致，只有
 * 提示文案与结果列表不同。现外壳收敛到本组件，业务侧只提供文案与结果内容。
 *
 * 收起时是常驻药丸输入框，点击展开后接管全屏；展开态由 [results] 渲染结果。
 *
 * @param query 当前搜索关键词
 * @param onQueryChange 关键词变更回调
 * @param placeholder 收起态占位文案
 * @param modifier 外部修饰符
 * @param results 展开态结果内容；收到 `collapse` 回调，点击结果时调用它收起搜索
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AppSearchBar(
    query: String,
    onQueryChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    results: @Composable (collapse: () -> Unit) -> Unit,
) {
    var searchActive by rememberSaveable { mutableStateOf(false) }
    val collapse = { searchActive = false }
    SearchBar(
        query = query,
        onQueryChange = onQueryChange,
        onSearch = { searchActive = false },
        active = searchActive,
        onActiveChange = { searchActive = it },
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp),
        placeholder = {
            Text(placeholder, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
            if (query.isNotEmpty()) {
                IconButton(onClick = { onQueryChange("") }) {
                    Icon(Icons.Default.Clear, "清除", modifier = Modifier.size(18.dp))
                }
            } else if (searchActive) {
                IconButton(onClick = collapse) {
                    Icon(Icons.Default.Close, "收起", modifier = Modifier.size(18.dp))
                }
            }
        },
    ) {
        results(collapse)
    }
}

/**
 * 搜索结果为空/未输入时的居中提示。
 *
 * @param query 当前关键词，决定提示文案
 * @param idleHint 未输入关键词时的引导文案
 * @param noun 结果对象称呼，用于"没有匹配「x」的{noun}"
 */
@Composable
internal fun SearchEmptyState(query: String, idleHint: String, noun: String) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 48.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            if (query.isBlank()) idleHint else "没有匹配「$query」的$noun",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** 搜索结果列表外壳：统一列表内边距与 key 约定。 */
@Composable
internal fun <T> SearchResultList(
    results: List<T>,
    key: (T) -> Any,
    itemContent: @Composable (T) -> Unit,
) {
    LazyColumn(contentPadding = PaddingValues(vertical = 8.dp)) {
        items(results, key = key) { itemContent(it) }
    }
}
