package com.eareyereading.ui.components.category

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.eareyereading.ui.components.coverPatternOf
import com.eareyereading.ui.components.drawCoverPattern
import com.eareyereading.ui.theme.CoverGradients
import com.eareyereading.ui.theme.CoverPattern
import com.eareyereading.ui.theme.EareyeShapes
import com.eareyereading.ui.theme.Primary

/**
 * 封面磁贴的共享外观。
 *
 * ── 重构说明（DRY）──
 * 「3:4 圆角磁贴 + 渐变底 + 选中描边 + 点击」这条修饰符链，以及右上角的
 * 选中对勾徽标，在新增书籍流程内嵌的封面选择（AddBookFlowSheet）与
 * 封面选择弹窗（CoverPickerSheet.CoverOption）两处逐行相同，仅渐变色来源
 * 与选中判定表达式不同。任何视觉调整（描边粗细、圆角、对勾尺寸）漏改一处，
 * 两处的封面预览就会看起来不是同一套控件。
 */

/**
 * 封面磁贴外观：3:4 比例、圆角裁剪、渐变填充、选中态描边、点击响应。
 *
 * @param brush 磁贴底色（由调用方提供，两处的调色板来源不同）
 * @param selected 是否选中，决定描边有无
 * @param onClick 点击回调
 */
internal fun Modifier.coverTile(
    brush: Brush,
    selected: Boolean,
    onClick: () -> Unit,
): Modifier = this
    .aspectRatio(0.75f)  // 3:4
    .clip(EareyeShapes.md)
    .background(brush)
    .border(
        width = if (selected) 2.5.dp else 0.dp,
        color = if (selected) Primary else Color.Transparent,
        shape = EareyeShapes.md,
    )
    .clickable(onClick = onClick)

/**
 * 封面选中态右上角对勾徽标。
 * 调用方在 Box 作用域内传入 `Modifier.align(Alignment.TopEnd)` 完成定位。
 */
@Composable
internal fun CoverSelectedBadge(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
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

/**
 * 封面背景库中的一枚可选磁贴：渐变底 + 几何纹理层 + 书名/作者预览 + 选中对勾。
 *
 * ── 重构说明（DRY）──
 * 「取渐变 → 铺底 → 叠纹理 → 右上角对勾 → 左上书名 / 左下作者」这整套磁贴内容，
 * 此前在 [CoverPickerSheet] 与 [AddBookFlowSheet] 各写了一份，两份只有
 * 「封面 ID 从哪来」不同。收敛到此处后，封面库新增图案只需改一处。
 *
 * ── 纹理层 ──
 * 索引 10-14 的封面定义了几何图案（见 [CoverPattern]），必须显式绘制才能
 * 与纯渐变封面区分开；[CoverPattern.NONE] 时跳过该层，不做无谓的绘制调用。
 *
 * @param coverId 封面背景库索引（越界时退回渐变集首项）
 */
@Composable
internal fun CoverTilePreview(
    coverId: Int,
    title: String,
    author: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val gradient = remember(coverId) {
        CoverGradients.getOrNull(coverId) ?: CoverGradients.first()
    }
    val pattern = remember(coverId) { coverPatternOf(coverId) }

    Box(
        modifier = modifier.coverTile(
            brush = Brush.linearGradient(gradient),
            selected = selected,
            onClick = onClick,
        ),
    ) {
        // 纹理层：铺满磁贴，位于文字之下
        if (pattern != CoverPattern.NONE) {
            Canvas(modifier = Modifier.matchParentSize()) { drawCoverPattern(pattern) }
        }
        if (selected) CoverSelectedBadge(Modifier.align(Alignment.TopEnd))
        Column(
            modifier = Modifier
                .fillMaxSize()
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
