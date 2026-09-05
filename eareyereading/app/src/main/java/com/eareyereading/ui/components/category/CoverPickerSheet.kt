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
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.eareyereading.ui.theme.CoverGradients
import com.eareyereading.ui.theme.CoverPatterns
import com.eareyereading.ui.theme.EareyeShapes
import com.eareyereading.ui.theme.OnSurface
import com.eareyereading.ui.theme.OnSurfaceVariant
import com.eareyereading.ui.theme.Primary

/**
 * 封面背景选择器（SPEC §4.10）
 *
 * 15 个预设封面背景，分 3 类（纯色渐变 / 几何图案 / 装饰风格）。
 * 3 列网格，每个 3:4 长宽比，含书名 + 作者预览。
 *
 * 设计判断：
 * - 用 LazyVerticalGrid 3 列，maxHeight 限制内部滚动
 * - 分段切换切换 pane（filter items）
 * - 选中态主色边框 + 右上角圆形对勾
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CoverPickerSheet(
    initialCoverId: Int = 0,
    previewTitle: String = "书名",
    previewAuthor: String = "作者",
    onPick: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var selectedId by remember { mutableIntStateOf(initialCoverId) }
    // 0: 渐变 / 1: 图案 / 2: 装饰
    var segment by remember { mutableIntStateOf(0) }

    val segments = listOf("纯色渐变", "几何图案", "装饰风格")
    // 0-9 = 渐变 / 10-12 = 图案 / 13-14 = 装饰
    val ranges = listOf(0..9, 10..12, 13..14)
    val currentRange = ranges[segment]

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
                text = "选择封面背景",
                style = MaterialTheme.typography.titleLarge,
                color = OnSurface,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(bottom = 16.dp),
            )

            // 分段切换
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 16.dp)
                    .clip(EareyeShapes.full)
                    .background(OnSurfaceVariant.copy(alpha = 0.08f))
                    .padding(4.dp),
                horizontalArrangement = Arrangement.spacedBy(0.dp),
            ) {
                segments.forEachIndexed { idx, label ->
                    val isSelected = idx == segment
                    TextButton(
                        onClick = { segment = idx },
                        modifier = Modifier.weight(1f),
                        shape = EareyeShapes.full,
                    ) {
                        Text(
                            text = label,
                            color = if (isSelected) Color.White else OnSurfaceVariant,
                            fontSize = 13.sp,
                            fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                        )
                    }
                    // 选中态背景通过 Modifier.background 在外层 Box 处理
                }
            }

            // 当前 pane 的封面网格（3 列）
            val currentIds = currentRange.toList()
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 20.dp),
            ) {
                LazyVerticalGrid(
                    columns = GridCells.Fixed(3),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(2.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    userScrollEnabled = true,
                ) {
                    itemsIndexed(currentIds) { _, coverId ->
                        CoverOption(
                            coverId = coverId,
                            title = previewTitle,
                            author = previewAuthor,
                            selected = selectedId == coverId,
                            onClick = { selectedId = coverId },
                        )
                    }
                }
            }

            // 底部按钮
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedButton(
                    onClick = onDismiss,
                    modifier = Modifier.weight(1f),
                    shape = EareyeShapes.md,
                ) {
                    Text("取消")
                }
                Button(
                    onClick = { onPick(selectedId) },
                    modifier = Modifier.weight(2f),
                    shape = EareyeShapes.md,
                ) {
                    Text("应用此封面", fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}

@Composable
private fun CoverOption(
    coverId: Int,
    title: String,
    author: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val gradient = CoverGradients[coverId]
    val pattern = CoverPatterns[coverId]
    Box(
        modifier = Modifier
            .aspectRatio(0.75f)  // 3:4
            .clip(EareyeShapes.md)
            .background(Brush.linearGradient(gradient))
            .border(
                width = if (selected) 2.5.dp else 0.dp,
                color = if (selected) Primary else Color.Transparent,
                shape = EareyeShapes.md,
            )
            .clickable(onClick = onClick),
    ) {
        // 右上角对勾
        if (selected) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(6.dp)
                    .size(20.dp)
                    .clip(CircleShape)
                    .background(Primary),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Default.Check,
                    contentDescription = null,
                    modifier = Modifier.size(12.dp),
                    tint = Color.White,
                )
            }
        }
        // 书名 + 作者预览
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(8.dp),
        ) {
            Text(
                text = title,
                color = Color.White,
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 2,
            )
            Box(modifier = Modifier.weight(1f))
            Text(
                text = author,
                color = Color.White.copy(alpha = 0.85f),
                fontSize = 8.sp,
                maxLines = 1,
            )
        }
    }
}
