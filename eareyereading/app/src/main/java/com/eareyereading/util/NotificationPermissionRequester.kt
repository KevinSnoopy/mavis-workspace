package com.eareyereading.util

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.core.app.ActivityCompat

/**
 * issue 5.1：统一封装"申请通知权限"的入口。
 *
 * 此前通知权限只有设置页拨开关时会申请，首启/复习完成/阅读完成等场景没有任何入口，
 * 导致用户到点通知被系统静默吞掉。本函数抽取出可复用的"点击申请"逻辑，供
 * 复习完成 / 主页有到期卡 / 阅读完成等多个场景接入，与设置页同款分流：
 *   已授权           → 直接回调 onGranted()；
 *   暂拒（可解释）   → Toast 说明后再 launch 一次；
 *   永久拒（已勾选 don't ask again）→ 无法再弹框，直接跳系统通知设置并 Toast 提示。
 *
 * @param onGranted 授权成功（或原本已授权）后的回调；未授权时函数自动分流请求。
 * @return 一个可挂到任意 onClick 的"申请通知权限"处理器。
 */
@Composable
fun rememberNotificationPermissionRequester(
    onGranted: () -> Unit = {},
): () -> Unit {
    val context = LocalContext.current
    val activity = context as? Activity
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) onGranted()
    }
    return {
        if (notificationPermissionGranted(context)) {
            onGranted()
        } else {
            val rationale = activity != null && ActivityCompat.shouldShowRequestPermissionRationale(
                activity,
                Manifest.permission.POST_NOTIFICATIONS,
            )
            if (rationale) {
                // 暂拒：解释一下为什么需要，再走系统权限框
                Toast.makeText(context, "开启通知需要授予通知权限，用于每日复习提醒", Toast.LENGTH_LONG).show()
                launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
            } else {
                // 永久拒：应用内再 launch 只会被系统静默路由，唯一入口就是系统设置
                openAppNotificationSettings(context)
                Toast.makeText(context, "已为你打开系统通知设置，请在设置中允许通知后重试", Toast.LENGTH_LONG).show()
            }
        }
    }
}

/** 当前是否已获得通知权限（Android 13+ 需要运行时权限；以下恒 true）。 */
fun notificationPermissionGranted(context: Context): Boolean =
    Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
        context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED

/** 跳转系统应用通知设置页（POST_NOTIFICATIONS 被永久拒后唯一的恢复入口）。 */
fun openAppNotificationSettings(context: Context) {
    try {
        val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
            putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    } catch (e: Exception) {
        android.util.Log.w("NotificationPermissionRequester", "open notification settings failed", e)
    }
}