package com.eareyereading.ui.components.category

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.eareyereading.ui.theme.EareyeShapes
import com.eareyereading.ui.theme.OnSurface
import com.eareyereading.ui.theme.OnSurfaceVariant
import com.eareyereading.ui.theme.Primary
import com.eareyereading.ui.theme.Surface

/**
 * 分类胶囊条（SPEC §4.9.1）
 *
 * 横向滚动的彩色胶囊，每分类含图标 + 名称 + 计数。
 * 末尾「+ 新建分类」入口（虚线边框）。
 *
 * 设计判断：
 * - 不用 M3 FilterChip（无法承载图标背景色 + 计数）
 * - 自绘 Row + clip 包装保证视觉控制权
 * - 选中态主色实底，未选中态透明底 + 边框
 */
@Composable
fun CategoryStrip(
    categories: List<Category>,
    selected: String?,
    onSelect: (String?) -> Unit,
    onAddCategory: () -> Unit,
    totalCount: Int,
    modifier: Modifier = Modifier,
) {
    LazyRow(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item(key = "cat_all") {
            CategoryChip(
                label = "全部",
                count = totalCount,
                color = Primary,
                selected = selected == null,
                onClick = { onSelect(null) },
            )
        }
        items(categories, key = { "cat_${it.name}" }) { cat ->
            CategoryChip(
                label = cat.name,
                count = cat.bookCount,
                color = cat.color,
                icon = cat.icon,
                selected = selected == cat.name,
                onClick = {
                    onSelect(if (selected == cat.name) null else cat.name)
                },
            )
        }
        item(key = "cat_add") {
            CategoryAddChip(onClick = onAddCategory)
        }
    }
}

@Composable
private fun CategoryChip(
    label: String,
    count: Int,
    color: Color,
    icon: CategoryIcon? = null,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .clip(EareyeShapes.full)
            .background(if (selected) color else Surface)
            .border(
                width = 1.dp,
                color = if (selected) color else OnSurfaceVariant.copy(alpha = 0.3f),
                shape = EareyeShapes.full,
            )
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            )
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (icon != null) {
            Box(
                modifier = Modifier
                    .size(20.dp)
                    .clip(CircleShape)
                    .background(if (selected) Color.White.copy(alpha = 0.25f) else color),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = icon.imageVector,
                    contentDescription = null,
                    modifier = Modifier.size(12.dp),
                    tint = Color.White,
                )
            }
        }
        Text(
            text = label,
            color = if (selected) Color.White else OnSurface,
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
        )
        Text(
            text = count.toString(),
            color = if (selected) Color.White.copy(alpha = 0.7f) else OnSurfaceVariant,
            fontSize = 11.sp,
        )
    }
}

@Composable
private fun CategoryAddChip(onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .clip(EareyeShapes.full)
            .border(
                width = 1.dp,
                color = Primary.copy(alpha = 0.6f),
                shape = EareyeShapes.full,
            )
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            )
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Icon(
            imageVector = Icons.Default.Add,
            contentDescription = "新建分类",
            modifier = Modifier.size(14.dp),
            tint = Primary,
        )
        Text(
            text = "新建分类",
            color = Primary,
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
        )
    }
}
