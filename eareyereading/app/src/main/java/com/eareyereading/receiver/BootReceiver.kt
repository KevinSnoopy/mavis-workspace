package com.eareyereading.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.eareyereading.util.NotificationHelper
import com.eareyereading.util.ReminderPrefs

/**
 * 设备启动 / 时区或时间变化后重新调度复习提醒闹钟。
 *
 * 精确闹钟不跨重启保留；跨时区后绝对时间也会错位，
 * 所以 TIMEZONE_CHANGED / TIME_SET 同样需要重排。
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val relevant = intent.action == Intent.ACTION_BOOT_COMPLETED ||
            intent.action == "android.intent.action.QUICKBOOT_POWERON" ||
            intent.action == Intent.ACTION_TIMEZONE_CHANGED ||
            intent.action == Intent.ACTION_TIME_CHANGED
        if (!relevant) return

        // 用户在设置里关闭提醒时不再私自重排闹钟
        // （DataStore 无法在广播窗口内同步读取，见 ReminderPrefs 说明）
        if (!ReminderPrefs.isEnabled(context)) {
            android.util.Log.d("BootReceiver", "Reminders disabled by user, skip reschedule")
            return
        }
        try {
            val helper = NotificationHelper(context)
            helper.scheduleReviewReminder()
        } catch (e: Exception) { android.util.Log.w("BootReceiver", "scheduleReviewReminder failed", e) }
    }
}
