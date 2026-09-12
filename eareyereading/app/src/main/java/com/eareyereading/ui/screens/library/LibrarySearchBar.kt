package com.eareyereading.ui.screens.library

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.size
import androidx.compose.material3.ListItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.eareyereading.domain.model.Book
import com.eareyereading.ui.components.AppSearchBar
import com.eareyereading.ui.components.BookCover
import com.eareyereading.ui.components.SearchEmptyState
import com.eareyereading.ui.components.SearchResultList

/**
 * 书库搜索栏：M3 SearchBar（Google 式收起/展开）。
 *
 * 收起时是常驻药丸输入框，点击展开后接管全屏，content 内渲染实时过滤结果。
 * 展开态点击搜索结果会收起搜索并回调 [onBookClick]。
 *
 * 搜索栏外壳（展开态管理、前后置图标、外边距）由 [AppSearchBar] 统一提供，
 * 本文件只保留书库特有的结果渲染。
 *
 * @param searchQuery 当前搜索关键词
 * @param onQueryChange 关键词变更回调
 * @param books 已按 searchQuery 过滤的书籍列表（展开态渲染用）
 * @param onBookClick 点击搜索结果回调（收起搜索 + 进书）
 */
@Composable
internal fun LibrarySearchBar(
    searchQuery: String,
    onQueryChange: (String) -> Unit,
    books: List<Book>,
    onBookClick: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    AppSearchBar(
        query = searchQuery,
        onQueryChange = onQueryChange,
        placeholder = "搜索书籍 / 作者...",
        modifier = modifier,
    ) { collapse ->
        LibrarySearchResults(
            searchQuery = searchQuery,
            books = books,
            onBookClick = { bookId ->
                collapse()
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
        SearchEmptyState(
            query = searchQuery,
            idleHint = "输入关键词搜索书架",
            noun = "书籍",
        )
    } else {
        SearchResultList(results = books, key = { it.id }) { book ->
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
