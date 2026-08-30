package com.eareyereading.receiver

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.eareyereading.MainActivity
import com.eareyereading.R
import com.eareyereading.util.NotificationHelper
import com.eareyereading.util.ReminderPrefs

/**
 * 接收闹钟广播，发送复习提醒通知
 */
class NotificationReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        // 用户在设置里关闭了提醒：终止每日提醒链，
        // 不再"发不出通知却永远重排明天的闹钟"
        if (!ReminderPrefs.isEnabled(context)) {
            Log.d(TAG, "Reminders disabled by user, stop reminder chain")
            return
        }

        // 系统通知权限是瞬态状态（13+ 未授权 / 用户随时可撤销再授予）：
        // 只跳过本次展示，但仍重排明天的闹钟——否则一旦某次触发时恰好没权限，
        // 提醒链永久死掉，重新授权也救不回来，只能去拨设置开关
        val canShow = NotificationManagerCompat.from(context).areNotificationsEnabled()
        if (canShow) {
            val pendingIntent = PendingIntent.getActivity(
                context,
                0,
                Intent(context, MainActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                },
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )

            val notification = NotificationCompat.Builder(context, NotificationHelper.CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle("📚 复习提醒")
                .setContentText("今天还有待复习的单词，点击查看 →")
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .setAutoCancel(true)
                .setContentIntent(pendingIntent)
                .build()

            // 权限撤销竞态防护：上面的 areNotificationsEnabled 检查通过后
            // 权限仍可能被撤销（13+），裸 notify 会抛 SecurityException 崩掉
            // Receiver。走 NotificationManagerCompat + catch 兜底
            try {
                NotificationManagerCompat.from(context)
                    .notify(NotificationHelper.NOTIFICATION_ID, notification)
            } catch (e: SecurityException) {
                Log.w(TAG, "Notification permission revoked, skip showing", e)
            }
        } else {
            Log.d(TAG, "System notifications disabled, skip showing but keep the chain alive")
        }

        // 重新调度明天的提醒
        // P0 修复: 即使 catch 住也记一条 warn,方便排查"用户没收到次日提醒"问题
        try {
            val helper = NotificationHelper(context)
            helper.scheduleReviewReminder()
        } catch (e: Exception) {
            Log.w(TAG, "Failed to schedule next-day reminder", e)
        }
    }

    private companion object {
        const val TAG = "NotificationReceiver"
    }
}
