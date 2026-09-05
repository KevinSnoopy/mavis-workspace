package com.eareyereading.ui.components.category

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.eareyereading.ui.theme.CategoryPalette
import com.eareyereading.ui.theme.EareyeShapes
import com.eareyereading.ui.theme.OnSurface
import com.eareyereading.ui.theme.OnSurfaceVariant
import com.eareyereading.ui.theme.Primary
import com.eareyereading.ui.theme.Surface

/**
 * 新建 / 编辑分类弹窗（SPEC §4.9.3）
 *
 * 三字段表单：名称 + 12 枚预设图标 + 10 个语义色 + 实时预览。
 *
 * 设计判断：
 * - icon-picker 6 列网格（12 图标正好两行）
 * - color-picker 5 列网格（10 色正好两行）
 * - 预览胶囊实时反映当前选择，用户能立即看到效果
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CategoryEditSheet(
    initial: Category? = null,
    onSave: (Category) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var name by remember { mutableStateOf(initial?.name ?: "") }
    var icon by remember { mutableStateOf(initial?.icon ?: CategoryIcon.BOOK) }
    var color by remember { mutableStateOf(initial?.color ?: Primary) }

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
            Text(
                text = if (initial == null) "新建分类" else "编辑分类",
                style = MaterialTheme.typography.titleLarge,
                color = OnSurface,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(bottom = 16.dp),
            )

            // 字段 1：名称
            Text(
                text = "分类名称",
                color = OnSurfaceVariant,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(bottom = 8.dp),
            )
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                placeholder = { Text("如：英语学习", color = OnSurfaceVariant.copy(alpha = 0.5f)) },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 20.dp),
                singleLine = true,
            )

            // 字段 2：图标（6 列网格，12 枚图标）
            Text(
                text = "图标",
                color = OnSurfaceVariant,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(bottom = 8.dp),
            )
            LazyVerticalGrid(
                columns = GridCells.Fixed(6),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 20.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                userScrollEnabled = false,
            ) {
                items(CategoryIcon.values().toList()) { ic ->
                    IconPickerItem(
                        icon = ic,
                        selected = ic == icon,
                        onClick = { icon = ic },
                    )
                }
            }

            // 字段 3：颜色（5 列网格，10 色）
            Text(
                text = "颜色",
                color = OnSurfaceVariant,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(bottom = 8.dp),
            )
            LazyVerticalGrid(
                columns = GridCells.Fixed(5),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                userScrollEnabled = false,
            ) {
                items(CategoryPalette) { c ->
                    ColorPickerItem(
                        color = c,
                        selected = c == color,
                        onClick = { color = c },
                    )
                }
            }
            Text(
                text = "预览",
                color = OnSurfaceVariant,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(bottom = 8.dp),
            )
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(EareyeShapes.md)
                    .background(Surface)
                    .border(
                        width = 1.dp,
                        color = OnSurfaceVariant.copy(alpha = 0.3f),
                        shape = EareyeShapes.md,
                    )
                    .padding(vertical = 16.dp),
                contentAlignment = Alignment.Center,
            ) {
                Row(
                    modifier = Modifier
                        .clip(EareyeShapes.full)
                        .background(color)
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Icon(
                        imageVector = icon.imageVector,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                        tint = Color.White,
                    )
                    Text(
                        text = name.ifBlank { "分类名称" },
                        color = Color.White,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium,
                    )
                }
            }

            // 保存按钮
            Button(
                onClick = {
                    if (name.isNotBlank()) {
                        onSave(Category(name = name, icon = icon, color = color))
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 20.dp, bottom = 16.dp),
                enabled = name.isNotBlank(),
                shape = EareyeShapes.md,
            ) {
                Text("保存分类", fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

@Composable
private fun IconPickerItem(
    icon: CategoryIcon,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .aspectRatio(1f)
            .clip(EareyeShapes.md)
            .background(if (selected) Primary.copy(alpha = 0.08f) else Surface)
            .border(
                width = 1.5.dp,
                color = if (selected) Primary else OnSurfaceVariant.copy(alpha = 0.2f),
                shape = EareyeShapes.md,
            )
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon.imageVector,
            contentDescription = null,
            modifier = Modifier.size(20.dp),
            tint = if (selected) Primary else OnSurface,
        )
    }
}

@Composable
private fun ColorPickerItem(
    color: Color,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val ringColor = if (selected) OnSurface else Color.Transparent
    Box(
        modifier = Modifier
            .aspectRatio(1f)
            .clip(CircleShape)
            .background(color)
            .border(
                width = 2.dp,
                color = ringColor,
                shape = CircleShape,
            )
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        if (selected) {
            Icon(
                imageVector = androidx.compose.material.icons.Icons.Default.Check,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
                tint = Color.White,
            )
        }
    }
}

// LazyVerticalGrid 的 items 已由 androidx.compose.foundation.lazy.grid.items 提供，
// 无需自定义 LazyGridScope 扩展。

