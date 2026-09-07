package com.eareyereading.ui.screens.home

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Replay
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.eareyereading.ui.theme.Warning

// ── 复习提醒 ─────────────────────────────────────
@Composable
internal fun ReviewReminderBanner(
    count: Int,
    onReview: () -> Unit,
    showEnableNotifications: Boolean,
    onEnableNotifications: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = Warning.copy(alpha = 0.12f),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .clip(CircleShape)
                        .background(Warning.copy(alpha = 0.2f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.Default.Replay, null, tint = Warning, modifier = Modifier.size(22.dp))
                }
                Spacer(modifier = Modifier.width(14.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        "待复习 $count 个生词",
                        style = androidx.compose.material3.MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = Warning,
                    )
                    Text(
                        "基于 SM-2 遗忘曲线，科学安排复习时间",
                        style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
                        color = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = Warning.copy(alpha = 0.15f),
                    onClick = onReview,
                ) {
                    Box(
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text("去复习", style = androidx.compose.material3.MaterialTheme.typography.labelMedium, color = Warning.copy(alpha = 0.9f))
                    }
                }
            }
            // issue 5.1：到期卡存在且未授权时，提供"开启通知"入口
            if (showEnableNotifications) {
                Spacer(modifier = Modifier.height(12.dp))
                OutlinedButton(
                    onClick = onEnableNotifications,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(10.dp),
                ) {
                    Icon(
                        Icons.Default.Notifications,
                        contentDescription = null,
                        tint = Warning,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("开启每日复习提醒", color = Warning, style = androidx.compose.material3.MaterialTheme.typography.labelLarge)
                }
            }
        }
    }
}
