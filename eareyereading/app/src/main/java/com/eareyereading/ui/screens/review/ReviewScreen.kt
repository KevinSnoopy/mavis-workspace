package com.eareyereading.ui.screens.review

import androidx.compose.animation.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.eareyereading.ui.screens.review.ReviewViewModel
import com.eareyereading.ui.theme.*
import com.eareyereading.util.notificationPermissionGranted
import com.eareyereading.util.rememberNotificationPermissionRequester
import androidx.compose.ui.platform.LocalContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReviewScreen(
    onBack: () -> Unit,
    viewModel: ReviewViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsState()
    val dueCount by viewModel.dueCount.collectAsState()

    // issue 5.1：复习完成页也作为通知权限申请入口（此前只有设置页能申请）。
    // 已授权则不展示该入口。
    val context = LocalContext.current
    val requestNotifications = rememberNotificationPermissionRequester()
    val onEnableNotifications = if (!notificationPermissionGranted(context)) requestNotifications else null

    LaunchedEffect(Unit) {
        viewModel.loadDueReviews()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        "复习",
                        style = MaterialTheme.typography.headlineLarge,
                        fontWeight = FontWeight.Bold,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, "返回")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                ),
                actions = {
                    if (dueCount > 0) {
                        Badge(
                            containerColor = Warning,
                            modifier = Modifier.padding(end = 8.dp),
                        ) {
                            Text("$dueCount")
                        }
                    }
                },
            )
        },
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            when {
                uiState.errorMessage != null && uiState.dueCards.isEmpty() -> {
                    // 加载失败必须与"全部完成"可区分，并提供重试入口
                    ErrorReviewView(
                        message = uiState.errorMessage,
                        onRetry = viewModel::loadDueReviews,
                        onBack = onBack,
                    )
                }
                uiState.isSessionComplete && uiState.totalReviewed == 0 -> {
                    // 没有待复习
                    EmptyReviewView()
                }
                uiState.isSessionComplete -> {
                    // 复习完成总结
                    SessionSummaryView(
                        totalReviewed = uiState.totalReviewed,
                        correctCount = uiState.correctCount,
                        // issue 11.4：loadDueReviews 一次只拉 50 张，仍有剩余待复习
                        // 卡片时必须提示，否则用户以为全部复习完了
                        remainingDue = (dueCount - uiState.totalReviewed).coerceAtLeast(0),
                        onRestart = viewModel::restartSession,
                        onBack = onBack,
                        onEnableNotifications = onEnableNotifications,
                    )
                }
                else -> {
                    // 复习卡片
                    val currentCard = uiState.dueCards.getOrNull(uiState.currentIndex)
                    if (currentCard != null) {
                        ReviewCardView(
                            card = currentCard,
                            currentIndex = uiState.currentIndex,
                            totalCards = uiState.dueCards.size,
                            isShowingAnswer = uiState.isShowingAnswer,
                            isSubmitting = uiState.isSubmitting,
                            errorMessage = uiState.errorMessage,
                            onReveal = viewModel::revealAnswer,
                            onAnswer = viewModel::answerCard,
                            onDismissError = viewModel::clearError,
                        )
                    } else if (uiState.errorMessage == null) {
                        // issue 11.10：dueCards 未加载完（初始空表 + 未完成 +
                        // 无错误）时 currentCard 为 null——此前该分支渲染空白屏
                        Box(
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center,
                        ) {
                            CircularProgressIndicator()
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun EmptyReviewView() {
    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            Icons.Default.CheckCircle,
            contentDescription = null,
            modifier = Modifier.size(80.dp),
            tint = Success,
        )
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            "太棒了！",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            "今日复习已完成",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun ErrorReviewView(
    message: String?,
    onRetry: () -> Unit,
    onBack: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            Icons.Default.Warning,
            contentDescription = null,
            modifier = Modifier.size(80.dp),
            tint = Error,
        )
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            message ?: "加载失败",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.height(24.dp))
        Button(onClick = onRetry) {
            Icon(Icons.Default.Refresh, null)
            Spacer(modifier = Modifier.width(8.dp))
            Text("重试")
        }
        Spacer(modifier = Modifier.height(12.dp))
        OutlinedButton(onClick = onBack) {
            Text("返回")
        }
    }
}

@Composable
private fun SessionSummaryView(
    totalReviewed: Int,
    correctCount: Int,
    remainingDue: Int = 0,
    onRestart: () -> Unit,
    onBack: () -> Unit,
    // issue 5.1：非空时展示"开启每日复习提醒"入口（未授权通知权限时）
    onEnableNotifications: (() -> Unit)? = null,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        val accuracy = if (totalReviewed > 0) (correctCount * 100 / totalReviewed) else 0

        Icon(
            Icons.Default.EmojiEvents,
            contentDescription = null,
            modifier = Modifier.size(80.dp),
            tint = if (accuracy >= 70) Success else Warning,
        )
        Spacer(modifier = Modifier.height(24.dp))
        Text(
            "复习完成！",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
        )
        Spacer(modifier = Modifier.height(16.dp))

        Row(
            horizontalArrangement = Arrangement.spacedBy(32.dp),
        ) {
            StatItem(label = "复习数", value = "$totalReviewed")
            StatItem(label = "正确率", value = "$accuracy%")
        }

        // issue 11.4：本轮只拉了最多 50 张，剩更多时如实告知，避免"以为复习完了"
        if (remainingDue > 0) {
            Spacer(modifier = Modifier.height(16.dp))
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = Warning.copy(alpha = 0.12f),
            ) {
                Text(
                    "还有 $remainingDue 张待复习",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Warning,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }
        }

        Spacer(modifier = Modifier.height(32.dp))

        // issue 5.1：复习完成时若还没授予通知权限，这里给出开启入口
        if (onEnableNotifications != null) {
            Button(onClick = onEnableNotifications) {
                Icon(Icons.Default.Notifications, null)
                Spacer(modifier = Modifier.width(8.dp))
                Text("开启每日复习提醒")
            }
            Spacer(modifier = Modifier.height(12.dp))
        }

        FilledTonalButton(onClick = onRestart) {
            Icon(Icons.Default.Refresh, null)
            Spacer(modifier = Modifier.width(8.dp))
            Text(if (remainingDue > 0) "继续复习" else "再复习一轮")
        }
        Spacer(modifier = Modifier.height(12.dp))
        OutlinedButton(onClick = onBack) {
            Text("返回")
        }
    }
}

@Composable
private fun StatItem(label: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            value,
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            color = Primary,
        )
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun ReviewCardView(
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

        // 单词卡片
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant,
            ),
        ) {
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
                Spacer(modifier = Modifier.height(16.dp))

                AnimatedVisibility(
                    visible = isShowingAnswer,
                    enter = fadeIn() + expandVertically(),
                    exit = fadeOut() + shrinkVertically(),
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
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
            }
        }

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
                    onClick = onReveal,
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
