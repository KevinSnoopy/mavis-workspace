package com.eareyereading.receiver

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.util.Log
import android.widget.RemoteViews
import com.eareyereading.MainActivity
import com.eareyereading.R
import com.eareyereading.data.local.dao.ReadingStatsDao
import com.eareyereading.data.local.dao.ReviewRecordDao
import com.eareyereading.util.ReadingStreak
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * 「复习速览」桌面小组件：待复习词数 + 连续打卡天数。
 * 经典 RemoteViews 实现（无新增依赖）：系统每 30 分钟拉起 onUpdate，
 * App 每次回到前台也主动触发一次刷新（[triggerUpdate]）。
 * 点击任意位置打开 App 进入复习。
 */
@AndroidEntryPoint
class ReviewWidgetProvider : AppWidgetProvider() {

    @Inject
    lateinit var reviewRecordDao: ReviewRecordDao

    @Inject
    lateinit var readingStatsDao: ReadingStatsDao

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray,
    ) {
        // 查库是挂起操作：goAsync 拿到广播处理的延期窗口，IO 协程里查完再回填
        val pendingResult = goAsync()
        // 单例 scope + 去重：旧实现每次 onUpdate new 一个无人持有、无法取消的
        // scope，30 分钟系统唤醒与回前台广播接踵而至时两组全表查询并发跑
        widgetScope.launch {
            try {
                val now = System.currentTimeMillis()
                val dueCount = reviewRecordDao.getDueReviewCountOnce(now)
                // 连胜只需去重日期：不再拉全表实体（旧行为随使用天数线性劣化）
                val streakDays = ReadingStreak.calculateFromDates(readingStatsDao.getAllDates())

                // 各实例共享同一个 PendingIntent：系统按 requestCode+intent 去重，
                // 循环内逐个构造只是重复的 Binder 往返
                val launchIntent = buildLaunchIntent(context)
                appWidgetIds.forEach { widgetId ->
                    val views = RemoteViews(context.packageName, R.layout.widget_review).apply {
                        setTextViewText(R.id.widget_due_count, "$dueCount")
                        setTextViewText(R.id.widget_streak, "连续打卡 $streakDays 天")
                        setOnClickPendingIntent(R.id.widget_root, launchIntent)
                    }
                    appWidgetManager.updateAppWidget(widgetId, views)
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                // 小组件失败绝不能崩 App 进程：保持上次内容即可
                Log.w("ReviewWidget", "widget update failed", e)
            } finally {
                pendingResult.finish()
            }
        }
    }

    private fun buildLaunchIntent(context: Context): PendingIntent =
        PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    companion object {
        /** widget 专用单例 scope：更新任务可追踪、不随每次广播重建。 */
        private val widgetScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

        /** App 回到前台 / 复习完成等时机调用：向全部实例广播一次更新。 */
        fun triggerUpdate(context: Context) {
            try {
                val ids = AppWidgetManager.getInstance(context)
                    .getAppWidgetIds(ComponentName(context, ReviewWidgetProvider::class.java))
                if (ids.isNotEmpty()) {
                    context.sendBroadcast(
                        Intent(context, ReviewWidgetProvider::class.java).apply {
                            action = AppWidgetManager.ACTION_APPWIDGET_UPDATE
                            putExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS, ids)
                        },
                    )
                }
            } catch (e: Exception) {
                Log.w("ReviewWidget", "triggerUpdate failed", e)
            }
        }
    }
}
