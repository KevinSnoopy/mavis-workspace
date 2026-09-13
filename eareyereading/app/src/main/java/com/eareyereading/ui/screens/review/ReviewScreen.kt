package com.eareyereading.ui.screens.review

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.eareyereading.ui.components.AppTopBar
import com.eareyereading.ui.theme.*
import com.eareyereading.util.notificationPermissionGranted
import com.eareyereading.util.rememberNotificationPermissionRequester

/**
 * 复习主屏：待复习列表与通知权限入口。
 *
 * 一级 Tab（底部导航「复习」）：顶栏不带返回箭头——它是导航图的顶层目的地，
 * 无栈可弹。页面内容区的动作显式声明目的地：
 * [onBack] = 回首页（错误态/完成页的"返回"），[onGoReading] = 去书库攒生词。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReviewScreen(
    onBack: () -> Unit,
    onGoReading: () -> Unit = onBack,
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
        contentWindowInsets = WindowInsets(0),
        topBar = {
            AppTopBar(title = "复习") {
                if (dueCount > 0) {
                    Badge(
                        containerColor = Warning,
                        modifier = Modifier.padding(end = 8.dp),
                    ) {
                        Text("$dueCount")
                    }
                }
            }
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
                    // 没有待复习：给"去阅读攒生词"出口，形成学习闭环
                    //（旧实现只有一句贺词，页面无任何下一步）
                    EmptyReviewView(onGoReading = onGoReading)
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
