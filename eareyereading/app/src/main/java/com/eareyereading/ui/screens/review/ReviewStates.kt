package com.eareyereading.ui.screens.review

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.eareyereading.ui.components.ConfettiCelebration
import com.eareyereading.ui.theme.*

/**
 * 空复习状态：没有待复习卡片时展示"去阅读攒生词"出口，形成学习闭环。
 */
@Composable
internal fun EmptyReviewView(onGoReading: () -> Unit) {
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
        Spacer(modifier = Modifier.height(20.dp))
        // 返回继续阅读：阅读中点词加生词才会进复习队列，
        // 这里指一条"攒生词"的去路，页面不留死胡同
        // （回调名点明目的地：复习是一级 Tab，无栈可弹，必须显式跳书库）
        OutlinedButton(onClick = onGoReading) {
            Icon(Icons.Default.MenuBook, null, modifier = Modifier.size(16.dp))
            Spacer(modifier = Modifier.width(6.dp))
            Text("去阅读攒生词")
        }
    }
}

/**
 * 加载失败状态：与"全部完成"可区分，并提供重试入口。
 */
@Composable
internal fun ErrorReviewView(
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

/**
 * 复习完成总结：彩带庆祝 + 统计数据 + 剩余提示 + 通知权限入口 + 操作按钮。
 */
@Composable
internal fun SessionSummaryView(
    totalReviewed: Int,
    correctCount: Int,
    remainingDue: Int = 0,
    onRestart: () -> Unit,
    onBack: () -> Unit,
    // issue 5.1：非空时展示"开启每日复习提醒"入口（未授权通知权限时）
    onEnableNotifications: (() -> Unit)? = null,
) {
    Box(modifier = Modifier.fillMaxSize()) {
        // 完成庆祝：彩带覆盖层（播放一次后自动透明，不拦截任何交互）
        ConfettiCelebration(modifier = Modifier.matchParentSize())

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
