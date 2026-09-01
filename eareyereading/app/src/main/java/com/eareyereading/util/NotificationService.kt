package com.eareyereading.util

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.eareyereading.MainActivity
import com.eareyereading.R
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 统一通知入口：集中管理 Channel 分类（Android 8+）与通知发送。
 *
 * 复习提醒、TTS 下载进度/完成等业务方都走此服务，避免通知 id / channel
 * 常量与发送逻辑散落多处（issue 5.2）。非前台服务，无需 <service> 声明。
 */
@Singleton
class NotificationService @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    companion object {
        // ── Channel（Android 8+）──
        const val CHANNEL_REVIEW_REMINDER = "eareye_review_reminder"
        const val CHANNEL_TTS_DOWNLOAD = "eareye_tts_download"
        const val CHANNEL_TTS_COMPLETE = "eareye_tts_complete"

        // ── Notification id ──
        const val NOTIFICATION_ID_REVIEW_REMINDER = 1001
        const val NOTIFICATION_ID_TTS_DOWNLOAD = 2001

        private const val TAG = "NotificationService"
    }

    private val notificationManagerCompat: NotificationManagerCompat by lazy {
        NotificationManagerCompat.from(context)
    }

    private fun notificationsEnabled(): Boolean =
        notificationManagerCompat.areNotificationsEnabled()

    private fun channelName(id: String): String = when (id) {
        CHANNEL_REVIEW_REMINDER -> "复习提醒"
        CHANNEL_TTS_DOWNLOAD -> "语音模型下载"
        else -> "语音模型下载完成"
    }

    private fun buildChannel(id: String, importance: Int): NotificationChannel =
        NotificationChannel(id, channelName(id), importance).apply {
            when (id) {
                CHANNEL_REVIEW_REMINDER -> {
                    description = "每日复习提醒，帮助你保持学习连胜"
                    enableVibration(true)
                }
                CHANNEL_TTS_DOWNLOAD -> setShowBadge(false)
                else -> description = "语音模型下载完成提醒"
            }
        }

    /** Android 8+ 幂等建 Channel：已存在则跳过。 */
    private fun ensureChannel(id: String, importance: Int) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
        if (nm.getNotificationChannel(id) != null) return
        nm.createNotificationChannel(buildChannel(id, importance))
    }

    /**
     * 设置页"通知偏好"切换后重建 Channel：Android 8+ 不允许原地修改重要性，
     * 必须先删除后重建才能让新的重要性生效。
     */
    fun rebuildChannel(id: String, importance: Int) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
        val existing = nm.getNotificationChannel(id)
        if (existing != null && existing.importance != importance) {
            nm.deleteNotificationChannel(id)
        }
        if (nm.getNotificationChannel(id) == null) {
            nm.createNotificationChannel(buildChannel(id, importance))
        }
    }

    // ── 复习提醒 ─────────────────────────────────────────

    /** 复习提醒入口（NotificationReceiver 用）。 */
    fun showReviewReminder() {
        ensureChannel(CHANNEL_REVIEW_REMINDER, NotificationManager.IMPORTANCE_DEFAULT)
        val notification = NotificationCompat.Builder(context, CHANNEL_REVIEW_REMINDER)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("📚 复习提醒")
            .setContentText("今天还有待复习的单词，点击查看 →")
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .setContentIntent(mainActivityPendingIntent())
            .build()
        // 权限撤销竞态防护：Android 13+ 通知权限可能在检查后被撤销，
        // 裸 notify 会抛 SecurityException 崩掉 Receiver。走 NotificationManagerCompat + catch 兜底
        try {
            notificationManagerCompat.notify(NOTIFICATION_ID_REVIEW_REMINDER, notification)
        } catch (e: SecurityException) {
            Log.w(TAG, "Notification permission revoked, skip showing review reminder", e)
        }
    }

    // ── TTS 下载通知 ─────────────────────────────────────

    /** 显示/更新下载进度通知。progress 0..1，null 表示不确定。 */
    fun showTtsDownloadProgress(percent: Float?, contentText: String) {
        if (!notificationsEnabled()) return
        ensureChannel(CHANNEL_TTS_DOWNLOAD, NotificationManager.IMPORTANCE_LOW)
        val builder = NotificationCompat.Builder(context, CHANNEL_TTS_DOWNLOAD)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("下载内置语音模型")
            .setContentText(contentText)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(mainActivityPendingIntent())
        if (percent != null) {
            builder.setProgress(100, (percent * 100).toInt(), false)
        } else {
            builder.setProgress(0, 0, true)
        }
        notificationManagerCompat.notify(NOTIFICATION_ID_TTS_DOWNLOAD, builder.build())
    }

    /**
     * 下载成功后的收尾通知：替换掉 ongoing 的进度通知。
     * 保证可划掉并结束进度通知的常驻状态。
     */
    fun showTtsDownloadComplete(contentText: String) {
        if (!notificationsEnabled()) {
            // 无权限：收尾通知发不出，但至少把 ongoing 进度通知残留清掉
            cancelTtsDownloadNotification()
            return
        }
        ensureChannel(CHANNEL_TTS_COMPLETE, NotificationManager.IMPORTANCE_DEFAULT)
        val notification = NotificationCompat.Builder(context, CHANNEL_TTS_COMPLETE)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle("内置语音模型")
            .setContentText(contentText)
            .setOngoing(false)
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(mainActivityPendingIntent())
            .build()
        notificationManagerCompat.notify(NOTIFICATION_ID_TTS_DOWNLOAD, notification)
    }

    /** 取消下载通知。 */
    fun cancelTtsDownloadNotification() {
        notificationManagerCompat.cancel(NOTIFICATION_ID_TTS_DOWNLOAD)
    }

    /** 取消本应用全部通知。 */
    fun cancelAll() {
        notificationManagerCompat.cancelAll()
    }

    // ── 设置页"通知偏好"用：按开关重建 Channel 重要性 ────────
    // 关闭时降级为 IMPORTANCE_NONE（静默），开启时恢复正常重要性

    fun rebuildReviewReminderChannel(enabled: Boolean) {
        rebuildChannel(
            CHANNEL_REVIEW_REMINDER,
            if (enabled) NotificationManager.IMPORTANCE_DEFAULT else NotificationManager.IMPORTANCE_NONE,
        )
    }

    fun rebuildTtsDownloadChannel(enabled: Boolean) {
        rebuildChannel(
            CHANNEL_TTS_DOWNLOAD,
            if (enabled) NotificationManager.IMPORTANCE_LOW else NotificationManager.IMPORTANCE_NONE,
        )
    }

    fun rebuildTtsCompleteChannel(enabled: Boolean) {
        rebuildChannel(
            CHANNEL_TTS_COMPLETE,
            if (enabled) NotificationManager.IMPORTANCE_DEFAULT else NotificationManager.IMPORTANCE_NONE,
        )
    }

    /** NotificationHelper 兜底建复习提醒 Channel（保持既有调用语义）。 */
    fun ensureReviewReminderChannel() {
        ensureChannel(CHANNEL_REVIEW_REMINDER, NotificationManager.IMPORTANCE_DEFAULT)
    }

    private fun mainActivityPendingIntent(): PendingIntent =
        PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
}