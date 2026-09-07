package com.eareyereading.ui.screens.review

import androidx.compose.animation.*
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.eareyereading.ui.theme.*

/**
 * 复习卡片视图：进度条 + 错误提示 + 翻面卡片 + 操作按钮（显示答案/评分）。
 */
@Composable
internal fun ReviewCardView(
    card: ReviewViewModel.ReviewCard,
    currentIndex: Int,
    totalCards: Int,
    isShowingAnswer: Boolean,
    isSubmitting: Boolean,
    errorMessage: String?,
    onReveal: () -> Unit,
    onAnswer: (Int, Int) -> Unit,
    onDismissError: () -> Unit,
) {
    val haptics = LocalHapticFeedback.current

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
    ) {
        // 进度
        LinearProgressIndicator(
            progress = (currentIndex.toFloat() / totalCards.coerceAtLeast(1)),
            modifier = Modifier.fillMaxWidth(),
            color = Primary,
        )
        Text(
            "${currentIndex + 1} / $totalCards",
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(vertical = 4.dp),
        )

        // 评分写库失败的会话内提示：保留在当前卡片，用户可知情并重试
        if (errorMessage != null) {
            Text(
                errorMessage,
                color = Error,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onDismissError() }
                    .padding(vertical = 4.dp),
            )
        }

        Spacer(modifier = Modifier.height(24.dp))

        // 单词卡片：3D 翻面（Anki/Quizlet 式）+ 滑动评分（Tinder 式）
        ReviewFlipCard(
            card = card,
            isShowingAnswer = isShowingAnswer,
            isSubmitting = isSubmitting,
            onAnswer = { q ->
                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                onAnswer(currentIndex, q)
            },
            onReveal = {
                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                onReveal()
            },
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
        )

        Spacer(modifier = Modifier.height(16.dp))

        // 操作按钮
        AnimatedContent(
            targetState = isShowingAnswer,
            transitionSpec = {
                fadeIn() togetherWith fadeOut()
            },
            label = "reveal_or_answer",
        ) { showing ->
            if (!showing) {
                Button(
                    onClick = {
                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                        onReveal()
                    },
                    enabled = !isSubmitting,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                ) {
                    Icon(Icons.Default.Visibility, null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("显示答案")
                }
            } else {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        "还记得这个单词吗？",
                        style = MaterialTheme.typography.labelMedium,
                        modifier = Modifier.align(Alignment.CenterHorizontally),
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        // isSubmitting 期间禁用：过渡动画中退场的按钮仍可命中，
                        // 不加防护会把评分记到下一张未展示的卡片上
                        AnswerButton(
                            label = "忘了",
                            color = Error,
                            enabled = !isSubmitting,
                            onClick = { onAnswer(currentIndex, 1) },
                            modifier = Modifier.weight(1f),
                        )
                        // issue 11.2：SM-2 里 q=2 才是"记得但困难"（重置 interval=1）。
                        // 此前"困难"发 onAnswer(3)——q≥3 走"通过"分支，间隔涨到 6 天、
                        // EF 增长，用户表达"答得吃力"算法却以为答得不错
                        AnswerButton(
                            label = "困难",
                            color = Warning,
                            enabled = !isSubmitting,
                            onClick = { onAnswer(currentIndex, 2) },
                            modifier = Modifier.weight(1f),
                        )
                        AnswerButton(
                            label = "一般",
                            color = Info,
                            enabled = !isSubmitting,
                            onClick = { onAnswer(currentIndex, 4) },
                            modifier = Modifier.weight(1f),
                        )
                        AnswerButton(
                            label = "完美",
                            color = Success,
                            enabled = !isSubmitting,
                            onClick = { onAnswer(currentIndex, 5) },
                            modifier = Modifier.weight(1f),
                        )
                    }
                    Text(
                        "也可以左右滑动卡片：左滑「忘了」/ 右滑「完美」",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.align(Alignment.CenterHorizontally),
                    )
                }
            }
        }
    }
}

@Composable
private fun AnswerButton(
    label: String,
    color: androidx.compose.ui.graphics.Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.height(52.dp),
        shape = RoundedCornerShape(8.dp),
        colors = ButtonDefaults.buttonColors(containerColor = color),
    ) {
        Text(label, fontSize = 13.sp)
    }
}
