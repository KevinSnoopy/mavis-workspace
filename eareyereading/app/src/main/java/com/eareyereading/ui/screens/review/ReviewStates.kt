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
import com.eareyereading.ui.components.EmptyState
import com.eareyereading.ui.theme.*

/**
 * 空复习状态：没有待复习卡片时展示"去阅读攒生词"出口，形成学习闭环。
 *
 * 副文案补上了闭环链路说明 —— 此前只写「今日复习已完成」，用户看不出来
 * 生词是从哪来的、什么时候会再出现，这个页面读起来像功能坏了。
 */
@Composable
internal fun EmptyReviewView(onGoReading: () -> Unit) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        EmptyState(
            icon = Icons.Default.CheckCircle,
            title = "今日复习已完成",
            // 说清"生词怎么来的、什么时候回来"，而不是只报一句状态
            subtitle = "生词来自阅读时点开的词。按遗忘曲线排期，到点会自动回到这里。",
            tint = Success,
            actionLabel = "去阅读攒生词",
            actionIcon = Icons.Default.MenuBook,
            onAction = onGoReading,
            // 满屏居中时文案会一路顶到屏幕两侧：留出边距让它是一个块，
            // 而不是横贯整屏的一行字
            modifier = Modifier.padding(horizontal = 32.dp),
        )
    }
}

/**
 * 加载失败状态：与"全部完成"可区分，并提供重试入口。
 *
 * 与 [EmptyReviewView] 共用 [EmptyState]，只换图标色与文案 ——
 * 两种都是"内容为空"，不该长出两套视觉。
 */
@Composable
internal fun ErrorReviewView(
    message: String?,
    onRetry: () -> Unit,
    onBack: () -> Unit,
) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            EmptyState(
                icon = Icons.Default.Warning,
                title = message ?: "加载失败",
                subtitle = "已复习的记录不会丢失，重试即可继续。",
                tint = Error,
                actionLabel = "重试",
                actionIcon = Icons.Default.Refresh,
                onAction = onRetry,
                modifier = Modifier.padding(horizontal = 32.dp),
            )
            Spacer(modifier = Modifier.height(4.dp))
            TextButton(onClick = onBack) { Text("返回") }
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
