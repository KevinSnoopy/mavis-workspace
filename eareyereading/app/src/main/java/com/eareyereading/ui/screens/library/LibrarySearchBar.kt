package com.eareyereading.ui.screens.library

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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.eareyereading.domain.model.Book
import com.eareyereading.ui.components.BookCover

/**
 * 书库搜索栏：M3 SearchBar（Google 式收起/展开）。
 *
 * 收起时是常驻药丸输入框，点击展开后接管全屏，content 内渲染实时过滤结果。
 * 展开态点击搜索结果会收起搜索并回调 [onBookClick]。
 *
 * @param searchQuery 当前搜索关键词
 * @param onQueryChange 关键词变更回调
 * @param books 已按 searchQuery 过滤的书籍列表（展开态渲染用）
 * @param onBookClick 点击搜索结果回调（收起搜索 + 进书）
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun LibrarySearchBar(
    searchQuery: String,
    onQueryChange: (String) -> Unit,
    books: List<Book>,
    onBookClick: (Long) -> Unit,
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
            Text("搜索书籍 / 作者...", color = MaterialTheme.colorScheme.onSurfaceVariant)
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
        LibrarySearchResults(
            searchQuery = searchQuery,
            books = books,
            onBookClick = { bookId ->
                searchActive = false
                onBookClick(bookId)
            },
        )
    }
}

/**
 * 搜索展开态结果列表。
 */
@Composable
private fun LibrarySearchResults(
    searchQuery: String,
    books: List<Book>,
    onBookClick: (Long) -> Unit,
) {
    if (books.isEmpty()) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 48.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                if (searchQuery.isBlank()) "输入关键词搜索书架"
                else "没有匹配「$searchQuery」的书籍",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    } else {
        LazyColumn(
            contentPadding = PaddingValues(vertical = 8.dp),
        ) {
            items(books, key = { it.id }) { book ->
                ListItem(
                    headlineContent = {
                        Text(book.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    },
                    supportingContent = {
                        Text(book.author, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    },
                    leadingContent = {
                        BookCover(
                            title = book.title,
                            coverPath = book.coverPath,
                            author = book.author,
                            coverStyle = book.coverStyle,
                            modifier = Modifier.size(38.dp, 52.dp),
                            cornerRadius = 6.dp,
                        )
                    },
                    modifier = Modifier.clickable { onBookClick(book.id) },
                )
            }
        }
    }
}
