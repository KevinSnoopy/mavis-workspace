package com.eareyereading.ui.screens.home

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.eareyereading.ui.components.rememberPlayOnceAnimation
import com.eareyereading.ui.theme.BorderStrong
import com.eareyereading.ui.theme.Primary
import com.eareyereading.ui.theme.PrimaryLight

// ── 周阅读图表 ───────────────────────────────────
/** 日均目标（分钟）：达标判定阈值，与热力图第一档对齐。 */
private const val DAILY_GOAL_MINUTES = 15

@Composable
internal fun WeeklyChart(
    data: List<DayReadingData>,
    modifier: Modifier = Modifier,
) {
    val maxMinutes = data.maxOfOrNull { it.minutes }?.coerceAtLeast(1) ?: 1

    // 进入页面时柱状图从 0 生长到目标高度（图表生长动画，0f→1f 单次推进）
    val growProgress = rememberPlayOnceAnimation(durationMillis = 750)

    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surface,

    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.Bottom,
        ) {
            data.forEachIndexed { index, day ->
                val isToday = index == data.lastIndex
                val heightRatio = day.minutes.toFloat() / maxMinutes
                // 改版C：今日柱即使未达标也保持"进行中"的视觉高度
                //（不低于昨日/前日较高者的 60%），不再是一根贴地的短柱
                val effectiveRatio = if (isToday) {
                    val recentMax = data.dropLast(1).takeLast(2)
                        .maxOfOrNull { it.minutes }?.toFloat() ?: 0f
                    heightRatio.coerceAtLeast((recentMax / maxMinutes) * 0.6f)
                } else {
                    heightRatio
                }
                // 颜色规则（§4.5.1）：今日 primary-container 高亮；
                // 已发生日达标=primary 深绿、未达标=outline 灰
                val barColor = when {
                    isToday -> PrimaryLight
                    day.minutes >= DAILY_GOAL_MINUTES -> Primary
                    else -> BorderStrong
                }
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Bottom,
                    modifier = Modifier.weight(1f),
                ) {
                    if (isToday) {
                        // 今日柱顶给带语义的文案，替代孤立数字（原图"34"含义不明）
                        Text(
                            "今日 +${day.minutes} min",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurface,
                            fontWeight = FontWeight.Medium,
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                    } else if (day.minutes >= DAILY_GOAL_MINUTES) {
                        // 仅达标日显示数值，未达标日不显示（降低负反馈噪音）
                        Text(
                            "${day.minutes}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurface,
                            fontWeight = FontWeight.Bold,
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                    }
                    // 柱条只能占"扣除顶部数值与底部周标签后的剩余高度"：
                    // 旧实现 fillMaxHeight(fraction) 以整列（含上下文本）为基准，
                    // ratio 接近 1 时柱高+文本超出 150dp 容器，柱条溢出盖住底部文字。
                    // 外层 weight(1f) 吃掉剩余空间，内层再按比例填充，永不越界
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(0.5f)
                            .weight(1f),
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .fillMaxHeight(
                                    (effectiveRatio * growProgress).coerceAtLeast(0.03f),
                                )
                                .align(Alignment.BottomCenter)
                                .clip(RoundedCornerShape(topStart = 4.dp, topEnd = 4.dp))
                                .background(barColor),
                        )
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        day.dayLabel,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}
