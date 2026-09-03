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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import com.eareyereading.ui.theme.Accent
import com.eareyereading.ui.theme.Info
import com.eareyereading.ui.theme.L1
import com.eareyereading.ui.theme.Primary
import com.eareyereading.ui.theme.Success
import com.eareyereading.ui.theme.Warning
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin

/** 粒子静态参数（只依赖下标 i，与帧无关）：预生成免每帧 128 次 sin+fract。 */
private class ConfettiParticle(
    val rand1: Float,
    val rand2: Float,
    val long: Float,
    val clockwise: Float,
)

private const val PARTICLE_COUNT = 64

private fun buildParticles(): Array<ConfettiParticle> = Array(PARTICLE_COUNT) { i ->
    val rand1 = fract(sin(i * 12.9898f) * 43758.5453f)
    val rand2 = fract(sin(i * 78.233f) * 12543.213f)
    ConfettiParticle(
        rand1 = rand1,
        rand2 = rand2,
        long = 7f + rand1 * 5f,
        clockwise = if (i % 2 == 0) 1f else -1f,
    )
}

/**
 * 庆祝彩带（Duolingo 式 Confetti）：进入组合后播放一次约 3 秒的纸屑下落，
 * 播完自动静止为透明，不占任何输入/布局空间。
 *
 * [onFinished] 在动画自然结束（或取消）后回调一次：调用方可据此把本组件
 * 从组合树移除，避免播放完毕后空 Canvas 节点常驻。
 */
@Composable
fun ConfettiCelebration(
    modifier: Modifier = Modifier,
    colors: List<Color> = DEFAULT_CONFETTI_COLORS,
    onFinished: () -> Unit = {},
) {
    val progress = remember { Animatable(0f) }
    // 粒子参数只与下标有关：预生成一次，免 64 粒子 × 每帧 2 次 sin+fract
    val particles = remember { buildParticles() }
    LaunchedEffect(Unit) {
        try {
            progress.animateTo(
                1f,
                animationSpec = tween(durationMillis = 3000, easing = LinearEasing),
            )
        } finally {
            onFinished()
        }
    }
    Canvas(modifier = modifier.fillMaxSize()) {
        // 进度只在绘制 lambda 内读取：动画期间只失效绘制阶段，
        // 不触发任何重组（这是旧实现做对的部分，保持）
        val t = progress.value
        if (t <= 0f || t >= 1f) return@Canvas
        val w = size.width
        val h = size.height
        // 透明度走 drawLine 的 alpha 参数：避免每粒子每帧 copy 出新
        // Color（64 粒子 × 180 帧 ≈ 1.1 万次分配）
        val globalAlpha = (1.2f - t * 1.2f).coerceIn(0f, 1f)
        particles.forEachIndexed { i, p ->
            // 确定性伪随机：同一 i 每帧结果一致，避免每帧重新"洗牌"造成的抖动
            val x = w * ((i.toFloat() / PARTICLE_COUNT) + p.rand1 * 0.06f)
            val fallSpeed = 0.55f + p.rand2 * 0.75f
            val y = -24f + (h + 48f) * t * fallSpeed
            val rotation = t * (360f + p.rand1 * 540f) * p.clockwise
            val color = colors[i % colors.size]
            // 旋转的细长矩形 = 沿长轴的粗线段：端点直接用三角函数算，
            // 免去每粒子每帧进出 rotate 变换栈
            val rad = Math.toRadians(rotation.toDouble())
            val dx = (p.long / 2f) * sin(rad).toFloat()
            val dy = (p.long / 2f) * cos(rad).toFloat()
            drawLine(
                color = color,
                start = Offset(x - dx, y + dy),
                end = Offset(x + dx, y - dy),
                strokeWidth = 5f,
                cap = StrokeCap.Round,
                alpha = globalAlpha,
            )
        }
    }
}

/** 默认配色（常量实例：默认参数表达式每次求值会产生新 List）。 */
private val DEFAULT_CONFETTI_COLORS = listOf(Success, Warning, Primary, Accent, Info, L1)

private fun fract(v: Float): Float = v - floor(v)
