package com.eareyereading.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.MenuBook
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.eareyereading.ui.theme.EareyeReadingTheme
import com.eareyereading.ui.theme.Primary

/**
 * 学习热力图（GitHub contributions 式）：
 * [dailyMinutes] 为按周一到周日、周序排列的每日阅读分钟数（最旧在前），
 * 负值表示"未来日期"（本周尚未到来的天），渲染为透明占位。
 */
@Composable
fun ReadingHeatmap(
    dailyMinutes: List<Int>,
    modifier: Modifier = Modifier,
) {
    val weeks = dailyMinutes.size / 7
    if (weeks == 0) return
    val activeDays = dailyMinutes.count { it > 0 }
    val totalMinutes = dailyMinutes.sumOf { it.coerceAtLeast(0) }

    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surface,
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("学习热力图", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                Text(
                    "近 12 周 · $activeDays 天 · ${totalMinutes / 60}h${totalMinutes % 60}m",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(modifier = Modifier.height(12.dp))
            Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                for (day in 0 until 7) {
                    Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                        for (week in 0 until weeks) {
                            val minutes = dailyMinutes.getOrElse(week * 7 + day) { 0 }
                            Box(
                                modifier = Modifier
                                    .size(12.dp)
                                    .clip(RoundedCornerShape(3.dp))
                                    .background(heatmapCellColor(minutes)),
                            )
                        }
                    }
                }
            }
            Spacer(modifier = Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "少",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(modifier = Modifier.padding(horizontal = 1.dp))
                listOf(0, 15, 35, 60, 120).forEach { threshold ->
                    Box(
                        modifier = Modifier
                            .padding(horizontal = 1.dp)
                            .size(9.dp)
                            .clip(RoundedCornerShape(2.dp))
                            .background(heatmapCellColor(threshold)),
                    )
                }
                Text(
                    "多",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** 热力格颜色：0 分钟用极浅底，阅读时长分四档加深（品牌棕的单色阶）。 */
private fun heatmapCellColor(minutes: Int): Color = when {
    minutes < 0 -> Color.Transparent          // 未来日期
    minutes == 0 -> Primary.copy(alpha = 0.08f)
    minutes < 15 -> Primary.copy(alpha = 0.28f)
    minutes < 35 -> Primary.copy(alpha = 0.48f)
    minutes < 60 -> Primary.copy(alpha = 0.68f)
    else -> Primary.copy(alpha = 0.92f)
}

/**
 * 通用空状态：图标 + 标题 + 副文案，替代原先的 emoji 占位。
 */
@Composable
fun EmptyState(
    icon: ImageVector,
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String = "",
) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier
                .size(84.dp)
                .clip(CircleShape)
                .background(Primary.copy(alpha = 0.1f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                icon,
                contentDescription = null,
                tint = Primary.copy(alpha = 0.85f),
                modifier = Modifier.size(38.dp),
            )
        }
        Spacer(modifier = Modifier.height(14.dp))
        Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        if (subtitle.isNotBlank()) {
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun ReadingHeatmapPreview() {
    EareyeReadingTheme {
        ReadingHeatmap(
            dailyMinutes = buildList {
                repeat(84) { i -> add(if (i % 3 == 0) (i * 7) % 130 else if (i > 77) -1 else 0) }
            },
            modifier = Modifier.padding(16.dp),
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun EmptyStatePreview() {
    EareyeReadingTheme {
        EmptyState(
            icon = Icons.Outlined.MenuBook,
            title = "书架为空",
            subtitle = "导入 EPUB/TXT，或从经典名著一键下载",
        )
    }
}
