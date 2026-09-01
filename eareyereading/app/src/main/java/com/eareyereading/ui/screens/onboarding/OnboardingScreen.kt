package com.eareyereading.ui.screens.onboarding

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.eareyereading.util.notificationPermissionGranted
import com.eareyereading.util.rememberNotificationPermissionRequester

/**
 * issue 5.1：轻量首启引导页（首次启动展示一次）。
 *
 * 此前通知权限只有设置页拨开关时会申请，首启没有任何入口，用户到点通知被系统静默吞。
 * 本页提供一个大按钮"开启通知"，授权通过（或已在别处授权 / API<33 无此权限）后进入主界面；
 * 也允许"暂时跳过"。已授权时直接回调 onDone，不重复弹框。
 *
 * @param onDone 用户完成引导（授权 / 跳过）后进入主界面。
 */
@Composable
fun FirstLaunchOnboarding(
    onDone: () -> Unit,
) {
    val context = LocalContext.current
    // 已授权（或旧版本无需权限）时直接进入主界面，不再停留在引导页
    val requestNotifications = rememberNotificationPermissionRequester(onGranted = onDone)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .padding(horizontal = 28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Box(
            modifier = Modifier
                .size(96.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.Default.Notifications,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(44.dp),
            )
        }
        Spacer(modifier = Modifier.height(28.dp))
        Text(
            "开启通知，不错过每日复习",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
        )
        Spacer(modifier = Modifier.height(12.dp))
        Text(
            "基于 SM-2 遗忘曲线安排每日复习提醒，到点通知你回炉巩固生词。",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(modifier = Modifier.height(36.dp))
        Button(
            onClick = {
                if (notificationPermissionGranted(context)) onDone() else requestNotifications()
            },
            contentPadding = PaddingValues(horizontal = 28.dp, vertical = 14.dp),
        ) {
            Icon(Icons.Default.Notifications, contentDescription = null, modifier = Modifier.size(20.dp))
            Spacer(modifier = Modifier.width(8.dp))
            Text("开启通知", fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
        }
        Spacer(modifier = Modifier.height(10.dp))
        TextButton(onClick = onDone) {
            Text("暂时跳过")
        }
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            "稍后可在「设置 → 通知」中随时开启",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
        )
    }
}