package com.eareyereading.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateIntAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight

/**
 * 骨架屏微光效果：一道高光周期性扫过底色，替代转圈加载指示器，
 * 让加载中的界面"预演"出真实内容的形状（Spotify/微信读书式感知优化）。
 *
 * 性能：改为 @Composable 工厂 + drawBehind 里延迟读动画值——每帧只失效
 * 绘制阶段。旧实现用已弃用的 composed 反模式（每个调用点独立小型组合），
 * 且动画值在组合阶段读取：骨架屏显示期间 8 个调用点 = 每帧 8 次重组 +
 * 每帧 8 组 Brush/Offset/List 分配，无限循环永不停止。
 * 颜色列表只随主题重建；高光带的 Brush 构造留在绘制阶段（每帧一个小对象，
 * 可接受），端点 220f 与旧实现保持一致。
 */
@Composable
fun Modifier.shimmer(): Modifier {
    val transition = rememberInfiniteTransition(label = "shimmer")
    val translate by transition.animateFloat(
        initialValue = -400f,
        targetValue = 1400f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1300, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "shimmerTranslate",
    )
    val base = MaterialTheme.colorScheme.surfaceVariant
    val highlight = MaterialTheme.colorScheme.surface
    val colors = remember(base, highlight) { listOf(base, highlight, base) }
    return background(base).drawBehind {
        val brush = Brush.linearGradient(
            colors = colors,
            start = Offset(translate - 400f, 0f),
            end = Offset(translate, 220f),
        )
        drawRect(brush)
    }
}

/**
 * 数字滚动动画（Keep / 苹果健康式 Count-up）：
 * 首次进入组合时从 0 滚动到目标值，之后目标值变化时平滑过渡。
 */
@Composable
fun AnimatedCountText(
    target: Int,
    modifier: Modifier = Modifier,
    style: TextStyle = MaterialTheme.typography.titleLarge,
    fontWeight: FontWeight? = FontWeight.Bold,
    color: androidx.compose.ui.graphics.Color = androidx.compose.ui.graphics.Color.Unspecified,
    durationMillis: Int = 800,
) {
    // 进入组合先停 0，LaunchedEffect 后置真值，保证每次进页都播放一次滚动
    var played by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { played = true }
    val count by animateIntAsState(
        targetValue = if (played) target else 0,
        animationSpec = tween(durationMillis = durationMillis, easing = FastOutSlowInEasing),
        label = "countUp",
    )
    Text(
        text = count.toString(),
        modifier = modifier,
        style = style,
        fontWeight = fontWeight,
        color = color,
    )
}

/**
 * 单次进度动画（0f → 1f）的通用驱动：供图表生长/彩带等一次性动效消费。
 * [onFinish] 在动画自然结束时回调（取消不回调）。
 */
@Composable
fun rememberPlayOnceAnimation(durationMillis: Int = 700): Float {
    val progress = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        progress.animateTo(
            1f,
            animationSpec = tween(durationMillis = durationMillis, easing = FastOutSlowInEasing),
        )
    }
    return progress.value
}
