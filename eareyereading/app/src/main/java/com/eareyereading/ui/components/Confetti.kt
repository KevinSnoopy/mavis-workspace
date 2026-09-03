package com.eareyereading.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.rotate
import com.eareyereading.ui.theme.Accent
import com.eareyereading.ui.theme.Info
import com.eareyereading.ui.theme.L1
import com.eareyereading.ui.theme.Primary
import com.eareyereading.ui.theme.Success
import com.eareyereading.ui.theme.Warning
import kotlin.math.floor
import kotlin.math.sin

/**
 * 庆祝彩带（Duolingo 式 Confetti）：进入组合后播放一次约 3 秒的纸屑下落，
 * 播完自动静止为透明，不占任何输入/布局空间。
 */
@Composable
fun ConfettiCelebration(
    modifier: Modifier = Modifier,
    colors: List<Color> = listOf(Success, Warning, Primary, Accent, Info, L1),
) {
    val progress = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        progress.animateTo(
            1f,
            animationSpec = tween(durationMillis = 3000, easing = LinearEasing),
        )
    }
    Canvas(modifier = modifier.fillMaxSize()) {
        val t = progress.value
        if (t <= 0f || t >= 1f) return@Canvas
        val w = size.width
        val h = size.height
        val particleCount = 64
        repeat(particleCount) { i ->
            // 确定性伪随机：同一 i 每帧结果一致，避免每帧重新"洗牌"造成的抖动
            val rand1 = fract(sin(i * 12.9898f) * 43758.5453f)
            val rand2 = fract(sin(i * 78.233f) * 12543.213f)
            val x = w * ((i.toFloat() / particleCount) + rand1 * 0.06f)
            val fallSpeed = 0.55f + rand2 * 0.75f
            val y = -24f + (h + 48f) * t * fallSpeed
            val alpha = (1.2f - t * 1.2f).coerceIn(0f, 1f)
            val rotation = t * (360f + rand1 * 540f) * (if (i % 2 == 0) 1f else -1f)
            val color = colors[i % colors.size]
            val long = 7f + rand1 * 5f
            rotate(degrees = rotation, pivot = Offset(x, y)) {
                drawRoundRect(
                    color = color.copy(alpha = alpha),
                    topLeft = Offset(x - 2.5f, y - long / 2f),
                    size = Size(5f, long),
                    cornerRadius = CornerRadius(1.5f, 1.5f),
                )
            }
        }
    }
}

private fun fract(v: Float): Float = v - floor(v)
