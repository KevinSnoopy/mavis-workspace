package com.eareyereading

import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.core.content.ContextCompat
import com.eareyereading.domain.repository.SettingsRepository
import com.eareyereading.util.NotificationHelper
import com.eareyereading.util.ReminderPrefs
import com.eareyereading.util.TranslationHelper
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject

@HiltAndroidApp
class EareyeReadingApp : Application() {

    @Inject
    lateinit var notificationHelper: NotificationHelper

    @Inject
    lateinit var settingsRepository: SettingsRepository

    @Inject
    lateinit var translationHelper: TranslationHelper

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        // 初始化通知渠道
        notificationHelper.createNotificationChannel()
        // DataStore 首读是磁盘 IO：此前 runBlocking 卡在主线程，
        // 冷启动触发 StrictMode DiskReadViolation、白屏可感知（issue 6.1）
        appScope.launch { syncReminderPrefsMirror() }
        // issue 6.2：ACTION_TIME_SET / ACTION_TIMEZONE_CHANGED 是系统受保护广播，
        // Android 5+ 不再投递给 manifest 静态注册的接收器，只有动态注册能收到。
        // 改在 Application.onCreate 动态注册，跨时区/手动改系统时间后提醒闹钟重新调度。
        registerTimeSyncReceiver()
    }

    /**
     * 动态注册时间/时区变化接收器（复用 BootReceiver 的重排逻辑）。
     * 仅接收系统广播，需 RECEIVER_EXPORTED；ContextCompat 会按
     * API 版本自动选择正确标志，避免 13+ 的 RECEIVER_NOT_EXPORTED/EXPORTED 强制要求。
     */
    private fun registerTimeSyncReceiver() {
        try {
            val filter = IntentFilter().apply {
                addAction(Intent.ACTION_TIME_CHANGED)
                addAction(Intent.ACTION_TIMEZONE_CHANGED)
            }
            val receiver: BroadcastReceiver = com.eareyereading.receiver.BootReceiver()
            ContextCompat.registerReceiver(
                this,
                receiver,
                filter,
                ContextCompat.RECEIVER_EXPORTED,
            )
        } catch (e: Exception) {
            android.util.Log.w("EareyeReadingApp", "register time receiver failed", e)
        }
    }

    /**
     * 把 DataStore 里的通知开关同步到 Receiver 用的同步镜像。
     * BroadcastReceiver 无法挂起读 DataStore，而镜像缺省为 true：
     * 不在启动时对账，开关已关闭的存量用户升级后会被当作开启状态重排闹钟。
     * 失败时保持镜像原值。
     *
     * 同时承担提醒链的自愈责任：
     * - 新装设备从不重启时，没有任何路径会排上首个闹钟（开关显示开启
     *   却永远不提醒）；启动时对账即补排。
     * - 进程被强杀会丢掉一次性精确闹钟，下次启动在这里复活提醒链。
     * scheduleReviewReminder 用 FLAG_UPDATE_CURRENT，重复调用幂等。
     */
    private suspend fun syncReminderPrefsMirror() {
        try {
            val enabled = withTimeoutOrNull(2_000) { settingsRepository.getNotifications().first() }
            if (enabled != null) {
                ReminderPrefs.setEnabled(this, enabled)
                if (enabled) {
                    notificationHelper.scheduleReviewReminder()
                } else {
                    // 开关关闭：确保没有遗留的闹钟在跑
                    notificationHelper.cancelReminder()
                }
            }
        } catch (e: Exception) {
            android.util.Log.w("EareyeReadingApp", "sync reminder prefs failed", e)
        }
    }

    override fun onTerminate() {
        // issue 8.2：兜底释放 ML Kit Translator native 资源
        // （onTerminate 在真机上很少回调，MainActivity.onDestroy 是主路径）
        try {
            translationHelper.close()
        } catch (e: Exception) {
            android.util.Log.w("EareyeReadingApp", "close translationHelper failed", e)
        }
        appScope.cancel()
        super.onTerminate()
    }
}
