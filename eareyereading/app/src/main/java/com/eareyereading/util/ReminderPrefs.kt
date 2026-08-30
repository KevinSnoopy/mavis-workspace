package com.eareyereading.util

import android.content.Context

/**
 * 复习提醒开关的同步镜像。
 *
 * 设置本体存在 DataStore（只有异步 Flow 接口），但 BroadcastReceiver
 * （开机/时区变化/提醒触发）运行在受限窗口内，无法安全挂起等待，
 * 需要同步读到"用户是否开启了提醒"。这里用 SharedPreferences 做镜像：
 * - 写入点：SettingsRepositoryImpl.setNotifications / clearAll（与 DataStore 同步更新）
 * - 读取点：BootReceiver / NotificationReceiver
 * 默认 true，与 DataStore 侧 getNotifications 的默认值保持一致。
 */
object ReminderPrefs {
    private const val PREFS_NAME = "reminder_prefs"
    private const val KEY_NOTIFICATIONS_ENABLED = "notifications_enabled"

    fun isEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_NOTIFICATIONS_ENABLED, true)

    fun setEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_NOTIFICATIONS_ENABLED, enabled).apply()
    }
}
