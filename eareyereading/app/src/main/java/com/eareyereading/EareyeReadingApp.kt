package com.eareyereading

import android.app.Application
import com.eareyereading.domain.repository.SettingsRepository
import com.eareyereading.util.NotificationHelper
import com.eareyereading.util.ReminderPrefs
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject

@HiltAndroidApp
class EareyeReadingApp : Application() {

    @Inject
    lateinit var notificationHelper: NotificationHelper

    @Inject
    lateinit var settingsRepository: SettingsRepository

    override fun onCreate() {
        super.onCreate()
        // 初始化通知渠道
        notificationHelper.createNotificationChannel()
        syncReminderPrefsMirror()
    }

    /**
     * 把 DataStore 里的通知开关同步到 Receiver 用的同步镜像。
     * BroadcastReceiver 无法挂起读 DataStore，而镜像缺省为 true：
     * 不在启动时对账，开关已关闭的存量用户升级后会被当作开启状态重排闹钟。
     * 一次性读取小文件，runBlocking 毫秒级可接受；失败时保持镜像原值。
     */
    private fun syncReminderPrefsMirror() {
        try {
            val enabled = runBlocking {
                withTimeoutOrNull(2_000) { settingsRepository.getNotifications().first() }
            }
            if (enabled != null) {
                ReminderPrefs.setEnabled(this, enabled)
            }
        } catch (e: Exception) {
            android.util.Log.w("EareyeReadingApp", "sync reminder prefs failed", e)
        }
    }
}
