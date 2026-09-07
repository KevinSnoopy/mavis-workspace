package com.eareyereading.ui.screens.settings

import android.content.Context
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Divider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.eareyereading.ui.theme.Accent
import com.eareyereading.ui.theme.OnSurfaceTertiary
import com.eareyereading.ui.theme.Primary
import com.eareyereading.ui.theme.SuccessBg
import com.eareyereading.ui.theme.SurfaceHover
import kotlinx.coroutines.launch

/**
 * 设置页「通知偏好」分区：复习提醒、下载进度/完成提醒开关与系统通知设置跳转。
 *
 * 从 [SettingsScreen] 按 SRP 抽出：本分区只负责通知偏好展示与权限交互，
 * 状态由 [SettingsViewModel] 统一管理。
 */
@Composable
internal fun SettingsNotificationSection(
    notifications: Boolean,
    notificationDownloadProgress: Boolean,
    notificationDownloadComplete: Boolean,
    context: Context,
    snackbarHostState: SnackbarHostState,
    notificationPermissionLauncher: androidx.activity.compose.ManagedActivityResultLauncher<String, Boolean>,
    onSetNotifications: (Boolean) -> Unit,
    onSetNotificationDownloadProgress: (Boolean) -> Unit,
    onSetNotificationDownloadComplete: (Boolean) -> Unit,
) {
    val scope = androidx.compose.runtime.rememberCoroutineScope()

    SettingsSectionTitle("通知偏好")
    SettingsListCard {
        SettingRowToggle(
            icon = Icons.Default.Notifications,
            iconBg = SuccessBg,
            iconColor = Accent,
            title = "复习提醒",
            checked = notifications,
            onCheckedChange = { enabled ->
                if (enabled) {
                    val granted = android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.TIRAMISU ||
                        context.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) ==
                            android.content.pm.PackageManager.PERMISSION_GRANTED
                    if (!granted) {
                        // issue 5.3：区分"暂拒（可解释）"与"永久拒（don't ask again）"。
                        // 永久拒后再 launch 也只会被系统路由到设置页，不如直接跳系统通知设置
                        val activity = context as? android.app.Activity
                        val rationale = activity != null &&
                            androidx.core.app.ActivityCompat.shouldShowRequestPermissionRationale(
                                activity, android.Manifest.permission.POST_NOTIFICATIONS,
                            )
                        if (rationale) {
                            scope.launch {
                                snackbarHostState.showSnackbar("开启通知需要授予通知权限，用于每日复习提醒")
                            }
                            notificationPermissionLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
                        } else {
                            openAppNotificationSettings(context)
                            scope.launch {
                                snackbarHostState.showSnackbar("已为你打开系统通知设置，请在设置中允许通知后重试")
                            }
                        }
                        return@SettingRowToggle
                    }
                }
                onSetNotifications(enabled)
            },
        )
        Divider(modifier = Modifier.padding(horizontal = 20.dp))
        SettingRowToggle(
            icon = Icons.Default.Download,
            iconBg = SurfaceHover,
            iconColor = Primary,
            title = "下载进度提醒",
            checked = notificationDownloadProgress,
            onCheckedChange = onSetNotificationDownloadProgress,
        )
        Divider(modifier = Modifier.padding(horizontal = 20.dp))
        SettingRowToggle(
            icon = Icons.Default.CheckCircle,
            iconBg = SuccessBg,
            iconColor = Accent,
            title = "下载完成提醒",
            checked = notificationDownloadComplete,
            onCheckedChange = onSetNotificationDownloadComplete,
        )
        Divider(modifier = Modifier.padding(horizontal = 20.dp))
        SettingRowClickable(
            icon = Icons.Default.Settings,
            iconBg = SurfaceHover,
            iconColor = OnSurfaceTertiary,
            title = "去系统通知设置",
            subtitle = "管理应用的通知权限与分类",
            onClick = { openAppNotificationSettings(context) },
        )
    }
    Spacer(modifier = Modifier.height(20.dp))
}

/**
 * issue 5.3：跳转到系统通知设置页（POST_NOTIFICATIONS 被"don't ask again"永久拒后，
 * 应用内再弹权限框也只会被系统静默路由，唯一的恢复入口就是系统设置）。
 */
internal fun openAppNotificationSettings(context: Context) {
    try {
        val intent = android.content.Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
            putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, context.packageName)
            addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    } catch (e: Exception) {
        android.util.Log.w("SettingsScreen", "open notification settings failed", e)
    }
}
