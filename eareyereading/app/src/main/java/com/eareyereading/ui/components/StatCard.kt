package com.eareyereading.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
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
                // 有限次呼吸（4 次 × 900ms，Reverse 偶数次收在初始值 1.0）：
                // 旧实现是 infiniteRepeatable——只要用户有打卡记录，Home 页
                // 可见期间永远无法进入静止帧，持续耗电耗 GPU。
                // 呼吸幅度收窄到 1.0~1.15，保证动画结束后图标停在原始大小
                val scale = remember(pulse) {
                    Animatable(1f)
                }
                LaunchedEffect(pulse) {
                    if (pulse) {
                        repeat(4) {
                            scale.animateTo(
                                targetValue = 1.15f,
                                animationSpec = tween(durationMillis = 900, easing = FastOutSlowInEasing),
                            )
                            scale.animateTo(
                                targetValue = 1f,
                                animationSpec = tween(durationMillis = 900, easing = FastOutSlowInEasing),
                            )
                        }
                    }
                }
                Icon(
                    icon,
                    contentDescription = label,
                    tint = color,
                    modifier = Modifier
                        .size(22.dp)
                        .graphicsLayer {
                            scaleX = scale.value
                            scaleY = scale.value
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
