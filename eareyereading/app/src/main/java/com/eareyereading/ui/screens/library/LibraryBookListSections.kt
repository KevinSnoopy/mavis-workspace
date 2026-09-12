package com.eareyereading.ui.screens.library

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.MenuBook
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.ui.Modifier
import com.eareyereading.data.repository.CategoryPrefs
import com.eareyereading.data.repository.LibraryViewMode
import com.eareyereading.domain.model.Book
import com.eareyereading.ui.components.EmptyState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * 书籍列表/网格分组片段（LazyListScope 扩展）。
 *
 * ── 修复记录（P0 崩溃）──
 * 此前两个函数是 `@Composable` 且内部各自 new 一个 `LazyColumn`，被
 * [LibraryBookTabContent] 放进外层 `LazyColumn` 的 `item { }` 里。垂直
 * 可滚动组件在外层 LazyColumn 的 item 中会被以 **无限最大高度** 测量，
 * Compose 直接抛：
 *
 * ```
 * IllegalStateException: Vertically scrollable component was measured
 * with an infinity maximum height constraints, which is disallowed.
 * ```
 *
 * 即"点击书库 Tab 必崩"。改为 **LazyListScope 扩展**：内容直接发射进外层
 * LazyColumn 的 item/items 槽位，layer 只剩一层，同时恢复列表虚拟化
 * （此前内层整表一次性组合，书多时首帧明显）。
 *
 * ── 排布视图 ──
 * LIST = 行式卡片逐本铺开；GRID = 封面网格（3 列），按行分块发射
 * （每行一个 item 内放 Row），同样保持单层 LazyColumn 虚拟化——
 * 网格行数 ≈ 书数/3，一屏可见书目约为列表的 3 倍，长列表问题随之消解。
 */

/** 书籍未分类时的展示名（与 [LibraryDataLoader] 的分组口径保持一致）。 */
internal const val UNCATEGORIZED = "未分类"

/**
 * 单分类视图：只展示指定分类的书籍。
 *
 * 分类为空时显示引导空状态；否则按 [viewMode] 发射书卡列表或网格行。
 */
@OptIn(ExperimentalFoundationApi::class)
internal fun LazyListScope.librarySingleCategoryItems(
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
    viewMode: LibraryViewMode = LibraryViewMode.LIST,
) {
    // 分类口径与 LibraryDataLoader 的分组一致：空分类按"未分类"归组，
    // 否则 category 为空的存量书在该分类下会一条都筛不出来
    val catBooks = books.filter { it.category.ifBlank { UNCATEGORIZED } == category }
    if (catBooks.isEmpty()) {
        item(key = "single_empty") {
            EmptyState(
                icon = Icons.Outlined.MenuBook,
                title = "「$category」暂无书籍",
                subtitle = "通过书卡右侧菜单把书移到这个分类",
            )
        }
        return
    }
    when (viewMode) {
        LibraryViewMode.LIST -> items(catBooks, key = { it.id }) { book ->
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
        LibraryViewMode.GRID -> gridBookRows(
            books = catBooks,
            keyPrefix = "single",
            categories = categories,
            bookCountByName = bookCountByName,
            categoryMeta = categoryMeta,
            onBookClick = onBookClick,
            onDelete = onDelete,
            onArchive = onArchive,
            onCategorize = onCategorize,
        )
    }
}

/**
 * 全部分类分组视图：按分类分组直接发射"分类头 + 该分类书卡（列表或网格）"。
 *
 * 每个非空分类发射一个分类头 item + 若干书卡 item（不再嵌套可滚动容器）。
 */
@OptIn(ExperimentalFoundationApi::class)
internal fun LazyListScope.libraryGroupedCategoryItems(
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
    viewMode: LibraryViewMode = LibraryViewMode.LIST,
) {
    var emitted = false
    categories.forEach { cat ->
        // 同 librarySingleCategoryItems：空分类按"未分类"归组再比对
        val catBooks = books.filter { it.category.ifBlank { UNCATEGORIZED } == cat }
        if (catBooks.isNotEmpty()) {
            emitted = true
            item(key = "cat_header_$cat") {
                CategoryHeader(
                    category = cat,
                    count = catBooks.size,
                )
            }
            when (viewMode) {
                LibraryViewMode.LIST -> items(catBooks, key = { it.id }) { book ->
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
                LibraryViewMode.GRID -> gridBookRows(
                    books = catBooks,
                    keyPrefix = "grouped_$cat",
                    categories = categories,
                    bookCountByName = bookCountByName,
                    categoryMeta = categoryMeta,
                    onBookClick = onBookClick,
                    onDelete = onDelete,
                    onArchive = onArchive,
                    onCategorize = onCategorize,
                )
            }
        }
    }
    // 兜底：一本书都没发射（分类与实际 category 全不匹配的异常数据）时
    // 不能留白屏，给一条可读的空态
    if (!emitted) {
        item(key = "grouped_empty") {
            EmptyState(
                icon = Icons.Outlined.MenuBook,
                title = "书架为空",
                subtitle = "导入 EPUB/TXT，或从下方一键下载英文经典名著",
            )
        }
    }
}

/**
 * 网格发射：书列表按 [GRID_COLUMNS] 分块，每行一个 item（key 带行号 +
 * 前缀，分组视图里前缀含分类名防跨组撞 key）。网格卡无滑动归档，
 * 归档动作不经撤销 Snackbar（菜单触发，误触概率低）。
 */
private fun LazyListScope.gridBookRows(
    books: List<Book>,
    keyPrefix: String,
    categories: List<String>,
    bookCountByName: Map<String, Int>,
    categoryMeta: Map<String, CategoryPrefs.Meta>,
    onBookClick: (Long) -> Unit,
    onDelete: (Long) -> Unit,
    onArchive: (Long) -> Unit,
    onCategorize: (Long, String) -> Unit,
) {
    books.chunked(GRID_COLUMNS).forEachIndexed { rowIdx, rowBooks ->
        item(key = "${keyPrefix}_grid_row_$rowIdx") {
            GridBookRow(rowBooks) { book, modifier ->
                BookGridCard(
                    book = book,
                    onClick = { onBookClick(book.id) },
                    onDelete = { onDelete(book.id) },
                    onArchive = { onArchive(book.id) },
                    onCategorize = { cat -> onCategorize(book.id, cat) },
                    categories = categories,
                    bookCounts = bookCountByName,
                    categoryMeta = categoryMeta,
                    modifier = modifier,
                )
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
