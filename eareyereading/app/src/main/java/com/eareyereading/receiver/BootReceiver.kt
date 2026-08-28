package com.eareyereading.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.eareyereading.util.NotificationHelper

/**
 * 设备启动后重新调度复习提醒闹钟
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED ||
            intent.action == "android.intent.action.QUICKBOOT_POWERON") {
            try {
                val helper = NotificationHelper(context)
                helper.scheduleReviewReminder()
            } catch (_: Exception) { }
        }
    }
}
