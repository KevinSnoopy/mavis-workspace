package com.eareyereading.ui.screens.library

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.Category
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.eareyereading.data.repository.CategoryPrefs
import com.eareyereading.domain.model.Book
import com.eareyereading.ui.components.BookCover
import com.eareyereading.ui.theme.Error
import com.eareyereading.ui.theme.Primary
import com.eareyereading.ui.theme.Success

/**
 * 书架卡片：书籍卡片（长按分类/删除菜单）、滑动归档。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BookCard(
    book: Book,
    onClick: () -> Unit,
    onDelete: () -> Unit,
    onArchive: () -> Unit,
    onCategorize: (String) -> Unit = {},
    categories: List<String> = emptyList(),
    bookCounts: Map<String, Int> = emptyMap(),
    categoryMeta: Map<String, CategoryPrefs.Meta> = emptyMap(),
    modifier: Modifier = Modifier,
) {
    var showMenu by remember { mutableStateOf(false) }
    // 删除是永久操作（级联清书签/高亮/进度/统计）：二次确认防误触
    var showDeleteConfirm by remember { mutableStateOf(false) }
    // 分类编辑弹窗（书架分类入口）
    var showCategoryDialog by remember { mutableStateOf(false) }

    // 滑动归档（Gmail 式）：左右滑均可归档，动作走可撤销入口（调用方配 Snackbar 撤销）。
    // confirmValueChange 返回 false 让卡片弹回原位——真正的移除由数据流刷新驱动
    val dismissState = rememberDismissState(
        confirmValueChange = { value ->
            if (value != DismissValue.Default) {
                onArchive()
            }
            false
        },
    )

    SwipeToDismiss(
        state = dismissState,
        background = {
            // 滑动背景：品牌绿 + 归档图标（两个方向共用同一动作）
            val direction = dismissState.dismissDirection
            val alignment = if (direction == DismissDirection.StartToEnd) {
                Alignment.CenterStart
            } else {
                Alignment.CenterEnd
            }
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clip(RoundedCornerShape(16.dp))
                    .background(Success.copy(alpha = 0.18f))
                    .padding(horizontal = 24.dp),
                contentAlignment = alignment,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Default.Archive,
                        contentDescription = "归档",
                        tint = Success,
                        modifier = Modifier.size(20.dp),
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("归档", color = Success, style = MaterialTheme.typography.labelLarge)
                }
            }
        },
        dismissContent = {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onClick),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),

        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // 封面：v2 预设封面 > EPUB 内嵌封面 > 书名哈希插图封面
                BookCover(
                    title = book.title,
                    coverPath = book.coverPath,
                    author = book.author,
                    coverStyle = book.coverStyle,
                    modifier = Modifier.size(58.dp, 80.dp),
                )

            Spacer(modifier = Modifier.width(14.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = book.title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = book.author,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(modifier = Modifier.height(8.dp))

                // 进度条
                Row(verticalAlignment = Alignment.CenterVertically) {
                    LinearProgressIndicator(
                        progress = book.readProgress,
                        modifier = Modifier
                            .weight(1f)
                            .height(4.dp)
                            .clip(RoundedCornerShape(2.dp)),
                        color = Primary,
                        trackColor = MaterialTheme.colorScheme.surfaceVariant,
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "${(book.readProgress * 100).toInt()}%",
                        style = MaterialTheme.typography.labelSmall,
                        color = Primary,
                        fontWeight = FontWeight.Medium,
                    )
                }

                // 分类标签 + 字数（一行收起，节省纵向空间）
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.padding(top = 4.dp),
                ) {
                    Surface(
                        shape = RoundedCornerShape(5.dp),
                        color = Primary.copy(alpha = 0.10f),
                    ) {
                        Text(
                            text = book.category.ifBlank { "未分类" },
                            style = MaterialTheme.typography.labelSmall,
                            color = Primary,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                        )
                    }
                    if (book.totalWords > 0) {
                        Text(
                            text = "${book.totalWords} 词",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                // issue 9.2：EPUB 因 MAX_TOTAL_CHARS 被截断时，卡片给明确提示，不再静默丢正文
                if (book.isTruncated) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = if (book.originalCharCount > 0) {
                            "正文超上限，已截断（保留约 ${book.originalCharCount / 1000}k 字）"
                        } else {
                            "正文超上限，已截断"
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = Error,
                    )
                }
            }

            Box {
                IconButton(onClick = { showMenu = true }) {
                    Icon(
                        Icons.Default.MoreVert,
                        "更多",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                DropdownMenu(
                    expanded = showMenu,
                    onDismissRequest = { showMenu = false },
                ) {
                    DropdownMenuItem(
                        text = { Text("移至分类（当前：${book.category.ifBlank { "未分类" }}）") },
                        onClick = { showMenu = false; showCategoryDialog = true },
                        leadingIcon = { Icon(Icons.Default.Category, null) },
                    )
                    DropdownMenuItem(
                        text = { Text("移至归档") },
                        onClick = { onArchive(); showMenu = false },
                        leadingIcon = { Icon(Icons.Default.Archive, null) },
                    )
                    DropdownMenuItem(
                        text = { Text("删除") },
                        onClick = { showMenu = false; showDeleteConfirm = true },
                        leadingIcon = { Icon(Icons.Default.Delete, null, tint = MaterialTheme.colorScheme.error) },
                    )
                }
            }
        }
        }
    }
    ) // 关闭 SwipeToDismiss

    if (showCategoryDialog) {
        CategoryEditDialog(
            current = book.category.ifBlank { "未分类" },
            categories = categories,
            bookCountByName = bookCounts,
            categoryMetaByName = categoryMeta,
            onDismiss = { showCategoryDialog = false },
            onConfirm = { cat ->
                showCategoryDialog = false
                onCategorize(cat)
            },
        )
    }

    if (showDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = { Text("删除《${book.title}》？") },
            text = { Text("将同时删除该书的书签、高亮、阅读进度和统计，且无法恢复。") },
            confirmButton = {
                TextButton(onClick = { showDeleteConfirm = false; onDelete() }) {
                    Text("删除", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirm = false }) { Text("取消") }
            },
        )
    }
}
