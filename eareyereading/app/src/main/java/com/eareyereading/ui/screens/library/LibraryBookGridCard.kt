package com.eareyereading.ui.screens.library

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.eareyereading.data.repository.CategoryPrefs
import com.eareyereading.domain.model.Book
import com.eareyereading.ui.components.BookCover
import com.eareyereading.ui.components.shimmer
import com.eareyereading.ui.theme.Primary

/** 网格视图每行书卡数：手机屏 3 列封面可读性最佳（微信读书同款密度）。 */
internal const val GRID_COLUMNS = 3

/**
 * 书架网格卡：封面占主体（2:3 比例自适应），标题两行 + 进度条。
 *
 * 与列表卡 [BookCard] 同一动作集（分类/归档/删除）：封面右上角 ⋮ 常驻入口
 * （长按在网格里发现性差，列表卡的 ⋮ 用户已有肌肉记忆）。
 * 无滑动归档（横向滑动手势在网格里会与换行视线冲突），归档走菜单。
 *
 * ── 修复记录（P0）──
 * 此前 [onClick] 只声明未接到卡片上（Card 用了无 onClick 的重载），
 * 导致「切换为网格/瀑布流后整块书卡点不开、无法进入阅读」。
 * 改用 Card 的可点击重载：涟漪与圆角裁剪由 Card 内部处理，
 * 右上角 ⋮ 的 IconButton 自行消费点击，不会误触发进入阅读。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun BookGridCard(
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
    var showDeleteConfirm by remember { mutableStateOf(false) }
    var showCategoryDialog by remember { mutableStateOf(false) }

    Card(
        onClick = onClick,
        modifier = modifier,
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(modifier = Modifier.padding(8.dp)) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(0.7f)
                    .clip(RoundedCornerShape(10.dp)),
            ) {
                BookCover(
                    title = book.title,
                    coverPath = book.coverPath,
                    author = book.author,
                    coverStyle = book.coverStyle,
                    modifier = Modifier.fillMaxWidth().aspectRatio(0.7f),
                )
                // ⋮ 菜单：右上角半透明底，不遮标题区
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = Color.Black.copy(alpha = 0.30f),
                    modifier = Modifier.align(Alignment.TopEnd).padding(4.dp),
                ) {
                    Box {
                        IconButton(onClick = { showMenu = true }, modifier = Modifier.size(24.dp)) {
                            Icon(
                                Icons.Default.MoreVert,
                                "更多",
                                tint = Color.White,
                                modifier = Modifier.size(16.dp),
                            )
                        }
                        BookCardMenu(
                            expanded = showMenu,
                            book = book,
                            onDismiss = { showMenu = false },
                            onCategorizeClick = { showMenu = false; showCategoryDialog = true },
                            onArchiveClick = { onArchive(); showMenu = false },
                            onDeleteClick = { showMenu = false; showDeleteConfirm = true },
                        )
                    }
                }
            }
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = book.title,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                maxLines = 2,
                minLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(modifier = Modifier.height(6.dp))
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
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = "${(book.readProgress * 100).toInt()}%",
                    style = MaterialTheme.typography.labelSmall,
                    color = Primary,
                    fontWeight = FontWeight.Medium,
                )
            }
        }
    }

    BookActionDialogs(
        book = book,
        showCategory = showCategoryDialog,
        showDelete = showDeleteConfirm,
        categories = categories,
        bookCounts = bookCounts,
        categoryMetaByName = categoryMeta,
        onDismissCategory = { showCategoryDialog = false },
        onConfirmCategory = { cat ->
            showCategoryDialog = false
            onCategorize(cat)
        },
        onDismissDelete = { showDeleteConfirm = false },
        onConfirmDelete = {
            showDeleteConfirm = false
            onDelete()
        },
    )
}

/** 网格骨架：与 BookGridCard 同构的封面占位。 */
@Composable
internal fun BookGridSkeleton(modifier: Modifier = Modifier) {
    Card(
        modifier = modifier,
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(modifier = Modifier.padding(8.dp)) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(0.7f)
                    .clip(RoundedCornerShape(10.dp))
                    .shimmer(),
            )
            Spacer(modifier = Modifier.height(6.dp))
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(14.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .shimmer(),
            )
        }
    }
}

/** 网格视图单行：满行三卡，缺位用等重 Spacer 占住避免末行卡片被拉伸。 */
@Composable
internal fun GridBookRow(
    rowBooks: List<Book>,
    content: @Composable (Book, Modifier) -> Unit,
) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        rowBooks.forEach { book -> content(book, Modifier.weight(1f)) }
        repeat(GRID_COLUMNS - rowBooks.size) { Spacer(modifier = Modifier.weight(1f)) }
    }
}
