package com.eareyereading.ui.components.category

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.eareyereading.ui.theme.EareyeShapes
import com.eareyereading.ui.theme.OnSurface
import com.eareyereading.ui.theme.OnSurfaceVariant
import com.eareyereading.ui.theme.Primary
import com.eareyereading.ui.theme.Surface

/**
 * 分类选择网格（v2 公共组件）
 *
 * 3 列「图标 + 颜色」分类卡，单选。
 * 两处复用：
 * 1. AddBookFlowSheet 步骤 2（导入后选分类）
 * 2. 书籍「移至分类」对话框（原 FlowRow + FilterChip 的 v2 替代）
 *
 * @param showAddTile 末尾是否显示「新建」占位卡（触发 [onAddClick]）
 */
@Composable
fun CategorySelectGrid(
    categories: List<Category>,
    selectedName: String?,
    onSelect: (Category) -> Unit,
    modifier: Modifier = Modifier,
    showAddTile: Boolean = false,
    onAddClick: () -> Unit = {},
) {
    LazyVerticalGrid(
        columns = GridCells.Fixed(3),
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(categories, key = { "grid_${it.name}" }) { cat ->
            CategorySelectItem(
                category = cat,
                selected = cat.name == selectedName,
                onClick = { onSelect(cat) },
            )
        }
        if (showAddTile) {
            item(key = "grid_add") {
                CategoryAddTile(onClick = onAddClick)
            }
        }
    }
}

/** 单个分类选择卡：图标色块 + 名称，选中态主色边框 + 主色 8% 底 */
@Composable
private fun CategorySelectItem(
    category: Category,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(0.95f)
            .clip(EareyeShapes.md)
            .background(if (selected) Primary.copy(alpha = 0.08f) else Surface)
            .border(
                width = if (selected) 1.5.dp else 1.dp,
                color = if (selected) Primary else OnSurfaceVariant.copy(alpha = 0.2f),
                shape = EareyeShapes.md,
            )
            .clickable(onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Box(
            modifier = Modifier
                .size(32.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(category.color),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = category.icon.imageVector,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
                tint = Color.White,
            )
        }
        Text(
            text = category.name,
            color = OnSurface,
            fontSize = 11.sp,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 6.dp, start = 4.dp, end = 4.dp),
        )
    }
}

/** 「新建分类」占位卡：虚线边框 + 加号 */
@Composable
private fun CategoryAddTile(onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(0.95f)
            .clip(EareyeShapes.md)
            .border(
                width = 1.5.dp,
                color = OnSurfaceVariant.copy(alpha = 0.5f),
                shape = EareyeShapes.md,
            )
            .clickable(onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Box(
            modifier = Modifier
                .size(32.dp)
                .clip(RoundedCornerShape(8.dp))
                .border(
                    width = 1.5.dp,
                    color = OnSurfaceVariant.copy(alpha = 0.5f),
                    shape = RoundedCornerShape(8.dp),
                ),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Default.Add,
                contentDescription = "新建分类",
                modifier = Modifier.size(16.dp),
                tint = OnSurfaceVariant,
            )
        }
        Text(
            text = "新建",
            color = OnSurfaceVariant,
            fontSize = 11.sp,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.padding(top = 6.dp),
        )
    }
}
