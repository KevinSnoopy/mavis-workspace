package com.eareyereading.ui.screens.review

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.eareyereading.ui.theme.*
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * 3D 翻面复习卡：正面单词，背面释义。翻面用 rotationY + cameraDistance
 * 的 graphicsLayer 实现（透视感来自相机距离，不是缩放）。
 * 显示答案后支持水平拖动：越过阈值左滑=忘了(q=1)、右滑=完美(q=5)，
 * 拖动偏移实时驱动"忘了/完美"印章的透明度。
 */
@Composable
internal fun ReviewFlipCard(
    card: ReviewViewModel.ReviewCard,
    isShowingAnswer: Boolean,
    isSubmitting: Boolean,
    onAnswer: (Int) -> Unit,
    onReveal: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // 翻面动画：0f(正面) ↔ 180f(背面)
    val flip = remember { Animatable(0f) }
    LaunchedEffect(isShowingAnswer) {
        flip.animateTo(
            targetValue = if (isShowingAnswer) 180f else 0f,
            animationSpec = tween(durationMillis = 450),
        )
    }

    // 水平拖动偏移（px）：普通 state 直接累积，回弹用协程动画写回
    var dragOffset by remember { mutableStateOf(0f) }
    val scope = rememberCoroutineScope()
    val screenDensity = LocalDensity.current
    val swipeThreshold = with(screenDensity) { 120.dp.toPx() }
    // 切换卡片时清零残留偏移（上一张的滑动不得带进下一张）
    LaunchedEffect(card.record.id) { dragOffset = 0f }

    Box(modifier = modifier) {
        Card(
            modifier = Modifier
                .fillMaxSize()
                // 正面整卡可点翻面：文案写着"点击下方按钮查看释义"，但用户
                // 本能是点卡片本身；背面可点无操作（评分走滑动/按钮）
                .clickable(enabled = !isShowingAnswer && !isSubmitting) { onReveal() }
                .graphicsLayer {
                    rotationY = flip.value + (dragOffset / swipeThreshold) * 4f
                    // cameraDistance 的单位是 1dp 像素密度：14 * density.density
                    cameraDistance = 14f * screenDensity.density
                }
                .offset {
                    IntOffset(dragOffset.roundToInt(), 0)
                }
                .draggable(
                    enabled = isShowingAnswer && !isSubmitting,
                    orientation = Orientation.Horizontal,
                    state = rememberDraggableState { delta ->
                        // 只在未越过阈值时累积；越过后锁死，避免印章抖动
                        if (abs(dragOffset) < swipeThreshold) {
                            dragOffset += delta
                        }
                    },
                    onDragStopped = {
                        val offset = dragOffset
                        when {
                            offset >= swipeThreshold -> onAnswer(5)   // 右滑：完美
                            offset <= -swipeThreshold -> onAnswer(1)  // 左滑：忘了
                            else -> {
                                // 未过阈值：弹回原位（帧动画写回偏移）
                                scope.launch {
                                    val anim = Animatable(offset)
                                    anim.animateTo(
                                        targetValue = 0f,
                                        animationSpec = tween(200),
                                    ) {
                                        dragOffset = value
                                    }
                                    dragOffset = 0f
                                }
                            }
                        }
                    },
                ),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant,
            ),
        ) {
            Box(modifier = Modifier.fillMaxSize()) {
                // 正面（< 90° 可见）
                if (flip.value < 90f) {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                    ) {
                        Text(
                            text = card.record.word,
                            style = MaterialTheme.typography.displaySmall,
                            fontWeight = FontWeight.Bold,
                            textAlign = TextAlign.Center,
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            "点击下方按钮查看释义",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                } else {
                    // 背面（>= 90° 可见，先转 180° 再渲染，保证文字不镜像）
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .graphicsLayer { rotationY = 180f }
                            .padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                    ) {
                        Text(
                            text = card.record.word,
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                            textAlign = TextAlign.Center,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        if (card.vocabulary == null) {
                            // 词条已被删除但复习记录仍在：给出占位说明，
                            // 不让用户面对空白答案盲评
                            Text(
                                text = "词条已删除，无法展示释义",
                                style = MaterialTheme.typography.bodyLarge,
                                textAlign = TextAlign.Center,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        } else {
                            card.vocabulary.definition?.let { def ->
                                Text(
                                    text = def,
                                    style = MaterialTheme.typography.bodyLarge,
                                    textAlign = TextAlign.Center,
                                    color = Primary,
                                )
                            }
                            card.vocabulary.context?.let { ctx ->
                                Spacer(modifier = Modifier.height(8.dp))
                                Text(
                                    text = "\"$ctx\"",
                                    style = MaterialTheme.typography.bodyMedium,
                                    textAlign = TextAlign.Center,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }

                // 滑动评分印章：透明度随偏移比例增长
                val swipeRatio = (abs(dragOffset) / swipeThreshold).coerceIn(0f, 1f)
                if (swipeRatio > 0.05f && !isSubmitting) {
                    val right = dragOffset > 0
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = (if (right) Success else Error).copy(alpha = 0.16f),
                        modifier = Modifier
                            .align(if (right) Alignment.CenterStart else Alignment.CenterEnd)
                            .padding(20.dp)
                            .graphicsLayer { alpha = swipeRatio },
                    ) {
                        Text(
                            text = if (right) "完美" else "忘了",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                            color = if (right) Success else Error,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                        )
                    }
                }
            }
        }
    }
}
