package com.eareyereading.ui.components.category

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import com.eareyereading.ui.theme.EareyeShapes
import com.eareyereading.ui.theme.OnSurface
import com.eareyereading.ui.theme.OnSurfaceVariant
import com.eareyereading.ui.theme.Primary

/** 管理列表固定行高：拖拽重排的位移换算基准 */
private val ManageRowHeight = 60.dp

/**
 * 分类管理弹窗（SPEC §4.9.2）
 *
 * 列出所有分类，每行：拖动手柄 / 图标 / 名称+计数 / 编辑+删除按钮。
 * 顶部「新建」次按钮。
 *
 * 拖动排序（v2）：
 * - 长按手柄进入拖拽，固定行高 + 位移换算，越过整行即交换两项
 * - 拖动项 zIndex 提升 + 阴影浮起；松手回调 [onReorder] 持久化 order
 * - 实现为经典 offset 换算方案（Compose 1.5 尚无 LazyColumn 内建重排）
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CategoryManageSheet(
    categories: List<Category>,
    onEdit: (Category) -> Unit,
    onAdd: () -> Unit,
    onDelete: (Category) -> Unit,
    onReorder: (List<Category>) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    // 删除确认：null = 未在确认流程
    var pendingDelete by remember { mutableStateOf<Category?>(null) }

    // 本地顺序：外部列表变化（保存/删除后刷新）时重置
    var order by remember(categories) { mutableStateOf(categories) }
    var draggingIndex by remember { mutableStateOf<Int?>(null) }
    var dragOffsetY by remember { mutableFloatStateOf(0f) }
    val rowHeightPx = with(LocalDensity.current) { ManageRowHeight.toPx() }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        shape = EareyeShapes.xxl,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 8.dp),
        ) {
            // 头部：返回 + 标题 + 新建按钮
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = "分类管理",
                    style = MaterialTheme.typography.titleLarge,
                    color = OnSurface,
                    fontWeight = FontWeight.SemiBold,
                )
                TextButton(onClick = onAdd) {
                    Text("新建", color = Primary)
                }
            }
            Text(
                text = "长按手柄拖动排序；点击编辑修改图标和颜色；删除分类不影响书籍本身。",
                style = MaterialTheme.typography.bodySmall,
                color = OnSurfaceVariant,
                modifier = Modifier.padding(vertical = 8.dp),
            )
            // 分类列表：长按手柄拖动重排；固定行高让位换算
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(bottom = 16.dp),
            ) {
                order.forEachIndexed { index, cat ->
                    val isDragging = draggingIndex == index
                    CategoryManageRow(
                        category = cat,
                        isDragging = isDragging,
                        dragOffsetY = if (isDragging) dragOffsetY else 0f,
                        onDragStart = { draggingIndex = index },
                        onDrag = { dy ->
                            dragOffsetY += dy
                            // 越过整行即交换：round(offset / rowHeight) = 目标位移行数
                            val target = (index + Math.round(dragOffsetY / rowHeightPx))
                                .toInt()
                                .coerceIn(0, order.lastIndex)
                            if (target != index) {
                                order = order.toMutableList().apply { add(target, removeAt(index)) }
                                draggingIndex = target
                                dragOffsetY -= (target - index) * rowHeightPx
                            }
                        },
                        onDragEnd = {
                            draggingIndex = null
                            dragOffsetY = 0f
                            if (order != categories) onReorder(order)
                        },
                        onEdit = { onEdit(cat) },
                        onDelete = { pendingDelete = cat },
                    )
                }
            }
        }
    }

    // 删除确认弹窗
    pendingDelete?.let { cat ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("删除分类「${cat.name}」？") },
            text = {
                Text(
                    if (cat.bookCount > 0) {
                        "该分类下有 ${cat.bookCount} 本书。删除后书籍保留，但会失去此分类的图标与颜色标识。"
                    } else {
                        "该分类暂无书籍，删除后仅移除其图标与颜色标识。"
                    }
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        onDelete(cat)
                        pendingDelete = null
                    },
                ) {
                    Text("删除", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) {
                    Text("取消")
                }
            },
        )
    }
}

@Composable
private fun CategoryManageRow(
    category: Category,
    isDragging: Boolean,
    dragOffsetY: Float,
    onDragStart: () -> Unit,
    onDrag: (Float) -> Unit,
    onDragEnd: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(ManageRowHeight)
            .zIndex(if (isDragging) 1f else 0f)
            .graphicsLayer {
                translationY = dragOffsetY
                shadowElevation = if (isDragging) 12f else 0f
                alpha = if (isDragging) 0.95f else 1f
            }
            .clickable(onClick = onEdit),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // 拖动手柄：长按进入拖拽
        Box(
            modifier = Modifier
                .size(24.dp)
                .pointerInput(Unit) {
                    detectDragGesturesAfterLongPress(
                        onDragStart = { onDragStart() },
                        onDrag = { change, dragAmount ->
                            change.consume()
                            onDrag(dragAmount.y)
                        },
                        onDragEnd = { onDragEnd() },
                        onDragCancel = { onDragEnd() },
                    )
                },
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Default.DragHandle,
                contentDescription = "长按拖动排序",
                modifier = Modifier.size(20.dp),
                tint = if (isDragging) Primary else OnSurfaceVariant.copy(alpha = 0.5f),
            )
        }
        Box(
            modifier = Modifier
                .size(36.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(category.color),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = category.icon.imageVector,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
                tint = Color.White,
            )
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = category.name,
                color = OnSurface,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
            )
            Text(
                text = "${category.bookCount} 本",
                color = OnSurfaceVariant,
                fontSize = 12.sp,
            )
        }
        IconButton(onClick = onEdit) {
            Icon(
                imageVector = Icons.Default.Edit,
                contentDescription = "编辑",
                modifier = Modifier.size(18.dp),
                tint = OnSurfaceVariant,
            )
        }
        IconButton(onClick = onDelete) {
            Icon(
                imageVector = Icons.Default.Delete,
                contentDescription = "删除分类",
                modifier = Modifier.size(18.dp),
                tint = OnSurfaceVariant.copy(alpha = 0.7f),
            )
        }
    }
}
