package com.eareyereading.ui.components.category

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.eareyereading.ui.theme.EareyeShapes
import com.eareyereading.ui.theme.OnSurfaceVariant
import com.eareyereading.ui.theme.Primary

/**
 * 封面背景库的三分段（SPEC §4.10）。
 *
 * 索引区间与 [com.eareyereading.ui.theme.CoverPatterns] 一一对应：
 * 0-9 纯色渐变（无图案）/ 10-12 几何图案 / 13-14 装饰风格。
 * 分段与图案表是两个独立的列表，二者长度必须对齐——集中在此处定义，
 * 新增封面时只需同步这一处。
 */
internal val CoverSegmentLabels = listOf("纯色渐变", "几何图案", "装饰风格")

internal val CoverSegmentRanges = listOf(0..9, 10..12, 13..14)

/**
 * 封面选择器的可复用内容：分段切换 + 3 列封面网格。
 *
 * ── 重构说明（DRY）──
 * 新增书籍流程的「选封面」步骤与独立的「选择封面背景」弹窗需要完全相同的
 * 选择体验。此前前者手写了一份简化版（硬编码只展示前 6 个封面、无分段切换），
 * 于是用户在导入流程里根本选不到封面库后半部分的几何图案与装饰风格封面。
 * 抽为共享内容后，两条入口展示同一套 15 个封面，且选择行为一致。
 *
 * 本组件只负责内容，不含 [androidx.compose.material3.ModalBottomSheet] 外壳：
 * 调用方可能在已有弹窗内部嵌套使用（不能再叠一层 ModalBottomSheet）。
 *
 * @param gridHeight 网格高度：内部是惰性网格，必须给定高度约束
 */
@Composable
internal fun CoverPickerContent(
    selectedId: Int,
    onSelect: (Int) -> Unit,
    previewTitle: String,
    previewAuthor: String,
    modifier: Modifier = Modifier,
    gridHeight: Dp = 280.dp,
) {
    var segment by remember { mutableIntStateOf(0) }
    val currentIds = remember(segment) { CoverSegmentRanges[segment].toList() }

    Column(modifier = modifier) {
        // 分段切换
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 16.dp)
                .clip(EareyeShapes.full)
                .background(OnSurfaceVariant.copy(alpha = 0.08f))
                .padding(4.dp),
        ) {
            CoverSegmentLabels.forEachIndexed { idx, label ->
                val isSelected = idx == segment
                TextButton(
                    onClick = { segment = idx },
                    modifier = Modifier.weight(1f),
                    shape = EareyeShapes.full,
                    // 选中态底色：白字必须配实底，否则在浅灰容器上等于不可见
                    colors = ButtonDefaults.textButtonColors(
                        containerColor = if (isSelected) Primary else Color.Transparent,
                    ),
                ) {
                    Text(
                        text = label,
                        color = if (isSelected) Color.White else OnSurfaceVariant,
                        fontSize = 13.sp,
                        fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                    )
                }
            }
        }

        // 当前分段的封面网格（3 列）
        LazyVerticalGrid(
            columns = GridCells.Fixed(3),
            modifier = Modifier
                .fillMaxWidth()
                .height(gridHeight),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            items(items = currentIds, key = { it }) { coverId ->
                CoverTilePreview(
                    coverId = coverId,
                    title = previewTitle,
                    author = previewAuthor,
                    selected = selectedId == coverId,
                    onClick = { onSelect(coverId) },
                )
            }
        }
    }
}
