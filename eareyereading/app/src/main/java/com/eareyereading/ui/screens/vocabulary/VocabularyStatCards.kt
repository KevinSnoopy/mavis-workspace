package com.eareyereading.ui.screens.vocabulary

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.eareyereading.ui.theme.L1
import com.eareyereading.ui.theme.L2
import com.eareyereading.ui.theme.L3
import com.eareyereading.ui.theme.L4
import com.eareyereading.ui.theme.L5
import com.eareyereading.ui.theme.OnPrimaryContainer
import com.eareyereading.ui.theme.OnSurface

internal val levelColors = listOf(L1, L2, L3, L4, L5)

/**
 * 紧凑统计卡片：数值 + 标签，实色底。
 */
@Composable
internal fun StatMiniCard(
    value: String,
    label: String,
    bg: Color,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(16.dp),
        color = bg,
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = value,
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                color = L5,
            )
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * 难度等级块：56×56dp 实色底（§3.1.2 饱和度阶梯），
 * 文字 L1-L3 用 on-surface / L4-L5 用 on-primary-container。
 */
@Composable
internal fun LevelChip(level: Int, count: Int) {
    val color = levelColors.getOrElse(level - 1) { Color.Gray }
    val textColor = if (level >= 4) OnPrimaryContainer else OnSurface
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Surface(
            modifier = Modifier.size(56.dp),
            shape = RoundedCornerShape(16.dp),
            color = color,
        ) {
            Box(contentAlignment = Alignment.Center) {
                Text(
                    "L$level",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold,
                    color = textColor,
                )
            }
        }
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            "${count}词",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
