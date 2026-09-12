package com.eareyereading.ui.screens.library

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.LocalFireDepartment
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material.icons.outlined.MenuBook
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.eareyereading.domain.model.Book
import com.eareyereading.ui.components.EmptyState
import com.eareyereading.ui.components.StatCard
import com.eareyereading.ui.theme.Primary
import com.eareyereading.ui.theme.SectionTitle
import com.eareyereading.ui.theme.Warning

/**
 * 书籍 Tab 内容：统计面板、分类筛选 chips、书籍列表（骨架屏/空状态/单分类/全部分组）、
 * 英文经典名著横滑卡片。
 *
 * @param uiState 书库 UI 状态
 * @param viewModel 书库 ViewModel
 * @param onBookClick 点击书籍回调
 * @param snackbarHostState Snackbar 宿主（归档撤销用）
 * @param onPickFile 触发文件选择器
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun LibraryBookTabContent(
    uiState: LibraryUiState,
    viewModel: LibraryViewModel,
    onBookClick: (Long) -> Unit,
    snackbarHostState: SnackbarHostState,
    onPickFile: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val stats = uiState.readingStats
    // 有效分类：选中分类被清空（最后一本书改走）时自动回"全部"
    val effectiveCategory = uiState.selectedCategory?.takeIf { it in uiState.categories }
    val bookCountByName = rememberBookCountByName(uiState.books)

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // 今日阅读统计面板（紧凑）
        item(key = "stats") {
            LibraryStatsRow(stats = stats)
        }

        // ── 书架分类筛选（横滑 chips）──
        if (uiState.books.isNotEmpty()) {
            item(key = "category_chips") {
                LibraryCategoryChips(
                    books = uiState.books,
                    categories = uiState.categories,
                    effectiveCategory = effectiveCategory,
                    onSetCategory = viewModel::setCategory,
                )
            }
        }

        if (uiState.isLoading) {
            // 骨架屏：与 BookCard 同构的占位 + 微光扫过，
            // 替代居中转圈（感知加载速度更快）
            items(3, key = { "skeleton_$it" }) { BookCardSkeleton() }
            if (uiState.loadingMessage.isNotBlank()) {
                item {
                    Text(
                        uiState.loadingMessage,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 8.dp),
                    )
                }
            }
        } else if (uiState.books.isEmpty()) {
            item(key = "empty") {
                LibraryEmptyState(onPickFile = onPickFile, onShowUrlDialog = viewModel::showUrlDialog)
            }
        } else if (effectiveCategory != null) {
            // ── 单分类视图 ──
            item(key = "single_category") {
                LibrarySingleCategoryList(
                    category = effectiveCategory,
                    books = uiState.books,
                    categories = uiState.categories,
                    bookCountByName = bookCountByName,
                    categoryMeta = uiState.categoryMeta,
                    onBookClick = onBookClick,
                    onDelete = viewModel::deleteBook,
                    onArchive = viewModel::archiveBook,
                    onUnarchive = viewModel::unarchiveBook,
                    onCategorize = viewModel::updateBookCategory,
                    snackbarHostState = snackbarHostState,
                    scope = scope,
                )
            }
        } else {
            // ── 全部分类：分组展示（书架式）──
            item(key = "grouped_categories") {
                LibraryGroupedCategoryList(
                    books = uiState.books,
                    categories = uiState.categories,
                    bookCountByName = bookCountByName,
                    categoryMeta = uiState.categoryMeta,
                    onBookClick = onBookClick,
                    onDelete = viewModel::deleteBook,
                    onArchive = viewModel::archiveBook,
                    onUnarchive = viewModel::unarchiveBook,
                    onCategorize = viewModel::updateBookCategory,
                    snackbarHostState = snackbarHostState,
                    scope = scope,
                )
            }
        }

        // ── 英文经典名著：横滑卡片（一屏内收起，不再整列铺开）──
        item(key = "classics_header") {
            LibraryClassicsHeader()
        }
        item(key = "classics_row") {
            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                items(uiState.classics, key = { it.id }) { classic ->
                    ClassicBookCard(
                        classic = classic,
                        downloading = classic.id in uiState.downloadingClassicIds,
                        owned = classic.id in uiState.ownedClassicIds,
                        onDownload = { viewModel.downloadClassic(classic) },
                    )
                }
            }
        }

        // 改版A：FAB 遮挡修复——列表末尾留 96dp（FAB 56dp + 安全距离），
        // 最后一项的进度百分比不再被悬浮按钮盖住
        item(key = "bottom_space") { Spacer(modifier = Modifier.height(96.dp)) }
    }
}

// ── 统计面板 ──────────────────────────────────────────

@Composable
private fun LibraryStatsRow(stats: ReadingStatsSummary) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        StatCard(
            icon = Icons.Default.Timer,
            value = stats.todayMinutes,
            unit = "min",
            label = "今日阅读",
            color = Primary,
            modifier = Modifier.weight(1f),
        )
        // streak 卡底色升为 primary-container：色彩承担语义
        //（绿=活跃），是用户情感焦点（多邻国火焰心理学）
        StatCard(
            icon = Icons.Default.LocalFireDepartment,
            value = stats.streakDays,
            unit = "天",
            label = "连续打卡",
            color = Warning,
            pulse = stats.streakDays > 0,
            highlight = true,
            modifier = Modifier.weight(1f),
        )
        StatCard(
            icon = Icons.Outlined.MenuBook,
            value = stats.totalBooks,
            label = "累计书籍",
            color = Primary,
            modifier = Modifier.weight(1f),
        )
    }
}

// ── 分类筛选 chips ────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LibraryCategoryChips(
    books: List<Book>,
    categories: List<String>,
    effectiveCategory: String?,
    onSetCategory: (String?) -> Unit,
) {
    LazyRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        contentPadding = PaddingValues(vertical = 2.dp),
    ) {
        item(key = "cat_all") {
            FilterChip(
                selected = effectiveCategory == null,
                onClick = { onSetCategory(null) },
                label = { Text("全部 ${books.size}") },
            )
        }
        items(
            categories,
            key = { "cat_$it" },
        ) { cat ->
            FilterChip(
                selected = effectiveCategory == cat,
                onClick = {
                    onSetCategory(
                        if (effectiveCategory == cat) null else cat,
                    )
                },
                label = {
                    Text("$cat ${books.count { it.category == cat }}")
                },
            )
        }
    }
}

// ── 空状态 ────────────────────────────────────────────

@Composable
private fun LibraryEmptyState(
    onPickFile: () -> Unit,
    onShowUrlDialog: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(vertical = 20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        EmptyState(
            icon = Icons.Outlined.MenuBook,
            title = "书架为空",
            subtitle = "导入 EPUB/TXT，或从下方一键下载英文经典名著",
        )
        Spacer(modifier = Modifier.height(16.dp))
        Button(
            onClick = onPickFile,
            colors = ButtonDefaults.buttonColors(containerColor = Primary),
            shape = RoundedCornerShape(12.dp),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
        ) {
            Icon(Icons.Default.Add, null, modifier = Modifier.size(18.dp))
            Spacer(modifier = Modifier.width(8.dp))
            Text("导入书籍", fontWeight = FontWeight.SemiBold)
        }
        Spacer(modifier = Modifier.height(6.dp))
        TextButton(onClick = onShowUrlDialog) {
            Icon(Icons.Default.Link, null, modifier = Modifier.size(16.dp))
            Spacer(modifier = Modifier.width(4.dp))
            Text("从网址导入")
        }
    }
}

// ── 经典名著区头部 ────────────────────────────────────

@Composable
private fun LibraryClassicsHeader() {
    Spacer(modifier = Modifier.height(4.dp))
    Text("英文经典名著 · Project Gutenberg", style = SectionTitle)
    Spacer(modifier = Modifier.height(2.dp))
    Text(
        "免费公版英文长篇小说，下载后可离线阅读",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(modifier = Modifier.height(8.dp))
}
