package com.eareyereading.util

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import com.eareyereading.receiver.NotificationReceiver
import android.content.Intent
import android.os.Build
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 通知助手
 * 使用 AlarmManager 调度每日复习提醒
 */
@Singleton
class NotificationHelper @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    companion object {
        // Channel/NOTIFICATION_ID 复用 NotificationService 常量，避免一字多定义
        const val CHANNEL_ID = NotificationService.CHANNEL_REVIEW_REMINDER
        const val CHANNEL_NAME = "复习提醒"
        const val NOTIFICATION_ID = NotificationService.NOTIFICATION_ID_REVIEW_REMINDER
        const val REQUEST_CODE = 1001
    }

    // Channel 创建统一委派给 NotificationService，不在散落处重复建 channel
    private val notificationService: NotificationService by lazy {
        NotificationService(context)
    }

    // P1 修复: getSystemService 在系统服务被禁用/移除时返回 null(罕见但会发生,
    // 如 Device Owner 策略/企业 MDM),`as` 会抛 ClassCastException。这里改 `as?` 防御性返回 null。
    private val alarmManager: AlarmManager? by lazy {
        context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager
    }

    /**
     * 创建通知渠道（Android 8.0+），委派给 NotificationService 统一管理
     */
    fun createNotificationChannel() {
        notificationService.ensureReviewReminderChannel()
    }

    /**
     * 调度每日复习提醒（每天晚上 20:00）
     */
    fun scheduleReviewReminder() {
        createNotificationChannel()

        val intent = Intent(context, NotificationReceiver::class.java)
        val pendingIntent = PendingIntent.getBroadcast(
            context,
            REQUEST_CODE,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        // 计算下次触发时间：今天 20:00，如果已过则明天
        val calendar = java.util.Calendar.getInstance().apply {
            set(java.util.Calendar.HOUR_OF_DAY, 20)
            set(java.util.Calendar.MINUTE, 0)
            set(java.util.Calendar.SECOND, 0)
            set(java.util.Calendar.MILLISECOND, 0)
            if (timeInMillis <= System.currentTimeMillis()) {
                add(java.util.Calendar.DAY_OF_YEAR, 1)
            }
        }

        // P1 修复: alarmManager 可能为 null(见 lazy 声明的注释),早返回避免 NPE
        val am = alarmManager ?: run {
            android.util.Log.w("NotificationHelper", "AlarmManager not available, cannot schedule reminder")
            return
        }
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                if (am.canScheduleExactAlarms()) {
                    am.setExactAndAllowWhileIdle(
                        AlarmManager.RTC_WAKEUP,
                        calendar.timeInMillis,
                        pendingIntent,
                    )
                } else {
                    // 无法获取精确闹钟权限，降级为每日重复闹钟
                    am.setInexactRepeating(
                        AlarmManager.RTC_WAKEUP,
                        calendar.timeInMillis,
                        AlarmManager.INTERVAL_DAY,
                        pendingIntent,
                    )
                }
            } else {
                am.setExactAndAllowWhileIdle(
                    AlarmManager.RTC_WAKEUP,
                    calendar.timeInMillis,
                    pendingIntent,
                )
            }
            android.util.Log.d("NotificationHelper", "Reminder scheduled for ${calendar.time}")
        } catch (e: SecurityException) {
            android.util.Log.w("NotificationHelper", "Cannot schedule exact alarm: ${e.message}")
            // 降级
            try {
                am.setInexactRepeating(
                    AlarmManager.RTC_WAKEUP,
                    calendar.timeInMillis,
                    AlarmManager.INTERVAL_DAY,
                    pendingIntent,
                )
            } catch (e: Exception) {
                android.util.Log.w("NotificationHelper", "Fallback inexactRepeating also failed: ${e.message}")
            }
        }
    }

    /**
     * 取消已调度的提醒
     */
    fun cancelReminder() {
        val intent = Intent(context, NotificationReceiver::class.java)
        // FLAG_NO_CREATE：不存在时返回 null，避免为了取消反而创建/刷新 PendingIntent
        val pendingIntent = PendingIntent.getBroadcast(
            context,
            REQUEST_CODE,
            intent,
            PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE,
        )
        // P1 修复: 同上
        val am = alarmManager
        if (pendingIntent == null) {
            android.util.Log.d("NotificationHelper", "No scheduled reminder to cancel")
            return
        }
        if (am != null) {
            am.cancel(pendingIntent)
            pendingIntent.cancel()
        } else {
            android.util.Log.w("NotificationHelper", "AlarmManager not available, cannot cancel reminder")
        }
        android.util.Log.d("NotificationHelper", "Reminder cancelled")
    }
}
