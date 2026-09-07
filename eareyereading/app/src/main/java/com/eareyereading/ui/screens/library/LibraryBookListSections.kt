package com.eareyereading.ui.screens.library

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.MenuBook
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.eareyereading.data.repository.CategoryPrefs
import com.eareyereading.domain.model.Book
import com.eareyereading.ui.components.EmptyState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * 单分类视图：只展示指定分类的书籍。
 *
 * 分类为空时显示引导空状态；否则渲染书籍卡片列表。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun LibrarySingleCategoryList(
    category: String,
    books: List<Book>,
    categories: List<String>,
    bookCountByName: Map<String, Int>,
    categoryMeta: Map<String, CategoryPrefs.Meta>,
    onBookClick: (Long) -> Unit,
    onDelete: (Long) -> Unit,
    onArchive: (Long) -> Unit,
    onUnarchive: (Long) -> Unit,
    onCategorize: (Long, String) -> Unit,
    snackbarHostState: SnackbarHostState,
    scope: CoroutineScope,
) {
    val catBooks = books.filter { it.category == category }
    if (catBooks.isEmpty()) {
        EmptyState(
            icon = Icons.Outlined.MenuBook,
            title = "「$category」暂无书籍",
            subtitle = "通过书卡右侧菜单把书移到这个分类",
        )
    } else {
        LazyColumn(
            modifier = Modifier.fillMaxWidth(),
        ) {
            items(catBooks, key = { it.id }) { book ->
                BookCard(
                    book = book,
                    onClick = { onBookClick(book.id) },
                    onDelete = { onDelete(book.id) },
                    onArchive = {
                        onArchive(book.id)
                        scope.launchArchiveUndo(snackbarHostState, book, onUnarchive)
                    },
                    onCategorize = { cat -> onCategorize(book.id, cat) },
                    categories = categories,
                    bookCounts = bookCountByName,
                    categoryMeta = categoryMeta,
                    modifier = Modifier.animateItemPlacement(),
                )
            }
        }
    }
}

/**
 * 全部分类分组视图：按分类分组展示书籍（书架式）。
 *
 * 每个非空分类渲染分类头 + 该分类下的书籍卡片列表。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun LibraryGroupedCategoryList(
    books: List<Book>,
    categories: List<String>,
    bookCountByName: Map<String, Int>,
    categoryMeta: Map<String, CategoryPrefs.Meta>,
    onBookClick: (Long) -> Unit,
    onDelete: (Long) -> Unit,
    onArchive: (Long) -> Unit,
    onUnarchive: (Long) -> Unit,
    onCategorize: (Long, String) -> Unit,
    snackbarHostState: SnackbarHostState,
    scope: CoroutineScope,
) {
    LazyColumn(
        modifier = Modifier.fillMaxWidth(),
    ) {
        categories.forEach { cat ->
            val catBooks = books.filter { it.category == cat }
            if (catBooks.isNotEmpty()) {
                item(key = "cat_header_$cat") {
                    CategoryHeader(
                        category = cat,
                        count = catBooks.size,
                    )
                }
                items(catBooks, key = { it.id }) { book ->
                    BookCard(
                        book = book,
                        onClick = { onBookClick(book.id) },
                        onDelete = { onDelete(book.id) },
                        onArchive = {
                            onArchive(book.id)
                            scope.launchArchiveUndo(snackbarHostState, book, onUnarchive)
                        },
                        onCategorize = { c -> onCategorize(book.id, c) },
                        categories = categories,
                        bookCounts = bookCountByName,
                        categoryMeta = categoryMeta,
                        modifier = Modifier.animateItemPlacement(),
                    )
                }
            }
        }
    }
}

/**
 * 归档后弹出可撤销 Snackbar：点击"撤销"回调 [onUnarchive]。
 */
private fun CoroutineScope.launchArchiveUndo(
    snackbarHostState: SnackbarHostState,
    book: Book,
    onUnarchive: (Long) -> Unit,
) = launch {
    val result = snackbarHostState.showSnackbar(
        message = "已归档《${book.title.take(12)}》",
        actionLabel = "撤销",
        duration = SnackbarDuration.Short,
    )
    if (result == SnackbarResult.ActionPerformed) {
        onUnarchive(book.id)
    }
}
