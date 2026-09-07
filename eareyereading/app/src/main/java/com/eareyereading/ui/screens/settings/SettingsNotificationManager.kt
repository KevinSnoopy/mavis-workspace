package com.eareyereading.ui.screens.settings

import android.content.Context
import com.eareyereading.domain.repository.SettingsRepository
import com.eareyereading.util.NotificationHelper
import com.eareyereading.util.NotificationService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * 通知配置管理：复习提醒总开关、TTS 下载进度/完成通知开关。
 *
 * 从 [SettingsViewModel] 抽出的单一职责类（SRP）：通知偏好持久化、
 * Channel 重要性重建与闹钟调度集中在本类。
 *
 * @param scope ViewModel 协程作用域
 * @param settingsRepository 设置仓库（DataStore 持久化）
 * @param notificationService 通知服务（Channel 重建）
 * @param context 应用上下文（构造 NotificationHelper）
 * @param uiState UI 状态流
 */
internal class SettingsNotificationManager(
    private val scope: CoroutineScope,
    private val settingsRepository: SettingsRepository,
    private val notificationService: NotificationService,
    context: Context,
    private val uiState: MutableStateFlow<SettingsUiState>,
) {
    private val notificationHelper = NotificationHelper(context)

    fun setNotifications(enabled: Boolean) {
        scope.launch {
            settingsRepository.setNotifications(enabled)
            // channel 重要性随开关重建（关闭→静默）
            notificationService.rebuildReviewReminderChannel(enabled)
            if (enabled) {
                notificationHelper.scheduleReviewReminder()
            } else {
                notificationHelper.cancelReminder()
            }
        }
    }

    fun setNotificationDownloadProgress(enabled: Boolean) {
        scope.launch {
            settingsRepository.setNotificationDownloadProgress(enabled)
            notificationService.rebuildTtsDownloadChannel(enabled)
        }
    }

    fun setNotificationDownloadComplete(enabled: Boolean) {
        scope.launch {
            settingsRepository.setNotificationDownloadComplete(enabled)
            notificationService.rebuildTtsCompleteChannel(enabled)
        }
    }

    /** 重置到默认后补排闹钟（默认值是"开启提醒"）。 */
    fun scheduleReviewReminder() {
        notificationHelper.scheduleReviewReminder()
    }
}
