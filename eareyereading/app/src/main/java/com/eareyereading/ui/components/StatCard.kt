package com.eareyereading.ui.components

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MenuBook
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * 首页/书库共用的统计卡片（P3 组件抽取：此前 Home/Library 各写一份）。
 *
 * 数字带 Count-up 滚动动画；[pulse] 为 true 时图标做呼吸脉动
 * （连续打卡火焰，多邻国式的存活暗示）。
 */
@Composable
fun StatCard(
    icon: ImageVector,
    value: Int,
    label: String,
    color: Color,
    modifier: Modifier = Modifier,
    unit: String? = null,
    pulse: Boolean = false,
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(16.dp),
        color = color.copy(alpha = 0.1f),
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            if (pulse) {
                val transition = rememberInfiniteTransition(label = "pulse")
                val scale by transition.animateFloat(
                    initialValue = 0.85f,
                    targetValue = 1.15f,
                    animationSpec = infiniteRepeatable(
                        animation = tween(durationMillis = 900, easing = FastOutSlowInEasing),
                        repeatMode = RepeatMode.Reverse,
                    ),
                    label = "pulseScale",
                )
                Icon(
                    icon,
                    contentDescription = label,
                    tint = color,
                    modifier = Modifier
                        .size(22.dp)
                        .graphicsLayer {
                            scaleX = scale
                            scaleY = scale
                        },
                )
            } else {
                Icon(icon, contentDescription = label, tint = color, modifier = Modifier.size(22.dp))
            }
            Spacer(modifier = Modifier.height(6.dp))
            Row(
                verticalAlignment = Alignment.Bottom,
                horizontalArrangement = Arrangement.Center,
            ) {
                AnimatedCountText(
                    target = value,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = color,
                )
                if (unit != null) {
                    Spacer(modifier = Modifier.width(2.dp))
                    Text(
                        text = unit,
                        style = MaterialTheme.typography.labelSmall,
                        color = color.copy(alpha = 0.7f),
                        modifier = Modifier.padding(bottom = 2.dp),
                    )
                }
            }
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@androidx.compose.ui.tooling.preview.Preview(showBackground = true)
@Composable
private fun StatCardPreview() {
    com.eareyereading.ui.theme.EareyeReadingTheme {
        Row(modifier = Modifier.padding(16.dp)) {
            StatCard(
                icon = Icons.Default.MenuBook,
                value = 128,
                unit = "词",
                label = "生词本",
                color = com.eareyereading.ui.theme.Accent,
                modifier = Modifier.width(120.dp),
            )
        }
    }
}
