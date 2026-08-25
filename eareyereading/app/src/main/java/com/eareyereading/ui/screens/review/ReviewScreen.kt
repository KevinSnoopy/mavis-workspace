package com.eareyereading.ui.screens.review

import androidx.compose.animation.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.eareyereading.ui.theme.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReviewScreen(
    onBack: () -> Unit,
    viewModel: ReviewViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsState()
    val dueCount by viewModel.dueCount.collectAsState()

    LaunchedEffect(Unit) {
        viewModel.loadDueReviews()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("复习") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回")
                    }
                },
                actions = {
                    if (dueCount > 0) {
                        Badge(
                            containerColor = Secondary,
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
                uiState.isSessionComplete && uiState.totalReviewed == 0 -> {
                    // 没有待复习
                    EmptyReviewView()
                }
                uiState.isSessionComplete -> {
                    // 复习完成总结
                    SessionSummaryView(
                        totalReviewed = uiState.totalReviewed,
                        correctCount = uiState.correctCount,
                        onRestart = viewModel::restartSession,
                        onBack = onBack,
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
                            onReveal = viewModel::revealAnswer,
                            onAnswer = viewModel::answerCard,
                        )
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
private fun SessionSummaryView(
    totalReviewed: Int,
    correctCount: Int,
    onRestart: () -> Unit,
    onBack: () -> Unit,
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

        Spacer(modifier = Modifier.height(32.dp))

        FilledTonalButton(onClick = onRestart) {
            Icon(Icons.Default.Refresh, null)
            Spacer(modifier = Modifier.width(8.dp))
            Text("再复习一轮")
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
    card: ReviewCard,
    currentIndex: Int,
    totalCards: Int,
    isShowingAnswer: Boolean,
    onReveal: () -> Unit,
    onAnswer: (Int) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
    ) {
        // 进度
        LinearProgressIndicator(
            progress = { (currentIndex.toFloat() / totalCards.coerceAtLeast(1)) },
            modifier = Modifier.fillMaxWidth(),
            color = Primary,
        )
        Text(
            "${currentIndex + 1} / $totalCards",
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(vertical = 4.dp),
        )

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
                        card.vocabulary?.definition?.let { def ->
                            Text(
                                text = def,
                                style = MaterialTheme.typography.bodyLarge,
                                textAlign = TextAlign.Center,
                                color = Primary,
                            )
                        }
                        card.vocabulary?.context?.let { ctx ->
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
                        AnswerButton(
                            label = "忘了",
                            color = Error,
                            onClick = { onAnswer(1) },
                            modifier = Modifier.weight(1f),
                        )
                        AnswerButton(
                            label = "困难",
                            color = Warning,
                            onClick = { onAnswer(3) },
                            modifier = Modifier.weight(1f),
                        )
                        AnswerButton(
                            label = "一般",
                            color = Info,
                            onClick = { onAnswer(4) },
                            modifier = Modifier.weight(1f),
                        )
                        AnswerButton(
                            label = "完美",
                            color = Success,
                            onClick = { onAnswer(5) },
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
) {
    Button(
        onClick = onClick,
        modifier = modifier.height(52.dp),
        shape = RoundedCornerShape(8.dp),
        colors = ButtonDefaults.buttonColors(containerColor = color),
    ) {
        Text(label, fontSize = 13.sp)
    }
}
